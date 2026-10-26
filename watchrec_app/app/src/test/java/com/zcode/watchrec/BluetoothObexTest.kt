package com.zcode.watchrec

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class BluetoothObexTest {
    private fun response(code: Int, body: ByteArray = byteArrayOf()): ByteArray {
        val size = body.size + 3
        return byteArrayOf(code.toByte(), (size ushr 8).toByte(), size.toByte()) + body
    }
    private val connection = byteArrayOf(0xCB.toByte(), 0, 0, 0, 7)
    private fun connect(maxPacket: Int = 1024) = response(0xA0, byteArrayOf(0x10, 0, (maxPacket ushr 8).toByte(), maxPacket.toByte()) + connection)
    private fun frames(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        var at = 0
        while (at < bytes.size) {
            val length = ((bytes[at + 1].toInt() and 255) shl 8) or (bytes[at + 2].toInt() and 255)
            frames.add((bytes[at].toInt() and 255) to bytes.copyOfRange(at + 3, at + length))
            at += length
        }
        assertEquals(bytes.size, at)
        return frames
    }
    private fun fixture(block: (File, ByteArray) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("wanlu-obex-").toFile()
        val file = File(dir, "REC_20261023_120000.m4a")
        val data = ByteArray(4500) { (it % 251).toByte() }
        file.writeBytes(data)
        try { block(file, data) } finally { dir.deleteRecursively() }
    }
    @Test fun streamsExactBytesAndRespectsNegotiatedPacketSize() = fixture { file, data ->
        var replies = connect() + response(0x90)
        val capacity = 1024 - 6 - connection.size
        val count = (data.size + capacity - 1) / capacity
        repeat(count) { replies += response(if (it == count - 1) 0xA0 else 0x90) }
        val wire = ByteArrayOutputStream()
        var confirmed = 0L
        BluetoothObex(ByteArrayInputStream(replies), wire).send(file, progress = { sent, _ -> confirmed = sent })
        val packets = frames(wire.toByteArray())
        assertEquals(0x80, packets.first().first)
        assertEquals(0x81, packets.last().first)
        assertTrue(packets.all { it.second.size + 3 <= 1024 })
        val metadata = packets[1].second
        assertArrayEquals(connection, metadata.copyOfRange(0, 5))
        assertEquals(0x01, metadata[5].toInt())
        val nameLength = ((metadata[6].toInt() and 255) shl 8) or (metadata[7].toInt() and 255)
        assertEquals(file.name + '\u0000', String(metadata, 8, nameLength - 3, Charsets.UTF_16BE))
        assertTrue(String(metadata, Charsets.ISO_8859_1).contains("audio/mp4"))
        val rebuilt = ByteArrayOutputStream()
        val bodies = packets.drop(2).dropLast(1)
        bodies.forEachIndexed { index, packet ->
            assertEquals(if (index == bodies.lastIndex) 0x82 else 0x02, packet.first)
            assertArrayEquals(connection, packet.second.copyOfRange(0, 5))
            assertEquals(if (index == bodies.lastIndex) 0x49 else 0x48, packet.second[5].toInt() and 255)
            rebuilt.write(packet.second, 8, packet.second.size - 8)
        }
        assertArrayEquals(data, rebuilt.toByteArray())
        assertEquals(data.size.toLong(), confirmed)
        assertArrayEquals(data, file.readBytes())
    }
    @Test fun receiverRejectionNeverReportsSuccessOrDeletesFile() = fixture { file, data ->
        val wire = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            BluetoothObex(ByteArrayInputStream(connect() + response(0xC3)), wire).send(file)
        }
        assertEquals(2, frames(wire.toByteArray()).size)
        assertArrayEquals(data, file.readBytes())
    }
    @Test fun failedFinalAcknowledgementDoesNotReachOneHundredPercent() = fixture { file, _ ->
        var replies = connect() + response(0x90)
        repeat(4) { replies += response(0x90) }
        replies += response(0xC3)
        var confirmed = 0L
        assertThrows(IOException::class.java) {
            BluetoothObex(ByteArrayInputStream(replies), ByteArrayOutputStream()).send(file, progress = { sent, _ -> confirmed = sent })
        }
        assertTrue(confirmed < file.length())
    }
    @Test fun cancellationDoesNotSendAudioBody() = fixture { file, _ ->
        val wire = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            BluetoothObex(ByteArrayInputStream(connect() + response(0x90)), wire).send(file, stopped = { true })
        }
        assertEquals(2, frames(wire.toByteArray()).size)
    }
    @Test fun truncatedAndMalformedResponsesFailPromptly() = fixture { file, _ ->
        for (reply in listOf(byteArrayOf(0xA0.toByte()), byteArrayOf(0xA0.toByte(), 0, 1), connect(10))) {
            assertThrows(IOException::class.java) { BluetoothObex(ByteArrayInputStream(reply), ByteArrayOutputStream()).send(file) }
        }
    }
}
