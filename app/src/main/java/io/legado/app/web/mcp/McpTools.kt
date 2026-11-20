package io.legado.app.web.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx

/**
 * MCP工具：书源的读取、保存、抓取网页、测试规则、完整调试
 */
object McpTools {

    class Tool(
        val name: String,
        val description: String,
        val inputSchema: Map<String, Any>,
        val handler: suspend (JsonObject) -> String
    )

    /**
     * 工具执行失败，返回给AI的错误信息
     */
    class ToolException(message: String) : Exception(message)

    private const val MAX_DEBUG_LOG = 60000
    private const val MAX_HTML_LOG = 3000
    private const val MAX_ELEMENT = 800

    // 调试日志中网页源码的状态码：搜索页、详情页、目录页、正文页
    private val htmlStates = setOf(10, 20, 30, 40)

    // 调试器是全局单例，同时只能调试一个书源
    private val debugMutex = Mutex()

    private val helpFiles = linkedMapOf(
        "rule" to "web/help/md/ruleHelp.md",
        "js" to "web/help/md/jsHelp.md",
        "regex" to "web/help/md/regexHelp.md",
        "xpath" to "web/help/md/xpathHelp.md",
        "debug" to "web/help/md/debugHelp.md"
    )

    val tools: List<Tool> = listOf(
        Tool(
            name = "get_rule_help",
            description = "获取书源规则文档。rule：书源规则语法（CSS/XPath/JSONPath/正则/JS、URL参数等），" +
                    "js：JS扩展函数（java.ajax等），regex：正则，xpath：XPath，debug：调试说明。编写书源前先阅读rule。",
            inputSchema = schema(
                listOf("topic"),
                "topic" to enumProp("文档主题", helpFiles.keys.toList())
            ),
            handler = ::getRuleHelp
        ),
        Tool(
            name = "list_book_sources",
            description = "列出App中已有的书源（名称、URL、分组、是否启用），可按关键字筛选，用于参考已有书源写法",
            inputSchema = schema(
                emptyList(),
                "query" to prop("string", "按名称、URL、分组筛选的关键字，可选"),
                "limit" to prop("integer", "最多返回条数，默认50")
            ),
            handler = ::listBookSources
        ),
        Tool(
            name = "get_book_source",
            description = "获取书源完整JSON",
            inputSchema = schema(
                listOf("source_url"),
                "source_url" to prop("string", "书源URL（bookSourceUrl）")
            ),
            handler = ::getBookSource
        ),
        Tool(
            name = "save_book_source",
            description = "保存书源到App。bookSourceUrl相同则覆盖原书源。" +
                    "必须包含bookSourceName和bookSourceUrl，规则字段见get_rule_help(rule)。保存后用debug_book_source调试。",
            inputSchema = schema(
                listOf("source"),
                "source" to prop("object", "书源JSON对象")
            ),
            handler = ::saveBookSource
        ),
        Tool(
            name = "fetch_url",
            description = "在App中请求网页并返回源码，用于查看页面结构以编写规则。" +
                    "url支持书源URL写法（如 https://a.com/s?q={{key}},{\"method\":\"POST\",\"body\":\"...\",\"webView\":true}）。" +
                    "指定source_url时使用该书源的请求头、Cookie和登录信息。内容较长时用offset分段读取。",
            inputSchema = schema(
                listOf("url"),
                "url" to prop("string", "网址，支持书源URL写法"),
                "source_url" to prop("string", "使用哪个书源的配置请求，可选"),
                "key" to prop("string", "替换url中{{key}}的搜索关键字，可选"),
                "page" to prop("integer", "替换url中{{page}}的页码，可选"),
                "offset" to prop("integer", "从第几个字符开始返回，默认0"),
                "max_length" to prop("integer", "最多返回字符数，默认20000")
            ),
            handler = ::fetchUrl
        ),
        Tool(
            name = "test_rule",
            description = "用App的规则引擎测试单条规则，返回解析结果。content和url二选一：" +
                    "content直接给出网页源码或JSON，url则先请求网页（同fetch_url）。" +
                    "mode：string取单个字符串（如书名），list取字符串列表，elements取元素列表（如bookList规则）。",
            inputSchema = schema(
                listOf("rule"),
                "rule" to prop("string", "要测试的规则"),
                "content" to prop("string", "网页源码或JSON，可选"),
                "url" to prop("string", "要请求的网址，可选"),
                "source_url" to prop("string", "使用哪个书源的配置（请求头、JS环境），可选"),
                "base_url" to prop("string", "content对应的网址，用于把相对链接转成绝对链接，可选"),
                "key" to prop("string", "url中{{key}}的搜索关键字，可选"),
                "mode" to enumProp("返回方式，默认string", listOf("string", "list", "elements")),
                "is_url" to prop("boolean", "结果是否为链接，为true时转换成绝对链接，默认false")
            ),
            handler = ::testRule
        ),
        Tool(
            name = "debug_book_source",
            description = "完整调试书源，返回调试日志（同App和网页端的书源调试）。key的写法：" +
                    "搜索关键字（搜索→详情→目录→正文）；详情页URL（从详情页开始）；" +
                    "\"标题::发现URL\"（从发现页开始）；\"++目录URL\"（只调试目录）；\"--正文URL\"（只调试正文）。" +
                    "日志以✓/︽等符号标记各步骤，出错时会给出异常信息。",
            inputSchema = schema(
                listOf("source_url", "key"),
                "source_url" to prop("string", "书源URL（需先save_book_source保存）"),
                "key" to prop("string", "调试关键字，见工具说明"),
                "timeout_seconds" to prop("integer", "超时秒数，默认60，最大300"),
                "include_html" to prop("boolean", "日志中是否包含各页面源码（每页截取前3000字符），默认false")
            ),
            handler = ::debugBookSource
        )
    )

