package io.github.whyoolw.monopod.ui.menu

import androidx.compose.foundation.Canvas
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whyoolw.monopod.ui.screen.Lcd
import io.github.whyoolw.monopod.ui.theme.LocalMenuPicture
import io.github.whyoolw.monopod.ui.theme.rememberThemePicture
import io.github.whyoolw.monopod.ui.screen.LocalLcdFont

@Composable
fun PageView(state: PageState, modifier: Modifier = Modifier) {
    when (val page = state.page) {
        // The main menu may sit on the body theme's picture, veiled in the screen color
        is ListPage -> if (state.depth == 0 && LocalMenuPicture.current != null) {
            val picture = LocalMenuPicture.current!!
            val image = rememberThemePicture(picture.path, maxPx = 1000)
            Box(modifier.fillMaxSize()) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Lcd.background.copy(alpha = picture.veil)))
                MenuList(page, state, Modifier)
            }
        } else MenuList(page, state, modifier)
        is MessagePage -> Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText(
                page.text,
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 15.sp, color = Lcd.dimText, textAlign = TextAlign.Center),
            )
        }
        is SliderPage -> Slider(page, modifier)
        // Pages with their own view (Now Playing) are drawn by the caller
        else -> Unit
    }
}

@Composable
private fun MenuList(page: ListPage, state: PageState, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // ~30 dp per row: 7 rows on the classic screen, more on the bigger layouts
        val rows = (maxHeight / 30.dp).toInt().coerceAtLeast(4)
        SideEffect { state.visibleRows = rows }
        val rowHeight = maxHeight / rows
        // Read once per composition: a dynamic page rebuilds its items on every read
        val items = page.items
        val overflow = items.size > rows
        val selected = state.selected.coerceIn(0, maxOf(items.lastIndex, 0))
        // Keep the selection on screen when the row count changed under it
        val first = state.firstVisible.coerceIn(maxOf(selected - rows + 1, 0), minOf(selected, maxOf(items.size - rows, 0)))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val end = minOf(first + rows, items.size)
                for (i in first until end) {
                    MenuRow(items[i], selected = i == selected, Modifier.height(rowHeight))
                }
            }
            if (overflow) {
                ScrollBar(
                    first = first, total = items.size, rows = rows,
                    Modifier.width(9.dp).fillMaxHeight(),
                )
            }
            if (page.split) ArtPane(items.getOrNull(selected)?.art.orEmpty(), Modifier.weight(1f).fillMaxHeight())
        }
        // The big letter while flying through a long list, like the iPod
        var showLetter by remember { mutableStateOf(false) }
        LaunchedEffect(state.letterUntil) {
            val left = state.letterUntil - SystemClock.uptimeMillis()
            if (left > 0) {
                showLetter = true
                delay(left)
            }
            showLetter = false
        }
        if (showLetter && items.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.Center).size(72.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    indexLetter(items[selected].label),
                    style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White),
                )
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, selected: Boolean, modifier: Modifier) {
    val textColor = if (selected) Lcd.onSelection else Lcd.text
    Row(
        modifier
            .fillMaxWidth()
            .then(
                when {
                    !selected -> Modifier
                    // Material: a rounded "pill" like Android's navigation highlight
                    Lcd.material -> Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        .background(Lcd.selectionTop, RoundedCornerShape(10.dp))
                    else -> Modifier.background(Brush.verticalGradient(listOf(Lcd.selectionTop, Lcd.selectionBottom)))
                },
            )
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.withCover) {
            CoverThumb(item.cover, Modifier.padding(end = 8.dp).fillMaxHeight().padding(vertical = 3.dp).aspectRatio(1f))
        }
        item.icon?.let {
            Image(painterResource(it), null, Modifier.padding(end = 7.dp).size(20.dp))
        }
        BasicText(
            item.label,
            Modifier.weight(1f),
            style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = textColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.value?.let {
            BasicText(
                it(),
                Modifier.padding(start = 6.dp),
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 15.sp, color = if (selected) Lcd.onSelection else Lcd.dimText),
            )
        }
        val trailing = when {
            item.checked?.invoke() == true -> "✓"
            item.opensPage -> "›"
            else -> null
        }
        if (trailing != null) {
            BasicText(
                trailing,
                Modifier.padding(start = 6.dp),
                style = TextStyle(fontFamily = LocalLcdFont.current, 
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = if (selected) Lcd.onSelection else Lcd.dimText,
                ),
            )
        }
    }
}

