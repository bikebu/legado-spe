package io.legado.app.help.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import com.google.gson.JsonParser
import java.util.UUID
import splitties.init.appCtx

/** Run in the debug test app. Every row and history payload belongs to a unique fixture. */
@RunWith(AndroidJUnit4::class)
class BookMergeHistoryInstrumentedTest {
    private val token = UUID.randomUUID().toString()
    private val books = (0..2).map { index ->
        Book(bookUrl = "https://merge-test.invalid/$token/$index", name = "$token-$index",
            author = token, origin = "https://merge-test.invalid", durChapterIndex = index,
            totalChapterNum = 1)
    }
    private val records = mutableListOf<BookMergeHistory.Record>()

    private fun prepare() {
        appDb.bookDao.insert(*books.toTypedArray())
        books.forEach { book ->
            appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl,
                url = book.bookUrl + "/chapter", title = "第一章", index = 0))
        }
    }

    @After
    fun cleanup() = synchronized(BookMergeHistory) {
        books.forEach { book ->
            appDb.bookDao.getBook(book.bookUrl)?.let { appDb.bookDao.delete(it) }
            appDb.bookmarkDao.getByBook(book.name, book.author).forEach { appDb.bookmarkDao.delete(it) }
            appDb.readRecordDao.deleteByName(book.name)
        }
        records.forEach {
            appDb.cacheDao.delete("bookDuplicateMerge:" + it.id)
            File(appCtx.filesDir, "book-merge-history/${it.id}.json.gz").delete()
        }
    }

    private fun mergeSnapshot(keep: Book, remove: Book): BookMergeHistory.Record {
        lateinit var record: BookMergeHistory.Record
        synchronized(BookMergeHistory) {
            appDb.runInTransaction {
                record = BookMergeHistory.capture(listOf(keep, remove))
                records.add(record)
                appDb.bookDao.update(keep.copy(durChapterIndex = 99))
                appDb.bookDao.delete(remove)
            }
        }
        return record
    }

    @Test
    fun restoreRecoversBooksChaptersBookmarksAndReadTime() {
        prepare()
        val old = books[1]
        val bookmark = Bookmark(bookName = old.name, bookAuthor = old.author, content = "saved note")
        appDb.bookmarkDao.insert(bookmark)
        appDb.readRecordDao.insert(ReadRecord(deviceId = token, bookName = old.name, readTime = 123))
        val record = mergeSnapshot(books[0], old)
        appDb.bookmarkDao.delete(bookmark)
        appDb.readRecordDao.deleteByName(old.name)
        assertNull(appDb.bookDao.getBook(old.bookUrl))
        assertTrue(appDb.bookChapterDao.getChapterList(old.bookUrl).isEmpty())
        BookMergeHistory.restore(record.id)
        assertEquals(0, appDb.bookDao.getBook(books[0].bookUrl)!!.durChapterIndex)
        assertEquals(old.name, appDb.bookDao.getBook(old.bookUrl)!!.name)
        assertEquals(1, appDb.bookChapterDao.getChapterList(old.bookUrl).size)
        assertEquals("saved note", appDb.bookmarkDao.getByBook(old.name, old.author).single().content)
        assertEquals(123L, appDb.readRecordDao.getByName(old.name).single().readTime)
        assertNull(appDb.cacheDao.get("bookDuplicateMerge:" + record.id))
    }

    @Test
    fun corruptPayloadLeavesDatabaseAndHistoryUntouched() {
        prepare()
        val record = mergeSnapshot(books[0], books[1])
        File(appCtx.filesDir, "book-merge-history/${record.id}.json.gz").writeText("broken")
        try {
            BookMergeHistory.restore(record.id)
            fail("Expected corrupt snapshot rejection")
        } catch (_: IllegalStateException) { }
        assertNull(appDb.bookDao.getBook(books[1].bookUrl))
        assertEquals(99, appDb.bookDao.getBook(books[0].bookUrl)!!.durChapterIndex)
        assertNotNull(appDb.cacheDao.get("bookDuplicateMerge:" + record.id))
    }

    @Test
    fun duplicateChapterIndexInValidJsonDoesNotConsumeHistory() {
        prepare()
        val record = mergeSnapshot(books[0], books[1])
        val file = File(appCtx.filesDir, "book-merge-history/${record.id}.json.gz")
        val json = GZIPInputStream(file.inputStream()).bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val chapters = json.getAsJsonArray("books")[0].asJsonObject.getAsJsonArray("chapters")
        val duplicate = chapters[0].deepCopy().asJsonObject
        duplicate.addProperty("url", "https://merge-test.invalid/duplicate")
        chapters.add(duplicate)
        GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(json.toString()) }
        try {
            BookMergeHistory.restore(record.id)
            fail("Expected duplicate index rejection")
        } catch (_: IllegalStateException) { }
        assertNotNull(appDb.cacheDao.get("bookDuplicateMerge:" + record.id))
        assertNull(appDb.bookDao.getBook(books[1].bookUrl))
        assertEquals(99, appDb.bookDao.getBook(books[0].bookUrl)!!.durChapterIndex)
    }

    @Test
    fun failedMergeTransactionDoesNotPublishUndoRecord() {
        prepare()
        var id: String? = null
        synchronized(BookMergeHistory) {
            try {
                appDb.runInTransaction {
                    val record = BookMergeHistory.capture(books.take(2))
                    records.add(record)
                    id = record.id
                    appDb.bookDao.delete(books[1])
                    error("Simulated merge failure")
                }
                fail("Expected rollback")
            } catch (_: IllegalStateException) { }
        }
        assertNotNull(appDb.bookDao.getBook(books[1].bookUrl))
        assertNull(appDb.cacheDao.get("bookDuplicateMerge:$id"))
    }

    @Test
    fun sameNameNewBookIsNotOverwrittenByRestore() {
        prepare()
        val record = mergeSnapshot(books[0], books[1])
        val replacement = books[2].copy(name = books[1].name)
        appDb.bookDao.update(replacement)
        try {
            BookMergeHistory.restore(record.id)
            fail("Expected name conflict rejection")
        } catch (_: IllegalStateException) { }
        assertEquals(replacement.name, appDb.bookDao.getBook(replacement.bookUrl)!!.name)
        assertNull(appDb.bookDao.getBook(books[1].bookUrl))
        assertNotNull(appDb.cacheDao.get("bookDuplicateMerge:" + record.id))
    }

    @Test
    fun newerRelatedMergeMustBeUndoneFirst() {
        prepare()
        val first = mergeSnapshot(books[0], books[1])
        val second = mergeSnapshot(appDb.bookDao.getBook(books[0].bookUrl)!!, books[2])
        try {
            BookMergeHistory.restore(first.id)
            fail("Expected dependency rejection")
        } catch (_: IllegalStateException) { }
        assertNotNull(appDb.cacheDao.get("bookDuplicateMerge:" + first.id))
        BookMergeHistory.restore(second.id)
        BookMergeHistory.restore(first.id)
        assertTrue(books.all { appDb.bookDao.getBook(it.bookUrl) != null })
    }
}
