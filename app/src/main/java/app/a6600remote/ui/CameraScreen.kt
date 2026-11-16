package app.a6600remote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.a6600remote.CameraViewModel
import app.a6600remote.ExpParam
import app.a6600remote.Phase
import app.a6600remote.UiState
import app.a6600remote.title
import app.a6600remote.wbLabel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val RecRed = Color(0xFFFF3B30)
private val Glass = Color(0x8C0B0B0D)
private val GlassBorder = Color(0x26FFFFFF)
private val Dim = Color.White.copy(alpha = 0.55f)
private val Chip = Color(0x33FFFFFF)

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val s by vm.state.collectAsState()
    val hasPicture by vm.player.hasPicture.collectAsState()
    val stats by vm.player.stats.collectAsState()
    val frameAspect by vm.player.frameAspect.collectAsState()

    var activeParam by remember { mutableStateOf<ExpParam?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scanQr: () -> Unit = {
        showSettings = false
        scanCameraQr(context, onResult = vm::onQrScanned, onError = vm::onScanError)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ---------- Превью: рисуется напрямую, мимо Compose ----------
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { vm.player.attach(it) } },
            onRelease = { vm.player.detach(it) },
            modifier = Modifier.fillMaxSize(),
        )
        if (s.showGrid && s.phase == Phase.Ready && hasPicture && frameAspect > 0f) {
            ThirdsGrid(frameAspect)
        }
        // Тап по картинке: закрыть настройку или сфокусироваться (полунажатие)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        if (activeParam != null) {
                            activeParam = null
                        } else {
                            // Переводим точку экрана в точку кадра (кадр вписан по центру)
                            val vw = size.width.toFloat()
                            val vh = size.height.toFloat()
                            val a = vm.player.frameAspect.value
                            var imgX: Float? = null
                            var imgY: Float? = null
                            if (a > 0f) {
                                val w = if (vw / vh > a) vh * a else vw
                                val h = if (vw / vh > a) vh else vw / a
                                val fx = (pos.x - (vw - w) / 2f) / w
                                val fy = (pos.y - (vh - h) / 2f) / h
                                if (fx in 0f..1f && fy in 0f..1f) {
                                    imgX = if (vm.state.value.mirror) 1f - fx else fx
                                    imgY = fy
                                }
                            }
                            vm.tapFocus(pos.x / vw, pos.y / vh, imgX, imgY)
                        }
                    }
                }
        )
        s.focusMark?.let { mark -> FocusRing(mark, s.focusStatus) }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            val landscape = maxWidth > maxHeight
            val railWidth = 104.dp
            val ready = s.phase == Phase.Ready
            val endPad = if (landscape && ready) railWidth else 0.dp

            // ---------- Сообщения по центру ----------
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(end = endPad),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !ready -> ConnectPanel(
                        s = s,
                        onConnect = vm::connect,
                        onScanQr = scanQr,
                        onUseCurrentWifi = vm::useCurrentWifi,
                        onSettings = { showSettings = true },
                    )

                    s.cameraInPlayback -> CenterNote(
                        "На камере открыт просмотр снимков.\n" +
                                "Вернитесь в режим съёмки — превью продолжится само."
                    )

                    !hasPicture -> CenterNote("Жду картинку с камеры…", progress = true)
                }
                if (s.countdown > 0) {
                    Text(
                        "${s.countdown}",
                        color = Color.White,
                        fontSize = 140.sp,
                        fontWeight = FontWeight.Thin,
                    )
                }
            }

            TopBar(
                s = s,
                fps = if (s.showFps) stats else null,
                onGrid = vm::toggleGrid,
                onMirror = vm::toggleMirror,
                onSettings = { showSettings = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(end = endPad),
            )

            s.message?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp, start = 24.dp, end = 24.dp + endPad)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }

            if (ready) {
                if (s.canZoom) {
                    ZoomSlider(
                        position = s.zoomPosition,
                        target = s.zoomTarget,
                        onStart = vm::zoomStart,
                        onStop = vm::zoomStop,
                        onStep = vm::zoomStep,
                        onDrag = vm::zoomDrag,
                        onDragEnd = vm::zoomDragEnd,
                        modifier = Modifier
                            .align(if (landscape) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(horizontal = 8.dp),
                    )
                }

                val params: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth()) {
                        if (activeParam != null) {
                            ParamEditor(s, activeParam!!, onValue = vm::setParam)
                        }
                        ParamBar(s, activeParam, onClick = { p ->
                            activeParam = if (activeParam == p) null else p
                        })
                    }
                }

                if (landscape) {
                    ControlRail(
                        s = s,
                        vm = vm,
                        onAlbum = { activeParam = null; vm.openAlbum() },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(railWidth)
                            .fillMaxHeight(),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 56.dp, end = railWidth + 8.dp, bottom = 4.dp)
                            .widthIn(max = 620.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xB3000000)),
                    ) { params() }
                } else {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.25f to Color(0xCC000000),
                                    1f to Color.Black,
                                )
                            )
                            .padding(top = 20.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        params()
                        Spacer(Modifier.height(10.dp))
                        ModeCarousel(s, onMode = vm::selectShootMode, onUnavailable = vm::showMessage)
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                AlbumButton(s.lastShot, onClick = { activeParam = null; vm.openAlbum() })
                            }
                            ShutterButton(s, onClick = vm::onShutterPressed)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                TimerButton(s.timerSeconds, onClick = vm::cycleTimer)
                            }
                        }
                    }
                }
            }
        }

        if (s.album.open) {
            AlbumScreen(
                album = s.album,
                onClose = vm::closeAlbum,
                onLoadMore = vm::loadMoreAlbum,
                onSave = vm::saveToPhone,
                onRetry = vm::retryAlbum,
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            s = s,
            onBuffer = vm::setPreviewBuffer,
            onShowFps = vm::setShowFps,
            onScanQr = scanQr,
            onShowLog = { showSettings = false; showDiagnostics = true },
            onRecordState = vm::recordCameraState,
            onDisconnect = { showSettings = false; vm.disconnect() },
            onSave = { ssid, pass ->
                vm.saveWifi(ssid, pass)
                showSettings = false
                vm.connect()
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(s = s, stats = stats, onDismiss = { showDiagnostics = false })
    }
}

// ---------------------------------------------------------------------------
// Верх: статус слева, тонкие иконки справа (без подложек, как в камере Xiaomi)

@Composable
private fun TopBar(
    s: UiState,
    fps: String?,
    onGrid: () -> Unit,
    onMirror: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(top = 12.dp)
        ) {
            StatusLine(s)
            if (fps != null && fps.isNotEmpty()) {
                Text(
                    fps,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (s.phase == Phase.Ready) {
            IconAction(Glyph.Grid, active = s.showGrid, onClick = onGrid)
        }
        IconAction(Glyph.Mirror, active = s.mirror, onClick = onMirror)
        IconAction(Glyph.Gear, onClick = onSettings)
    }
}

@Composable
private fun IconAction(glyph: Glyph, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, color = if (active) Accent else Color.White, size = 24.dp)
    }
}

