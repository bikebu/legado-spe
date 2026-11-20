package io.legado.app.help.book

import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.ReadRecord
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import splitties.init.appCtx
import java.io.File
import java.io.FilterOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Call on a worker thread. Hold this object's monitor around the entire merge transaction. */
object BookMergeHistory {
    private const val PREFIX = "bookDuplicateMerge:"
    private const val VERSION = 1
    // 备份只解析自身生成的数据，不能使用会吞掉非法数值的书源兼容适配器。
    private val snapshotJson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val directory get() = File(appCtx.filesDir, "book-merge-history")

    @Keep
    data class Record(
        val id: String,
        val createdAt: Long,
        val sequence: Long,
        val keepName: String,
        val removedCount: Int,
        val bookUrls: List<String>,
        val bookNames: List<String>,
        // 每本书的"书名 · 作者 · 来源"，第一本为保留的书；只用于展示，旧记录为null
        val bookLabels: List<String>? = null
    )

    @Keep
    private data class BookSnapshot(val book: Book, val chapters: List<BookChapter>)

    @Keep
    private data class BookmarkScope(
        val name: String,
        val author: String,
        val bookmarks: List<Bookmark>
    )

    @Keep
    private data class Snapshot(
        val version: Int,
        val record: Record,
        val books: List<BookSnapshot>,
        val bookmarkScopes: List<BookmarkScope>,
        val readRecords: List<ReadRecord>
    )

