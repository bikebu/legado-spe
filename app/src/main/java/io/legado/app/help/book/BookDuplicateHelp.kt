package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookFingerprint
import io.legado.app.data.entities.ReadRecord
import io.legado.app.model.ReadBook
import io.legado.app.help.CacheManager
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * 书架去重
 * 1. 书名、作者标准化（繁转简、全半角、去除版本后缀与标点）后比较
 * 2. 已缓存正文的SimHash指纹比较
 * 只给出疑似重复分组，由用户确认后合并
 */
object BookDuplicateHelp {

    enum class Reason {
        // 标准化后书名、作者都相同
        SAME_NAME_AUTHOR,
        // 标准化后书名相同，作者不同
        SAME_NAME,
        // 作者相同，书名相近
        SIMILAR_NAME,
        // 正文相似
        SIMILAR_CONTENT,
        // 仅一个独立章节命中，低置信疑似
        POSSIBLE_CONTENT,
        // 正文命中但系列标记不同，必须人工核对
        SERIES_CONFLICT
    }

    class DuplicateGroup(
        val books: List<Book>,
        val reasons: Set<Reason>,
        val matches: Map<Set<String>, Set<Reason>> = emptyMap(),
        val contentMatches: Map<Set<String>, Int> = emptyMap()
    ) {
        val key: String = groupKey(books)
    }

    private const val FINGERPRINT_VERSION = 2
    private const val IGNORE_KEY = "bookDuplicateIgnore"

    // 每本书最多取几个章节计算指纹
    private const val SAMPLE_CHAPTERS = 3

    // 只在目录前多少章中寻找采样章节
    private const val MAX_SCAN_CHAPTERS = 20

    // 采样章节标准化后的最少字数，太短的章节多为公告、请假条
    private const val MIN_CHAPTER_CHARS = 300

    // 每个采样章节最多读取的字数
    private const val MAX_CHAPTER_CHARS = 10000

    private const val SHINGLE_SIZE = 3

    private val noiseKeywords =
        "精校版|精校|全本|完本|完结版|全集|校对版|校对|修订版|典藏版|未删减版|未删减|无删减版|无删减|未删节版|未删节|txt|epub|mobi|azw3"

    // 括号内的版本说明，如（精校版）【全本+番外】
    private val bracketNoiseRegex = Regex(
        "[(（\\[【<〈「『{]\\s*(?:$noiseKeywords)(?:\\s*[+＋、/|·,，\\s]\\s*(?:$noiseKeywords|番外))*\\s*[)）\\]】>〉」』}]",
        RegexOption.IGNORE_CASE
    )

    // 末尾的版本说明或文件后缀，如 xxx 精校版、xxx.txt
    private val tailNoiseRegex = Regex(
        "[\\s\\-_—·.:：]*(?:$noiseKeywords)\\s*$",
        RegexOption.IGNORE_CASE
    )

    // 系列标记，不同时不认为是同一本书
    private val seriesRegex = Regex(
        "第?[0-9零〇一二三四五六七八九十百千两]+[部卷册季集篇]|[0-9]+|[上中下][册部卷篇]|[上中下]$|续集?|外传|前传|后传|别传|番外|同人|[ⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹⅺⅻ]"
    )

    // 不参与指纹计算的章节标题
    private val boilerplateTitleRegex =
        Regex("序|前言|引子|楔子|公告|作品相关|感言|请假|上架|通知|说明|简介|推荐|新书|求票")

    private val imgRegex = Regex("<img[^>]*>", RegexOption.IGNORE_CASE)

    enum class ScanPhase { METADATA, FINGERPRINTS, CONTENT, GROUPING }

