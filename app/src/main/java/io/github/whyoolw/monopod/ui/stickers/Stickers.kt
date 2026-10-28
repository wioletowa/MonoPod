package io.github.whyoolw.monopod.ui.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.core.content.edit
import io.github.whyoolw.monopod.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sticker on the body. Position and size are fractions of the window, so it stays put when
 * the phone or layout changes size: center ([cx], [cy]) and [width]; [rotation] in degrees.
 */
data class Sticker(
    val id: String,
    val file: String,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val rotation: Float,
    /** Height / width of the image. */
    val aspect: Float,
)

/** The stickers and their files; saved as JSON in the settings. */
class StickerStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "stickers").apply { mkdirs() }

    val stickers = mutableStateListOf<Sticker>().apply { addAll(load()) }
    /** Sticker files a saved preset still needs; those are kept when removed from the body. */
    var inUseElsewhere: () -> Set<String> = { emptySet() }

    fun update(sticker: Sticker) {
        val i = stickers.indexOfFirst { it.id == sticker.id }
        if (i >= 0) stickers[i] = sticker
    }

    /** Saves positions; called when a gesture ends rather than on every frame. */
    fun save() {
        val array = JSONArray()
        stickers.forEach { s ->
            array.put(
                JSONObject().put("id", s.id).put("file", s.file).put("cx", s.cx.toDouble()).put("cy", s.cy.toDouble())
                    .put("width", s.width.toDouble()).put("rotation", s.rotation.toDouble()).put("aspect", s.aspect.toDouble()),
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    fun remove(id: String) {
        val file = stickers.firstOrNull { it.id == id }?.file
        stickers.removeAll { it.id == id }
        save()
        // A preset may still use the picture
        file?.let(::deleteIfUnused)
    }

    /** Puts these stickers on the body (a preset), exactly where they were. */
    fun replaceAll(list: List<Sticker>) {
        val old = stickers.map { it.file }
        stickers.clear()
        stickers.addAll(list.filter { File(it.file).exists() })
        save()
        old.forEach(::deleteIfUnused)
    }

    /** Removes a picture nothing uses any more (a deleted preset's stickers). */
    fun deleteIfUnused(file: String) {
        if (stickers.any { it.file == file } || file in inUseElsewhere()) return
        File(file).delete()
    }

    fun toJson(list: List<Sticker>) = JSONArray().apply {
        list.forEach { s ->
            put(
                JSONObject().put("id", s.id).put("file", s.file).put("cx", s.cx.toDouble()).put("cy", s.cy.toDouble())
                    .put("width", s.width.toDouble()).put("rotation", s.rotation.toDouble()).put("aspect", s.aspect.toDouble()),
            )
        }
    }

    fun fromJson(array: JSONArray) = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        Sticker(
            o.getString("id"), o.getString("file"), o.getDouble("cx").toFloat(), o.getDouble("cy").toFloat(),
            o.getDouble("width").toFloat(), o.getDouble("rotation").toFloat(), o.getDouble("aspect").toFloat(),
        )
    }

    /** Turns a picked image into a sticker file and adds it at [place]'s choice of spot. */
    suspend fun add(uri: Uri, place: (aspect: Float) -> Offset?): Sticker? {
        val bitmap = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let(::makeSticker)
        } ?: return null
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.png")
        withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val aspect = bitmap.height.toFloat() / bitmap.width
        val spot = place(aspect) ?: Offset(0.15f, 0.9f)
        val sticker = Sticker(id, file.path, spot.x, spot.y, START_WIDTH, 0f, aspect)
        stickers.add(sticker)
        save()
        return sticker
    }

    private fun load(): List<Sticker> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Sticker(
                o.getString("id"), o.getString("file"), o.getDouble("cx").toFloat(), o.getDouble("cy").toFloat(),
                o.getDouble("width").toFloat(), o.getDouble("rotation").toFloat(), o.getDouble("aspect").toFloat(),
            )
        }.filter { File(it.file).exists() }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val KEY = "stickers"
        /** A new sticker's width as a share of the window; small enough for the gaps around the wheel. */
        const val START_WIDTH = 0.18f
        const val MIN_WIDTH = 0.06f
        const val MAX_WIDTH = 0.7f
    }
}

