package com.zcode.watchrec

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

class TingwuSessionTest {
    private class Connection(val code: Int, val body: String) : HttpURLConnection(URL("https://tingwu.aliyun.com/")) {
        var released = false
        override fun connect() {}
        override fun disconnect() { released = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Before fun setup() {
        TingwuSession.accountKey.value = "previous-account"
        TingwuSession.connected.value = true
        TingwuSession.problem.value = ""
    }

    @Test fun failedNetworkCheckPreservesTheKnownAccount() {
        val failure = assertThrows(TingwuFailure::class.java) {
            TingwuSession.verify("synthetic", { throw UnknownHostException("hidden") })
        }
        assertTrue(failure.retryable)
        assertTrue(TingwuSession.connected.value)
        assertEquals("previous-account", TingwuSession.accountKey.value)
        assertTrue(TingwuSession.problem.value.contains("域名解析失败"))
    }

    @Test fun explicitExpiryClearsTheSessionButServiceOutageDoesNot() {
        val down = Connection(503, "")
        assertThrows(TingwuFailure::class.java) { TingwuSession.verify("synthetic", { down }) }
        assertTrue(TingwuSession.connected.value)
        assertTrue(down.released)
        val expired = Connection(200, "{\"code\":\"CMN.NotLogin\"}")
        assertFalse(TingwuSession.verify("synthetic", { expired }))
        assertFalse(TingwuSession.connected.value)
        assertEquals("", TingwuSession.accountKey.value)
        assertTrue(expired.released)
    }

    @Test fun malformedAccountResponseIsNotReportedAsLoggedOut() {
        val connection = Connection(200, "{\"code\":\"0\",\"data\":{}}")
        assertThrows(TingwuFailure::class.java) { TingwuSession.verify("synthetic", { connection }) }
        assertTrue(TingwuSession.connected.value)
        assertEquals("previous-account", TingwuSession.accountKey.value)
    }

    @Test fun verifiedAccountResetsNetworkProblemAndReleasesActiveConnection() {
        val connection = Connection(200, "{\"code\":\"0\",\"data\":{\"userId\":\"synthetic-id\"}}")
        TingwuSession.problem.value = "previous error"
        val active = mutableListOf<HttpURLConnection?>()
        assertTrue(TingwuSession.verify("synthetic", { connection }, { active.add(it) }))
        assertEquals(64, TingwuSession.accountKey.value.length)
        assertEquals("", TingwuSession.problem.value)
        assertEquals(listOf(connection, null), active)
        assertTrue(connection.released)
        assertFalse(connection.instanceFollowRedirects)
    }
}
