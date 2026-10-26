package com.zcode.watchrec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class CloudProtocolTest {
    @Test fun endpointRequiresHttpsAndRejectsCredentialAndPathLeaks() {
        assertEquals("https://nas.example.com:443", CloudProtocol.endpoint(" https://nas.example.com:443/ "))
        for (url in listOf("http://nas.example.com", "https://user:secret@nas.example.com", "https://nas.example.com/api", "https://nas.example.com?token=secret", "https://nas.example.com#fragment", "nas.example.com", "https://nas.example.com:99999")) {
            assertThrows(IllegalArgumentException::class.java) { CloudProtocol.endpoint(url) }
        }
    }
    @Test fun digestMatchesCloudDeduplicationProtocol() {
        val file = File.createTempFile("wanlu-hash", ".m4a")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CloudProtocol.sha256(file))
        } finally { file.delete() }
    }
}
