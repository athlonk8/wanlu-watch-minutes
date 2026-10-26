package com.zcode.watchrec

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** OPP over RFCOMM: connect, metadata, bounded body packets, final acknowledgement, disconnect.
 * Protocol references: Android BluetoothOppObexClientSession / javax.obex HeaderSet.
 */
class BluetoothObex(private val input: InputStream, private val output: OutputStream) {
    private var connection = byteArrayOf()
    private var maxPacket = 1024
    private fun response(): Pair<Int, ByteArray> {
        val code = input.read()
        val high = input.read(); val low = input.read()
        if (code < 0 || high < 0 || low < 0) throw IOException("手机断开了蓝牙连接")
        val size = (high shl 8) or low
        if (size !in 3..65535) throw IOException("蓝牙响应格式错误")
        val body = ByteArray(size - 3)
        var position = 0
        while (position < body.size) {
            val count = input.read(body, position, body.size - position)
            if (count < 0) throw IOException("手机断开了蓝牙连接")
            position += count
        }
        return code to body
    }
    private fun packet(opcode: Int, payload: ByteArray): Pair<Int, ByteArray> {
        val size = payload.size + 3
        require(size <= 65535)
        output.write(byteArrayOf(opcode.toByte(), (size ushr 8).toByte(), size.toByte()))
        output.write(payload); output.flush()
        return response()
    }
    private fun expect(code: Int, expected: Int) {
        if (code != expected) throw IOException(if (code in 0xC0..0xCF) "手机拒绝接收，请在手机确认蓝牙文件请求" else "蓝牙传输未完成，请重试")
    }
    private fun bytesHeader(id: Int, value: ByteArray): ByteArray {
        val size = value.size + 3
        return byteArrayOf(id.toByte(), (size ushr 8).toByte(), size.toByte()) + value
    }
    fun send(file: File, stopped: () -> Boolean = { false }, progress: (Long, Long) -> Unit = { _, _ -> }) {
        require(file.isFile && file.length() in 1..0xFFFFFFFFL)
        val (status, connect) = packet(0x80, byteArrayOf(0x10, 0, 0xFF.toByte(), 0xFE.toByte()))
        expect(status, 0xA0)
        if (connect.size < 4) throw IOException("手机不支持蓝牙文件传输")
        maxPacket = ((connect[2].toInt() and 255) shl 8) or (connect[3].toInt() and 255)
        if (maxPacket < 255) throw IOException("手机的蓝牙数据包过小")
        var offset = 4
        while (offset < connect.size) {
            val id = connect[offset].toInt() and 255
            val size = when (id and 0xC0) {
                0x00, 0x40 -> {
                    if (offset + 2 >= connect.size) throw IOException("蓝牙响应格式错误")
                    ((connect[offset + 1].toInt() and 255) shl 8) or (connect[offset + 2].toInt() and 255)
                }
                0x80 -> 2
                else -> 5
            }
            if (size < 2 || offset + size > connect.size) throw IOException("蓝牙响应格式错误")
            if (id == 0xCB) connection = connect.copyOfRange(offset, offset + size)
            offset += size
        }
        val length = file.length()
        val metadata = connection + bytesHeader(0x01, (file.name + '\u0000').toByteArray(Charsets.UTF_16BE)) +
            bytesHeader(0x42, "audio/mp4\u0000".toByteArray(Charsets.US_ASCII)) +
            byteArrayOf(0xC3.toByte(), (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
        if (metadata.size + 3 > maxPacket) throw IOException("录音文件名过长")
        expect(packet(0x02, metadata).first, 0x90)
        val buffer = ByteArray(minOf(16 * 1024, maxPacket - 6 - connection.size))
        var sent = 0L
        file.inputStream().use { source ->
            while (sent < length) {
                if (stopped()) throw IOException("传输已取消")
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), length - sent).toInt())
                if (count < 0) throw IOException("录音文件已改变")
                val last = sent + count == length
                val body = connection + bytesHeader(if (last) 0x49 else 0x48, buffer.copyOf(count))
                expect(packet(if (last) 0x82 else 0x02, body).first, if (last) 0xA0 else 0x90)
                sent += count; progress(sent, length)
            }
        }
        try {
            // The final PUT acknowledgement proves acceptance; don't wait on a
            // device that closes its OBEX session without replying to Disconnect.
            val size = connection.size + 3
            output.write(byteArrayOf(0x81.toByte(), (size ushr 8).toByte(), size.toByte()))
            output.write(connection); output.flush()
        } catch (_: IOException) { /* Final PUT already acknowledged. */ }
    }
}