@Composable
private fun StatusLine(s: UiState) {
    val recording = s.isRecording
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(recording) {
        while (recording) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    if (recording && s.recordingSince != null) {
        val sec = ((now - s.recordingSince) / 1000).coerceAtLeast(0)
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(RecRed)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "%02d:%02d".format(sec / 60, sec % 60),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        return
    }
    val dot = when {
        s.phase == Phase.Ready && s.cameraInPlayback -> Color(0xFFFFCA28)
        s.phase == Phase.Ready -> Color(0xFF34C759)
        s.phase == Phase.Error -> RecRed
        s.phase == Phase.Idle -> Color.Gray
        else -> Color(0xFFFFCA28)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(7.dp))
        Text(phaseText(s), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Кольцо фокуса: белое — фокусируется, зелёное — есть фокус, красное — не нашёл. */
@Composable
private fun FocusRing(mark: app.a6600remote.FocusMark, status: String?) {
    val color = when (status) {
        "Focused" -> Color(0xFF34C759)
        "Failed" -> RecRed
        else -> Color.White
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width * mark.x, size.height * mark.y)
        val r = 34.dp.toPx()
        drawCircle(color, radius = r, center = c, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color, radius = 3.dp.toPx(), center = c)
    }
}

/** Линии третей внутри кадра (кадр вписан в экран по центру). */
@Composable
private fun ThirdsGrid(aspect: Float) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val vw = size.width
        val vh = size.height
        val w: Float
        val h: Float
        if (vw / vh > aspect) {
            h = vh; w = vh * aspect
        } else {
            w = vw; h = vw / aspect
        }
        val left = (vw - w) / 2f
        val top = (vh - h) / 2f
        val c = Color.White.copy(alpha = 0.4f)
        val sw = 1.dp.toPx()
        for (i in 1..2) {
            val x = left + w * i / 3f
            val y = top + h * i / 3f
            drawLine(c, Offset(x, top), Offset(x, top + h), sw)
            drawLine(c, Offset(left, y), Offset(left + w, y), sw)
        }
    }
}

