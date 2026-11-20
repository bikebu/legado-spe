package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookFingerprint
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.CacheManager
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.MD5Utils
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
        SIMILAR_CONTENT
    }

    class DuplicateGroup(
        val books: List<Book>,
        val reasons: Set<Reason>,
        val matches: Map<Set<String>, Set<Reason>> = emptyMap()
    ) {
        val key: String = groupKey(books)
    }

    private const val IGNORE_KEY = "bookDuplicateIgnore"

    // 每本书最多取几个章节计算指纹
    private const val SAMPLE_CHAPTERS = 3

    // 只在目录前多少章中寻找采样章节
    private const val MAX_SCAN_CHAPTERS = 20

    // 采样章节标准化后的最少字数，太短的章节多为公告、请假条
    private const val MIN_CHAPTER_CHARS = 300

    // 每个采样章节最多读取的字数
    private const val MAX_CHAPTER_CHARS = 10000

    // SimHash汉明距离阈值
    private const val SIMHASH_DISTANCE = 10

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

    /**
     * 查找书架重复书籍
     * @param progress 计算指纹进度 (当前, 总数)
     */
    suspend fun findDuplicates(
        books: List<Book>,
        progress: ((Int, Int) -> Unit)? = null
    ): List<DuplicateGroup> {
        val shelfBooks = books.filterNot { it.isNotShelf }
        val size = shelfBooks.size
        if (size < 2) return emptyList()
        val keys = shelfBooks.map { BookKey(it) }
        val unionFind = UnionFind(size)
        val pairReasons = hashMapOf<Long, MutableSet<Reason>>()
        fun link(i: Int, j: Int, reason: Reason) {
            unionFind.union(i, j)
            val pair = if (i < j) i.toLong() shl 32 or j.toLong() else j.toLong() shl 32 or i.toLong()
            pairReasons.getOrPut(pair) { hashSetOf() }.add(reason)
        }
        // 书名相同
        keys.indices.filter { keys[it].name.isNotEmpty() }
            .groupBy { keys[it].media to keys[it].name }
            .values.forEach { indices ->
                forEachPair(indices) { i, j ->
                    if (keys[i].author == keys[j].author) {
                        link(i, j, Reason.SAME_NAME_AUTHOR)
                    } else {
                        link(i, j, Reason.SAME_NAME)
                    }
                }
            }
        // 作者相同，书名相近
        keys.indices.filter { keys[it].author.isNotEmpty() && keys[it].name.isNotEmpty() }
            .groupBy { keys[it].media to keys[it].author }
            .values.forEach { indices ->
                forEachPair(indices) { i, j ->
                    val a = keys[i]
                    val b = keys[j]
                    if (a.name != b.name && a.series == b.series && isSimilarName(a.name, b.name)) {
                        link(i, j, Reason.SIMILAR_NAME)
                    }
                }
            }
        // 正文相似
        val hashes = shelfBooks.mapIndexed { index, book ->
            coroutineContext.ensureActive()
            progress?.invoke(index + 1, size)
            kotlin.runCatching { getFingerprint(book)?.hashList() }.getOrNull().orEmpty()
        }
        for (i in 0 until size) {
            if (hashes[i].isEmpty()) continue
            coroutineContext.ensureActive()
            for (j in i + 1 until size) {
                if (hashes[j].isEmpty() || keys[i].media != keys[j].media) continue
                if (isSimilarContent(hashes[i], hashes[j])) {
                    link(i, j, Reason.SIMILAR_CONTENT)
                }
            }
        }
        // 分组
        val ignored = getIgnored()
        val groups = (0 until size).groupBy { unionFind.find(it) }.values
            .filter { it.size > 1 }
            .mapNotNull { indices ->
                val indexSet = indices.toHashSet()
                val reasons = hashSetOf<Reason>()
                pairReasons.forEach { (pair, r) ->
                    if ((pair ushr 32).toInt() in indexSet) reasons.addAll(r)
                }
                val groupBooks = indices.map { shelfBooks[it] }.sortedWith(keepComparator)
                val matches = pairReasons.filterKeys { (it ushr 32).toInt() in indexSet }
                    .mapKeys { (pair, _) ->
                        setOf(shelfBooks[(pair ushr 32).toInt()].bookUrl, shelfBooks[pair.toInt()].bookUrl)
                    }
                DuplicateGroup(groupBooks, reasons, matches).takeIf { it.key !in ignored }
            }
        return groups.sortedBy { group -> group.reasons.minOf { it.ordinal } }
    }

    /**
     * 合并重复书籍，保留keep，删除removes
     * 合并分组、阅读进度、书签、阅读时长、自定义信息
     */
    fun merge(keep: Book, removes: List<Book>) {
        val removeList = arrayListOf<Book>()
        appDb.runInTransaction {
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
                mergeProgress(newKeep, old, keepToc)
                mergeBookmarks(newKeep, old, keepToc, removeUrls)
                mergeReadRecord(newKeep, old, removeUrls)
            }
            appDb.bookDao.update(newKeep)
            removeList.forEach { it.delete() }
        }
        removeList.forEach {
            runCatching {
                if (it.isLocal) {
                    LocalBook.deleteBook(it, false)
                } else {
                    val source = appDb.bookSourceDao.getBookSource(it.origin)
                    SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, it)
                }
            }.onFailure { error ->
                // 数据库已提交，清理失败不能让界面把合并当作失败再次提交。
                AppLog.put("合并后的书籍清理失败", error)
            }
        }
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

    private fun mergeProgress(keep: Book, old: Book, keepToc: List<BookChapter>) {
        keep.durChapterTime = max(keep.durChapterTime, old.durChapterTime)
        if (keepToc.isEmpty()) return
        val index = BookHelp.getDurChapter(old, keepToc)
        if (index < keep.durChapterIndex) return
        // 不同来源的字符偏移不可直接比较，只有处理后的正文和标题一致才迁移位置。
        val oldChapter = appDb.bookChapterDao.getChapterList(old.bookUrl)
            .getOrNull(old.durChapterIndex)
        val sameContent = oldChapter?.let { chapter ->
            hasSameReadingContent(old, chapter, keep, keepToc[index])
        } ?: false
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
        keep: Book, old: Book, keepToc: List<BookChapter>, removeUrls: Set<String>
    ) {
        if (keep.name == old.name && keep.author == old.author) return
        val shared = appDb.bookDao.findByName(old.name).any {
            it.author == old.author && it.bookUrl !in removeUrls
        }
        val existing = appDb.bookmarkDao.getByBook(keep.name, keep.author).toMutableList()
        var nextTime = if (shared) {
            max(System.currentTimeMillis(), appDb.bookmarkDao.all.maxOfOrNull { it.time } ?: 0L)
        } else 0L
        val oldToc = appDb.bookChapterDao.getChapterList(old.bookUrl)
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
            val sourceChapter = oldToc.getOrNull(bookmark.chapterIndex)
            val sameContent = targetChapter != null && sourceChapter != null &&
                hasSameReadingContent(old, sourceChapter, keep, targetChapter)
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
    fun getFingerprint(book: Book): BookFingerprint? {
        if (book.isAudio || book.isVideo || book.isImage || book.isPdf) return null
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl, 0, MAX_SCAN_CHAPTERS - 1)
        val candidates = chapters.filter {
            !it.isVolume
                && !boilerplateTitleRegex.containsMatchIn(it.title)
                && BookHelp.hasContent(book, it)
        }
        val sourceKey = MD5Utils.md5Encode16(candidates.joinToString("\n") { it.url })
        val old = appDb.bookFingerprintDao.get(book.bookUrl)
        if (old != null && old.sourceKey == sourceKey) return old
        val hashes = arrayListOf<Long>()
        for (chapter in candidates) {
            if (hashes.size >= SAMPLE_CHAPTERS) break
            val content = kotlin.runCatching {
                BookHelp.getContent(book, chapter)
            }.getOrNull() ?: continue
            val text = normalizeText(
                content.take(MAX_CHAPTER_CHARS * 2).replace(imgRegex, "")
            ).take(MAX_CHAPTER_CHARS)
            if (text.length < MIN_CHAPTER_CHARS) continue
            hashes.add(simHash(text))
        }
        val fingerprint = BookFingerprint(
            bookUrl = book.bookUrl,
            sourceKey = sourceKey,
            chapterHashes = hashes.joinToString(",")
        )
        appDb.bookFingerprintDao.insert(fingerprint)
        return fingerprint
    }

    private fun isSimilarContent(a: List<Long>, b: List<Long>): Boolean {
        for (x in a) {
            for (y in b) {
                if (java.lang.Long.bitCount(x xor y) <= SIMHASH_DISTANCE) return true
            }
        }
        return false
    }

    private fun simHash(text: String): Long {
        if (text.length < SHINGLE_SIZE) return 0L
        val weights = IntArray(64)
        for (start in 0..text.length - SHINGLE_SIZE) {
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
