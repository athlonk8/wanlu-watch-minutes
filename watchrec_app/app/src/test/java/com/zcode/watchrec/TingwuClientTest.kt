package com.zcode.watchrec

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class TingwuClientTest {
    private class Connection(url: URL, val code: Int = 200, val body: String = "{\"code\":\"0\",\"data\":{}}") : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var released = false
        override fun connect() {}
        override fun disconnect() { released = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }
    private fun fixture(): File = File.createTempFile("wanlu-tingwu-", ".m4a").apply { writeBytes(ByteArray(150_001) { (it % 251).toByte() }) }

    @Test fun uploadDestinationsAreHttpsOssOnly() {
        assertEquals("bucket.oss-cn-shanghai.aliyuncs.com", TingwuProtocol.uploadUrl("https://bucket.oss-cn-shanghai.aliyuncs.com/a?signature=test").host)
        listOf("http://bucket.oss-cn-shanghai.aliyuncs.com/a", "https://evil.test/a", "https://aliyuncs.com.evil.test/a", "https://x.aliyuncs.com@evil.test/a", "https://x.aliyuncs.com:8443/a", "https://x.aliyuncs.com/a#token", "https://bucket.oss-cn-shanghai-internal.aliyuncs.com/a").forEach { value ->
            assertThrows(TingwuFailure::class.java) { TingwuProtocol.uploadUrl(value) }
        }
    }

    @Test fun cookieIsSentOnlyToTingwuAndStorageGetsExactBytes() {
        val connections = mutableListOf<Connection>()
        val client = TingwuClient({ "session=synthetic-test" }, open = { url -> Connection(url).also(connections::add) })
        val file = fixture()
        try {
            client.api("generatePutLink", JSONObject().put("fileSize", file.length()))
            var progress = 0L
            client.put(file, "https://bucket.oss-cn-shanghai.aliyuncs.com/file?signature=test") { sent, _ -> progress = sent }
            assertEquals("tingwu.aliyun.com", connections[0].url.host)
            assertEquals("session=synthetic-test", connections[0].getRequestProperty("Cookie"))
            assertNull(connections[1].getRequestProperty("Cookie"))
            assertNull(connections[1].getRequestProperty("Authorization"))
            assertEquals("PUT", connections[1].requestMethod)
            assertEquals("audio/mp4", connections[1].getRequestProperty("Content-Type"))
            assertArrayEquals(file.readBytes(), connections[1].sent.toByteArray())
            assertEquals(file.length(), progress)
            assertTrue(connections.all { it.released && !it.instanceFollowRedirects })
        } finally { file.delete() }
    }

    @Test fun redirectsNeverReceiveSessionCredentials() {
        val connection = Connection(URL("https://tingwu.aliyun.com/"), 302)
        val client = TingwuClient({ "session=test" }, open = { connection })
        val error = assertThrows(TingwuFailure::class.java) { client.api("startTrans") }
        assertTrue(error.loginRequired)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.released)
    }

    @Test fun expiredLoginIsNotReportedAsUploaded() {
        val client = TingwuClient({ "session=test" }, open = { Connection(it, body = "{\"code\":\"CMN.NotLogin\",\"data\":{}}") })
        assertTrue(assertThrows(TingwuFailure::class.java) { client.api("syncPutLink") }.loginRequired)
        assertThrows(TingwuFailure::class.java) { TingwuProtocol.data(JSONObject("{\"success\":true}")) }
    }

    @Test fun boundedResponseRejectsOversizedAndMalformedJson() {
        assertThrows(TingwuFailure::class.java) { TingwuProtocol.readJson(ByteArrayInputStream(ByteArray(256 * 1024 + 1))) }
        assertThrows(TingwuFailure::class.java) { TingwuProtocol.readJson(ByteArrayInputStream("<html>login</html>".toByteArray())) }
    }

    @Test fun cancellationDoesNotStartARequestOrRemoveSource() {
        var opened = false
        val client = TingwuClient({ "session=test" }, open = { opened = true; Connection(it) }, stopped = { true })
        val file = fixture()
        try {
            assertThrows(TingwuFailure::class.java) { client.put(file, "https://bucket.oss-cn-shanghai.aliyuncs.com/a") }
            assertThrows(TingwuFailure::class.java) { client.api("generatePutLink") }
            assertFalse(opened); assertEquals(150_001L, file.length())
        } finally { file.delete() }
    }

    @Test fun rejectedStorageUploadPreservesTheSource() {
        val client = TingwuClient({ "session=test" }, open = { Connection(it, 403) })
        val file = fixture()
        try {
            val before = file.readBytes()
            assertThrows(TingwuFailure::class.java) { client.put(file, "https://bucket.oss-cn-shanghai.aliyuncs.com/a") }
            assertArrayEquals(before, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun nativeApiRequestsUseObservedActionAndVersion() {
        val connection = Connection(URL("https://tingwu.aliyun.com/"))
        TingwuClient({ "session=test" }, open = { connection }).api("syncPutLink", JSONObject().put("action", "unexpected").put("transId", "fixture-id"))
        val body = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals("syncPutLink", body.getString("action")); assertEquals("1.0", body.getString("version")); assertEquals("fixture-id", body.getString("transId"))
    }

    @Test fun emptySessionDoesNotCreateCloudTasks() {
        var opened = false
        val client = TingwuClient({ "" }, open = { opened = true; Connection(it) })
        assertTrue(assertThrows(TingwuFailure::class.java) { client.api("generatePutLink") }.loginRequired)
        assertFalse(opened)
    }

    @Test fun cloudReconciliationMatchesOnlyTheSelectedRecord() {
        val response = JSONObject("{\"data\":[{\"transId\":\"other\",\"status\":0},{\"transId\":\"selected\",\"status\":3}]}")
        assertEquals(3, TingwuProtocol.cloudStatus(response, "selected"))
        assertEquals(-1, TingwuProtocol.cloudStatus(response, "missing"))
        assertEquals(-1, TingwuProtocol.cloudStatus(JSONObject("{\"data\":[{\"transId\":\"selected\",\"status\":99}]}"), "selected"))
    }

    @Test fun explicitRejectionKeepsOnlyTheNonSensitiveServiceCode() {
        val error = assertThrows(TingwuFailure::class.java) { TingwuProtocol.data(JSONObject("{\"code\":\"TRS.ExampleFailure\"}")) }
        assertEquals("TRS.ExampleFailure", error.serviceCode)
        val invalid = assertThrows(TingwuFailure::class.java) { TingwuProtocol.data(JSONObject("{\"code\":\"https://example.test/?token=secret\"}")) }
        assertEquals("", invalid.serviceCode)
    }

    @Test fun alreadyProcessingOrFinishedRecordsAreNotSubmittedAgain() {
        for (state in 0..2) {
            val actions = mutableListOf<String?>()
            val client = TingwuClient({ "session=test" }, open = {
                actions.add(it.query)
                Connection(it, body = "{\"code\":\"0\",\"data\":[{\"transId\":\"selected\",\"status\":$state}]}")
            })
            assertEquals(state, client.startOrReconcile("selected", JSONObject()))
            assertEquals(listOf("getTransStatus&c=web"), actions)
        }
    }

    @Test fun uploadReadyRecordsAreSubmittedOnce() {
        val actions = mutableListOf<String?>()
        val client = TingwuClient({ "session=test" }, open = {
            actions.add(it.query)
            Connection(it, body = if (it.query.startsWith("getTransStatus")) "{\"code\":\"0\",\"data\":[{\"transId\":\"selected\",\"status\":3}]}" else "{\"code\":\"0\"}")
        })
        assertEquals(1, client.startOrReconcile("selected", JSONObject()))
        assertEquals(listOf("getTransStatus&c=web", "startTrans&c=web"), actions)
    }

    @Test fun processingRaceIsReconciledBeforeReportingFailure() {
        var requests = 0
        val client = TingwuClient({ "session=test" }, open = {
            requests++
            Connection(it, body = when(requests) {
                1 -> "{\"code\":\"0\",\"data\":[{\"transId\":\"selected\",\"status\":3}]}"
                2 -> "{\"code\":\"TRS.AlreadyProcessing\"}"
                else -> "{\"code\":\"0\",\"data\":[{\"transId\":\"selected\",\"status\":0}]}"
            })
        })
        assertEquals(0, client.startOrReconcile("selected", JSONObject()))
        assertEquals(3, requests)
    }
}
