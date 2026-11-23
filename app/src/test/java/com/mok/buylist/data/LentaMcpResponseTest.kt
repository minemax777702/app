package com.mok.buylist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.ByteArrayInputStream

class LentaMcpResponseTest {
    @Test fun plainJsonIsUnchanged() {
        val json = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        assertEquals(json, readMcpResponse("application/json; charset=utf-8", ByteArrayInputStream(json.toByteArray())))
    }

    @Test fun streamUsesLatestJsonRpcDataMessage() {
        val response = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"result\":{\"progress\":1}}\n\n" +
            ": keep-alive\n\n" +
            "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}\n\n"
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{"content":[]}}""",
            readMcpResponse("text/event-stream; charset=utf-8", ByteArrayInputStream(response.toByteArray())) {
                it.contains("\"id\":1")
            })
    }

    @Test fun streamRejectsMissingJson() {
        assertThrows(IOException::class.java) {
            readMcpResponse("text/event-stream",
                ByteArrayInputStream(": ping\n\ndata: [DONE]\n\n".toByteArray())) {
                it.trimStart().startsWith("{")
            }
        }
    }
}
