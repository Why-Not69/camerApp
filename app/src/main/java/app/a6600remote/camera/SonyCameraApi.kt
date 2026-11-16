package app.a6600remote.camera

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class CameraApiException(val code: Int, message: String) : Exception(message)

/** Пара «текущее значение + доступные варианты» (ISO, выдержка, режим съёмки). */
data class Choice(val current: String?, val options: List<String>)

/** Экспокоррекция в «шагах» камеры: значение EV = index * stepEv. */
data class ExposureComp(val index: Int, val min: Int, val max: Int, val stepIndex: Int) {
    val stepEv: Double get() = if (stepIndex == 2) 0.5 else 1.0 / 3.0
}

/** Файл на карте памяти. */
data class CameraContent(
    val uri: String,
    val name: String,
    val kind: String,
    val created: String?,
    val thumbnailUrl: String?,
    val largeUrl: String?,
    val originalUrl: String?,
) {
    val isVideo: Boolean get() = kind.startsWith("movie", ignoreCase = true)
}

/**
 * Клиент старого Wi‑Fi протокола Sony (Camera Remote API, HTTP + JSON-RPC).
 * Его использует a6600 в режиме «Управление со смартфона» (Ctrl w/ Smartphone).
 */
class SonyCameraApi(val endpoint: String) {

    private val ids = AtomicInteger(1)

    suspend fun call(
        method: String,
        params: JSONArray = JSONArray(),
        version: String = "1.0",
        timeoutMs: Int = 5_000,
    ): JSONArray = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("method", method)
            .put("params", params)
            .put("id", ids.getAndIncrement())
            .put("version", version)

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream
            else (conn.errorStream ?: conn.inputStream)
            val text = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val err = json.optJSONArray("error")
            if (err != null) {
                val code = err.optInt(0)
                throw CameraApiException(code, describeError(code, err.optString(1)))
            }
            json.optJSONArray("result") ?: json.optJSONArray("results") ?: JSONArray()
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Готовые команды ----------

    suspend fun availableApis(): List<String> =
        call("getAvailableApiList").optJSONArray(0).toStringList()

    suspend fun startRecMode() {
        call("startRecMode", timeoutMs = 10_000)
    }

    /** Возвращает URL потока живой картинки. */
    suspend fun startLiveview(): String = call("startLiveview", timeoutMs = 10_000).getString(0)

    suspend fun stopLiveview() {
        call("stopLiveview")
    }

    /** Снимок. Возвращает адрес превью снятого кадра (если камера его даёт). */
    suspend fun takePicture(): String? {
        val result = try {
            call("actTakePicture", timeoutMs = 30_000)
        } catch (e: CameraApiException) {
            // 40403 = длинная выдержка / обработка ещё идёт — ждём результат
            if (e.code == 40403) call("awaitTakePicture", timeoutMs = 60_000) else throw e
        }
        return result.optJSONArray(0)?.optString(0)
    }

    suspend fun startMovieRec() {
        call("startMovieRec", timeoutMs = 10_000)
    }

    suspend fun stopMovieRec() {
        call("stopMovieRec", timeoutMs = 15_000)
    }

    suspend fun shootModes(): Choice = choice("getAvailableShootMode")
    suspend fun setShootMode(mode: String) {
        call("setShootMode", JSONArray().put(mode), timeoutMs = 10_000)
    }

    suspend fun isoOptions(): Choice = choice("getAvailableIsoSpeedRate")
    suspend fun setIso(value: String) {
        call("setIsoSpeedRate", JSONArray().put(value))
    }

    suspend fun shutterOptions(): Choice = choice("getAvailableShutterSpeed")
    suspend fun setShutter(value: String) {
        call("setShutterSpeed", JSONArray().put(value))
    }

    suspend fun fNumberOptions(): Choice = choice("getAvailableFNumber")
    suspend fun setFNumber(value: String) {
        call("setFNumber", JSONArray().put(value))
    }

