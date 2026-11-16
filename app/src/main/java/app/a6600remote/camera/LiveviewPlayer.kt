package app.a6600remote.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.TextureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Показ живой картинки камеры.
 *
 * - Кадры рисуются в TextureView из отдельного потока, мимо Compose.
 * - Рисуем в буфер размером с кадр камеры (≈640×424), а растягивает его видеокарта —
 *   так отрисовка кадра занимает доли миллисекунды вместо 10–20 мс.
 * - Буфер воспроизведения: каждый кадр показывается в момент, когда камера его сняла
 *   (по меткам времени камеры) плюс постоянная задержка bufferMs. Камера шлёт кадры
 *   неравномерно (на неподвижной сцене 1–2 к/с, в движении до 25–30), а Wi‑Fi
 *   приносит их пачками — по часам камеры ритм восстанавливается точно.
 */
class LiveviewPlayer {

    @Volatile var mirror = false
        set(value) { field = value; redrawRequested = true }

    /** Запас буфера, мс. 0 — показывать каждый кадр сразу (минимальная задержка). */
    @Volatile var bufferMs = 600

    /** Время последнего полученного кадра (SystemClock.elapsedRealtime). */
    @Volatile var lastFrameAt = 0L
        private set

    private val _hasPicture = MutableStateFlow(false)
    val hasPicture: StateFlow<Boolean> = _hasPicture.asStateFlow()

    /** «приём 24 · показ 24 к/с · пауза до 90 мс» — для диагностики. */
    private val _stats = MutableStateFlow("")
    val stats: StateFlow<String> = _stats.asStateFlow()

    /** Пропорции кадра (ширина / высота), 0 — пока неизвестно. Нужно для сетки третей. */
    private val _frameAspect = MutableStateFlow(0f)
    val frameAspect: StateFlow<Float> = _frameAspect.asStateFlow()

    private class Frame(val bitmap: Bitmap, val ts: Long, val arrival: Long)

    /** Средняя частота кадров камеры (сглаженная за несколько секунд). */
    @Volatile private var cameraFps = 15.0
    private val receivedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var fpsWindowStart = 0L
    private var fpsWindowCount = 0

    /** Сколько единиц часов камеры в 1 мс (определяется само). 0 — пока неизвестно. */
    @Volatile private var tsPerMs = 0.0
    private var calLastTs = -1L
    private var calLastArrival = 0L
    private var calSumTs = 0.0
    private var calSumArrival = 0.0
    private var calBad = 0

    private val free = ArrayBlockingQueue<Bitmap>(POOL_SIZE)
    private val ready = LinkedBlockingDeque<Frame>()

    @Volatile private var view: TextureView? = null
    @Volatile private var redrawRequested = false
    @Volatile private var clearRequested = false
    @Volatile private var connection: java.io.Closeable? = null

    /** Получилось ли включить быстрые TCP-подтверждения (для журнала). */
    @Volatile var quickAckActive = false
        private set

    /** Если «голый» сокет не сработал — откатываемся на HttpURLConnection. */
    @Volatile private var useRawSocket = true

    /** Настройка пользователя: читать поток своим сокетом (true) или стандартно (false). */
    @Volatile var rawSocketEnabled = true

    /** Сырые кадры: поток чтения → поток декодирования. Так медленное декодирование
     *  никогда не тормозит приём (а значит, и камеру). */
    private class RawFrame(val bytes: ByteArray, val size: Int, val ts: Long, val arrival: Long)
    private val rawQueue = java.util.concurrent.LinkedBlockingQueue<RawFrame>()
    private val bytePool = ArrayBlockingQueue<ByteArray>(16)

    // ---- Диагностика (пишут разные потоки) ----
    private val diagGenerated = java.util.concurrent.atomic.AtomicInteger(0) // кадров сняла камера (по номерам)
    private val diagNetWaitMs = java.util.concurrent.atomic.AtomicLong(0)    // суммарное ожидание сети
    private val diagNetWaitMax = java.util.concurrent.atomic.AtomicLong(0)
    private val diagDecodeMs = java.util.concurrent.atomic.AtomicLong(0)
    private val diagDecodeMax = java.util.concurrent.atomic.AtomicLong(0)
    private val diagDecoded = java.util.concurrent.atomic.AtomicInteger(0)
    private val diagDroppedRaw = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastSeq = -1
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0

    /** Подробная строка для журнала диагностики. */
    private val _details = MutableStateFlow("")
    val details: StateFlow<String> = _details.asStateFlow()
    private val bytesWindow = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var running = true
    @Volatile private var bufferResetRequested = false

    // Принадлежит потоку отрисовки
    private var shown: Bitmap? = null
    private var bufferW = 0
    private var bufferH = 0
    private var transformKey = ""

    // Статистика
    private var statFrames = 0
    private var statMaxGap = 0L
    private var statStart = 0L

