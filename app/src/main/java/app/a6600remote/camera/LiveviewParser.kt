package app.a6600remote.camera

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Разбор потока живой картинки Sony.
 *
 * Каждый пакет:
 *   общий заголовок, 8 байт:  0xFF | тип (0x01 = JPEG, 0x02 = инфо о фокусе) | номер (2) | время, мс (4)
 *   заголовок данных, 128 байт: 0x24 0x35 0x68 0x79 | размер данных (3) | размер «хвоста» (1) | ...
 *   данные (JPEG), затем «хвост» из нулей.
 *
 * Без зависимостей от Android — проверяется обычным тестом на JVM.
 */
class LiveviewParser(input: InputStream) {

    private val data = DataInputStream(input)
    private val common = ByteArray(8)
    private val header = ByteArray(128)

    /** Буфер с последним JPEG: байты [0, frameSize). Перезаписывается следующим кадром. */
    var buffer = ByteArray(256 * 1024)
        private set
    var frameSize = 0
        private set

    /** Время кадра по часам камеры, мс. */
    var timestampMs = 0L
        private set

    /** Номер кадра от камеры (16 бит, идёт по кругу). По пропускам видно, сколько кадров потеряно. */
    var sequence = 0
        private set

    /** Читает следующий JPEG-кадр в [buffer]. Бросает EOFException, если поток закончился. */
    fun readNextImage() {
        while (true) {
            syncToStart()
            data.readFully(common, 1, 7)
            val type = common[1].toInt() and 0xFF
            val ts = ((common[4].toLong() and 0xFF) shl 24) or
                    ((common[5].toLong() and 0xFF) shl 16) or
                    ((common[6].toLong() and 0xFF) shl 8) or
                    (common[7].toLong() and 0xFF)

            data.readFully(header)
            if (header[0] != 0x24.toByte() || header[1] != 0x35.toByte() ||
                header[2] != 0x68.toByte() || header[3] != 0x79.toByte()
            ) {
                throw IOException("Повреждён заголовок кадра")
            }
            val size = ((header[4].toInt() and 0xFF) shl 16) or
                    ((header[5].toInt() and 0xFF) shl 8) or
                    (header[6].toInt() and 0xFF)
            val padding = header[7].toInt() and 0xFF

            if (size > 8 * 1024 * 1024) throw IOException("Слишком большой кадр: $size")
            if (buffer.size < size) buffer = ByteArray(size)
            data.readFully(buffer, 0, size)
            if (padding > 0) skipFully(padding)

            if (type == 0x01 && size > 0) {
                frameSize = size
                timestampMs = ts
                sequence = ((common[2].toInt() and 0xFF) shl 8) or (common[3].toInt() and 0xFF)
                return
            }
            // тип 0x02 (рамки фокуса) пока пропускаем
        }
    }

    /** То же, но возвращает копию JPEG. */
    fun nextJpeg(): ByteArray {
        readNextImage()
        return buffer.copyOf(frameSize)
    }

    /** Ищем стартовый байт 0xFF (на случай сбоя в потоке). */
    private fun syncToStart() {
        var skipped = 0
        while (true) {
            val b = data.read()
            if (b == -1) throw EOFException()
            if (b == 0xFF) {
                common[0] = 0xFF.toByte()
                return
            }
            if (++skipped > 2 * 1024 * 1024) throw IOException("Не удаётся найти начало кадра")
        }
    }

    private fun skipFully(n: Int) {
        var left = n
        while (left > 0) {
            val skipped = data.skipBytes(left)
            if (skipped <= 0) {
                if (data.read() == -1) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }
}
