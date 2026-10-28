package io.github.whyoolw.monopod.ui.stickers

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whyoolw.monopod.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
private fun rememberStickerImage(file: String): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file)?.asImageBitmap() }
    }
    return image
}

/** A sticker drawn at its place: [box] is the square (side = longer edge) it turns inside. */
@Composable
private fun StickerImage(sticker: Sticker, windowW: Float, windowH: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val w = sticker.width * windowW
    val h = w * sticker.aspect
    val side = max(w, h)
    val image = rememberStickerImage(sticker.file) ?: return
    with(density) {
        Box(
            modifier
                .offset { IntOffset((sticker.cx * windowW - side / 2).roundToInt(), (sticker.cy * windowH - side / 2).roundToInt()) }
                .size(side.toDp()),
            contentAlignment = Alignment.Center,
        ) {
            Image(image, null, Modifier.size(w.toDp(), h.toDp()).graphicsLayer { rotationZ = sticker.rotation })
        }
    }
}

/** Stickers on the body, under the screen and the wheel; not touchable outside the editor. */
@Composable
fun StickersLayer(store: StickerStore) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        store.stickers.forEach { s -> key(s.id) { StickerImage(s, w, h) } }
    }
}

/**
 * The sticker editor, the one place the touch screen works: drag to move, pinch to resize and
 * turn, tap to select. The screen and the wheel are dimmed and kept free.
 */
@Composable
fun StickerEditor(store: StickerStore, keepout: Keepout, onAdd: () -> Unit, onDone: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    // Save once a gesture settles, not on every frame of it
    LaunchedEffect(store) {
        snapshotFlow { store.stickers.toList() }.collectLatest {
            delay(400)
            store.save()
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { selected = null } },
    ) {
        val windowW = constraints.maxWidth.toFloat()
        val windowH = constraints.maxHeight.toFloat()
            Canvas(Modifier.fillMaxSize()) {
            val dim = Color.Black.copy(alpha = 0.45f)
            val dash = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            drawRect(dim, keepout.screen.topLeft, keepout.screen.size)
            drawRect(Color.White, keepout.screen.topLeft, keepout.screen.size, style = dash)
            drawCircle(dim, keepout.wheel.width / 2, keepout.wheel.center)
            drawCircle(Color.White, keepout.wheel.width / 2, keepout.wheel.center, style = dash)
        }
        // The pictures: whatever reaches over the screen or the wheel is cut away, as if it
        // were tucked under them, which is exactly how they look outside the editor
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val hidden = Path().apply {
                    addRect(keepout.screen)
                    addOval(keepout.wheel)
                }
                clipPath(hidden, ClipOp.Difference) { this@drawWithContent.drawContent() }
            },
        ) {
            store.stickers.forEach { s -> key(s.id) { StickerImage(s, windowW, windowH) } }
        }
        // Handles: the whole sticker stays grabbable, even the part that is tucked away
        store.stickers.forEach { s ->
            key(s.id) {
                val current by rememberUpdatedState(s)
                val w = s.width * windowW
                val side = max(w, w * s.aspect)
                val isSelected = selected == s.id
                with(LocalDensity.current) {
                    Box(
                        Modifier
                            .offset { IntOffset((s.cx * windowW - side / 2).roundToInt(), (s.cy * windowH - side / 2).roundToInt()) }
                            .size(side.toDp())
                            .then(if (isSelected) Modifier.border(2.dp, Color(0xFF2F7FD8), RoundedCornerShape(6.dp)) else Modifier)
                            .pointerInput(s.id) { detectTapGestures { selected = current.id } }
                            .pointerInput(s.id) {
                                detectTransformGestures { _, pan, zoom, rotation ->
                                    selected = current.id
                                    val c = current
                                    // Free movement; only the center has to stay on the phone's screen
                                    store.update(
                                        c.copy(
                                            cx = (c.cx + pan.x / windowW).coerceIn(0f, 1f),
                                            cy = (c.cy + pan.y / windowH).coerceIn(0f, 1f),
                                            width = (c.width * zoom).coerceIn(StickerStore.MIN_WIDTH, StickerStore.MAX_WIDTH),
                                            rotation = c.rotation + rotation,
                                        ),
                                    )
                                }
                            },
                    )
                }
            }
        }
        // The buttons sit on the (dimmed) iPod screen: always free, and clear of camera cutouts
        val screen = keepout.screen
        val onScreen = if (screen.isEmpty) {
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)
        } else {
            with(LocalDensity.current) {
                Modifier
                    .offset { IntOffset(screen.left.roundToInt(), screen.top.roundToInt()) }
                    .size(screen.width.toDp(), screen.height.toDp())
                    .wrapContentHeight(Alignment.CenterVertically)
            }
        }
        Row(
            onScreen,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorButton(stringResource(R.string.sticker_add), enabled = true, onClick = onAdd)
            EditorButton(stringResource(R.string.sticker_delete), enabled = selected != null) {
                selected?.let(store::remove)
                selected = null
            }
            EditorButton(stringResource(R.string.sticker_done), enabled = true) {
                store.save()
                onDone()
            }
        }
    }
}

@Composable
private fun EditorButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(Color.White, RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xFFC9CCD2), RoundedCornerShape(20.dp))
            .pointerInput(enabled) { if (enabled) detectTapGestures { onClick() } }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        BasicText(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1F)))
    }
}

/** First free spot (window fractions) for a new sticker of [aspect], scanning row by row. */
fun findStickerSpot(aspect: Float, windowW: Float, windowH: Float, keepout: Keepout): Offset? {
    val w = StickerStore.START_WIDTH * windowW
    val window = Rect(0f, 0f, windowW, windowH)
    // From 12% down: the very top is the phone's status bar and the editor buttons
    for (fy in 12..95 step 3) for (fx in 10..90 step 4) {
        val c = Offset(windowW * fx / 100f, windowH * fy / 100f)
        if (stickerFits(c, w, w * aspect, 0f, window, keepout)) return Offset(fx / 100f, fy / 100f)
    }
    return null
}