// ---------------------------------------------------------------------------
// Параметры: строка WB · S · ISO · F · EV и редактор над ней

private val ParamOrder = listOf(ExpParam.Wb, ExpParam.Shutter, ExpParam.Iso, ExpParam.Aperture, ExpParam.Ev)

@Composable
private fun ParamBar(s: UiState, active: ExpParam?, onClick: (ExpParam) -> Unit) {
    val params = ParamOrder.filter { p ->
        when (p) {
            ExpParam.Ev -> s.ev != null
            else -> s.current(p) != null || s.options(p).isNotEmpty()
        }
    }
    if (params.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        params.forEach { p ->
            val isActive = p == active
            val color = if (isActive) Accent else Color.White
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClick(p) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when (p) {
                        ExpParam.Wb -> "WB"
                        ExpParam.Shutter -> "S"
                        ExpParam.Iso -> "ISO"
                        ExpParam.Aperture -> "F"
                        ExpParam.Ev -> "EV"
                    },
                    color = if (isActive) Accent else Dim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    s.display(p, s.current(p)).removePrefix("F"),
                    color = color,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun ParamEditor(s: UiState, param: ExpParam, onValue: (ExpParam, String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (param == ExpParam.Wb) {
            WbEditor(s, onValue = { onValue(ExpParam.Wb, it) })
            return@Column
        }
        val options = s.options(param)
        if (options.isEmpty()) {
            Text(
                when (param) {
                    ExpParam.Shutter -> "Выдержка меняется в режимах S и M (колесо режимов на камере)"
                    ExpParam.Aperture -> "Диафрагма меняется в режимах A и M"
                    ExpParam.Iso -> "ISO меняется в режимах P, A, S и M"
                    ExpParam.Ev -> "Экспокоррекция недоступна в этом режиме"
                    ExpParam.Wb -> ""
                },
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        } else {
            ValueWheel(
                options = options,
                selected = s.current(param),
                display = { s.display(param, it) },
                onSelected = { onValue(param, it) },
                tickSpacing = if (param == ExpParam.Shutter) 14.dp else 16.dp,
                labelMinWidth = if (param == ExpParam.Shutter) 56.dp else 48.dp,
            )
        }
    }
}

/** Баланс белого: «Авто», «K» (ручная температура) и пресеты. */
@Composable
private fun WbEditor(s: UiState, onValue: (String) -> Unit) {
    val options = s.wbOptions
    if (options.isEmpty()) {
        Text(
            "Баланс белого недоступен в этом режиме",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            modifier = Modifier.padding(12.dp),
        )
        return
    }
    val temps = options.filter { it.startsWith("CT:") }
    val presets = options.filter { !it.startsWith("CT:") && it != "Auto WB" }
    val current = s.wb
    val isManual = current?.startsWith("CT:") == true
    var kelvinOpen by remember { mutableStateOf(isManual) }
    LaunchedEffect(isManual) { if (isManual) kelvinOpen = true }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if ("Auto WB" in options) {
            WbChip("Авто", selected = current == "Auto WB") {
                kelvinOpen = false
                onValue("Auto WB")
            }
        }
        if (temps.isNotEmpty()) {
            WbChip(if (isManual) wbLabel(current!!) else "K  ручной", selected = isManual) {
                kelvinOpen = true
                if (!isManual) {
                    temps.minByOrNull { abs((it.removePrefix("CT:").toIntOrNull() ?: 0) - 5500) }
                        ?.let(onValue)
                }
            }
        }
        presets.forEach { p ->
            WbChip(wbLabel(p), selected = current == p) {
                kelvinOpen = false
                onValue(p)
            }
        }
    }
    if (kelvinOpen && temps.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        ValueWheel(
            options = temps,
            selected = if (isManual) current else null,
            display = { wbLabel(it) },
            onSelected = onValue,
            tickSpacing = 11.dp,
            labelMinWidth = 54.dp,
        )
    }
}

@Composable
private fun WbChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// ---------------------------------------------------------------------------
// Режим, спуск, таймер, альбом

@Composable
private fun ControlRail(
    s: UiState,
    vm: CameraViewModel,
    onAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Color(0xE6000000))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        ModeCarousel(s, onMode = vm::selectShootMode, onUnavailable = vm::showMessage, vertical = true)
        TimerButton(s.timerSeconds, onClick = vm::cycleTimer)
        ShutterButton(s, onClick = vm::onShutterPressed)
        AlbumButton(s.lastShot, onClick = onAlbum)
    }
}

/** «ФОТО  ВИДЕО» — как переключатель режимов в камере телефона. */
@Composable
private fun ModeCarousel(
    s: UiState,
    onMode: (String) -> Unit,
    onUnavailable: (String) -> Unit,
    vertical: Boolean = false,
) {
    val items = listOf("still" to "ФОТО", "movie" to "ВИДЕО")
    val current = if (s.isMovieMode) "movie" else "still"
    val item: @Composable (String, String) -> Unit = { mode, label ->
        val selected = mode == current
        Text(
            label,
            color = if (selected) Color.White else Dim,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) Chip else Color.Transparent)
                .clickable {
                    when {
                        selected -> Unit
                        s.isRecording -> onUnavailable("Сначала остановите запись")
                        s.canSwitchMode -> onMode(mode)
                        else -> onUnavailable("Камера не переключает режим по Wi‑Fi — поверните колесо режимов на камере")
                    }
                }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
    if (vertical) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { items.forEach { (m, l) -> item(m, l) } }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { (m, l) -> item(m, l) }
        }
    }
}

