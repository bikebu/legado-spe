package io.legado.app.web.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.AppLog
import io.legado.app.help.config.LocalConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MCP服务（Streamable HTTP，无状态，只返回JSON不使用SSE）
 * 地址：http://手机IP:Web服务端口/mcp，需要 Authorization: Bearer 令牌
 */
object McpServer {

    const val PATH = "/mcp"

    private const val LATEST_PROTOCOL_VERSION = "2025-06-18"
    private val supportedProtocolVersions = setOf("2025-06-18", "2025-03-26", "2024-11-05")

    private const val JSON_MIME = "application/json; charset=utf-8"

    // JSON-RPC错误响应的id可能为null，需要保留null
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    private val instructions = """
        Sigma-Spe 书源编写与调试服务，书源保存在手机上的App中。
        建议流程：
        1. get_rule_help(rule) 了解书源规则语法，必要时再看 js；
        2. list_book_sources / get_book_source 参考已有书源的写法；
        3. fetch_url 查看目标网站搜索页、详情页、目录页、正文页的源码；
        4. test_rule 逐条验证规则（bookList 等列表规则用 elements 模式）；
        5. save_book_source 保存书源；
        6. debug_book_source 完整调试，根据日志修改后重新保存、调试，直到能取到正文。
    """.trimIndent()

    fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!LocalConfig.mcpEnabled) {
            return textResponse(Status.NOT_FOUND, "MCP服务未开启，请在App 设置-其它设置-MCP服务 中开启")
        }
        if (!isAuthorized(session.headers["authorization"])) {
            return textResponse(Status.UNAUTHORIZED, "令牌无效").apply {
                addHeader("WWW-Authenticate", "Bearer")
            }
        }
        if (session.method != NanoHTTPD.Method.POST) {
            // 不提供SSE推送流
            return textResponse(Status.METHOD_NOT_ALLOWED, "只支持POST").apply {
                addHeader("Allow", "POST")
            }
        }
        val body = kotlin.runCatching {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"]
        }.getOrNull()
        val request = kotlin.runCatching { JsonParser.parseString(body) }.getOrNull()
        if (request == null || !(request.isJsonObject || request.isJsonArray)) {
            return jsonResponse(Status.BAD_REQUEST, errorMessage(null, -32700, "JSON解析失败"))
        }
        val response: Any? = if (request.isJsonArray) {
            request.asJsonArray.mapNotNull { handle(it) }.takeIf { it.isNotEmpty() }
        } else {
            handle(request)
        }
        // 只有通知没有请求时返回202
        if (response == null) {
            return NanoHTTPD.newFixedLengthResponse(Status.ACCEPTED, JSON_MIME, "")
        }
        return jsonResponse(Status.OK, response)
    }

    private fun isAuthorized(header: String?): Boolean {
        val token = LocalConfig.mcpToken
        if (token.isNullOrEmpty() || header == null) return false
        val provided = header.removePrefix("Bearer ").trim()
        // 常量时间比较，避免通过响应时间猜测令牌
        return MessageDigest.isEqual(provided.toByteArray(), token.toByteArray())
    }

    /**
     * 处理一条JSON-RPC消息，通知和客户端返回的响应不需要回复，返回null
     */
    private fun handle(element: JsonElement): Map<String, Any?>? {
        if (!element.isJsonObject) {
            return errorMessage(null, -32600, "无效请求")
        }
        val message = element.asJsonObject
        val id = message.get("id")?.takeUnless { it.isJsonNull }
        val method = message.get("method")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return if (id == null) null else errorMessage(id, -32600, "缺少method")
        if (id == null) {
            // notifications/initialized 等通知
            return null
        }
        val params = message.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return try {
            val result: Any = when (method) {
                "initialize" -> initialize(params)
                "ping" -> emptyMap<String, Any>()
                "tools/list" -> mapOf("tools" to McpTools.tools.map {
                    mapOf(
                        "name" to it.name,
                        "description" to it.description,
                        "inputSchema" to it.inputSchema
                    )
                })

                "tools/call" -> callTool(params)
                else -> return errorMessage(id, -32601, "不支持的方法：$method")
            }
            mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)
        } catch (e: InvalidParamsException) {
            errorMessage(id, -32602, e.message ?: "参数错误")
        } catch (e: Exception) {
            AppLog.put("MCP处理请求出错：$method\n${e.localizedMessage}", e)
            errorMessage(id, -32603, e.localizedMessage ?: e.toString())
        }
    }

    private fun initialize(params: JsonObject): Map<String, Any> {
        val requested = params.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
        val version = requested?.takeIf { it in supportedProtocolVersions } ?: LATEST_PROTOCOL_VERSION
        return mapOf(
            "protocolVersion" to version,
            "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
            "serverInfo" to mapOf("name" to "sigma-spe", "version" to appInfo.versionName),
            "instructions" to instructions
        )
    }

    private fun callTool(params: JsonObject): Map<String, Any> {
        val name = params.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw InvalidParamsException("缺少工具名称")
        val tool = McpTools.get(name) ?: throw InvalidParamsException("未知工具：$name")
        val args = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return try {
            val text = runBlocking(Dispatchers.IO) { tool.handler(args) }
            toolResult(text, false)
        } catch (e: McpTools.ToolException) {
            toolResult(e.message ?: "执行失败", true)
        } catch (e: Exception) {
            AppLog.put("MCP工具执行出错：$name\n${e.localizedMessage}", e)
            toolResult("执行出错：${e.localizedMessage ?: e}", true)
        }
    }

    private fun toolResult(text: String, isError: Boolean): Map<String, Any> {
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "isError" to isError
        )
    }

    private fun errorMessage(id: JsonElement?, code: Int, message: String): Map<String, Any?> {
        return mapOf(
            "jsonrpc" to "2.0",
            "id" to (id ?: JsonNull.INSTANCE),
            "error" to mapOf("code" to code, "message" to message)
        )
    }

    private fun jsonResponse(status: Status, data: Any): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(status, JSON_MIME, gson.toJson(data))
    }

    private fun textResponse(status: Status, text: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", text)
    }

    private class InvalidParamsException(message: String) : Exception(message)

}
