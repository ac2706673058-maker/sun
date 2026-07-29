package com.murmur.app.engine

import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.net.OpenAiClient
import com.murmur.app.net.StreamEvent
import com.murmur.app.net.WireMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 用一个真的 socket 跑一遍流式解析。
 * SSE 的分片、粘包、[DONE]、错误体这几种情况在真机上最难查，
 * 所以这里直接按字节喂进去，连拆包边界都自己控制。
 */
class StreamingTest {

    private lateinit var server: ServerSocket
    private val client = OpenAiClient()

    /** 每个测试设定一次响应：状态码 + 若干次写入（每次写入之间会 flush，模拟网络分片）。 */
    private var response: Pair<Int, List<String>> = 200 to emptyList()
    private var contentLength = false

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            val input = s.getInputStream()
            // 读完请求头
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            var read = 0
            while (read < len) {
                val n = input.read(ByteArray(len - read))
                if (n < 0) break
                read += n
            }

            val (code, chunks) = response
            val out = s.getOutputStream()
            val body = chunks.joinToString("")
            val header = buildString {
                append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
                if (contentLength) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${body.toByteArray().size}\r\n")
                } else {
                    append("Content-Type: text/event-stream\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray())
            out.flush()
            chunks.forEach {
                out.write(it.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            out.flush()
        }
    }

    private fun stream(vararg chunks: String) {
        response = 200 to chunks.toList()
        contentLength = false
    }

    private fun json(code: Int, body: String) {
        response = code to listOf(body)
        contentLength = true
    }

    private fun profile(useStream: Boolean = true) = ApiProfileEntity(
        id = "t", name = "t",
        baseUrl = "http://127.0.0.1:${server.localPort}/v1",
        apiKey = "k", model = "test-model", stream = useStream,
    )

    private fun collect(p: ApiProfileEntity) = runBlocking {
        client.chat(p, listOf(WireMessage("user", "hi"))).toList()
    }

    private fun textOf(events: List<StreamEvent>) =
        events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }

    @Test
    fun `assembles deltas from a normal sse stream`() {
        stream(
            "data: {\"choices\":[{\"delta\":{\"content\":\"*她抬起\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"头。* 你来了。\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n",
        )
        val events = collect(profile())
        assertEquals("*她抬起头。* 你来了。", textOf(events))
        assertEquals("stop", events.filterIsInstance<StreamEvent.Done>().single().finishReason)
    }

    @Test
    fun `handles an event split across packet boundaries`() {
        stream(
            "data: {\"choices\":[{\"delta\":",
            "{\"content\":\"分片也要拼对\"}}]}",
            "\n\ndata: [DONE]\n\n",
        )
        assertEquals("分片也要拼对", textOf(collect(profile())))
    }

    @Test
    fun `handles multiple events arriving in one packet`() {
        stream(
            "data: {\"choices\":[{\"delta\":{\"content\":\"一\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"二\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"三\"}}]}\n\n" +
                "data: [DONE]\n\n"
        )
        assertEquals("一二三", textOf(collect(profile())))
    }

    @Test
    fun `captures reasoning content separately from the answer`() {
        stream(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想一下\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"正文\"}}]}\n\n",
            "data: [DONE]\n\n",
        )
        val events = collect(profile())
        assertEquals("正文", textOf(events))
        assertEquals(
            "先想一下",
            events.filterIsInstance<StreamEvent.Reasoning>().joinToString("") { it.text },
        )
    }

    @Test
    fun `reads usage from the trailing chunk`() {
        stream(
            "data: {\"choices\":[{\"delta\":{\"content\":\"嗯\"}}]}\n\n",
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":42}}\n\n",
            "data: [DONE]\n\n",
        )
        val done = collect(profile()).filterIsInstance<StreamEvent.Done>().single()
        assertEquals(1200, done.promptTokens)
        assertEquals(42, done.completionTokens)
    }

    @Test
    fun `surfaces a 404 with the server's own words`() {
        json(404, """{"error":{"message":"model not found","type":"invalid_request_error"}}""")
        val failed = collect(profile()).filterIsInstance<StreamEvent.Failed>().single()
        assertEquals(404, failed.httpCode)
        assertTrue(failed.message.contains("Base URL"))
        assertTrue(failed.message.contains("model not found"))
    }

    @Test
    fun `explains a 401 in plain language`() {
        json(401, """{"error":{"message":"invalid api key"}}""")
        val failed = collect(profile()).filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.message.contains("API Key"))
    }

    @Test
    fun `suggests disabling advanced sampling on a 400`() {
        json(400, """{"error":{"message":"unknown field min_p"}}""")
        val failed = collect(profile()).filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.message.contains("高级采样参数"))
    }

    @Test
    fun `handles an error delivered inside the stream`() {
        stream("data: {\"error\":{\"message\":\"content filtered upstream\"}}\n\n")
        val failed = collect(profile()).filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.message.contains("content filtered upstream"))
    }

    @Test
    fun `non streaming mode reads the whole message`() {
        json(
            200,
            """{"choices":[{"message":{"content":"整段返回的内容"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
        )
        val events = collect(profile(useStream = false))
        assertEquals("整段返回的内容", textOf(events))
        assertEquals(10, events.filterIsInstance<StreamEvent.Done>().single().promptTokens)
    }

    @Test
    fun `reports an empty stream instead of silently succeeding`() {
        stream("data: [DONE]\n\n")
        val failed = collect(profile()).filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.message.contains("没吐出任何内容"))
    }

    @Test
    fun `complete() returns joined text for background tasks`() = runBlocking {
        json(200, """{"choices":[{"message":{"content":"{\"location\":\"直播间\"}"}}]}""")
        val r = client.complete(profile(), listOf(WireMessage("user", "x")), "test-model", 100, 0.3f)
        assertTrue(r.isSuccess)
        assertEquals("""{"location":"直播间"}""", r.getOrNull())
    }

    @Test
    fun `missing address fails fast with an actionable message`() {
        val failed = collect(profile().copy(baseUrl = ""))
            .filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.message.contains("接口地址"))
    }

    @Test
    fun `missing model fails fast with an actionable message`() {
        val failed = collect(profile().copy(model = ""))
            .filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.message.contains("模型"))
    }

    @Test
    fun `lists models from the models endpoint`() = runBlocking {
        json(200, """{"data":[{"id":"model-b"},{"id":"model-a"}]}""")
        val p = profile()
        assertEquals(listOf("model-a", "model-b"), client.listModels(p.baseUrl, p.apiKey, "{}").getOrNull())
    }
}