/** Largest side of a stored sticker, px. */
private const val STICKER_PX = 512

/**
 * The sticker look: the image scaled down, a white outline around its shape and a soft shadow.
 * Images without transparency become a rounded card with a white border instead.
 */
fun makeSticker(source: Bitmap): Bitmap {
    val scale = min(1f, STICKER_PX.toFloat() / max(source.width, source.height))
    val img = Bitmap.createScaledBitmap(source, max(1, (source.width * scale).toInt()), max(1, (source.height * scale).toInt()), true)
        .copy(Bitmap.Config.ARGB_8888, false)
    val stroke = max(6, (min(img.width, img.height) * 0.05f).toInt())
    val shadow = stroke / 2
    val out = Bitmap.createBitmap(img.width + stroke * 2, img.height + stroke * 2 + shadow, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    if (!hasTransparency(img)) {
        val radius = stroke * 2f
        val card = RectF(0f, 0f, out.width.toFloat(), (out.height - shadow).toFloat())
        canvas.drawRoundRect(RectF(card).apply { offset(0f, shadow.toFloat()) }, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) })
        canvas.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val inner = RectF(stroke.toFloat(), stroke.toFloat(), (stroke + img.width).toFloat(), (stroke + img.height).toFloat())
        val clip = android.graphics.Path().apply { addRoundRect(inner, radius / 2, radius / 2, android.graphics.Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(img, null, inner, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        return out
    }

    // Outline: the shape's silhouette stamped in white all around, a little further each ring
    val silhouette = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    silhouette.colorFilter = PorterDuffColorFilter(Color.argb(70, 0, 0, 0), PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(img, stroke.toFloat(), (stroke + shadow).toFloat(), silhouette)
    silhouette.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    for (ring in listOf(1f, 0.66f, 0.33f)) {
        val r = stroke * ring
        for (k in 0 until 24) {
            val a = Math.toRadians(k * 15.0)
            canvas.drawBitmap(img, stroke + (r * cos(a)).toFloat(), stroke + (r * sin(a)).toFloat(), silhouette)
        }
    }
    canvas.drawBitmap(img, stroke.toFloat(), stroke.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

/** True when a noticeable part of the image is see-through (a cut-out PNG). */
private fun hasTransparency(b: Bitmap): Boolean {
    if (!b.hasAlpha()) return false
    var clear = 0
    var total = 0
    val step = max(1, min(b.width, b.height) / 40)
    for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
        total++
        if (Color.alpha(b.getPixel(x, y)) < 200) clear++
    }
    return clear > total / 50
}

/** Where a sticker may not go: the screen and the click wheel (in window pixels). */
class Keepout(val screen: Rect, val wheel: Rect)

/**
 * How far (px) a sticker of [w]×[h] centered at [center], turned by [rotation]°, reaches out of
 * [window] or into the screen and the wheel; 0 when it sits clear of all of them.
 */
fun stickerOverlap(center: Offset, w: Float, h: Float, rotation: Float, window: Rect, keepout: Keepout): Float {
    val rad = Math.toRadians(rotation.toDouble())
    val bw = (abs(w * cos(rad)) + abs(h * sin(rad))).toFloat()
    val bh = (abs(w * sin(rad)) + abs(h * cos(rad))).toFloat()
    val box = Rect(center.x - bw / 2, center.y - bh / 2, center.x + bw / 2, center.y + bh / 2)
    var overlap = max(0f, window.left - box.left) + max(0f, window.top - box.top) +
        max(0f, box.right - window.right) + max(0f, box.bottom - window.bottom)
    if (box.overlaps(keepout.screen)) {
        val i = box.intersect(keepout.screen)
        overlap += min(i.width, i.height)
    }
    // Wheel: circle against the box
    val wc = keepout.wheel.center
    val wr = keepout.wheel.width / 2
    val nx = wc.x.coerceIn(box.left, box.right)
    val ny = wc.y.coerceIn(box.top, box.bottom)
    val dx = wc.x - nx
    val dy = wc.y - ny
    overlap += max(0f, wr - sqrt(dx * dx + dy * dy))
    return overlap
}

fun stickerFits(center: Offset, w: Float, h: Float, rotation: Float, window: Rect, keepout: Keepout): Boolean =
    stickerOverlap(center, w, h, rotation, window, keepout) == 0f