    private val renderThread = Thread({ renderLoop() }, "liveview-render").apply {
        isDaemon = true
        start()
    }

    private val decodeThread = Thread({ decodeLoop() }, "liveview-decode").apply {
        isDaemon = true
        start()
    }

    // ---------------- Экран ----------------

    fun attach(v: TextureView) {
        view = v
        v.isOpaque = true
        v.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                bufferResetRequested = true
                redrawRequested = true
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                // При повороте TextureView сам ставит буферу размер экрана —
                // возвращаем размер кадра, иначе картинка становится маленькой
                bufferResetRequested = true
                redrawRequested = true
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (view === v) view = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        bufferResetRequested = true
        if (v.isAvailable) redrawRequested = true
    }

    fun detach(v: TextureView) {
        if (view === v) view = null
    }

    /** Убрать картинку (отключились от камеры). */
    fun clear() {
        stopStream()
        clearRequested = true
        _hasPicture.value = false
    }

    fun release() {
        running = false
        stopStream()
        renderThread.interrupt()
        decodeThread.interrupt()
    }

    // ---------------- Приём потока ----------------

    /**
     * Читает поток до обрыва (исключение) или до [stopStream].
     * Блокирующее чтение, поэтому остановка — через закрытие соединения.
     */
    suspend fun play(url: String) = withContext(Dispatchers.IO) {
        lastSeq = -1
        if (useRawSocket && rawSocketEnabled) {
            val raw = try {
                LiveviewConnection(url)
            } catch (e: Exception) {
                useRawSocket = false   // в следующий раз — обычным способом
                throw e
            }
            connection = raw
            quickAckActive = raw.quickAckActive
            try {
                readFrames(LiveviewParser(raw.input)) { connection === raw }
            } finally {
                if (connection === raw) connection = null
                raw.close()
            }
        } else {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 3_000      // 3 с без данных — поток считаем оборванным
            val closer = java.io.Closeable { conn.disconnect() }
            connection = closer
            quickAckActive = false
            try {
                readFrames(LiveviewParser(BufferedInputStream(conn.inputStream, 64 * 1024))) { connection === closer }
            } finally {
                if (connection === closer) connection = null
                conn.disconnect()
            }
        }
    }

    /** Поток чтения: только принимает кадры и кладёт байты в очередь. */
    private fun readFrames(parser: LiveviewParser, alive: () -> Boolean) {
        while (alive()) {
            val t0 = SystemClock.elapsedRealtime()
            parser.readNextImage()
            val now = SystemClock.elapsedRealtime()
            val wait = now - t0
            diagNetWaitMs.addAndGet(wait)
            if (wait > diagNetWaitMax.get()) diagNetWaitMax.set(wait)

            // Номера кадров: разница показывает, сколько кадров камера сняла между полученными
            val seq = parser.sequence
            val d = if (lastSeq < 0) 1 else ((seq - lastSeq) and 0xFFFF)
            diagGenerated.addAndGet(if (d in 1..300) d else 1)
            lastSeq = seq

            // Средняя частота камеры
            if (fpsWindowStart == 0L || now - lastFrameAt > 2_000) {
                fpsWindowStart = now
                fpsWindowCount = 0
            }
            fpsWindowCount++
            if (now - fpsWindowStart >= 1_000) {
                val fps = fpsWindowCount * 1000.0 / (now - fpsWindowStart)
                cameraFps = (cameraFps * 0.75 + fps * 0.25).coerceIn(4.0, 30.0)
                fpsWindowStart = now
                fpsWindowCount = 0
            }
            lastFrameAt = now
            receivedCount.incrementAndGet()
            bytesWindow.addAndGet(parser.frameSize.toLong())
            calibrateClock(parser.timestampMs, now)

            // Копия байтов кадра (буферы переиспользуются)
            val size = parser.frameSize
            var buf = bytePool.poll()
            if (buf == null || buf.size < size) buf = ByteArray(maxOf(size, 96 * 1024))
            System.arraycopy(parser.buffer, 0, buf, 0, size)
            rawQueue.offer(RawFrame(buf, size, parser.timestampMs, now))
            // Декодирование не успевает — выбрасываем самые старые сырые кадры
            while (rawQueue.size > 6) {
                rawQueue.poll()?.let { bytePool.offer(it.bytes) }
                diagDroppedRaw.incrementAndGet()
            }
        }
    }

