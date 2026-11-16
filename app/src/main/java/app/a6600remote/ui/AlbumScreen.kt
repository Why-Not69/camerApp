package app.a6600remote.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.a6600remote.AlbumItem
import app.a6600remote.AlbumUi
import app.a6600remote.camera.RemoteImages

@Composable
fun AlbumScreen(
    album: AlbumUi,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onSave: (AlbumItem) -> Unit,
    onRetry: () -> Unit,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    BackHandler {
        if (viewerIndex != null) viewerIndex = null else onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } }   // не пропускать нажатия к экрану камеры
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("← Назад", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(
                    buildString {
                        append(if (album.fromCamera) "Карта памяти" else "Снимки из приложения")
                        if (album.total > 0) append(" · ${album.total}")
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(72.dp))
            }

            album.note?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            when {
                album.error != null -> Message(album.error, actionText = "Повторить", onAction = onRetry)

                album.items.isEmpty() && album.loading -> Message(
                    "Открываю карту памяти…\nКамера переключится в режим просмотра.",
                    progress = true,
                )

                album.items.isEmpty() -> Message(
                    if (album.fromCamera) "На карте нет снимков"
                    else "Пока нет снимков. Снимки, сделанные кнопкой в приложении, появятся здесь."
                )

                else -> Grid(album, onOpen = { viewerIndex = it }, onLoadMore = onLoadMore)
            }
        }

        viewerIndex?.let { start ->
            Viewer(
                items = album.items,
                startIndex = start.coerceIn(0, (album.items.size - 1).coerceAtLeast(0)),
                saving = album.saving,
                onSave = onSave,
                onClose = { viewerIndex = null },
            )
        }
    }
}

@Composable
private fun Grid(album: AlbumUi, onOpen: (Int) -> Unit, onLoadMore: () -> Unit) {
    val gridState = rememberLazyGridState()
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val count by rememberUpdatedState(album.items.size)

    // Догружаем следующую страницу, когда долистали почти до конца
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= count - 12) currentOnLoadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(album.items, key = { _, item -> item.id }) { i, item ->
            Box(
                Modifier
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .clickable { onOpen(i) }
            ) {
                RemoteImage(item.thumb, maxSize = 320, modifier = Modifier.fillMaxSize())
                if (item.isVideo) {
                    Text(
                        "▶ видео",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (album.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(
    items: List<AlbumItem>,
    startIndex: Int,
    saving: Set<String>,
    onSave: (AlbumItem) -> Unit,
    onClose: () -> Unit,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex, pageCount = { items.size })
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            RemoteImage(
                item.large ?: item.thumb,
                maxSize = 2048,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                background = Color.Black,
            )
        }

        val current = items.getOrNull(pager.currentPage)
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← Назад", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(
                    "${pager.currentPage + 1} / ${items.size}",
                    color = Color.White,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (current != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        current.name,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current.isVideo) {
                        Text(
                            "Видео по Wi‑Fi не передаётся — здесь только превью",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                        )
                    } else {
                        val isSaving = current.id in saving
                        Button(onClick = { onSave(current) }, enabled = !isSaving) {
                            Text(if (isSaving) "Сохраняю…" else "Сохранить на телефон")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(
    text: String,
    progress: Boolean = false,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (progress) CircularProgressIndicator(color = Color.White)
            Text(text, color = Color.White, textAlign = TextAlign.Center)
            if (actionText != null) Button(onClick = onAction) { Text(actionText) }
        }
    }
}

/** Картинка с камеры (http) или из файла на телефоне. */
@Composable
fun RemoteImage(
    src: String?,
    maxSize: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    background: Color = Color(0xFF222222),
) {
    val bitmap by produceState<Bitmap?>(null, src, maxSize) {
        value = src?.let { RemoteImages.load(it, maxSize) }
    }
    Box(modifier.background(background), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