@Composable
private fun ScrollBar(first: Int, total: Int, rows: Int, modifier: Modifier) {
    val lcd = Lcd
    Canvas(modifier) {
        if (lcd.material) {
            // Material: a slim rounded thumb, no track box
            val w = size.width * 0.45f
            val thumbH = (size.height * rows / total).coerceAtLeast(24f)
            val y = (size.height - thumbH) * first / (total - rows).coerceAtLeast(1)
            drawRoundRect(lcd.dimText.copy(alpha = 0.5f), Offset((size.width - w) / 2, y), Size(w, thumbH), CornerRadius(w / 2))
            return@Canvas
        }
        drawRect(lcd.scrollTrack)
        drawRect(lcd.barLine, style = Stroke(1f))
        val inset = 1.5f
        val track = size.height - inset * 2
        val thumbH = (track * rows / total).coerceAtLeast(12f)
        val maxFirst = (total - rows).coerceAtLeast(1)
        val y = inset + (track - thumbH) * first / maxFirst
        drawRect(
            Brush.horizontalGradient(listOf(lcd.progressTop, lcd.progressBottom)),
            topLeft = Offset(inset, y),
            size = Size(size.width - inset * 2, thumbH),
        )
    }
}

/**
 * The right half of a split list: the selected row's cover, cross-fading when the selection
 * moves; a row with several covers (an artist's "All Songs") shows them in turn.
 */
@Composable
private fun ArtPane(covers: List<String?>, modifier: Modifier) {
    var turn by remember(covers) { mutableStateOf(0) }
    LaunchedEffect(covers) {
        while (covers.size > 1) {
            delay(2500)
            turn = (turn + 1) % covers.size
        }
    }
    val path = covers.getOrNull(turn)
    Box(modifier.background(Lcd.placeholder), contentAlignment = Alignment.Center) {
        Crossfade(path, animationSpec = tween(450), label = "art") { p ->
            val image by produceState(CoverCache.peek(p, full = true), p) { value = CoverCache.load(p, full = true) }
            val img = image
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else BasicText("♪", style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 34.sp, color = Lcd.placeholderText))
            }
        }
    }
}

@Composable
private fun CoverThumb(path: String?, modifier: Modifier) {
    val image by produceState(CoverCache.peek(path), path) { value = CoverCache.load(path) }
    Box(modifier.background(Lcd.placeholder), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            BasicText("♪", style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 14.sp, color = Lcd.placeholderText))
        }
    }
}

@Composable
private fun Slider(page: SliderPage, modifier: Modifier) {
    val lcd = Lcd
    val fraction = page.get().toFloat() / page.max
    Column(
        modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            page.format(page.get()),
            style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = lcd.text),
        )
        Canvas(Modifier.padding(top = 12.dp).fillMaxWidth().height(10.dp)) {
            val r = CornerRadius(2f)
            drawRoundRect(Brush.verticalGradient(listOf(lcd.trackTop, lcd.trackBottom)), cornerRadius = r)
            drawRoundRect(
                Brush.verticalGradient(listOf(lcd.progressTop, lcd.progressBottom)),
                size = Size(size.width * fraction, size.height), cornerRadius = r,
            )
            drawRoundRect(lcd.barLine, cornerRadius = r, style = Stroke(1f))
        }
    }
}
