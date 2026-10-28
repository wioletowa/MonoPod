package io.github.whyoolw.monopod.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The theme's main menu picture and how much screen color veils it; null without one. */
class MenuPicture(val path: String, val veil: Float)

val LocalMenuPicture = staticCompositionLocalOf<MenuPicture?> { null }

/** A theme picture decoded no bigger than about [maxPx] on its longer side. */
@Composable
fun rememberThemePicture(path: String?, maxPx: Int = 1600): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
    }
    return image
}