    /** Поток декодирования: JPEG → Bitmap → очередь показа. */
    private fun decodeLoop() {
        val opts = BitmapFactory.Options()
        while (running) {
            val raw = try {
                rawQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            val t0 = SystemClock.elapsedRealtime()
            val reuse = free.poll()
            opts.inMutable = true
            opts.inBitmap = reuse
            val bmp = try {
                BitmapFactory.decodeByteArray(raw.bytes, 0, raw.size, opts)
            } catch (e: IllegalArgumentException) {
                // размер кадра поменялся — декодируем в новый Bitmap
                opts.inBitmap = null
                try {
                    BitmapFactory.decodeByteArray(raw.bytes, 0, raw.size, opts)
                } catch (_: Exception) {
                    null
                }
            }
            bytePool.offer(raw.bytes)
            val dt = SystemClock.elapsedRealtime() - t0
            diagDecodeMs.addAndGet(dt)
            if (dt > diagDecodeMax.get()) diagDecodeMax.set(dt)
            if (bmp == null) {
                reuse?.let { free.offer(it) }
                continue
            }
            diagDecoded.incrementAndGet()
            frameW = bmp.width
            frameH = bmp.height
            ready.offerLast(Frame(bmp, raw.ts, raw.arrival))
            // Не копим отставание: лишние старые кадры выбрасываем
            while (ready.size > maxQueue()) {
                ready.pollFirst()?.let { free.offer(it.bitmap) }
            }
        }
    }

    /**
     * Метки времени камеры идут в миллисекундах (проверено по журналам a6600).
     * Пересчитывать их не нужно — раньше оценка после пауз прыгала до 0.4–1.7 и сбивала показ.
     * Отключаем их, только если они не растут.
     */
    private fun calibrateClock(ts: Long, now: Long) {
        if (calLastTs >= 0) {
            val dTs = ts - calLastTs
            if (dTs > 0) {
                calBad = 0
                tsPerMs = 1.0
            } else if (++calBad >= 5) {
                tsPerMs = 0.0
            }
        }
        calLastTs = ts
        calLastArrival = now
    }

    fun stopStream() {
        val c = connection
        connection = null
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }

    // ---------------- Отрисовка ----------------

    private fun maxQueue(): Int {
        // Считаем на максимальные 30 к/с: на неподвижной сцене средняя частота низкая,
        // но при движении кадры идут быстро, и выбрасывать их нельзя
        val target = (bufferMs * 30 / 1000)
        return (target * 2 + 4).coerceIn(4, POOL_SIZE - 4)
    }

    private fun renderLoop() {
        var lastShow = 0L
        var baseWall = -1L
        var baseTs = 0L
        var lastTs = 0L
        statStart = SystemClock.elapsedRealtime()

        while (running) {
            if (clearRequested) {
                clearRequested = false
                while (true) {
                    val r = rawQueue.poll() ?: break
                    bytePool.offer(r.bytes)
                }
                while (true) {
                    val f = ready.pollFirst() ?: break
                    free.offer(f.bitmap)
                }
                shown = null
                baseWall = -1L
                drawBlack()
            }
            if (redrawRequested) {
                redrawRequested = false
                shown?.let { draw(it) }
            }

            val now = SystemClock.elapsedRealtime()
            updateStats(now)

            // ---- Без буфера: сразу самый свежий кадр ----
            if (bufferMs <= 0) {
                val first = try {
                    ready.pollFirst(50, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                var f: Frame = first
                while (true) {
                    val newer = ready.pollFirst() ?: break
                    free.offer(f.bitmap)
                    f = newer
                }
                show(f, lastShow)
                lastShow = SystemClock.elapsedRealtime()
                baseWall = -1L
                continue
            }

            // ---- С буфером: по часам камеры + постоянная задержка ----
            val f = ready.peekFirst()
            if (f == null) {
                sleepQuietly(4)
                continue
            }
            val scale = tsPerMs
            if (scale > 0.0 && (baseWall < 0 || f.ts < lastTs || (f.ts - lastTs) / scale > 3_000)) {
                // старт или скачок часов камеры — привязываемся заново
                baseWall = f.arrival + bufferMs
                baseTs = f.ts
            }
            val target = targetOf(f, scale, baseWall, baseTs)
            val nowT = SystemClock.elapsedRealtime()
            if (nowT < target) {
                sleepQuietly(minOf(target - nowT, 15L))
                continue
            }
            var frame = ready.pollFirst() ?: continue
            // Опоздали сразу на несколько кадров — показываем последний из «просроченных»,
            // а не прокручиваем их пачкой
            while (true) {
                val next = ready.peekFirst() ?: break
                if (targetOf(next, scale, baseWall, baseTs) > nowT) break
                val newer = ready.pollFirst() ?: break
                free.offer(frame.bitmap)
                frame = newer
            }
            lastTs = frame.ts
            show(frame, lastShow)
            lastShow = SystemClock.elapsedRealtime()

            // Медленная подстройка под расхождение часов камеры и телефона:
            // держим фактическую задержку около bufferMs
            if (scale > 0.0) {
                val planned = targetOf(frame, scale, baseWall, baseTs) - frame.arrival
                if (planned > bufferMs * 2) {
                    baseWall -= planned - bufferMs          // слишком большая задержка — сразу сокращаем
                } else {
                    // плавно держим задержку около bufferMs (5% расхождения за кадр)
                    baseWall += ((bufferMs - planned) * 0.05).toLong()
                }
            }
        }
    }

    /** Момент показа кадра. Без откалиброванных часов — время прихода + задержка. */
    private fun targetOf(f: Frame, scale: Double, baseWall: Long, baseTs: Long): Long =
        if (scale > 0.0 && baseWall >= 0) baseWall + ((f.ts - baseTs) / scale).toLong()
        else f.arrival + bufferMs

    private fun show(f: Frame, lastShow: Long) {
        draw(f.bitmap)
        val now = SystemClock.elapsedRealtime()
        statFrames++
        if (lastShow > 0) statMaxGap = max(statMaxGap, now - lastShow)
        val previous = shown
        shown = f.bitmap
        if (previous != null && previous !== f.bitmap) free.offer(previous)
        if (!_hasPicture.value) _hasPicture.value = true
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun updateStats(now: Long) {
        if (now - statStart >= 1_000) {
            val dt = now - statStart
            val shownFps = statFrames * 1000 / dt
            val receivedFrames = receivedCount.getAndSet(0)
            val receivedFps = receivedFrames * 1000L / dt
            val bytes = bytesWindow.getAndSet(0)
            val kb = if (receivedFrames > 0) bytes / receivedFrames / 1024 else 0
            val kbPerSec = bytes * 1000 / dt / 1024
            val generated = diagGenerated.getAndSet(0)
            val generatedFps = generated * 1000L / dt
            val netWait = diagNetWaitMs.getAndSet(0)
            val netMax = diagNetWaitMax.getAndSet(0)
            val decoded = diagDecoded.getAndSet(0)
            val decMs = diagDecodeMs.getAndSet(0)
            val decMax = diagDecodeMax.getAndSet(0)
            val dropped = diagDroppedRaw.getAndSet(0)
            val buf = if (bufferMs > 0) " · запас ${ready.size}" else ""
            val clock = if (tsPerMs > 0.0) " · часы %.2f".format(tsPerMs) else " · без часов"
            val qa = if (quickAckActive) " · QA" else ""
            _stats.value = "камера $receivedFps (сняла $generatedFps) · показ $shownFps к/с · $kb КБ/кадр · пауза до $statMaxGap мс$buf$clock$qa"
            _details.value = "снято=$generatedFps/с получено=$receivedFps/с показано=$shownFps/с" +
                    " · ожидание кадра: ср ${if (receivedFrames > 0) netWait / receivedFrames else 0} мс, макс $netMax мс" +
                    " · декод: ср ${if (decoded > 0) decMs / decoded else 0} мс, макс $decMax мс" +
                    " · сброшено сырых $dropped · $kbPerSec КБ/с · $kb КБ/кадр · ${frameW}x$frameH" +
                    " · пауза показа до $statMaxGap мс$buf$clock$qa · ${if (connection is LiveviewConnection) "сокет" else "стандарт"}"
            statFrames = 0
            statMaxGap = 0
            statStart = now
        }
    }

    private fun draw(bmp: Bitmap) {
        val v = view ?: return
        if (!v.isAvailable) return
        val st = v.surfaceTexture ?: return

        // Буфер размером с кадр — GPU растянет его до размеров экрана
        if (bufferResetRequested || bmp.width != bufferW || bmp.height != bufferH) {
            bufferResetRequested = false
            bufferW = bmp.width
            bufferH = bmp.height
            _frameAspect.value = bufferW.toFloat() / bufferH
            st.setDefaultBufferSize(bufferW, bufferH)
            transformKey = ""
        }
        updateTransform(v)

        val canvas = try {
            v.lockCanvas()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } finally {
            try {
                v.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    /** Вписываем картинку в экран с сохранением пропорций (+ зеркало). */
    private fun updateTransform(v: TextureView) {
        val vw = v.width
        val vh = v.height
        if (vw == 0 || vh == 0 || bufferW == 0 || bufferH == 0) return
        val key = "$vw,$vh,$bufferW,$bufferH,$mirror"
        if (key == transformKey) return
        transformKey = key
        val s = min(vw.toFloat() / bufferW, vh.toFloat() / bufferH)
        val sx = bufferW * s / vw
        val sy = bufferH * s / vh
        val m = Matrix()
        m.setScale(if (mirror) -sx else sx, sy, vw / 2f, vh / 2f)
        v.post { v.setTransform(m) }
    }

    private fun drawBlack() {
        val v = view ?: return
        if (!v.isAvailable) return
        val canvas = try {
            v.lockCanvas()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
        } finally {
            try {
                v.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val POOL_SIZE = 48
    }
}