    /** The payload is durable before its marker participates in the caller's Room transaction. */
    @Synchronized
    fun capture(books: List<Book>): Record {
        check(appDb.inTransaction()) { "合并备份必须在数据库事务中创建" }
        require(books.size >= 2 && books.map { it.bookUrl }.distinct().size == books.size) {
            "合并备份需要至少两本不同的书籍"
        }
        val previous = loadRecords().maxOfOrNull { it.sequence } ?: 0L
        check(previous < Long.MAX_VALUE) { "合并历史序号已用尽" }
        val record = Record(
            UUID.randomUUID().toString(), System.currentTimeMillis(), previous + 1,
            books.first().name, books.size - 1,
            books.map { it.bookUrl }, books.map { it.name }.distinct(),
            books.map { label(it) }
        )
        val snapshot = Snapshot(
            VERSION, record,
            books.map { BookSnapshot(it, appDb.bookChapterDao.getChapterList(it.bookUrl)) },
            books.distinctBy { it.name to it.author }.map {
                BookmarkScope(it.name, it.author, appDb.bookmarkDao.getByBook(it.name, it.author))
            },
            record.bookNames.flatMap { appDb.readRecordDao.getByName(it) }
        )
        check(directory.isDirectory || directory.mkdirs()) { "无法创建合并备份目录" }
        val file = atomicFile(record.id)
        val output = file.startWrite()
        try {
            // Do not close the underlying stream until AtomicFile has synced and committed it.
            val nonClosing = object : FilterOutputStream(output) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) =
                    output.write(bytes, offset, length)
                override fun close() = flush()
            }
            GZIPOutputStream(nonClosing).writer(Charsets.UTF_8).use { writer ->
                snapshotJson.toJson(snapshot, writer)
            }
            output.fd.sync()
            file.finishWrite(output)
            check(payload(record.id).isFile && !File(payload(record.id).path + ".new").exists()) {
                "无法完成合并备份文件写入"
            }
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        appDb.cacheDao.insert(Cache(PREFIX + record.id, snapshotJson.toJson(record), 0L))
        return record
    }

    @Synchronized
    fun records(): List<Record> {
        prune()
        return loadRecords().sortedByDescending { it.sequence }
    }

    @Synchronized
    fun restore(id: String) {
        check(!appDb.inTransaction()) { "撤销合并不能嵌套在其他事务中" }
        val all = loadRecords()
        val record = all.firstOrNull { it.id == id } ?: error("合并记录不存在或已过期")
        val now = System.currentTimeMillis()
        check(!BookMergeHistoryPolicy.isExpired(record.createdAt, now)) { "合并记录已超过七天，无法撤销" }
        check(all.none {
            !BookMergeHistoryPolicy.isExpired(it.createdAt, now) &&
                BookMergeHistoryPolicy.blocksRestore(record.sequence, record.bookUrls, record.bookNames,
                    it.sequence, it.bookUrls, it.bookNames)
        }) { "请先撤销涉及相同书籍或书名的较新合并记录" }
        // Read without AtomicFile.openRead(): a failed restore must not rename/delete any payload.
        val snapshot = try {
            readSnapshot(record)
        } catch (error: Exception) {
            throw IllegalStateException("合并备份丢失或损坏，无法撤销；原记录已保留", error)
        }
        appDb.runInTransaction {
            // Books also have a unique (name, author) index. Never let INSERT REPLACE
            // delete a different URL, including another affected book renamed since capture.
            snapshot.books.forEach { saved ->
                check(appDb.bookDao.findByName(saved.book.name).none {
                    it.author == saved.book.author && it.bookUrl != saved.book.bookUrl
                }) { "书名及作者已被其他书籍占用，请先处理冲突后再撤销合并" }
            }
            snapshot.books.forEach { saved ->
                val book = saved.book
                if (appDb.bookDao.has(book.bookUrl)) appDb.bookDao.update(book)
                else appDb.bookDao.insert(book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                saved.chapters.forEach { appDb.bookChapterDao.insert(it) }
            }
            snapshot.bookmarkScopes.forEach { scope ->
                appDb.bookmarkDao.getByBook(scope.name, scope.author).forEach {
                    appDb.bookmarkDao.delete(it)
                }
            }
            val occupied = appDb.bookmarkDao.all.mapTo(hashSetOf()) { it.time }
            // Reserve snapshot IDs too, so collision allocation cannot steal another saved ID.
            val reserved = snapshot.bookmarkScopes.flatMap { it.bookmarks }.mapTo(hashSetOf()) { it.time }
            var nextId = System.currentTimeMillis()
            snapshot.bookmarkScopes.flatMap { it.bookmarks }.forEach { bookmark ->
                val restored = if (bookmark.time in occupied) {
                    while (nextId in occupied || nextId in reserved) {
                        nextId = if (nextId == Long.MAX_VALUE) Long.MIN_VALUE else nextId + 1
                    }
                    bookmark.copy(time = nextId)
                } else bookmark
                appDb.bookmarkDao.insert(restored)
                occupied.add(restored.time)
            }
            record.bookNames.forEach { appDb.readRecordDao.deleteByName(it) }
            snapshot.readRecords.forEach { appDb.readRecordDao.insert(it) }
            appDb.cacheDao.delete(PREFIX + id)
        }
        // These are deliberately outside the transaction; cleanup must never turn success into failure.
        cleanup {
            if (ReadBook.book?.bookUrl in record.bookUrls) ReadBook.book = null
        }
        cleanup {
            atomicFile(id).delete()
            check(!payload(id).exists() && !File(payload(id).path + ".bak").exists()) {
                "无法删除已撤销的合并备份"
            }
        }
    }

    @Synchronized
    fun prune() {
        check(!appDb.inTransaction()) { "合并历史清理不能在数据库事务中执行" }
        val now = System.currentTimeMillis()
        val (all, corrupt) = loadMarkers()
        val expired = all.filter { BookMergeHistoryPolicy.isExpired(it.createdAt, now) }
        expired.forEach { record ->
            cleanup { releaseRemovedBooks(record) }
        }
        appDb.runInTransaction {
            expired.forEach { appDb.cacheDao.delete(PREFIX + it.id) }
            corrupt.forEach { appDb.cacheDao.delete(it) }
        }
        val activeIds = (all - expired.toSet()).mapTo(hashSetOf()) { it.id }
        cleanup {
            directory.listFiles()?.forEach { file ->
                val id = when {
                    file.name.endsWith(".json.gz.bak") -> file.name.removeSuffix(".json.gz.bak")
                    file.name.endsWith(".json.gz.new") -> file.name.removeSuffix(".json.gz.new")
                    file.name.endsWith(".json.gz") -> file.name.removeSuffix(".json.gz")
                    else -> return@forEach
                }
                if (id !in activeIds && !file.delete()) {
                    AppLog.put("无法清理合并备份：${file.name}")
                }
            }
        }
    }

    private fun loadRecords(): List<Record> = loadMarkers().first

    /**
     * 返回(有效记录, 损坏的标记key)。损坏的记录无法撤回，只记录日志，
     * 不能因为一条坏记录阻断后续所有合并和合并记录页面。
     */
    private fun loadMarkers(): Pair<List<Record>, List<String>> {
        val records = arrayListOf<Record>()
        val corrupt = arrayListOf<String>()
        appDb.cacheDao.getByPrefix(PREFIX).forEach { marker ->
            try {
                records.add(snapshotJson.fromJson(marker.value, Record::class.java).also {
                    require(marker.key == PREFIX + it.id)
                    require(UUID.fromString(it.id).toString() == it.id)
                    require(it.sequence > 0 && it.removedCount >= 1)
                    require(it.bookUrls.size == it.removedCount + 1)
                    require(it.bookUrls.distinct().size == it.bookUrls.size)
                    require(it.bookNames.isNotEmpty() && it.keepName in it.bookNames)
                    require(it.bookLabels == null || it.bookLabels.size == it.bookUrls.size)
                })
            } catch (error: Exception) {
                corrupt.add(marker.key)
                runCatching { AppLog.put("合并记录损坏，已忽略：${marker.key}", error) }
            }
        }
        return records to corrupt
    }

    private fun label(book: Book): String {
        val origin = if (book.isLocal) "本地" else book.originName
        return listOf(book.name, book.author, origin).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun readSnapshot(record: Record): Snapshot {
        val base = payload(record.id)
        val backup = File(base.path + ".bak")
        return GZIPInputStream((if (backup.exists()) backup else base).inputStream()).use { input ->
            input.reader(Charsets.UTF_8).use { snapshotJson.fromJson(it, Snapshot::class.java) }
        }.also { validate(it, record) }
    }

    /**
     * 记录过期后已不能撤回，释放被合并书籍为撤回保留的正文缓存和本地封面。
     * 书籍又被加回书架（同一URL）时跳过。
     */
    private fun releaseRemovedBooks(record: Record) {
        val snapshot = readSnapshot(record)
        snapshot.books.drop(1).forEach { saved ->
            val book = saved.book
            if (appDb.bookDao.has(book.bookUrl)) return@forEach
            if (book.isLocal) {
                // 只清理缓存和封面，不删除本地源文件
                LocalBook.deleteBook(book, false)
            } else {
                BookHelp.clearCache(book)
            }
        }
    }

    private fun validate(snapshot: Snapshot, record: Record) {
        require(snapshot.version == VERSION && snapshot.record == record)
        require(snapshot.books.map { it.book.bookUrl } == record.bookUrls)
        require(snapshot.books.map { it.book.name }.distinct() == record.bookNames)
        require(snapshot.books.first().book.name == record.keepName)
        snapshot.books.forEach { saved ->
            require(saved.chapters.all { it.bookUrl == saved.book.bookUrl })
            require(saved.chapters.map { it.url }.distinct().size == saved.chapters.size)
            require(saved.chapters.map { it.index }.distinct().size == saved.chapters.size)
        }
        val scopes = snapshot.books.map { it.book.name to it.book.author }.distinct()
        require(snapshot.bookmarkScopes.map { it.name to it.author } == scopes)
        snapshot.bookmarkScopes.forEach { scope ->
            require(scope.bookmarks.all { it.bookName == scope.name && it.bookAuthor == scope.author })
        }
        val bookmarks = snapshot.bookmarkScopes.flatMap { it.bookmarks }
        require(bookmarks.map { it.time }.distinct().size == bookmarks.size)
        require(snapshot.readRecords.all { it.bookName in record.bookNames })
        require(snapshot.readRecords.map { it.deviceId to it.bookName }.distinct().size == snapshot.readRecords.size)
    }

    private fun payload(id: String) = File(directory, "$id.json.gz")
    private fun atomicFile(id: String) = AtomicFile(payload(id))

    private inline fun cleanup(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            // Logging itself must also be best effort after a committed restore.
            runCatching { AppLog.put("合并备份清理失败，将在下次清理时重试", error) }
        }
    }
}
