package io.github.whyoolw.monopod.spotify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.io.File

/** Liked Songs has no picture in the Web API; it is drawn the way Spotify draws it. */
object SpotifyCovers {
    /** A white heart on a gradient (Spotify: violet to mint), cached as a file like a real cover. */
    fun liked(dir: File, from: Int = 0xFF450AF5.toInt(), to: Int = 0xFFC4EFD9.toInt()): String {
        val file = File(dir, "liked_${from.toUInt()}.png")
        if (file.exists()) return file.path
        val size = 512f
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size, size, from, to, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = 0xFFFFFFFF.toInt()
        val heart = Path().apply {
            moveTo(256f, 352f)
            cubicTo(150f, 280f, 150f, 190f, 205f, 175f)
            cubicTo(232f, 168f, 250f, 182f, 256f, 200f)
            cubicTo(262f, 182f, 280f, 168f, 307f, 175f)
            cubicTo(362f, 190f, 362f, 280f, 256f, 352f)
            close()
        }
        canvas.drawPath(heart, paint)
        dir.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.path
    }
}