@Composable
private fun ShutterButton(s: UiState, onClick: () -> Unit) {
    val recording = s.isRecording
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(3.5.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .clickable(enabled = !s.busy || recording, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            s.busy && !recording -> CircularProgressIndicator(
                color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp),
            )
            recording -> Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(RecRed)
            )
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        when {
                            s.countdown > 0 -> Accent
                            s.isMovieMode -> RecRed
                            else -> Color.White
                        }
                    )
            )
        }
    }
}

@Composable
private fun TimerButton(seconds: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (seconds > 0) {
            Text("${seconds}с", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        } else {
            GlyphIcon(Glyph.Timer, color = Color.White, size = 22.dp)
        }
    }
}

@Composable
private fun AlbumButton(path: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Chip)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            RemoteImage(path, maxSize = 160, modifier = Modifier.fillMaxSize())
        } else {
            GlyphIcon(Glyph.Album, color = Color.White, size = 22.dp)
        }
    }
}

// ---------------------------------------------------------------------------
// Зум: тонкая полоска с ползунком; T/W — шаг или удержание

@Composable
private fun ZoomSlider(
    position: Int?,
    target: Int?,
    onStart: (Boolean) -> Unit,
    onStop: (Boolean) -> Unit,
    onStep: (Boolean) -> Unit,
    onDrag: (Int) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZoomKey("T", zoomIn = true, onStart, onStop, onStep)
        ZoomTrack(position, target, onStart, onStop, onDrag, onDragEnd)
        ZoomKey("W", zoomIn = false, onStart, onStop, onStep)
    }
}

/**
 * Полоска зума. Ведите по ней пальцем:
 *  - если камера сообщает положение зума — объектив едет к точке под пальцем;
 *  - если нет — работает как джойстик: вверх — приближение, вниз — отдаление.
 */
