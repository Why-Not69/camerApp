package app.a6600remote

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.a6600remote.camera.CameraApiException
import app.a6600remote.camera.CameraContent
import app.a6600remote.camera.CameraDiscovery
import app.a6600remote.camera.CameraQr
import app.a6600remote.camera.CameraWifi
import app.a6600remote.camera.ExposureComp
import app.a6600remote.camera.LiveviewPlayer
import app.a6600remote.camera.RemoteImages
import app.a6600remote.camera.SonyCameraApi
import app.a6600remote.camera.toStringList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class FocusMark(val x: Float, val y: Float, val id: Long)

enum class Phase { Idle, ConnectingWifi, Searching, Starting, Ready, Reconnecting, Error }

/** Параметр экспозиции для колеса настроек. */
enum class ExpParam { Iso, Shutter, Aperture, Ev, Wb }

data class AlbumItem(
    val id: String,
    val name: String,
    val isVideo: Boolean,
    val thumb: String?,      // http-адрес или путь к файлу
    val large: String?,
    val original: String?,
)

data class AlbumUi(
    val open: Boolean = false,
    val fromCamera: Boolean = false,   // true — карта памяти камеры, false — снимки этой сессии
    val loading: Boolean = false,
    val items: List<AlbumItem> = emptyList(),
    val total: Int = -1,
    val error: String? = null,
    val note: String? = null,
    val saving: Set<String> = emptySet(),
)

