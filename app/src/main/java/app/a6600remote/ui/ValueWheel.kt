package app.a6600remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

/** Акцентный цвет (как жёлтый в камере Xiaomi). */
val Accent = Color(0xFFFFC21A)

/**
 * Линейка значений в стиле камеры Xiaomi: частые тонкие деления, подписи только
 * у каждого N-го, текущее значение крупно над центральной меткой.
 * Значение отправляется в камеру, когда линейка остановилась.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ValueWheel(
    options: List<String>,
    selected: String?,
    display: (String) -> String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    tickSpacing: Dp = 16.dp,
    labelMinWidth: Dp = 52.dp,
) {
    if (options.isEmpty()) return
    val startIndex = options.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentOptions by rememberUpdatedState(options)

    // Подписи — у каждого N-го деления, чтобы текст не налезал
    val labelEvery = ceil(labelMinWidth.value / tickSpacing.value).toInt().coerceAtLeast(1)

    val centerIndex by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
                ?: listState.firstVisibleItemIndex
        }
    }

    LaunchedEffect(listState) {
        var first = true
        snapshotFlow { centerIndex }.collect {
            if (!first && listState.isScrollInProgress) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            first = false
        }
    }

    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                wasScrolling = true
            } else if (wasScrolling) {
                wasScrolling = false
                val value = currentOptions.getOrNull(centerIndex)
                if (value != null && value != currentSelected) currentOnSelected(value)
            }
        }
    }

    LaunchedEffect(selected, options) {
        val i = options.indexOf(selected)
        if (i >= 0 && !listState.isScrollInProgress && i != centerIndex) {
            listState.scrollToItem(i)
        }
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Текущее значение крупно
        Text(
            display(options.getOrElse(centerIndex) { options.first() }),
            color = Accent,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            val side = ((maxWidth - tickSpacing) / 2).coerceAtLeast(0.dp)
            LazyRow(
                state = listState,
                flingBehavior = fling,
                contentPadding = PaddingValues(horizontal = side),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(options) { i, option ->
                    val major = i % labelEvery == 0
                    Box(
                        Modifier
                            .width(tickSpacing)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { scope.launch { listState.animateScrollToItem(i) } },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val x = size.width / 2f
                            val top = 6.dp.toPx()
                            val len = (if (major) 14.dp else 8.dp).toPx()
                            drawLine(
                                Color.White.copy(alpha = if (major) 0.8f else 0.4f),
                                Offset(x, top + (14.dp.toPx() - len) / 2f),
                                Offset(x, top + (14.dp.toPx() + len) / 2f),
                                strokeWidth = (if (major) 1.5.dp else 1.dp).toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                        if (major) {
                            Text(
                                display(option),
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .padding(top = 24.dp)
                                    .wrapContentWidth(unbounded = true),
                            )
                        }
                    }
                }
            }
            // Центральная метка
            Canvas(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(4.dp)
                    .height(26.dp)
            ) {
                drawLine(
                    Accent,
                    Offset(size.width / 2f, 2.dp.toPx()),
                    Offset(size.width / 2f, size.height),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // Затухание по краям
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(48.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent)))
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(48.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black)))
            )
        }
    }
}