@Composable
private fun ZoomTrack(
    position: Int?,
    target: Int?,
    onStart: (Boolean) -> Unit,
    onStop: (Boolean) -> Unit,
    onDrag: (Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    val knowsPosition = position != null
    androidx.compose.foundation.Canvas(
        Modifier
            .width(44.dp)
            .height(170.dp)
            .pointerInput(knowsPosition) {
                val pad = 10.dp.toPx()
                val h = size.height - 2 * pad
                fun toTarget(y: Float) = ((1f - ((y - pad) / h)).coerceIn(0f, 1f) * 100).toInt()
                var startY = 0f
                var joystick: Boolean? = null
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        startY = offset.y
                        joystick = null
                        if (knowsPosition) onDrag(toTarget(offset.y))
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        if (knowsPosition) {
                            onDrag(toTarget(change.position.y))
                        } else {
                            val dy = startY - change.position.y
                            val want = when {
                                dy > 24f -> true
                                dy < -24f -> false
                                else -> null
                            }
                            if (want != joystick) {
                                joystick?.let { onStop(it) }
                                want?.let { onStart(it) }
                                joystick = want
                            }
                        }
                    },
                    onDragEnd = {
                        if (knowsPosition) onDragEnd()
                        joystick?.let { onStop(it) }
                        joystick = null
                    },
                    onDragCancel = {
                        if (knowsPosition) onDragEnd()
                        joystick?.let { onStop(it) }
                        joystick = null
                    },
                )
            }
            .pointerInput(knowsPosition) {
                val pad = 10.dp.toPx()
                val h = size.height - 2 * pad
                detectTapGestures { offset ->
                    if (knowsPosition) {
                        onDrag(((1f - (offset.y - pad) / h).coerceIn(0f, 1f) * 100).toInt())
                        onDragEnd()
                    }
                }
            }
    ) {
        val pad = 10.dp.toPx()
        val x = size.width / 2f
        val top = pad
        val bottom = size.height - pad
        fun yOf(p: Int) = bottom - (bottom - top) * (p.coerceIn(0, 100) / 100f)
        // дорожка
        drawLine(Color.White.copy(alpha = 0.3f), Offset(x, top), Offset(x, bottom), 3.dp.toPx(), StrokeCap.Round)
        if (position != null) {
            val y = yOf(position)
            drawLine(Accent, Offset(x, y), Offset(x, bottom), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(x, y))
        }
        if (target != null) {
            drawCircle(
                Accent, radius = 10.dp.toPx(), center = Offset(x, yOf(target)),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun ZoomKey(
    label: String,
    zoomIn: Boolean,
    onStart: (Boolean) -> Unit,
    onStop: (Boolean) -> Unit,
    onStep: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (pressed) Accent else Chip)
            .pointerInput(zoomIn) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val down = SystemClock.elapsedRealtime()
                        var started = false
                        // Держат дольше 250 мс — плавный зум
                        val released = withTimeoutOrNull(250) { tryAwaitRelease() }
                        if (released == null) {
                            started = true
                            onStart(zoomIn)
                            tryAwaitRelease()
                        }
                        pressed = false
                        if (started) onStop(zoomIn)
                        else if (SystemClock.elapsedRealtime() - down < 250) onStep(zoomIn)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun CenterNote(text: String, modifier: Modifier = Modifier, progress: Boolean = false) {
    Column(
        modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB3000000))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (progress) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        Text(text, color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ConnectPanel(
    s: UiState,
    onConnect: () -> Unit,
    onScanQr: () -> Unit,
    onUseCurrentWifi: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(24.dp)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF16161A))
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (s.phase) {
            Phase.Idle, Phase.Error -> {
                Text("A6600 Remote", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (s.phase == Phase.Error && s.error != null) {
                    Text(s.error, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
                }
                Text(
                    if (s.ssid.isBlank())
                        "На камере: MENU → Сеть → Упр. со смартфона → Вкл. → Подключение.\n" +
                                "Камера покажет QR-код — отсканируйте его."
                    else "Сеть камеры: ${s.ssid}",
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                val yellow = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                if (s.ssid.isNotBlank()) {
                    Button(onClick = onConnect, colors = yellow, modifier = Modifier.fillMaxWidth()) {
                        Text("Подключиться", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                        Text("Сканировать QR-код камеры", color = Color.White)
                    }
                } else {
                    Button(onClick = onScanQr, colors = yellow, modifier = Modifier.fillMaxWidth()) {
                        Text("Сканировать QR-код камеры", fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onUseCurrentWifi) {
                    Text("Уже подключён к камере в настройках Wi‑Fi", color = Dim)
                }
                TextButton(onClick = onSettings) { Text("Ввести вручную", color = Dim) }
            }

            else -> {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
                Text(phaseText(s), color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SettingsDialog(
    s: UiState,
    onBuffer: (Int) -> Unit,
    onShowFps: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onShowLog: () -> Unit,
    onRecordState: () -> Unit,
    onDisconnect: () -> Unit,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var ssidField by remember { mutableStateOf(s.ssid) }
    var passField by remember { mutableStateOf(s.password) }
    var debugOpen by remember { mutableStateOf(false) }
    val switchColors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.Black)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161A),
        title = { Text("Настройки", color = Color.White) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Wi‑Fi камеры", color = Color.White, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = onScanQr,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сканировать QR-код камеры") }
                Text(
                    "Или вручную: имя сети и пароль камера показывает на экране подключения " +
                            "(кнопка с корзиной). Пустое имя — использовать текущий Wi‑Fi телефона.",
                    fontSize = 12.sp,
                    color = Dim,
                )
                OutlinedTextField(
                    value = ssidField,
                    onValueChange = { ssidField = it },
                    label = { Text("Имя сети (DIRECT-…)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = passField,
                    onValueChange = { passField = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (s.phase != Phase.Idle) {
                    TextButton(onClick = onDisconnect) { Text("Отключиться", color = RecRed) }
                }
                HorizontalDivider(color = GlassBorder)
                // Отладка — свёрнута по умолчанию
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { debugOpen = !debugOpen }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Отладка", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (debugOpen) "▲" else "▼", color = Dim)
                }
                if (debugOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Плавность превью", color = Color.White)
                        Text(
                            "Камера отдаёт кадры неровно. Запас сглаживает их, но добавляет задержку.",
                            fontSize = 12.sp,
                            color = Dim,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF26262B)),
                        ) {
                            listOf(0 to "Быстро", 600 to "Плавно", 1200 to "Макс.").forEach { (ms, label) ->
                                val sel = s.previewBufferMs == ms
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) Accent else Color.Transparent)
                                        .clickable { onBuffer(ms) }
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(label, color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(
                                        if (ms == 0) "без запаса" else "+%.1f с".format(ms / 1000f),
                                        color = if (sel) Color.Black.copy(alpha = 0.7f) else Dim,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                        SettingSwitch(
                            "Показывать FPS", "Частота кадров и паузы превью — для проверки связи",
                            s.showFps, onShowFps, switchColors,
                        )
                        OutlinedButton(onClick = onRecordState, modifier = Modifier.fillMaxWidth()) {
                            Text("Записать состояние камеры", color = Color.White)
                        }
                        OutlinedButton(onClick = onShowLog, modifier = Modifier.fillMaxWidth()) {
                            Text("Журнал", color = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ssidField, passField) }) {
                Text("Сохранить и подключить", color = Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = Color.White) } },
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    colors: androidx.compose.material3.SwitchColors,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = Dim)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = colors)
    }
}

@Composable
private fun DiagnosticsDialog(s: UiState, stats: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val report = buildString {
        appendLine("Превью: $stats")
        appendLine("Состояние: ${s.phase}, камера: ${s.cameraStatus}, функция: ${s.cameraFunction}, режим: ${s.shootMode}")
        appendLine("ISO: ${s.iso} (${s.isoOptions.size}), выдержка: ${s.shutter} (${s.shutterOptions.size}), F: ${s.fNumber} (${s.fNumberOptions.size}), EV: ${s.ev}, зум: ${s.zoomPosition}")
        appendLine("Режимы: ${s.shootModes.joinToString()}")
        appendLine("Фокус в точке: ${if ("setTouchAFPosition" in s.allMethods) "умеет" else "нет"}, сейчас доступен: ${"setTouchAFPosition" in s.apis}")
        appendLine()
        appendLine("Команды камеры:")
        appendLine(s.apis.joinToString())
        appendLine()
        appendLine("Лог:")
        s.log.forEach { appendLine(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Диагностика") },
        text = {
            Text(
                report,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)!!
                cm.setPrimaryClip(ClipData.newPlainText("A6600 Remote log", report))
            }) { Text("Копировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** Сканер QR-кода Google Play Services (своё окно, разрешение на камеру не нужно). */
private fun scanCameraQr(
    context: Context,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { code ->
            val raw = code.rawValue
            if (raw != null) onResult(raw) else onError("пустой QR-код")
        }
        .addOnFailureListener { e -> onError(e.message ?: e.toString()) }
}

private fun phaseText(s: UiState): String = when (s.phase) {
    Phase.Idle -> "Не подключено"
    Phase.ConnectingWifi -> "Подключение к Wi‑Fi камеры…"
    Phase.Searching -> "Поиск камеры…"
    Phase.Starting -> "Запуск режима съёмки…"
    Phase.Reconnecting -> "Переподключение…"
    Phase.Error -> "Ошибка"
    Phase.Ready -> when {
        s.album.open -> "Альбом"
        s.cameraInPlayback -> "Просмотр на камере"
        else -> when (s.cameraStatus) {
            "IDLE", "" -> "Готово"
            "StillCapturing" -> "Снимаю…"
            "StillSaving" -> "Сохраняю…"
            "MovieWaitRecStart" -> "Старт записи…"
            "MovieWaitRecStop" -> "Остановка…"
            "MovieSaving" -> "Сохраняю видео…"
            "NotReady" -> "Камера занята"
            else -> s.cameraStatus
        }
    }
}
