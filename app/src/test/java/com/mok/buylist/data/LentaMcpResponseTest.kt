package com.mok.buylist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LentaMcpResponseTest {
    @Test fun plainJsonIsUnchanged() {
        val json = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        assertEquals(json, mcpJsonMessage("application/json; charset=utf-8", json))
    }

    @Test fun streamUsesLatestJsonRpcDataMessage() {
        val response = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"result\":{\"progress\":1}}\n\n" +
            ": keep-alive\n\n" +
            "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}\n\n"
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{"content":[]}}""",
            mcpJsonMessage("text/event-stream; charset=utf-8", response))
    }

    @Test fun streamRejectsMissingJson() {
        assertThrows(IOException::class.java) {
            mcpJsonMessage("text/event-stream", ": ping\n\ndata: [DONE]\n\n")
        }
    }
}