    private val toolMap = tools.associateBy { it.name }

    fun get(name: String): Tool? = toolMap[name]

    private suspend fun getRuleHelp(args: JsonObject): String {
        val topic = args.str("topic") ?: "rule"
        val path = helpFiles[topic] ?: throw ToolException("未知主题：$topic，可选：${helpFiles.keys}")
        return appCtx.assets.open(path).use { String(it.readBytes()) }
    }

    private suspend fun listBookSources(args: JsonObject): String {
        val query = args.str("query")?.trim().orEmpty()
        val limit = (args.int("limit") ?: 50).coerceIn(1, 500)
        val all = appDb.bookSourceDao.allPart
        val matched = if (query.isEmpty()) all else all.filter {
            it.bookSourceName.contains(query, true)
                    || it.bookSourceUrl.contains(query, true)
                    || it.bookSourceGroup?.contains(query, true) == true
        }
        if (matched.isEmpty()) return "没有找到书源"
        return buildString {
            append("共${matched.size}个书源")
            if (matched.size > limit) append("，显示前${limit}个")
            append("\n名称 | URL | 分组 | 启用\n")
            matched.take(limit).forEach {
                append("${it.bookSourceName} | ${it.bookSourceUrl} | ${it.bookSourceGroup.orEmpty()} | ${if (it.enabled) "是" else "否"}\n")
            }
        }
    }

    private suspend fun getBookSource(args: JsonObject): String {
        val source = requireSource(args.str("source_url"))
        return GSON.toJson(source)
    }

    private suspend fun saveBookSource(args: JsonObject): String {
        val element = args.get("source") ?: throw ToolException("source不能为空")
        val json = when {
            element.isJsonObject -> element.toString()
            element.isJsonPrimitive -> element.asString
            else -> throw ToolException("source必须是书源JSON对象")
        }
        val source = GSON.fromJsonObject<BookSource>(json).getOrElse {
            throw ToolException("书源JSON解析失败：${it.localizedMessage}")
        }
        if (source.bookSourceName.isBlank() || source.bookSourceUrl.isBlank()) {
            throw ToolException("bookSourceName和bookSourceUrl不能为空")
        }
        val exists = appDb.bookSourceDao.getBookSource(source.bookSourceUrl) != null
        source.lastUpdateTime = System.currentTimeMillis()
        appDb.bookSourceDao.insert(source)
        return "${if (exists) "已覆盖" else "已新增"}书源：${source.bookSourceName}（${source.bookSourceUrl}）"
    }

    private suspend fun fetchUrl(args: JsonObject): String {
        val url = args.str("url") ?: throw ToolException("url不能为空")
        val source = args.str("source_url")?.let { requireSource(it) }
        val page = fetch(url, source, args.str("key"), args.int("page"))
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
        val maxLength = (args.int("max_length") ?: 20000).coerceIn(100, 200000)
        val body = page.body
        val end = (offset + maxLength).coerceAtMost(body.length)
        return buildString {
            append("请求地址：${page.requestUrl}\n")
            append("最终地址：${page.finalUrl}\n")
            append("状态码：${page.code}\n")
            append("源码长度：${body.length}")
            if (offset > 0 || end < body.length) {
                append("，本次返回 $offset-$end")
                if (end < body.length) append("，剩余内容用 offset=$end 继续读取")
            }
            append("\n\n")
            if (offset < body.length) append(body, offset, end)
        }
    }