data class UiState(
    val phase: Phase = Phase.Idle,
    val error: String? = null,
    val message: String? = null,
    val cameraStatus: String = "",
    val cameraFunction: String? = null,
    val shootMode: String? = null,
    val shootModes: List<String> = emptyList(),
    val iso: String? = null,
    val isoOptions: List<String> = emptyList(),
    val shutter: String? = null,
    val shutterOptions: List<String> = emptyList(),
    val fNumber: String? = null,
    val fNumberOptions: List<String> = emptyList(),
    val ev: ExposureComp? = null,
    val zoomPosition: Int? = null,
    val zoomTarget: Int? = null,
    /** "Focusing" / "Focused" / "Failed" / "Not Focusing" — из событий камеры. */
    val focusStatus: String? = null,
    /** Где показать кольцо фокуса (доли экрана), null — не показывать. */
    val focusMark: FocusMark? = null,
    val wb: String? = null,
    val wbOptions: List<String> = emptyList(),
    val apis: List<String> = emptyList(),
    /** Все методы камеры (getMethodTypes), для диагностики и подсказок. */
    val allMethods: List<String> = emptyList(),
    val timerSeconds: Int = 0,
    val countdown: Int = 0,
    val mirror: Boolean = false,
    val previewBufferMs: Int = 600,
    val showFps: Boolean = false,
    val showGrid: Boolean = false,
    val busy: Boolean = false,
    val recordingSince: Long? = null,
    val lastShot: String? = null,
    val album: AlbumUi = AlbumUi(),
    val ssid: String = "",
    val password: String = "",
    val log: List<String> = emptyList(),
) {
    val isRecording: Boolean get() = cameraStatus == "MovieRecording"
    val isMovieMode: Boolean get() = shootMode == "movie"
    val canSwitchMode: Boolean
        get() = "setShootMode" in apis && "movie" in shootModes && "still" in shootModes

    val canHalfPress: Boolean get() = "actHalfPressShutter" in apis

    /** Электронный зум (объективы PZ или цифровой зум камеры). */
    val canZoom: Boolean get() = "actZoom" in apis

    /** На самой камере открыт просмотр снимков (или другой режим без превью). */
    val cameraInPlayback: Boolean
        get() = phase == Phase.Ready && !album.open && (
                cameraFunction == "Contents Transfer" ||
                        cameraStatus == "ContentsTransfer" ||
                        (apis.isNotEmpty() && "startLiveview" !in apis && "actTakePicture" !in apis))

    fun options(p: ExpParam): List<String> = when (p) {
        ExpParam.Iso -> isoOptions
        ExpParam.Shutter -> shutterOptions
        ExpParam.Aperture -> fNumberOptions
        ExpParam.Ev -> ev?.let { e -> (e.min..e.max).map { it.toString() } } ?: emptyList()
        ExpParam.Wb -> wbOptions
    }

    fun current(p: ExpParam): String? = when (p) {
        ExpParam.Iso -> iso
        ExpParam.Shutter -> shutter
        ExpParam.Aperture -> fNumber
        ExpParam.Ev -> ev?.index?.toString()
        ExpParam.Wb -> wb
    }

    fun display(p: ExpParam, value: String?): String {
        if (value == null) return "—"
        return when (p) {
            ExpParam.Iso -> if (value.equals("AUTO", true)) "AUTO" else value
            ExpParam.Shutter -> value
            ExpParam.Aperture -> "F$value"
            ExpParam.Wb -> wbLabel(value)
            ExpParam.Ev -> {
                val e = ev ?: return value
                val v = (value.toIntOrNull() ?: 0) * e.stepEv
                val rounded = (v * 10).roundToInt() / 10.0
                when {
                    abs(rounded) < 0.05 -> "0.0"
                    rounded > 0 -> "+$rounded"
                    else -> "−${-rounded}"
                }
            }
        }
    }
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val wifi = CameraWifi(app)
    val player = LiveviewPlayer()

    private val _state = MutableStateFlow(
        UiState(
            ssid = prefs.getString("ssid", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            timerSeconds = prefs.getInt("timer", 0),
            mirror = prefs.getBoolean("mirror", false),
            previewBufferMs = prefs.getInt("bufferMs", 600),
            showFps = prefs.getBoolean("showFps", false),
            showGrid = prefs.getBoolean("showGrid", false),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var api: SonyCameraApi? = null
    private var avApi: SonyCameraApi? = null
    private var albumSource: String? = null
    private var sessionJob: Job? = null
    private var countdownJob: Job? = null
    private var messageJob: Job? = null
    private var albumJob: Job? = null
    private var reconnectAttempts = 0

    /** Пока открыт альбом на телефоне, превью не запрашиваем. */
    @Volatile
    private var albumMode = false

    private val sessionShots = mutableListOf<AlbumItem>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val crashGuard = CoroutineExceptionHandler { _, e ->
        log("Внутренняя ошибка: ${e.message}")
        _state.update { it.copy(phase = Phase.Error, error = e.message ?: e.toString()) }
    }

    init {
        player.mirror = _state.value.mirror
        player.bufferMs = _state.value.previewBufferMs
        wifi.onLost = {
            viewModelScope.launch { onConnectionLost("Wi‑Fi камеры пропал") }
        }
        // Уже настраивали подключение — подключаемся сразу при запуске
        if (prefs.getBoolean("configured", false)) connect()
    }

    // ---------------- Подключение ----------------

    fun connect() {
        reconnectAttempts = 0
        startSession()
    }

    private fun startSession() {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch(crashGuard) { runSession() }
    }

    private suspend fun runSession() {
        try {
            _state.update { it.copy(phase = if (reconnectAttempts > 0) Phase.Reconnecting else Phase.ConnectingWifi, error = null) }
            val s = _state.value
            wifi.connect(s.ssid.ifBlank { null }, s.password.ifBlank { null })
            log("Wi‑Fi подключён")

            if (reconnectAttempts == 0) _state.update { it.copy(phase = Phase.Searching) }
            val endpoint = CameraDiscovery.findEndpoint(getApplication<Application>(), ::logFromAnyThread)
                ?: throw IllegalStateException(
                    "Камера не найдена. Проверьте, что на камере включено «Упр. со смартфона» " +
                            "и закрыто Imaging Edge Mobile."
                )
            val api = SonyCameraApi(endpoint)
            this.api = api
            avApi = SonyCameraApi(endpoint.replace(Regex("/camera$"), "/avContent"))
            log("API камеры: $endpoint")

            if (reconnectAttempts == 0) _state.update { it.copy(phase = Phase.Starting) }
            var apis = api.availableApis()

            // Камеру могли оставить в режиме просмотра карты — возвращаем в съёмку
            if ("setCameraFunction" in apis && "startLiveview" !in apis) {
                try {
                    api.setCameraFunction("Remote Shooting")
                    delay(1_000)
                    apis = api.availableApis()
                } catch (e: Exception) {
                    log("Режим съёмки: ${e.message}")
                }
            }
            if ("startRecMode" in apis) {
                log("startRecMode…")
                api.startRecMode()
                apis = waitForApi(api, "startLiveview") ?: apis
            }
            _state.update { it.copy(apis = apis) }
            log("Команды камеры: ${apis.joinToString()}")

            refreshSettings()
            tryLog("Список методов") {
                val all = api.methodTypes()
                _state.update { it.copy(allMethods = all) }
                log(
                    "Фокус в точке (setTouchAFPosition): " +
                            if ("setTouchAFPosition" in all) "камера умеет" else "камера по Wi‑Fi не умеет"
                )
            }
            reconnectAttempts = 0
            _state.update { it.copy(phase = Phase.Ready) }

            // Два параллельных цикла: превью и опрос состояния камеры.
            coroutineScope {
                launch { liveviewLoop(api) }
                eventLoop(api)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Ошибка: ${e.message}")
            if (reconnectAttempts > 0) {
                scheduleReconnect(e.message)
            } else {
                _state.update { it.copy(phase = Phase.Error, error = e.message ?: e.toString()) }
            }
        }
    }

    /** Ждём, пока камера разрешит одну из команд (до 10 с). Возвращает свежий список команд. */
    private suspend fun waitForApi(api: SonyCameraApi, vararg names: String): List<String>? {
        var apis: List<String>? = null
        for (i in 0 until 20) {
            delay(500)
            apis = try {
                api.availableApis()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (names.any { it in apis }) break
        }
        return apis
    }

    private suspend fun liveviewLoop(api: SonyCameraApi) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val s = _state.value
            // Альбом или просмотр на камере — превью не дёргаем, просто ждём
            if (albumMode || s.cameraInPlayback || (s.apis.isNotEmpty() && "startLiveview" !in s.apis)) {
                delay(500)
                continue
            }
            try {
                val url = api.startLiveview()
                if (failures > 0 || player.lastFrameAt == 0L) log("Превью запущено")
                player.play(url)
            } catch (e: CancellationException) {
                player.stopStream()
                throw e
            } catch (e: Exception) {
                if (albumMode) continue
                failures++
                if (failures <= 3 || failures % 10 == 0) log("Превью прервалось: ${e.message}")
            }
            // Чем дольше не получается — тем реже пробуем (до 3 с)
            delay((300L * failures).coerceIn(300L, 3_000L))
            if (SystemClock.elapsedRealtime() - player.lastFrameAt < 5_000) failures = 0
        }
    }

    private suspend fun eventLoop(api: SonyCameraApi) {
        var fails = 0
        var longPoll = false          // первый запрос — сразу всё состояние
        var longPollSupported = true
        while (currentCoroutineContext().isActive) {
            val started = SystemClock.elapsedRealtime()
            try {
                applyEvent(api.getEvent(longPoll = longPoll && longPollSupported))
                if (fails >= 3) log("Камера снова отвечает")
                fails = 0
                longPoll = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                // Долгий опрос: за 35 с ничего не изменилось — это нормально,
                // если превью при этом идёт. Иначе считаем это сбоем.
                val noFrames = SystemClock.elapsedRealtime() - player.lastFrameAt > 10_000
                if (noFrames && !albumMode && !_state.value.cameraInPlayback) fails += 3
            } catch (e: CameraApiException) {
                when (e.code) {
                    40402 -> delay(1_000)                     // уже идёт другой опрос
                    12, 14 -> {                               // долгий опрос не поддерживается
                        if (longPollSupported) log("getEvent: опрос без ожидания")
                        longPollSupported = false
                    }
                    else -> fails++
                }
            } catch (e: Exception) {
                fails++
                if (fails == 3) log("Камера не отвечает: ${e.message}")
            }
            // Переподключаемся, только если камера долго молчит
            if (fails >= 6) {
                onConnectionLost("Связь с камерой потеряна")
                return
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            // Если камера отвечает на долгий опрос мгновенно — не заваливаем её запросами
            delay(
                when {
                    fails > 0 -> 1_500L
                    !longPollSupported -> 1_500L
                    elapsed < 300 -> 700L
                    else -> 100L
                }
            )
        }
    }

    private fun onConnectionLost(reason: String) {
        val phase = _state.value.phase
        if (phase == Phase.Idle || phase == Phase.Error || phase == Phase.Reconnecting) return
        log(reason)
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String?) {
        reconnectAttempts++
        player.stopStream()
        if (reconnectAttempts > 20) {
            _state.update { it.copy(phase = Phase.Error, error = reason ?: "Нет связи с камерой") }
            return
        }
        _state.update { it.copy(phase = Phase.Reconnecting) }
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch(crashGuard) {
            delay((1_500L * reconnectAttempts).coerceAtMost(6_000L))
            log("Переподключение, попытка $reconnectAttempts")
            runSession()
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        countdownJob?.cancel()
        albumJob?.cancel()
        albumMode = false
        api = null
        avApi = null
        player.clear()
        wifi.release()
        _state.update {
            it.copy(
                phase = Phase.Idle, cameraStatus = "", countdown = 0, recordingSince = null,
                album = AlbumUi(),
            )
        }
    }

    // ---------------- Состояние камеры ----------------

    private fun applyEvent(arr: JSONArray) {
        _state.update { old ->
            var s = old
            for (i in 0 until arr.length()) {
                val o = arr.opt(i) as? JSONObject ?: continue
                when (o.optString("type")) {
                    "availableApiList" ->
                        s = s.copy(apis = o.optJSONArray("names").toStringList())

                    "cameraStatus" -> {
                        val status = o.optString("cameraStatus")
                        val since = when {
                            status == "MovieRecording" && s.recordingSince == null ->
                                SystemClock.elapsedRealtime()
                            status == "MovieRecording" -> s.recordingSince
                            else -> null
                        }
                        s = s.copy(cameraStatus = status, recordingSince = since)
                    }

                    "focusStatus" -> {
                        val fs = o.optString("focusStatus")
                        if (fs.isNotEmpty()) s = s.copy(focusStatus = fs)
                    }

                    "zoomInformation" -> {
                        val pos = o.optInt("zoomPosition", -1)
                        s = s.copy(zoomPosition = if (pos >= 0) pos else null)
                    }

                    "whiteBalance" -> {
                        val mode = o.optString("currentWhiteBalanceMode")
                        if (mode.isNotEmpty()) {
                            s = s.copy(wb = SonyCameraApi.encodeWb(mode, o.optInt("currentColorTemperature", -1)))
                        }
                    }

                    "cameraFunction" ->
                        s = s.copy(cameraFunction = o.optString("currentCameraFunction").ifEmpty { null })

                    "shootMode" ->
                        s = s.copy(shootMode = o.optString("currentShootMode").ifEmpty { s.shootMode })

                    "isoSpeedRate" -> {
                        s = s.copy(iso = o.optString("currentIsoSpeedRate").ifEmpty { s.iso })
                        if (o.has("isoSpeedRateCandidates")) {
                            s = s.copy(isoOptions = o.optJSONArray("isoSpeedRateCandidates").toStringList())
                        }
                    }

                    "shutterSpeed" -> {
                        s = s.copy(shutter = o.optString("currentShutterSpeed").ifEmpty { s.shutter })
                        if (o.has("shutterSpeedCandidates")) {
                            s = s.copy(shutterOptions = o.optJSONArray("shutterSpeedCandidates").toStringList())
                        }
                    }

                    "fNumber" -> {
                        s = s.copy(fNumber = o.optString("currentFNumber").ifEmpty { s.fNumber })
                        if (o.has("fNumberCandidates")) {
                            s = s.copy(fNumberOptions = o.optJSONArray("fNumberCandidates").toStringList())
                        }
                    }

                    "exposureCompensation" -> {
                        if (o.has("currentExposureCompensation")) {
                            s = s.copy(
                                ev = ExposureComp(
                                    index = o.optInt("currentExposureCompensation"),
                                    min = o.optInt("minExposureCompensation"),
                                    max = o.optInt("maxExposureCompensation"),
                                    stepIndex = o.optInt("stepIndexOfExposureCompensation", 1),
                                )
                            )
                        }
                    }
                }
            }
            s
        }
    }

    private suspend fun refreshSettings() {
        val api = api ?: return
        val apis = _state.value.apis
        if ("getAvailableShootMode" in apis) tryLog("Режимы съёмки") {
            val c = api.shootModes()
            _state.update { it.copy(shootMode = c.current, shootModes = c.options) }
        }
        if ("getAvailableIsoSpeedRate" in apis) tryLog("ISO") {
            val c = api.isoOptions()
            _state.update { it.copy(iso = c.current, isoOptions = c.options) }
        }
        if ("getAvailableShutterSpeed" in apis) tryLog("Выдержка") {
            val c = api.shutterOptions()
            _state.update { it.copy(shutter = c.current, shutterOptions = c.options) }
        }
        if ("getAvailableFNumber" in apis) tryLog("Диафрагма") {
            val c = api.fNumberOptions()
            _state.update { it.copy(fNumber = c.current, fNumberOptions = c.options) }
        }
        if ("getAvailableWhiteBalance" in apis) tryLog("Баланс белого") {
            val c = api.whiteBalanceOptions()
            _state.update { it.copy(wb = c.current ?: it.wb, wbOptions = c.options) }
        }
        if ("getAvailableExposureCompensation" in apis) tryLog("Экспокоррекция") {
            val e = api.exposureCompensation()
            _state.update { it.copy(ev = e) }
        }
    }

    // ---------------- Съёмка ----------------

    fun onShutterPressed() {
        val s = _state.value
        val api = api ?: return
        if (s.phase != Phase.Ready || s.busy) return

        // Повторное нажатие во время отсчёта — отмена таймера
        if (countdownJob?.isActive == true) {
            countdownJob?.cancel()
            _state.update { it.copy(countdown = 0) }
            toast("Таймер отменён")
            return
        }

        if (s.isRecording) {
            runAction("Стоп записи") { api.stopMovieRec() }
            return
        }

        countdownJob = viewModelScope.launch {
            for (n in s.timerSeconds downTo 1) {
                _state.update { it.copy(countdown = n) }
                delay(1_000)
            }
            _state.update { it.copy(countdown = 0) }
            if (s.isMovieMode) {
                runAction("Старт записи") { api.startMovieRec() }
            } else {
                runAction("Снимок") { focusAndShoot(api) }
            }
        }
    }

    /**
     * Снимок с проверкой фокуса: полунажатие → ждём «Focused» → спуск → отпускаем.
     * Без этого камера в режиме AF иногда не снимает (ошибка 40400).
     */
    private suspend fun focusAndShoot(api: SonyCameraApi) {
        val halfPress = _state.value.canHalfPress
        if (halfPress) {
            val fs = focusNow(api, showMark = false)
            if (fs == "Failed") {
                try { api.cancelHalfPress() } catch (_: Exception) {}
                toast("Камера не нашла фокус — снимок не сделан. Тапните по экрану, чтобы сфокусироваться заново")
                return
            }
        }
        try {
            val postview = try {
                api.takePicture()
            } catch (e: CameraApiException) {
                if (e.code != 40400) throw e
                // Повторяем один раз: камера могла не успеть сфокусироваться
                delay(300)
                api.takePicture()
            }
            toast("Снято")
            if (!postview.isNullOrBlank()) savePostview(postview)
        } finally {
            if (halfPress) try { api.cancelHalfPress() } catch (_: Exception) {}
        }
    }

    /** Полунажатие и ожидание результата фокусировки (до 3 с). */
    private suspend fun focusNow(api: SonyCameraApi, showMark: Boolean): String? {
        _state.update { it.copy(focusStatus = "Focusing") }
        api.halfPress()
        val until = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < until) {
            val fs = _state.value.focusStatus
            if (fs == "Focused" || fs == "Failed") return fs
            // Если долгий опрос событий молчит — спросим сами
            try {
                applyEvent(api.getEvent(longPoll = false))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(150)
        }
        return _state.value.focusStatus
    }

    private var focusJob: Job? = null

    /**
     * Тап по экрану. Если камера сейчас принимает точку фокуса (setTouchAFPosition) —
     * фокусируемся в точке касания; иначе — полунажатием по зоне AF камеры.
     * x, y — доли экрана (для кольца), imgX, imgY — доли кадра (null — мимо кадра).
     */
    fun tapFocus(x: Float, y: Float, imgX: Float?, imgY: Float?) {
        val api = api ?: return
        val s = _state.value
        if (s.phase != Phase.Ready || s.busy || s.isRecording) return
        val touch = "setTouchAFPosition" in s.apis && imgX != null && imgY != null
        if (!touch && !s.canHalfPress) return
        focusJob?.cancel()
        val mark = FocusMark(x, y, SystemClock.elapsedRealtime())
        _state.update { it.copy(focusMark = mark, focusStatus = "Focusing") }
        focusJob = viewModelScope.launch {
            var usedHalfPress = false
            try {
                if (touch) {
                    val ok = api.setTouchAf((imgX!! * 100.0).coerceIn(0.0, 100.0), (imgY!! * 100.0).coerceIn(0.0, 100.0))
                    _state.update { it.copy(focusStatus = if (ok) "Focused" else "Failed") }
                    if (!ok) toast("Не удалось сфокусироваться в этой точке")
                    delay(1_500)
                } else {
                    if (!touchHintShown && "setTouchAFPosition" in s.allMethods) {
                        touchHintShown = true
                        toast("Чтобы фокусироваться в точке касания, на камере выберите «Область фокус.» → «Гибкая точечная»")
                    }
                    usedHalfPress = true
                    val fs = focusNow(api, showMark = true)
                    if (fs == "Failed") toast("Не удалось сфокусироваться")
                    delay(1_500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Фокус: ${e.message}")
                toast("Фокус: ${e.message}")
            } finally {
                if (usedHalfPress) {
                    try {
                        withContext(NonCancellable) { api.cancelHalfPress() }
                    } catch (_: Exception) {
                    }
                }
                _state.update { if (it.focusMark?.id == mark.id) it.copy(focusMark = null) else it }
            }
        }
    }

    private var touchHintShown = false

    /** Камера после снимка отдаёт уменьшенную копию — сохраняем её для «последнего кадра». */
    private fun savePostview(url: String) {
        viewModelScope.launch {
            val bytes = RemoteImages.readBytes(url, timeoutMs = 20_000) ?: return@launch
            val file = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "shots").apply { mkdirs() }
                File(dir, "shot_${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
            }
            val item = AlbumItem(
                id = file.absolutePath,
                name = file.name,
                isVideo = false,
                thumb = file.absolutePath,
                large = file.absolutePath,
                original = file.absolutePath,
            )
            sessionShots.add(0, item)
            _state.update { it.copy(lastShot = file.absolutePath) }
        }
    }

    fun selectShootMode(mode: String) {
        val api = api ?: return
        runAction("Режим") {
            api.setShootMode(mode)
            _state.update { it.copy(shootMode = mode) }
            delay(500)
            refreshSettings()
        }
    }

    fun setParam(p: ExpParam, value: String) {
        val api = api ?: return
        runAction(p.title) {
            when (p) {
                ExpParam.Iso -> {
                    api.setIso(value)
                    _state.update { it.copy(iso = value) }
                }
                ExpParam.Shutter -> {
                    api.setShutter(value)
                    _state.update { it.copy(shutter = value) }
                }
                ExpParam.Aperture -> {
                    api.setFNumber(value)
                    _state.update { it.copy(fNumber = value) }
                }
                ExpParam.Wb -> {
                    api.setWhiteBalance(value)
                    _state.update { it.copy(wb = value) }
                }
                ExpParam.Ev -> {
                    val index = value.toIntOrNull() ?: return@runAction
                    api.setExposureCompensation(index)
                    _state.update { s -> s.copy(ev = s.ev?.copy(index = index)) }
                }
            }
        }
    }

    fun cycleTimer() {
        val next = when (_state.value.timerSeconds) {
            0 -> 3
            3 -> 5
            5 -> 10
            else -> 0
        }
        prefs.edit().putInt("timer", next).apply()
        _state.update { it.copy(timerSeconds = next) }
    }

    fun toggleMirror() {
        val next = !_state.value.mirror
        prefs.edit().putBoolean("mirror", next).apply()
        player.mirror = next
        _state.update { it.copy(mirror = next) }
    }

    /** Запас буфера превью: 0 — без буфера, 600 — плавно, 1200 — очень плавно. */
    fun setPreviewBuffer(ms: Int) {
        prefs.edit().putInt("bufferMs", ms).apply()
        player.bufferMs = ms
        _state.update { it.copy(previewBufferMs = ms) }
    }

    fun toggleGrid() {
        val next = !_state.value.showGrid
        prefs.edit().putBoolean("showGrid", next).apply()
        _state.update { it.copy(showGrid = next) }
    }

    fun setShowFps(on: Boolean) {
        prefs.edit().putBoolean("showFps", on).apply()
        _state.update { it.copy(showFps = on) }
    }

    /** Подсказка внизу экрана. */
    fun showMessage(text: String) = toast(text)

    // ---------------- Зум ----------------

    /** Нажали и держат W или T. */
    fun zoomStart(zoomIn: Boolean) = zoomCall(zoomIn, "start")

    fun zoomStop(zoomIn: Boolean) = zoomCall(zoomIn, "stop")

    /** Короткое нажатие — один шаг. */
    fun zoomStep(zoomIn: Boolean) = zoomCall(zoomIn, "1shot")

    // Зум перетаскиванием по полоске: задаём целевое положение, камера едет к нему

    @Volatile private var zoomTargetValue: Int? = null
    @Volatile private var zoomDragging = false
    private var zoomCtlJob: Job? = null

    fun zoomDrag(target: Int) {
        zoomDragging = true
        val t = target.coerceIn(0, 100)
        zoomTargetValue = t
        _state.update { it.copy(zoomTarget = t) }
        if (zoomCtlJob?.isActive != true) zoomCtlJob = viewModelScope.launch { zoomController() }
    }

    fun zoomDragEnd() {
        zoomDragging = false
    }

    private suspend fun zoomController() {
        val api = api ?: return
        var moving: String? = null
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (true) {
                val pos = _state.value.zoomPosition ?: break
                val target = zoomTargetValue ?: break
                val diff = target - pos
                val want = when {
                    diff > 2 -> "in"
                    diff < -2 -> "out"
                    else -> null
                }
                if (want != moving) {
                    moving?.let { safeZoom(api, it, "stop") }
                    want?.let { safeZoom(api, it, "start") }
                    moving = want
                }
                if (want == null && !zoomDragging) break
                // Защита: если камера перестала сообщать положение — не крутим вечно
                if (!zoomDragging && SystemClock.elapsedRealtime() - startedAt > 15_000) break
                delay(50)
            }
        } finally {
            moving?.let { m -> withContext(NonCancellable) { safeZoom(api, m, "stop") } }
            zoomTargetValue = null
            _state.update { it.copy(zoomTarget = null) }
        }
    }

    private suspend fun safeZoom(api: SonyCameraApi, direction: String, movement: String) {
        try {
            api.actZoom(direction, movement)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun zoomCall(zoomIn: Boolean, movement: String) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.actZoom(if (zoomIn) "in" else "out", movement)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (movement != "stop") log("Зум: ${e.message}")
            }
        }
    }

    // ---------------- Альбом ----------------

    fun openAlbum() {
        if (_state.value.album.open) return
        val api = api
        val apis = _state.value.apis
        albumJob?.cancel()

        if (api == null || "setCameraFunction" !in apis || _state.value.isRecording) {
            showSessionAlbum(
                if (api == null) null
                else if (_state.value.isRecording) "Во время записи карта памяти недоступна. Здесь — снимки, сделанные из приложения."
                else "Камера не даёт открыть карту памяти по Wi‑Fi. Здесь — снимки, сделанные из приложения."
            )
            return
        }

        _state.update { it.copy(album = AlbumUi(open = true, fromCamera = true, loading = true)) }
        albumMode = true
        player.stopStream()

        albumJob = viewModelScope.launch {
            try {
                if ("getSupportedCameraFunction" in apis) {
                    val functions = api.supportedCameraFunctions()
                    if ("Contents Transfer" !in functions) {
                        albumMode = false
                        showSessionAlbum("Камера не даёт открыть карту памяти по Wi‑Fi. Здесь — снимки, сделанные из приложения.")
                        return@launch
                    }
                }
                log("Альбом: переключаю камеру в просмотр")
                api.setCameraFunction("Contents Transfer")

                val av = avApi ?: throw IllegalStateException("Нет сервиса просмотра")
                var source: String? = null
                for (i in 0 until 24) {
                    delay(500)
                    try {
                        val scheme = av.schemes().firstOrNull() ?: "storage"
                        source = av.sources(scheme).firstOrNull()
                        if (source != null) break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                if (source == null) throw IllegalStateException("Камера не открыла карту памяти")
                albumSource = source
                log("Альбом: $source")

                val total = try {
                    av.contentCount(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    -1
                }
                val items = av.contentList(source, 0, PAGE).map { it.toAlbumItem() }
                _state.update { it.copy(album = it.album.copy(loading = false, items = items, total = total)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Альбом: ${e.message}")
                _state.update {
                    it.copy(album = it.album.copy(loading = false, error = e.message ?: "Не удалось открыть альбом"))
                }
            }
        }
    }

    fun loadMoreAlbum() {
        val a = _state.value.album
        if (!a.open || !a.fromCamera || a.loading || albumJob?.isActive == true) return
        if (a.total in 0..a.items.size) return
        val av = avApi ?: return
        val source = albumSource ?: return
        albumJob = viewModelScope.launch {
            _state.update { it.copy(album = it.album.copy(loading = true)) }
            try {
                val more = av.contentList(source, a.items.size, PAGE).map { it.toAlbumItem() }
                _state.update {
                    it.copy(
                        album = it.album.copy(
                            loading = false,
                            items = it.album.items + more,
                            total = if (more.isEmpty()) it.album.items.size else it.album.total,
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(album = it.album.copy(loading = false)) }
                toast("Альбом: ${e.message}")
            }
        }
    }

    fun retryAlbum() {
        closeAlbum()
        viewModelScope.launch {
            delay(2_000)
            openAlbum()
        }
    }

    fun closeAlbum() {
        val wasCamera = _state.value.album.fromCamera
        albumJob?.cancel()
        _state.update { it.copy(album = AlbumUi()) }
        if (!wasCamera) return
        val api = api ?: run { albumMode = false; return }
        viewModelScope.launch {
            try {
                log("Альбом: возвращаю камеру в съёмку")
                api.setCameraFunction("Remote Shooting")
                var apis = waitForApi(api, "startRecMode", "startLiveview") ?: emptyList()
                if ("startRecMode" in apis && "startLiveview" !in apis) {
                    api.startRecMode()
                    apis = waitForApi(api, "startLiveview") ?: apis
                }
                if (apis.isNotEmpty()) _state.update { it.copy(apis = apis) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Возврат в съёмку: ${e.message}")
                toast("Не удалось вернуть камеру в съёмку: ${e.message}")
            } finally {
                albumMode = false
            }
        }
    }

    private fun showSessionAlbum(note: String?) {
        _state.update {
            it.copy(
                album = AlbumUi(
                    open = true, fromCamera = false, items = sessionShots.toList(),
                    total = sessionShots.size, note = note,
                )
            )
        }
    }

    /** Сохранить снимок в галерею телефона (полный размер, если камера его отдаёт). */
    fun saveToPhone(item: AlbumItem) {
        val src = item.original ?: item.large ?: return
        if (item.id in _state.value.album.saving) return
        _state.update { it.copy(album = it.album.copy(saving = it.album.saving + item.id)) }
        viewModelScope.launch {
            val bytes = RemoteImages.readBytes(src, timeoutMs = 60_000)
            val ok = bytes != null && RemoteImages.saveToGallery(
                getApplication<Application>(), bytes,
                item.name.substringAfterLast('/').let { if (it.contains('.')) it else "$it.jpg" },
            )
            _state.update { it.copy(album = it.album.copy(saving = it.album.saving - item.id)) }
            toast(if (ok) "Сохранено в галерею (Pictures/A6600Remote)" else "Не удалось сохранить")
        }
    }

    private fun CameraContent.toAlbumItem() = AlbumItem(
        id = uri,
        name = name,
        isVideo = isVideo,
        thumb = thumbnailUrl,
        large = largeUrl ?: thumbnailUrl,
        original = if (isVideo) null else (originalUrl ?: largeUrl),
    )

    // ---------------- Wi‑Fi настройки ----------------

    fun saveWifi(ssid: String, password: String) {
        prefs.edit()
            .putString("ssid", ssid.trim())
            .putString("password", password)
            .putBoolean("configured", true)
            .apply()
        _state.update { it.copy(ssid = ssid.trim(), password = password) }
    }

    /** Результат сканирования QR-кода с экрана камеры. */
    fun onQrScanned(raw: String) {
        val creds = CameraQr.parse(raw)
        if (creds == null) {
            log("QR не распознан: ${raw.take(60)}")
            toast("Это не QR-код камеры Sony")
            return
        }
        log("QR: сеть ${creds.ssid}")
        saveWifi(creds.ssid, creds.password)
        connect()
    }

    fun onScanError(text: String) {
        log("Сканер: $text")
        toast("Сканер QR недоступен: $text")
    }

    /** Подключение через Wi‑Fi, к которому телефон уже подключён в настройках. */
    fun useCurrentWifi() {
        saveWifi("", "")
        connect()
    }

    // ---------------- Вспомогательное ----------------

    private fun runAction(name: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("$name: ${e.message}")
                toast("$name: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun tryLog(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what: ${e.message}")
        }
    }

    private fun toast(text: String) {
        messageJob?.cancel()
        _state.update { it.copy(message = text) }
        messageJob = viewModelScope.launch {
            delay(3_500)
            _state.update { it.copy(message = null) }
        }
    }

    private fun log(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        _state.update { it.copy(log = (it.log + stamped).takeLast(300)) }
    }

    /** Лог из фонового потока (поиск камеры). */
    private fun logFromAnyThread(line: String) {
        viewModelScope.launch { log(line) }
    }

    override fun onCleared() {
        player.release()
        wifi.release()
        super.onCleared()
    }

    companion object {
        private const val PAGE = 60
    }
}

val ExpParam.title: String
    get() = when (this) {
        ExpParam.Iso -> "ISO"
        ExpParam.Shutter -> "Выдержка"
        ExpParam.Aperture -> "Диафрагма"
        ExpParam.Ev -> "EV"
        ExpParam.Wb -> "Баланс белого"
    }

/** Короткое название режима баланса белого для шкалы. */
fun wbLabel(value: String): String = when {
    value.startsWith("CT:") -> value.removePrefix("CT:") + "K"
    value == "Auto WB" -> "AWB"
    value == "Daylight" -> "Солнце"
    value == "Shade" -> "Тень"
    value == "Cloudy" -> "Облака"
    value == "Incandescent" -> "Лампа"
    value == "Flash" -> "Вспышка"
    value == "Underwater Auto" -> "Под водой"
    value.startsWith("Fluorescent") -> {
        val n = Regex("\\(([-+]?\\d+)\\)").find(value)?.groupValues?.get(1)
        if (n != null) "Флуор $n" else "Флуор"
    }
    value.startsWith("Custom") -> "Свой" + value.removePrefix("Custom").trim().let { if (it.isEmpty()) "" else " $it" }
    else -> value
}
