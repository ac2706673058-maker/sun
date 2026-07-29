package com.murmur.app.net

import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.data.db.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class WireMessage(val role: String, val content: String)

sealed interface StreamEvent {
    /** 正文增量 */
    data class Delta(val text: String) : StreamEvent
    /** 思维链增量（DeepSeek-R1 等会单独走 reasoning_content） */
    data class Reasoning(val text: String) : StreamEvent
    data class Done(val finishReason: String?, val promptTokens: Int?, val completionTokens: Int?) : StreamEvent
    data class Failed(val message: String, val httpCode: Int? = null) : StreamEvent
}

class OpenAiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun chat(
        profile: ApiProfileEntity,
        messages: List<WireMessage>,
        modelOverride: String? = null,
        maxTokensOverride: Int? = null,
        temperatureOverride: Float? = null,
        forceNonStream: Boolean = false,
    ): Flow<StreamEvent> = flow {
        val useStream = profile.stream && !forceNonStream
        val url = resolveChatUrl(profile.baseUrl)
        if (url == null) {
            emit(StreamEvent.Failed("接口地址没填，或者格式不对。到「设置 → 接口」里填一下。"))
            return@flow
        }
        val model = modelOverride?.takeIf { it.isNotBlank() } ?: profile.model
        if (model.isBlank()) {
            emit(StreamEvent.Failed("还没选模型。到「设置 → 接口」里填模型名，或点「拉取模型列表」。"))
            return@flow
        }

        val body = buildRequestBody(profile, messages, model, useStream, maxTokensOverride, temperatureOverride)
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .apply {
                header("Content-Type", "application/json")
                if (profile.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${profile.apiKey.trim()}")
                    header("x-api-key", profile.apiKey.trim())
                }
                if (useStream) header("Accept", "text/event-stream")
                parseExtraHeaders(profile.extraHeadersJson).forEach { (k, v) -> header(k, v) }
            }
            .build()

        val call = http.newCall(request)
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { if (it != null) call.cancel() }

        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                emit(StreamEvent.Failed(humanizeNetworkError(e)))
                return@flow
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val raw = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    emit(StreamEvent.Failed(humanizeHttpError(resp.code, raw), resp.code))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Failed("服务端没有返回内容。"))
                    return@flow
                }

                if (!useStream) {
                    val text = source.readUtf8()
                    val obj = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
                    if (obj == null) {
                        emit(StreamEvent.Failed("返回的不是合法 JSON：${text.take(200)}"))
                        return@flow
                    }
                    obj["error"]?.let {
                        emit(StreamEvent.Failed(extractErrorMessage(text) ?: "服务端返回了错误。"))
                        return@flow
                    }
                    val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    val msg = choice?.get("message")?.jsonObject
                    msg?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.Reasoning(it)) }
                    val content = msg?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (content.isNotEmpty()) emit(StreamEvent.Delta(content))
                    val usage = obj["usage"]?.jsonObject
                    emit(
                        StreamEvent.Done(
                            choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull,
                            usage?.get("prompt_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                            usage?.get("completion_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        )
                    )
                    return@flow
                }

                var finishReason: String? = null
                var promptTokens: Int? = null
                var completionTokens: Int? = null
                var sawAnything = false
                val dataBuf = StringBuilder()

                while (currentCoroutineContext().isActive) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: IOException) {
                        if (!currentCoroutineContext().isActive) break
                        emit(StreamEvent.Failed(humanizeNetworkError(e)))
                        return@flow
                    } ?: break

                    when {
                        line.startsWith("data:") -> dataBuf.append(line.removePrefix("data:").trim())
                        line.isEmpty() -> {
                            val payload = dataBuf.toString()
                            dataBuf.setLength(0)
                            if (payload.isEmpty()) continue
                            if (payload == "[DONE]") break

                            val obj = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull()
                                ?: continue
                            obj["error"]?.let {
                                emit(StreamEvent.Failed(extractErrorMessage(payload) ?: "服务端返回了错误。"))
                                return@flow
                            }
                            obj["usage"]?.jsonObject?.let { u ->
                                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: promptTokens
                                completionTokens = u["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: completionTokens
                            }
                            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                            choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                            val delta = choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                            delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotEmpty() }?.let { sawAnything = true; emit(StreamEvent.Reasoning(it)) }
                            delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotEmpty() }?.let { sawAnything = true; emit(StreamEvent.Reasoning(it)) }
                            val c = delta?.get("content")?.jsonPrimitive?.contentOrNull
                            if (!c.isNullOrEmpty()) { sawAnything = true; emit(StreamEvent.Delta(c)) }
                        }
                        // 非 SSE 格式（少数端点忽略 stream 直接返回整个 JSON）
                        line.startsWith("{") -> {
                            val obj = runCatching { AppJson.parseToJsonElement(line).jsonObject }.getOrNull()
                            val content = obj?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
                                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                            if (!content.isNullOrEmpty()) { sawAnything = true; emit(StreamEvent.Delta(content)) }
                        }
                    }
                }

                if (!sawAnything && finishReason == null) {
                    emit(StreamEvent.Failed("服务端连上了，但没吐出任何内容。可能是模型名不对，或者这个端点不支持流式，试试在接口设置里关掉「流式输出」。"))
                    return@flow
                }
                emit(StreamEvent.Done(finishReason, promptTokens, completionTokens))
            }
        } finally {
            handle?.dispose()
            if (!call.isCanceled()) call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** 一次性取完整文本，用于摘要 / 记忆抽取 / 场景跟踪这些后台小任务。 */
    suspend fun complete(
        profile: ApiProfileEntity,
        messages: List<WireMessage>,
        model: String,
        maxTokens: Int,
        temperature: Float,
    ): Result<String> {
        val sb = StringBuilder()
        var error: String? = null
        chat(
            profile = profile,
            messages = messages,
            modelOverride = model,
            maxTokensOverride = maxTokens,
            temperatureOverride = temperature,
            forceNonStream = true,
        ).collect { ev ->
            when (ev) {
                is StreamEvent.Delta -> sb.append(ev.text)
                is StreamEvent.Failed -> error = ev.message
                else -> Unit
            }
        }
        return error?.let { Result.failure(IOException(it)) } ?: Result.success(sb.toString())
    }

    suspend fun listModels(baseUrl: String, apiKey: String, extraHeadersJson: String): Result<List<String>> {
        val url = resolveModelsUrl(baseUrl) ?: return Result.failure(IOException("接口地址格式不对"))
        val req = Request.Builder().url(url).get().apply {
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer ${apiKey.trim()}")
                header("x-api-key", apiKey.trim())
            }
            parseExtraHeaders(extraHeadersJson).forEach { (k, v) -> header(k, v) }
        }.build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException(humanizeHttpError(resp.code, text))
                val root = AppJson.parseToJsonElement(text)
                val arr: JsonArray = when {
                    root is JsonObject && root["data"] != null -> root["data"]!!.jsonArray
                    root is JsonArray -> root
                    else -> throw IOException("没看懂返回的模型列表格式")
                }
                arr.mapNotNull {
                    it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        ?: it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                }.sorted()
            }
        }
    }

    // ---- 请求体 ----

    private fun buildRequestBody(
        p: ApiProfileEntity,
        messages: List<WireMessage>,
        model: String,
        stream: Boolean,
        maxTokensOverride: Int?,
        temperatureOverride: Float?,
    ): JsonObject {
        val base = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach {
                    add(buildJsonObject {
                        put("role", it.role)
                        put("content", it.content)
                    })
                }
            })
            put("stream", stream)
            put("temperature", (temperatureOverride ?: p.temperature).toDouble())
            put("max_tokens", maxTokensOverride ?: p.maxTokens)
            if (p.sendAdvancedSampling) {
                put("top_p", p.topP.toDouble())
                if (p.frequencyPenalty != 0f) put("frequency_penalty", p.frequencyPenalty.toDouble())
                if (p.presencePenalty != 0f) put("presence_penalty", p.presencePenalty.toDouble())
                if (p.topK > 0) put("top_k", p.topK)
                if (p.minP > 0f) put("min_p", p.minP.toDouble())
                if (p.repetitionPenalty != 1.0f) put("repetition_penalty", p.repetitionPenalty.toDouble())
            }
            if (p.stopSequences.isNotEmpty()) {
                put("stop", buildJsonArray { p.stopSequences.forEach { add(JsonPrimitive(it)) } })
            }
            if (stream) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
        }
        val extra = runCatching {
            AppJson.parseToJsonElement(p.extraBodyJson.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return base
        if (extra.isEmpty()) return base
        return JsonObject(base.toMutableMap().apply { putAll(extra) })
    }

    private fun parseExtraHeaders(json: String): Map<String, String> = runCatching {
        AppJson.parseToJsonElement(json.ifBlank { "{}" }).jsonObject
            .mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
            .toMap()
    }.getOrDefault(emptyMap())

    companion object {
        /** 用户会粘各种形状的地址，这里统统归一到 .../chat/completions。 */
        fun resolveChatUrl(raw: String): String? {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return when {
                s.endsWith("/chat/completions") -> s
                s.endsWith("/completions") -> s
                s.endsWith("/v1") -> "$s/chat/completions"
                s.contains("/v1/") -> s.substringBefore("/v1/") + "/v1/chat/completions"
                else -> "$s/v1/chat/completions"
            }
        }

        fun resolveModelsUrl(raw: String): String? {
            val chat = resolveChatUrl(raw) ?: return null
            return chat.replace("/chat/completions", "/models").replace("/completions", "/models")
        }

        fun humanizeHttpError(code: Int, raw: String): String {
            val detail = extractErrorMessage(raw)
            val head = when (code) {
                401, 403 -> "API Key 被拒了（$code）。检查 Key 是否填对、是否过期、是否有这个模型的权限。"
                404 -> "地址找不到（404）。多半是 Base URL 填错了，或者模型名不存在。"
                400 -> "请求被判为不合法（400）。常见原因：模型名写错，或者某个采样参数这个端点不支持——可以到接口设置里关掉「发送高级采样参数」再试。"
                408, 504 -> "网关超时（$code）。服务端太久没响应。"
                413 -> "请求太大（413）。把「上下文长度」调小一点。"
                429 -> "被限流了（429）。等一会儿，或者换个 Key。"
                in 500..599 -> "服务端出错（$code）。"
                else -> "请求失败（$code）。"
            }
            return if (detail.isNullOrBlank()) head else "$head\n\n服务端说：$detail"
        }

        fun extractErrorMessage(raw: String): String? = runCatching {
            val root = AppJson.parseToJsonElement(raw)
            val obj = root.jsonObject
            val err = obj["error"]
            when {
                err is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                err is JsonPrimitive -> err.contentOrNull
                else -> obj["message"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull() ?: raw.take(300).takeIf { it.isNotBlank() }

        fun humanizeNetworkError(e: IOException): String {
            val m = e.message.orEmpty()
            return when {
                m.contains("timeout", true) -> "连接超时。检查网络，或者这个接口本来就慢——可以把「最大回复长度」调小。"
                m.contains("Unable to resolve host", true) -> "域名解析不了。检查网络，或者地址写错了。"
                m.contains("CertPathValidator", true) || m.contains("SSL", true) ->
                    "HTTPS 证书校验没过。如果是自建服务，用 http:// 或者换个带正规证书的域名。"
                m.contains("Failed to connect", true) || m.contains("ECONNREFUSED", true) ->
                    "连不上服务器。地址/端口对吗？如果是本机服务，手机和电脑要在同一个网络里。"
                m.contains("Canceled", true) -> "已取消。"
                else -> "网络出错：${m.ifBlank { e.toString() }}"
            }
        }
    }
}