    /** Экспокоррекция: [текущий индекс, максимум, минимум, шаг (1 = 1/3 EV, 2 = 1/2 EV)]. */
    suspend fun exposureCompensation(): ExposureComp {
        val r = call("getAvailableExposureCompensation")
        return ExposureComp(r.optInt(0), r.optInt(2), r.optInt(1), r.optInt(3, 1))
    }

    suspend fun setExposureCompensation(index: Int) {
        call("setExposureCompensation", JSONArray().put(index))
    }

    // ---------- Баланс белого ----------

    /**
     * Режимы ББ одним списком для колеса: сначала пресеты ("Auto WB", "Daylight"…),
     * затем цветовая температура как "CT:2500" … "CT:9900".
     */
    suspend fun whiteBalanceOptions(): Choice {
        val r = call("getAvailableWhiteBalance")
        val cur = r.optJSONObject(0)?.let {
            encodeWb(it.optString("whiteBalanceMode"), it.optInt("colorTemperature", -1))
        }
        val arr = r.optJSONArray(1)
        val modes = mutableListOf<String>()
        var temps = emptyList<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mode = o.optString("whiteBalanceMode")
                if (mode == COLOR_TEMP) {
                    val range = o.optJSONArray("colorTemperatureRange")
                    if (range != null && range.length() >= 3) {
                        val a = range.optInt(0)
                        val b = range.optInt(1)
                        val step = range.optInt(2).coerceAtLeast(50)
                        temps = (minOf(a, b)..maxOf(a, b) step step).map { "CT:$it" }
                    }
                } else if (mode.isNotEmpty()) {
                    modes.add(mode)
                }
            }
        }
        return Choice(cur, modes + temps)
    }

    suspend fun setWhiteBalance(value: String) {
        val params = if (value.startsWith("CT:")) {
            JSONArray().put(COLOR_TEMP).put(true).put(value.removePrefix("CT:").toInt())
        } else {
            JSONArray().put(value).put(false).put(-1)
        }
        call("setWhiteBalance", params)
    }

    // ---------- Альбом (карта памяти) ----------

    suspend fun supportedCameraFunctions(): List<String> =
        call("getSupportedCameraFunction").optJSONArray(0).toStringList()

    /** "Remote Shooting" — съёмка, "Contents Transfer" — просмотр карты памяти. */
    suspend fun setCameraFunction(function: String) {
        call("setCameraFunction", JSONArray().put(function), timeoutMs = 10_000)
    }

    /** Вызывать на сервисе avContent. */
    suspend fun schemes(): List<String> {
        val arr = call("getSchemeList").optJSONArray(0) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("scheme")?.ifEmpty { null } }
    }

    /** Вызывать на сервисе avContent. */
    suspend fun sources(scheme: String): List<String> {
        val arr = call("getSourceList", JSONArray().put(JSONObject().put("scheme", scheme)))
            .optJSONArray(0) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("source")?.ifEmpty { null } }
    }

    /** Вызывать на сервисе avContent. */
    suspend fun contentCount(source: String): Int {
        val p = JSONObject().put("uri", source).put("view", "flat")
        return call("getContentCount", JSONArray().put(p), version = "1.2")
            .optJSONObject(0)?.optInt("count", -1) ?: -1
    }

    /** Вызывать на сервисе avContent. Новые снимки первыми. */
    suspend fun contentList(source: String, start: Int, count: Int): List<CameraContent> {
        fun params(sort: String) = JSONArray().put(
            JSONObject().put("uri", source).put("stIdx", start).put("cnt", count)
                .put("view", "flat").put("sort", sort)
        )
        val result = try {
            call("getContentList", params("descending"), version = "1.3", timeoutMs = 15_000)
        } catch (e: CameraApiException) {
            if (e.code == 3) call("getContentList", params(""), version = "1.3", timeoutMs = 15_000)
            else throw e
        }
        val arr = result.optJSONArray(0) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val content = o.optJSONObject("content")
            val originals = content?.optJSONArray("original")
            var originalUrl: String? = null
            var originalName: String? = null
            if (originals != null) {
                for (k in 0 until originals.length()) {
                    val orig = originals.optJSONObject(k) ?: continue
                    val isJpeg = orig.optString("stillObject").equals("jpeg", ignoreCase = true)
                    if (originalUrl == null || isJpeg) {
                        originalUrl = orig.optString("url").ifEmpty { null }
                        originalName = orig.optString("fileName").ifEmpty { null }
                    }
                    if (isJpeg) break
                }
            }
            CameraContent(
                uri = o.optString("uri"),
                name = originalName ?: o.optString("title").ifEmpty { o.optString("uri") },
                kind = o.optString("contentKind"),
                created = o.optString("createdTime").ifEmpty { null },
                thumbnailUrl = content?.optString("thumbnailUrl")?.ifEmpty { null },
                largeUrl = content?.optString("largeUrl")?.ifEmpty { null },
                originalUrl = originalUrl,
            )
        }
    }

    /**
     * Состояние камеры. longPoll = true — камера отвечает только когда что-то изменилось
     * (так её не дёргают каждую секунду, что давало периодические подвисания превью).
     */
    suspend fun getEvent(longPoll: Boolean = false): JSONArray =
        call(
            "getEvent", JSONArray().put(longPoll),
            timeoutMs = if (longPoll) 35_000 else 4_000,
        )

    /** Все методы, которые камера умеет в принципе (а не только доступные сейчас). */
    suspend fun methodTypes(): List<String> {
        val r = call("getMethodTypes", JSONArray().put(""), timeoutMs = 5_000)
        return (0 until r.length()).mapNotNull { r.optJSONArray(it)?.optString(0)?.ifEmpty { null } }
    }

    /** Фокус в точке кадра, координаты в процентах (0–100). true — фокус найден. */
    suspend fun setTouchAf(xPercent: Double, yPercent: Double): Boolean {
        val r = call("setTouchAFPosition", JSONArray().put(xPercent).put(yPercent), timeoutMs = 10_000)
        return r.optJSONObject(1)?.optBoolean("AFResult", false) ?: false
    }

    suspend fun cancelTouchAf() {
        call("cancelTouchAFPosition", timeoutMs = 5_000)
    }

    /** Полунажатие спуска — камера фокусируется (точку выбирает сама камера). */
    suspend fun halfPress() {
        call("actHalfPressShutter", timeoutMs = 8_000)
    }

    suspend fun cancelHalfPress() {
        call("cancelHalfPressShutter", timeoutMs = 5_000)
    }

    /** Электронный зум. direction: "in" / "out", movement: "start" / "stop" / "1shot". */
    suspend fun actZoom(direction: String, movement: String) {
        call("actZoom", JSONArray().put(direction).put(movement), timeoutMs = 5_000)
    }

    private suspend fun choice(method: String): Choice {
        val r = call(method)
        return Choice(r.optString(0).ifEmpty { null }, r.optJSONArray(1).toStringList())
    }

    companion object {
        const val COLOR_TEMP = "Color Temperature"

        fun encodeWb(mode: String, temperature: Int): String =
            if (mode == COLOR_TEMP && temperature > 0) "CT:$temperature" else mode

        fun describeError(code: Int, raw: String): String = when (code) {
            1 -> "Камера не может выполнить команду сейчас ($raw)"
            3 -> "Недопустимое значение ($raw)"
            12 -> "Камера не поддерживает эту команду"
            14 -> "Неподдерживаемая версия команды"
            40401 -> "Камера не готова"
            40402 -> "Уже выполняется другая команда"
            40403 -> "Идёт длинная съёмка"
            41003 -> "Нет места на карте памяти"
            40400 -> "Камера не смогла снять (обычно — не нашла фокус)"
            -1 -> "Нет связи"
            else -> "Ошибка камеры $code ${raw.ifBlank { "" }}".trim()
        }
    }
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).ifEmpty { null } }
}