    private suspend fun testRule(args: JsonObject): String {
        val rule = args.str("rule") ?: throw ToolException("rule不能为空")
        val source = args.str("source_url")?.let { requireSource(it) }
        var content = args.str("content")
        var baseUrl = args.str("base_url").orEmpty()
        val url = args.str("url")
        if (content == null) {
            url ?: throw ToolException("content和url至少提供一个")
            val page = fetch(url, source, args.str("key"), null)
            content = page.body
            baseUrl = page.finalUrl
        }
        val isUrl = args.bool("is_url") ?: false
        val analyzeRule = AnalyzeRule(RuleData(), source)
            .setCoroutineContext(currentCoroutineContext())
        analyzeRule.setContent(content, baseUrl.ifEmpty { null })
        if (baseUrl.isNotEmpty()) {
            analyzeRule.setRedirectUrl(baseUrl)
        }
        return when (val mode = args.str("mode") ?: "string") {
            "string" -> {
                val result = analyzeRule.getString(rule, isUrl = isUrl)
                if (result.isEmpty()) "结果为空" else result
            }

            "list" -> {
                val list = analyzeRule.getStringList(rule, isUrl = isUrl).orEmpty()
                if (list.isEmpty()) "结果为空" else buildString {
                    append("共${list.size}条\n")
                    list.take(100).forEachIndexed { i, s -> append("[$i] $s\n") }
                    if (list.size > 100) append("……只显示前100条")
                }
            }

            "elements" -> {
                val elements = analyzeRule.getElements(rule)
                if (elements.isEmpty()) "结果为空" else buildString {
                    append("共${elements.size}个元素\n")
                    elements.take(30).forEachIndexed { i, e ->
                        val text = if (e is Map<*, *> || e is List<*>) GSON.toJson(e) else e.toString()
                        append("[$i] ${text.take(MAX_ELEMENT)}")
                        if (text.length > MAX_ELEMENT) append("……")
                        append("\n")
                    }
                    if (elements.size > 30) append("……只显示前30个")
                }
            }

            else -> throw ToolException("未知mode：$mode")
        }
    }

    private suspend fun debugBookSource(args: JsonObject): String {
        val source = requireSource(args.str("source_url"))
        val key = args.str("key")?.takeIf { it.isNotBlank() } ?: throw ToolException("key不能为空")
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(5, 300)
        val includeHtml = args.bool("include_html") ?: false
        return debugMutex.withLock {
            val logs = StringBuilder()
            val done = CompletableDeferred<Unit>()
            val callback = object : Debug.Callback {
                override fun printLog(state: Int, msg: String) {
                    synchronized(logs) {
                        if (state in htmlStates) {
                            if (includeHtml) {
                                logs.append(msg.take(MAX_HTML_LOG))
                                if (msg.length > MAX_HTML_LOG) logs.append("……")
                                logs.append('\n')
                            }
                        } else {
                            logs.append(msg).append('\n')
                        }
                    }
                    if (state == -1 || state == 1000) {
                        done.complete(Unit)
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                Debug.callback = callback
                Debug.startDebug(scope, source, key)
                val finished = withTimeoutOrNull(timeout * 1000L) { done.await() } != null
                if (!finished) {
                    synchronized(logs) { logs.append("调试超时（${timeout}秒），已停止\n") }
                }
            } finally {
                Debug.cancelDebug(true)
                scope.cancel()
            }
            val text = synchronized(logs) { logs.toString() }
            if (text.length > MAX_DEBUG_LOG) {
                text.take(MAX_DEBUG_LOG) + "\n……日志过长，已截断"
            } else {
                text.ifEmpty { "没有调试日志" }
            }
        }
    }

    private class FetchResult(val requestUrl: String, val finalUrl: String, val code: Int, val body: String)

    private suspend fun fetch(url: String, source: BookSource?, key: String?, page: Int?): FetchResult {
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            key = key,
            page = page,
            baseUrl = source?.bookSourceUrl.orEmpty(),
            source = source,
            ruleData = RuleData(),
            coroutineContext = currentCoroutineContext()
        )
        val res = analyzeUrl.getStrResponseAwait()
        return FetchResult(analyzeUrl.url, res.url, res.code(), res.body.orEmpty())
    }

    private fun requireSource(url: String?): BookSource {
        if (url.isNullOrBlank()) throw ToolException("source_url不能为空")
        return appDb.bookSourceDao.getBookSource(url)
            ?: throw ToolException("书源不存在：$url，请先用save_book_source保存")
    }

    private fun schema(required: List<String>, vararg props: Pair<String, Map<String, Any>>): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to linkedMapOf(*props),
            "required" to required
        )
    }

    private fun prop(type: String, description: String): Map<String, Any> {
        return mapOf("type" to type, "description" to description)
    }

    private fun enumProp(description: String, values: List<String>): Map<String, Any> {
        return mapOf("type" to "string", "description" to description, "enum" to values)
    }

    private fun JsonObject.str(name: String): String? {
        val e: JsonElement = get(name) ?: return null
        if (e.isJsonNull) return null
        return if (e.isJsonPrimitive) e.asString else e.toString()
    }

    private fun JsonObject.int(name: String): Int? {
        val e = get(name) ?: return null
        return if (e.isJsonPrimitive) e.asString.toDoubleOrNull()?.toInt() else null
    }

    private fun JsonObject.bool(name: String): Boolean? {
        val e = get(name) ?: return null
        if (!e.isJsonPrimitive) return null
        val p = e.asJsonPrimitive
        return if (p.isBoolean) p.asBoolean else p.asString.toBooleanStrictOrNull()
    }

}
