package app.a6600remote.camera

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.min

/**
 * Поток превью через «голый» сокет вместо HttpURLConnection.
 *
 * Зачем: на неподвижной сцене кадры маленькие, и камера (алгоритм Нейгла) придерживает
 * хвост кадра, пока телефон не подтвердит приём. Android подтверждает с задержкой —
 * в итоге 2–3 к/с. Мы включаем TCP_QUICKACK перед каждым чтением, чтобы телефон
 * подтверждал сразу. Для этого нужен доступ к самому сокету.
 */
class LiveviewConnection(url: String) : Closeable {

    private val socket = Socket()
    private val quickAck: QuickAckInputStream
    val input: InputStream

    /** Удалось ли включить быстрые подтверждения (для диагностики). */
    val quickAckActive: Boolean get() = quickAck.active

    init {
        val u = URL(url)
        val port = if (u.port > 0) u.port else 80
        try {
            socket.receiveBufferSize = 512 * 1024
            socket.tcpNoDelay = true
            socket.soTimeout = 3_000
            socket.connect(InetSocketAddress(u.host, port), 4_000)

            val path = u.path.ifEmpty { "/" } + (u.query?.let { "?$it" } ?: "")
            val request = "GET $path HTTP/1.1\r\n" +
                    "Host: ${u.host}:$port\r\n" +
                    "User-Agent: A6600Remote\r\n" +
                    "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }

            quickAck = QuickAckInputStream(socket.getInputStream(), socket)
            val buffered = BufferedInputStream(quickAck, 64 * 1024)

            val status = readLine(buffered) ?: throw EOFException("Нет ответа")
            if (!status.contains(" 200")) throw IOException("Ответ камеры: $status")
            var chunked = false
            while (true) {
                val line = readLine(buffered) ?: throw EOFException("Обрыв заголовков")
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("transfer-encoding") && line.lowercase().contains("chunked")) {
                    chunked = true
                }
            }
            input = if (chunked) ChunkedInputStream(buffered) else buffered
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
        quickAck.release()
    }

    companion object {
        /** Linux TCP_QUICKACK (в OsConstants его нет). */
        const val TCP_QUICKACK = 12

        fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream(128)
            while (true) {
                val b = input.read()
                if (b == -1) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
                if (out.size() > 8 * 1024) throw IOException("Слишком длинная строка заголовка")
            }
            return out.toString(Charsets.ISO_8859_1.name())
        }
    }
}

/** Перед каждым чтением просим ядро подтверждать приём немедленно. */
class QuickAckInputStream(private val src: InputStream, socket: Socket) : InputStream() {

    private val pfd: ParcelFileDescriptor? = try {
        ParcelFileDescriptor.fromSocket(socket)
    } catch (_: Exception) {
        null
    }

    @Volatile var active = pfd != null
        private set

    private fun quickAck() {
        if (!active) return
        try {
            Os.setsockoptInt(pfd!!.fileDescriptor, OsConstants.IPPROTO_TCP, LiveviewConnection.TCP_QUICKACK, 1)
        } catch (_: Exception) {
            active = false
        }
    }

    override fun read(): Int {
        quickAck()
        return src.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        quickAck()
        val n = src.read(b, off, len)
        quickAck()
        return n
    }

    override fun available(): Int = src.available()

    fun release() {
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        release()
        src.close()
    }
}

/** Разбор HTTP chunked (если камера отдаёт поток кусками). */
class ChunkedInputStream(private val src: InputStream) : InputStream() {
    private var remaining = 0
    private var eof = false

    private fun nextChunk() {
        var line: String
        do {
            line = LiveviewConnection.readLine(src) ?: run { eof = true; return }
        } while (line.isBlank())
        val size = line.substringBefore(';').trim().toInt(16)
        if (size == 0) eof = true
        remaining = size
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (eof) return -1
        if (remaining == 0) {
            nextChunk()
            if (eof) return -1
        }
        val n = src.read(b, off, min(len, remaining))
        if (n < 0) return -1
        remaining -= n
        return n
    }
}