    /** 进度按阶段上报；正文校验和候选比较分别计数。 */
    suspend fun findDuplicates(
        books: List<Book>,
        forceRefresh: Boolean = false,
        progress: ((ScanPhase, Int, Int) -> Unit)? = null
    ): List<DuplicateGroup> {
        val scanContext = coroutineContext
        val shelfBooks = books.filterNot { it.isNotShelf }
        val size = shelfBooks.size
        if (size < 2) return emptyList()
        progress?.invoke(ScanPhase.METADATA, 0, size)
        val keys = shelfBooks.map {
            scanContext.ensureActive()
            BookKey(it)
        }
        val unionFind = UnionFind(size)
        val contentMatches = hashMapOf<Long, Int>()
        val pairReasons = hashMapOf<Long, MutableSet<Reason>>()
        fun pairKey(i: Int, j: Int): Long =
            minOf(i, j).toLong() shl 32 or maxOf(i, j).toLong()
        fun link(i: Int, j: Int, reason: Reason) {
            unionFind.union(i, j)
            pairReasons.getOrPut(pairKey(i, j)) { hashSetOf() }.add(reason)
        }
        var pairChecks = 0
        // 书名相同
        keys.indices.filter { keys[it].name.isNotEmpty() }
            .groupBy { keys[it].media to keys[it].name }
            .values.forEach { indices ->
                scanContext.ensureActive()
                forEachPair(indices) { i, j ->
                    if (pairChecks++ and 255 == 0) scanContext.ensureActive()
                    link(i, j, if (keys[i].author == keys[j].author) {
                        Reason.SAME_NAME_AUTHOR
                    } else Reason.SAME_NAME)
                }
            }
        // 作者相同，书名相近
        keys.indices.filter { keys[it].author.isNotEmpty() && keys[it].name.isNotEmpty() }
            .groupBy { keys[it].media to keys[it].author }
            .values.forEach { indices ->
                scanContext.ensureActive()
                forEachPair(indices) { i, j ->
                    if (pairChecks++ and 255 == 0) scanContext.ensureActive()
                    val a = keys[i]
                    val b = keys[j]
                    if (a.name != b.name && a.series == b.series && isSimilarName(a.name, b.name)) {
                        link(i, j, Reason.SIMILAR_NAME)
                    }
                }
            }
        // 分批预取目录和旧指纹，限制 IN 参数及临时目录占用。
        val hashes = MutableList<List<Long>>(size) { emptyList() }
        progress?.invoke(ScanPhase.FINGERPRINTS, 0, size)
        shelfBooks.indices.chunked(128).forEach { indices ->
            scanContext.ensureActive()
            val urls = indices.map { shelfBooks[it] }.filter { supportsFingerprint(it) }
                .map { it.bookUrl }
            val prefetched = try {
                val chapters = if (urls.isEmpty()) emptyMap() else {
                    appDb.bookChapterDao.getSampleChapters(urls, MAX_SCAN_CHAPTERS).groupBy { it.bookUrl }
                }
                val cached = if (urls.isEmpty()) emptyMap() else {
                    appDb.bookFingerprintDao.getByBookUrls(urls).associateBy { it.bookUrl }
                }
                chapters to cached
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 某条目录记录损坏时回退逐书读取，保留其他书籍和名称匹配结果。
                AppLog.put("批量读取去重数据失败，改为逐书读取", e)
                null
            }
            indices.forEach { index ->
                scanContext.ensureActive()
                val book = shelfBooks[index]
                try {
                    if (supportsFingerprint(book)) {
                        hashes[index] = if (prefetched == null) {
                            getFingerprint(book, forceRefresh)?.hashList().orEmpty()
                        } else {
                            buildFingerprint(
                                book, forceRefresh,
                                prefetched.first[book.bookUrl].orEmpty(), prefetched.second[book.bookUrl]
                            ).hashList()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("读取书籍指纹失败：${book.name}", e)
                }
                progress?.invoke(ScanPhase.FINGERPRINTS, index + 1, size)
            }
        }
        progress?.invoke(ScanPhase.CONTENT, 0, size)
        DuplicateContentMatcher(hashes) { scanContext.ensureActive() }.forEachMatch(
            checkActive = { scanContext.ensureActive() },
            progress = { current, total -> progress?.invoke(ScanPhase.CONTENT, current, total) }
        ) { i, j, count ->
            if (keys[i].media == keys[j].media) {
                contentMatches[pairKey(i, j)] = count
                link(i, j, if (count >= 2) Reason.SIMILAR_CONTENT else Reason.POSSIBLE_CONTENT)
                if (keys[i].series != keys[j].series) link(i, j, Reason.SERIES_CONFLICT)
            }
        }
        progress?.invoke(ScanPhase.GROUPING, 0, size)
        // 每条匹配关系只归组一次，避免每个分组都重新遍历全部关系。
        val edgesByRoot = pairReasons.entries.groupBy {
            scanContext.ensureActive()
            unionFind.find((it.key ushr 32).toInt())
        }
        val ignored = getIgnored()
        val groups = (0 until size).groupBy { unionFind.find(it) }.values
            .filter { it.size > 1 }
            .mapNotNull { indices ->
                scanContext.ensureActive()
                val edges = edgesByRoot[unionFind.find(indices.first())].orEmpty()
                val reasons = hashSetOf<Reason>()
                val matches = hashMapOf<Set<String>, Set<Reason>>()
                val counts = hashMapOf<Set<String>, Int>()
                edges.forEach { (pair, evidence) ->
                    scanContext.ensureActive()
                    val urls = setOf(shelfBooks[(pair ushr 32).toInt()].bookUrl, shelfBooks[pair.toInt()].bookUrl)
                    reasons.addAll(evidence)
                    matches[urls] = evidence
                    contentMatches[pair]?.let { counts[urls] = it }
                }
                val groupBooks = indices.map { shelfBooks[it] }.sortedWith(keepComparator)
                DuplicateGroup(groupBooks, reasons, matches, counts).takeIf { it.key !in ignored }
            }
        return groups.sortedBy { group -> group.reasons.minOf { it.ordinal } }
    }

    /**
     * 合并重复书籍，保留keep，删除removes
     * 合并分组、阅读进度、书签、阅读时长、自定义信息
     */
    /**
     * @param prepare 在合并事务内最先执行，用于把保留的书写入数据库（如换源时的新书），
     * 合并失败时随事务一起回滚，不会留下半完成的状态
     */
    fun merge(
        keep: Book,
        removes: List<Book>,
        prepare: (() -> Unit)? = null
    ) = synchronized(BookMergeHistory) {
        BookMergeHistory.prune()
        // 正文比较要读文件、执行替换规则，放在事务外预先算好，事务内只查表
        val sameContent = precomputeSameContent(keep, removes)
        val removeList = arrayListOf<Book>()
        appDb.runInTransaction {
            prepare?.invoke()
            val newKeep = checkNotNull(appDb.bookDao.getBook(keep.bookUrl)) {
                "保留的书籍已不在书架，请重新扫描"
            }
            check(!newKeep.isNotShelf) { "保留的书籍已不在书架，请重新扫描" }
            removes.distinctBy { it.bookUrl }.filter { it.bookUrl != keep.bookUrl }
                .forEach {
                    val current = checkNotNull(appDb.bookDao.getBook(it.bookUrl)) {
                        "待合并的书籍已发生变化，请重新扫描"
                    }
                    check(!current.isNotShelf) { "待合并的书籍已发生变化，请重新扫描" }
                    removeList.add(current)
                }
            if (removeList.isEmpty()) return@runInTransaction
            // 快照文件写入成功、撤回标记加入本次事务后才允许修改书籍。
            BookMergeHistory.capture(listOf(newKeep) + removeList)
            val removeUrls = removeList.map { it.bookUrl }.toHashSet()
            val keepToc = appDb.bookChapterDao.getChapterList(newKeep.bookUrl)
            removeList.forEach { old ->
                newKeep.group = newKeep.group or old.group
                if (newKeep.customCoverUrl.isNullOrEmpty()) {
                    newKeep.customCoverUrl = old.customCoverUrl
                }
                if (newKeep.customIntro.isNullOrEmpty()) {
                    newKeep.customIntro = old.customIntro
                }
                if (newKeep.customTag.isNullOrEmpty()) {
                    newKeep.customTag = old.customTag
                }
                mergeProgress(newKeep, old, keepToc, sameContent)
                mergeBookmarks(newKeep, old, keepToc, removeUrls, sameContent)
                mergeReadRecord(newKeep, old, removeUrls)
            }
            appDb.bookDao.update(newKeep)
            removeList.forEach { appDb.bookDao.delete(it) }
        }
        runCatching {
            if (removeList.any { it.bookUrl == ReadBook.book?.bookUrl }) ReadBook.book = null
        }.onFailure { error ->
            runCatching { AppLog.put("合并后刷新阅读状态失败", error) }
        }
        // 去重不代表不喜欢这本书，不调用书源删除事件。
        // 保留正文缓存和本地封面供撤回使用，源文件始终不删除。
    }

    fun ignore(group: DuplicateGroup) {
        val ignored = getIgnored()
        ignored.add(group.key)
        CacheManager.put(IGNORE_KEY, ignored.joinToString(","))
    }

    fun clearIgnored() {
        CacheManager.delete(IGNORE_KEY)
    }

    private fun getIgnored(): HashSet<String> {
        return CacheManager.get(IGNORE_KEY)
            ?.split(',')
            ?.filter { it.isNotEmpty() }
            ?.toHashSet()
            ?: hashSetOf()
    }

    private fun groupKey(books: List<Book>): String {
        return MD5Utils.md5Encode16(books.map { it.bookUrl }.sorted().joinToString("\n"))
    }

    /**
     * 建议保留阅读进度最靠后、章节最多的书
     */
    private val keepComparator = compareByDescending<Book> { it.durChapterIndex }
        .thenByDescending { it.durChapterPos }
        .thenByDescending { it.totalChapterNum }
        .thenByDescending { it.durChapterTime }

    /**
     * 事务外预先比较"被合并书的章节"和"保留书对应章节"处理后的正文是否一致。
     * key为(被合并书URL, 被合并书章节序号, 保留书章节序号)，查不到按不一致处理（从章节开头继续）。
     */
    private class SameContentTable(private val table: Map<Triple<String, Int, Int>, Boolean>) {
        fun get(oldUrl: String, oldIndex: Int, keepIndex: Int): Boolean =
            table[Triple(oldUrl, oldIndex, keepIndex)] ?: false
    }

    private fun precomputeSameContent(keep: Book, removes: List<Book>): SameContentTable {
        val table = hashMapOf<Triple<String, Int, Int>, Boolean>()
        runCatching {
            val keepBook = appDb.bookDao.getBook(keep.bookUrl) ?: return@runCatching
            val keepToc = appDb.bookChapterDao.getChapterList(keepBook.bookUrl)
            if (keepToc.isEmpty()) return@runCatching
            removes.distinctBy { it.bookUrl }.filter { it.bookUrl != keep.bookUrl }.forEach { removed ->
                val old = appDb.bookDao.getBook(removed.bookUrl) ?: return@forEach
                val oldToc = appDb.bookChapterDao.getChapterList(old.bookUrl)
                if (oldToc.isEmpty()) return@forEach
                val pairs = linkedSetOf<Pair<Int, Int>>()
                pairs.add(old.durChapterIndex to BookHelp.getDurChapter(old, keepToc))
                appDb.bookmarkDao.getByBook(old.name, old.author).forEach { bookmark ->
                    pairs.add(
                        bookmark.chapterIndex to BookHelp.getDurChapter(
                            bookmark.chapterIndex, bookmark.chapterName, keepToc, old.totalChapterNum
                        )
                    )
                }
                pairs.forEach { (oldIndex, keepIndex) ->
                    val oldChapter = oldToc.getOrNull(oldIndex) ?: return@forEach
                    val keepChapter = keepToc.getOrNull(keepIndex) ?: return@forEach
                    table[Triple(old.bookUrl, oldIndex, keepIndex)] =
                        hasSameReadingContent(old, oldChapter, keepBook, keepChapter)
                }
            }
        }.onFailure {
            AppLog.put("合并前比较正文失败，阅读位置将从章节开头继续", it)
        }
        return SameContentTable(table)
    }

    private fun mergeProgress(
        keep: Book, old: Book, keepToc: List<BookChapter>, sameContentTable: SameContentTable
    ) {
        keep.durChapterTime = max(keep.durChapterTime, old.durChapterTime)
        if (keepToc.isEmpty()) return
        val index = BookHelp.getDurChapter(old, keepToc)
        if (index < keep.durChapterIndex) return
        // 不同来源的字符偏移不可直接比较，只有处理后的正文和标题一致才迁移位置。
        val sameContent = sameContentTable.get(old.bookUrl, old.durChapterIndex, index)
        val position = mergedChapterPosition(
            keep.durChapterIndex, keep.durChapterPos, index, old.durChapterPos, sameContent
        ) ?: return
        keep.durChapterIndex = index
        keep.durChapterPos = position
        keep.durChapterTitle = keepToc[index].getDisplayTitle(
            ContentProcessor.get(keep.name, keep.origin).getTitleReplaceRules(),
            keep.getUseReplaceRule(),
            replaceBook = keep.toReplaceBook()
        )
    }

    private fun hasSameReadingContent(
        old: Book, oldChapter: BookChapter, keep: Book, keepChapter: BookChapter
    ): Boolean = runCatching {
        val oldRaw = BookHelp.getContent(old, oldChapter)
        val keepRaw = BookHelp.getContent(keep, keepChapter)
        if (oldRaw.isNullOrEmpty() || keepRaw.isNullOrEmpty()) return@runCatching false
        val oldText = ContentProcessor.get(old).getContent(old, oldChapter, oldRaw).toString()
        val keepText = ContentProcessor.get(keep).getContent(keep, keepChapter, keepRaw).toString()
        oldText.isNotEmpty() && oldText == keepText
    }.getOrDefault(false)

    /** null 表示保留当前进度；跨版本正文无法确认一致时从目标章开头继续。 */
    internal fun mergedChapterPosition(
        currentIndex: Int,
        currentPosition: Int,
        mappedIndex: Int,
        oldPosition: Int,
        sameContent: Boolean
    ): Int? {
        if (mappedIndex < currentIndex) return null
        val position = if (sameContent) oldPosition.coerceAtLeast(0) else 0
        if (mappedIndex == currentIndex && position <= currentPosition) return null
        return position
    }

    private fun mergeBookmarks(
        keep: Book,
        old: Book,
        keepToc: List<BookChapter>,
        removeUrls: Set<String>,
        sameContentTable: SameContentTable
    ) {
        if (keep.name == old.name && keep.author == old.author) return
        val shared = appDb.bookDao.findByName(old.name).any {
            it.author == old.author && it.bookUrl !in removeUrls
        }
        val existing = appDb.bookmarkDao.getByBook(keep.name, keep.author).toMutableList()
        var nextTime = if (shared) {
            max(System.currentTimeMillis(), appDb.bookmarkDao.all.maxOfOrNull { it.time } ?: 0L)
        } else 0L
        appDb.bookmarkDao.getByBook(old.name, old.author).forEach { bookmark ->
            val index = if (keepToc.isEmpty()) {
                bookmark.chapterIndex
            } else {
                BookHelp.getDurChapter(
                    bookmark.chapterIndex,
                    bookmark.chapterName,
                    keepToc,
                    old.totalChapterNum
                )
            }
            val targetChapter = keepToc.getOrNull(index)
            val sameContent = targetChapter != null &&
                sameContentTable.get(old.bookUrl, bookmark.chapterIndex, index)
            val migrated = bookmark.copy(
                time = if (shared) ++nextTime else bookmark.time,
                bookName = keep.name,
                bookAuthor = keep.author,
                chapterIndex = index,
                chapterPos = if (sameContent) bookmark.chapterPos else 0,
                chapterName = targetChapter?.getDisplayTitle(
                    ContentProcessor.get(keep).getTitleReplaceRules(),
                    keep.getUseReplaceRule(),
                    replaceBook = keep.toReplaceBook()
                ) ?: bookmark.chapterName
            )
            if (!shared) appDb.bookmarkDao.delete(bookmark)
            if (existing.none {
                    it.chapterIndex == migrated.chapterIndex && it.chapterPos == migrated.chapterPos
                        && it.chapterName == migrated.chapterName && it.bookText == migrated.bookText
                        && it.content == migrated.content
                }) {
                appDb.bookmarkDao.insert(migrated)
                existing.add(migrated)
            }
        }
    }

    /**
     * 阅读时长按书名记录，书名被其他保留的书使用时不迁移
     */
    private fun mergeReadRecord(keep: Book, old: Book, removeUrls: Set<String>) {
        if (keep.name == old.name) return
        val nameUsed = appDb.bookDao.findByName(old.name).any {
            it.bookUrl != old.bookUrl && it.bookUrl !in removeUrls
        }
        if (nameUsed) return
        val keepRecords = appDb.readRecordDao.getByName(keep.name).associateBy { it.deviceId }
        appDb.readRecordDao.getByName(old.name).forEach { record ->
            val keepRecord = keepRecords[record.deviceId]
            appDb.readRecordDao.insert(
                ReadRecord(
                    deviceId = record.deviceId,
                    bookName = keep.name,
                    readTime = record.readTime + (keepRecord?.readTime ?: 0L),
                    lastRead = max(record.lastRead, keepRecord?.lastRead ?: 0L)
                )
            )
            appDb.readRecordDao.delete(record)
        }
    }

    /**
     * 获取正文指纹，只使用已缓存的正文（本地TXT视为已缓存）
     * 采样章节没有变化时直接使用数据库中的指纹
     */
    private fun supportsFingerprint(book: Book) =
        !book.isAudio && !book.isVideo && !book.isImage && !book.isPdf

    suspend fun getFingerprint(book: Book, forceRefresh: Boolean = false): BookFingerprint? {
        if (!supportsFingerprint(book)) return null
        return buildFingerprint(
            book, forceRefresh,
            appDb.bookChapterDao.getChapterList(book.bookUrl, 0, MAX_SCAN_CHAPTERS - 1),
            appDb.bookFingerprintDao.get(book.bookUrl)
        )
    }

    private suspend fun buildFingerprint(
        book: Book,
        forceRefresh: Boolean,
        chapters: List<BookChapter>,
        old: BookFingerprint?
    ): BookFingerprint {
        // 每次读取有限的采样正文以校验内容变化，只复用昂贵的 SimHash 计算。
        val samples = arrayListOf<Pair<String, String>>()
        var readFailed = false
        for (chapter in chapters) {
            coroutineContext.ensureActive()
            if (samples.size >= SAMPLE_CHAPTERS) break
            if (chapter.isVolume || boilerplateTitleRegex.containsMatchIn(chapter.title)
                || !BookHelp.hasContent(book, chapter)
            ) continue
            val content = try {
                BookHelp.getContent(book, chapter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                readFailed = true
                AppLog.put("读取采样章节失败：${book.name} / ${chapter.title}", e)
                null
            } ?: continue
            val text = normalizeText(
                content.take(MAX_CHAPTER_CHARS * 2).replace(imgRegex, "")
            ).take(MAX_CHAPTER_CHARS)
            if (text.length < MIN_CHAPTER_CHARS) continue
            samples.add(chapter.url to text)
        }
        val sourceKey = fingerprintSourceKey(samples)
        if (!forceRefresh && old != null && old.sourceKey == sourceKey) return old
        val hashes = samples.map { (_, text) ->
            coroutineContext.ensureActive()
            simHash(text)
        }
        val fingerprint = BookFingerprint(
            bookUrl = book.bookUrl,
            sourceKey = sourceKey,
            chapterHashes = hashes.joinToString(",")
        )
        // 暂时不可读的章节不应使不完整采样覆盖已缓存的结果。
        if (!readFailed) appDb.bookFingerprintDao.insert(fingerprint)
        return fingerprint
    }

    internal fun fingerprintSourceKey(
        samples: List<Pair<String, String>>,
        version: Int = FINGERPRINT_VERSION
    ): String = MD5Utils.md5Encode(buildString {
        append(version).append(':')
        samples.forEach { (url, text) ->
            append(url.length).append(':').append(url)
            append(text.length).append(':').append(text)
        }
    })

    internal fun contentMatchCount(a: List<Long>, b: List<Long>): Int =
        DuplicateContentMatcher.count(a, b)

    private suspend fun simHash(text: String): Long {
        val scanContext = coroutineContext
        if (text.length < SHINGLE_SIZE) return 0L
        val weights = IntArray(64)
        for (start in 0..text.length - SHINGLE_SIZE) {
            if (start and 255 == 0) scanContext.ensureActive()
            val hash = shingleHash(text, start)
            for (bit in 0 until 64) {
                if ((hash ushr bit) and 1L == 1L) weights[bit]++ else weights[bit]--
            }
        }
        var result = 0L
        for (bit in 0 until 64) {
            if (weights[bit] > 0) result = result or (1L shl bit)
        }
        return result
    }

    /**
     * FNV-1a 64 + murmur3 fmix64
     */
    private fun shingleHash(text: String, start: Int): Long {
        var h = -3750763034362895579L
        for (i in start until start + SHINGLE_SIZE) {
            h = h xor text[i].code.toLong()
            h *= 1099511628211L
        }
        h = h xor (h ushr 33)
        h *= -49064778989728563L
        h = h xor (h ushr 33)
        h *= -4265267296055464877L
        h = h xor (h ushr 33)
        return h
    }

    /**
     * 繁转简、全角转半角、转小写，只保留文字和数字
     */
    fun normalizeText(text: String): String {
        val simple = toHalfWidth(ChineseUtils.t2s(text))
        val sb = StringBuilder(simple.length)
        for (ch in simple) {
            if (Character.isLetterOrDigit(ch)
                || Character.getType(ch) == Character.LETTER_NUMBER.toInt()
            ) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    private fun toHalfWidth(text: String): String {
        val chars = text.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            if (c in '！'..'～') {
                chars[i] = c - 0xFEE0
            } else if (c == '　') {
                chars[i] = ' '
            }
        }
        return String(chars)
    }

    fun normalizeName(name: String): String {
        var str = toHalfWidth(ChineseUtils.t2s(name.trim()))
        str = bracketNoiseRegex.replace(str, "")
        while (true) {
            val newStr = tailNoiseRegex.replace(str, "")
            if (newStr == str || newStr.isBlank()) break
            str = newStr
        }
        return normalizeText(str)
    }

    fun normalizeAuthor(book: Book): String {
        return normalizeAuthor(book.author)
    }

    fun normalizeAuthor(author: String): String {
        return normalizeText(author.replace(AppPattern.authorRegex, ""))
    }

    /**
     * 书名作者标准化后的key，用于判断书架上是否已有同一本书
     */
    fun shelfKey(name: String, author: String): String {
        return normalizeName(name) + "\u0001" + normalizeAuthor(author)
    }

    /**
     * 加书时查找书架上标准化后书名、作者都相同的书，只查数据库，不联网
     */
    fun findShelfMatch(book: Book): Book? {
        val name = normalizeName(book.name)
        if (name.isEmpty()) return null
        val author = normalizeAuthor(book)
        val media = mediaOf(book)
        return appDb.bookDao.all.firstOrNull {
            it.bookUrl != book.bookUrl
                && !it.isNotShelf
                && mediaOf(it) == media
                && normalizeName(it.name) == name
                && normalizeAuthor(it) == author
        }
    }

    private fun mediaOf(book: Book): Int {
        return when {
            book.isAudio -> 1
            book.isVideo -> 2
            else -> 0
        }
    }

    private fun isSimilarName(a: String, b: String): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length >= 2 && long.length - short.length <= 4 && long.contains(short)) {
            return true
        }
        return short.length >= 4 && long.length - short.length <= 1 && editDistance(short, long) <= 1
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    private inline fun forEachPair(indices: List<Int>, action: (Int, Int) -> Unit) {
        for (x in indices.indices) {
            for (y in x + 1 until indices.size) {
                action(indices[x], indices[y])
            }
        }
    }

    private class BookKey(book: Book) {
        val name = normalizeName(book.name)
        val author = normalizeAuthor(book)
        val series = seriesRegex.findAll(name).map { it.value }.sorted().toList()
        val media = mediaOf(book)
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
    }

}
