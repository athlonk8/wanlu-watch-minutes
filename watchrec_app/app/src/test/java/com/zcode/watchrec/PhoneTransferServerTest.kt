package com.zcode.watchrec

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class PhoneTransferServerTest {
    private val data = ByteArray(256 * 1024) { (it % 251).toByte() }
    private data class Response(val code: Int, val headers: String, val body: ByteArray)
    private fun withServer(test: (PhoneTransferServer, File) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("wanlu-phone-").toFile()
        val file = File(dir, "REC_20261023_120000.m4a").apply { writeBytes(data) }
        val server = PhoneTransferServer(file, { setOf("127.0.0.1") }, { "public pairing page".toByteArray() }, bind = InetAddress.getLoopbackAddress())
        try { test(server, file) } finally { server.close(); dir.deleteRecursively() }
    }
    private fun request(server: PhoneTransferServer, path: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Response {
        val bytes = Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 3000
            val all = mapOf("Host" to "127.0.0.1:${server.port}") + headers
            val wire = "$method $path HTTP/1.1\r\n" + all.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
            socket.getOutputStream().write(wire.toByteArray())
            socket.getInputStream().readBytes()
        }
        val delimiter = byteArrayOf(13, 10, 13, 10)
        val end = (0..bytes.size - 4).first { index -> delimiter.indices.all { bytes[index + it] == delimiter[it] } }
        val head = String(bytes, 0, end, Charsets.US_ASCII)
        return Response(head.substringBefore('\r').split(' ')[1].toInt(), head, bytes.copyOfRange(end + 4, bytes.size))
    }
    private fun auth(server: PhoneTransferServer) = mapOf("Authorization" to "Bearer ${server.token}")

    @Test fun unpairedClientsCannotReadMetadataOrAudio() = withServer { server, _ ->
        assertEquals(200, request(server, "/").code)
        assertEquals(401, request(server, "/api/recording").code)
        assertEquals(401, request(server, "/audio").code)
        assertFalse(String(request(server, "/").body).contains("REC_"))
    }
    @Test fun pairingCookieIsHttpOnlyAndDownloadIsByteIdentical() = withServer { server, _ ->
        val response = request(server, "/api/session", "POST", mapOf("X-Wanlu-Token" to server.token))
        assertEquals(204, response.code)
        assertTrue(response.headers.contains("HttpOnly; SameSite=Strict"))
        val cookie = response.headers.lineSequence().first { it.startsWith("Set-Cookie:") }.substringAfter(": ").substringBefore(';')
        val download = request(server, "/audio", headers = mapOf("Cookie" to cookie))
        assertEquals(200, download.code)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), MessageDigest.getInstance("SHA-256").digest(download.body))
        assertTrue(download.headers.contains("attachment; filename=\"REC_20261023_120000.m4a\""))
    }
    @Test fun byteRangesSupportResumeAndSuffixAndRejectInvalidRequests() = withServer { server, _ ->
        val resume = request(server, "/audio", headers = auth(server) + ("Range" to "bytes=100-199"))
        assertEquals(206, resume.code)
        assertArrayEquals(data.copyOfRange(100, 200), resume.body)
        val suffix = request(server, "/audio", headers = auth(server) + ("Range" to "bytes=-17"))
        assertArrayEquals(data.takeLast(17).toByteArray(), suffix.body)
        for (range in listOf("bytes=9999999999-", "bytes=10-2", "bytes=0-1,3-4", "bytes=-0", "bytes=999999999999999999999999-")) {
            assertEquals(416, request(server, "/audio", headers = auth(server) + ("Range" to range)).code)
        }
    }
    @Test fun headAndIfRangeDoNotCorruptResumedDownload() = withServer { server, _ ->
        val head = request(server, "/audio", "HEAD", auth(server))
        assertEquals(200, head.code); assertEquals(0, head.body.size)
        assertTrue(head.headers.contains("Content-Length: ${data.size}"))
        val fallback = request(server, "/audio", headers = auth(server) + mapOf("Range" to "bytes=100-", "If-Range" to "changed"))
        assertEquals(200, fallback.code); assertArrayEquals(data, fallback.body)
    }
    @Test fun wrongHostAndCrossOriginRequestsAreRejectedEvenWithToken() = withServer { server, _ ->
        assertEquals(403, request(server, "/audio", headers = auth(server) + ("Host" to "attacker.example:${server.port}")).code)
        assertEquals(403, request(server, "/api/session", "POST", mapOf("X-Wanlu-Token" to server.token, "Origin" to "https://attacker.example")).code)
    }
    @Test fun pairingPinHasBoundedGuessesAndQrStillWorks() = withServer { server, _ ->
        val wrong = if (server.pin == "000000") "111111" else "000000"
        repeat(8) { assertEquals(401, request(server, "/api/session", "POST", mapOf("X-Wanlu-Pin" to wrong)).code) }
        assertEquals(401, request(server, "/api/session", "POST", mapOf("X-Wanlu-Pin" to server.pin)).code)
        assertEquals(204, request(server, "/api/session", "POST", mapOf("X-Wanlu-Token" to server.token)).code)
    }
    @Test fun manualPairingWorksWithCorrectPin() = withServer { server, _ ->
        assertEquals(204, request(server, "/api/session", "POST", mapOf("X-Wanlu-Pin" to server.pin)).code)
    }
    @Test fun changedFileAndUnknownPathsAreNotServed() = withServer { server, file ->
        assertEquals(404, request(server, "/../other.m4a", headers = auth(server)).code)
        assertEquals(405, request(server, "/audio", "POST", auth(server)).code)
        file.appendText("changed")
        assertEquals(409, request(server, "/audio", headers = auth(server)).code)
    }
    @Test fun expiredSessionCannotReadAnAudioFile() {
        val file = File.createTempFile("REC_test", ".m4a").apply { writeBytes(data) }
        val clock = AtomicLong(0)
        val server = PhoneTransferServer(file, { setOf("127.0.0.1") }, { byteArrayOf() }, clock = { clock.get() }, lifetimeMs = 10, bind = InetAddress.getLoopbackAddress())
        try { clock.set(11); assertEquals(410, request(server, "/audio", headers = auth(server)).code) }
        finally { server.close(); file.delete() }
    }
    @Test fun closingServerRevokesTheLinkAndKeepsOriginalFile() = withServer { server, file ->
        val port = server.port
        server.close()
        assertThrows(Exception::class.java) { Socket("127.0.0.1", port).close() }
        assertArrayEquals(data, file.readBytes())
    }
}
