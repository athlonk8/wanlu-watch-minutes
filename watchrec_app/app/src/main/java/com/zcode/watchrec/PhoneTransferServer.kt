package com.zcode.watchrec

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One selected, immutable recording; bounded clients, short lifetime, no upload or deletion routes. */
class PhoneTransferServer(
    private val file: File,
    private val hosts: () -> Set<String>,
    private val asset: (String) -> ByteArray,
    private val onDelivered: () -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    lifetimeMs: Long = 15 * 60 * 1000L,
    bind: InetAddress = InetAddress.getByName("0.0.0.0"),
    requestedPort: Int = 0,
) : AutoCloseable {
    val token: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
    val expiresAt = clock() + lifetimeMs
    val pin: String = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
    private val failedPins = AtomicInteger()
    private val length = file.length()
    private val modified = file.lastModified()
    init { require(file.isFile && length > 0 && Regex("[A-Za-z0-9_.-]+").matches(file.name)) }
    private val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, requestedPort)) }
    val port: Int get() = listener.localPort
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = ThreadPoolExecutor(0, 4, 20, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
        Thread(task, "wanlu-phone-client").apply { isDaemon = true }
    })
    @Volatile private var closed = false
    private val acceptThread = Thread({
        while (!closed) {
            val client = try { listener.accept() } catch (_: Exception) { break }
            clients.add(client)
            try { pool.execute { client.use { handle(it) }; clients.remove(client) } }
            catch (_: Exception) { clients.remove(client); client.close() }
        }
    }, "wanlu-phone-transfer").apply { isDaemon = true; start() }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            if (!socket.inetAddress.isSiteLocalAddress && !socket.inetAddress.isLoopbackAddress) return
            val input = socket.getInputStream()
            val request = line(input) ?: return
            val parts = request.split(' ')
            if (parts.size != 3 || parts[2] !in listOf("HTTP/1.0", "HTTP/1.1")) return
            val headers = linkedMapOf<String, String>()
            var total = request.length
            var count = 0
            while (true) {
                val value = line(input) ?: return
                total += value.length
                if (total > 8192 || ++count > 32) return
                if (value.isEmpty()) break
                val split = value.indexOf(':')
                if (split <= 0) return
                val name = value.substring(0, split).lowercase(Locale.ROOT)
                if (headers.containsKey(name)) return
                headers[name] = value.substring(split + 1).trim()
            }
            val out = socket.getOutputStream()
            val host = headers["host"].orEmpty()
            if (host !in hosts().map { "$it:$port" }) return reply(out, 403)
            if (clock() >= expiresAt || closed) return reply(out, 410)
            if (headers.containsKey("transfer-encoding") || headers["content-length"]?.let { it != "0" } == true) return reply(out, 400)
            val method = parts[0]
            val path = parts[1]
            val static = mapOf("/" to ("index.html" to "text/html; charset=utf-8"), "/app.js" to ("app.js" to "text/javascript; charset=utf-8"), "/style.css" to ("style.css" to "text/css; charset=utf-8"))
            if (method == "GET" && path in static) {
                val (name, type) = static.getValue(path)
                return reply(out, 200, asset(name), type)
            }
            val origin = headers["origin"]
            if (origin != null && origin != "http://$host") return reply(out, 403)
            fun matches(value: String?) = value != null && MessageDigest.isEqual(token.toByteArray(), value.toByteArray())
            if (method == "POST" && path == "/api/session") {
                val enteredPin = headers["x-wanlu-pin"]
                val validPin = enteredPin != null && failedPins.get() < 8 && MessageDigest.isEqual(pin.toByteArray(), enteredPin.toByteArray())
                if (!matches(headers["x-wanlu-token"]) && !validPin) {
                    if (enteredPin != null) failedPins.incrementAndGet()
                    return reply(out, 401)
                }
                return reply(out, 204, extra = mapOf("Set-Cookie" to "wanlu_session=$token; HttpOnly; SameSite=Strict; Path=/; Max-Age=${((expiresAt - clock()) / 1000).coerceAtLeast(0)}"))
            }
            val cookie = headers["cookie"].orEmpty().split(';').map { it.trim() }.firstOrNull { it.startsWith("wanlu_session=") }?.substringAfter('=')
            if (!matches(cookie) && !matches(headers["authorization"]?.removePrefix("Bearer "))) return reply(out, 401)
            if (method !in listOf("GET", "HEAD")) return reply(out, 405)
            if (!file.isFile || file.length() != length || file.lastModified() != modified) return reply(out, 409)
            if (method == "GET" && path == "/api/recording") {
                val body = "{\"name\":\"${file.name}\",\"size\":$length,\"expiresAt\":$expiresAt}"
                return reply(out, 200, body.toByteArray(), "application/json; charset=utf-8")
            }
            if (path != "/audio") return reply(out, 404)
            val etag = "\"$length-$modified\""
            val rangeHeader = headers["range"]?.takeIf { headers["if-range"] == null || headers["if-range"] == etag }
            val range = try { byteRange(rangeHeader, length) } catch (_: IllegalArgumentException) {
                return reply(out, 416, extra = mapOf("Content-Range" to "bytes */$length"))
            }
            val extra = linkedMapOf("Accept-Ranges" to "bytes", "ETag" to etag,
                "Content-Disposition" to "attachment; filename=\"${file.name}\"")
            if (rangeHeader != null) extra["Content-Range"] = "bytes ${range.first}-${range.last}/$length"
            head(out, if (rangeHeader == null) 200 else 206, range.last - range.first + 1, "audio/mp4", extra)
            if (method == "HEAD") return
            file.inputStream().use { source ->
                source.channel.position(range.first)
                var remaining = range.last - range.first + 1
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0 && !closed && clock() < expiresAt) {
                    val n = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buffer, 0, n); remaining -= n
                }
                out.flush()
                if (remaining == 0L && range.first == 0L && range.last == length - 1) onDelivered()
            }
        } catch (_: Exception) { /* Disconnects do not delete recordings. */ }
    }

    private fun line(input: InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 2048) {
            val value = input.read()
            if (value == -1) return null
            if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.add(value.toByte())
        }
        return null
    }

    private fun reply(output: OutputStream, code: Int, body: ByteArray = byteArrayOf(), type: String = "text/plain; charset=utf-8", extra: Map<String, String> = emptyMap()) {
        head(output, code, body.size.toLong(), type, extra)
        output.write(body); output.flush()
    }
    private fun head(output: OutputStream, code: Int, size: Long, type: String, extra: Map<String, String> = emptyMap()) {
        val reason = mapOf(200 to "OK", 206 to "Partial Content", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 410 to "Gone", 416 to "Range Not Satisfiable")[code] ?: "Error"
        val headers = linkedMapOf("Content-Type" to type, "Content-Length" to "$size", "Connection" to "close", "Cache-Control" to "no-store", "Referrer-Policy" to "no-referrer", "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'") + extra
        output.write(("HTTP/1.1 $code $reason\r\n" + headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        closed = true; listener.close()
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        pool.shutdownNow()
    }

    companion object {
        fun byteRange(header: String?, length: Long): LongRange {
            require(length > 0)
            if (header == null) return 0L until length
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: throw IllegalArgumentException()
            val (first, last) = match.destructured
            require(first.isNotEmpty() || last.isNotEmpty())
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: throw IllegalArgumentException()
                require(suffix > 0)
                return (length - suffix).coerceAtLeast(0) until length
            }
            val start = first.toLongOrNull() ?: throw IllegalArgumentException()
            val end = if (last.isEmpty()) length - 1 else (last.toLongOrNull() ?: throw IllegalArgumentException()).coerceAtMost(length - 1)
            require(start in 0 until length && end >= start)
            return start..end
        }
    }
}
