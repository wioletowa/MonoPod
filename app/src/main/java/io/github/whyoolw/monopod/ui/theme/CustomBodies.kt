package io.github.whyoolw.monopod.ui.theme

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import java.io.File
import java.util.UUID

/**
 * A body from a user's theme, and the settings it sets once when it is picked; [file] is the
 * theme's folder (with its pictures) or, for older themes, its single .css file.
 */
class CustomBody(val body: BodyTheme, val settings: Map<String, String>, val file: File)

/** The theme file could not be used; [message] says why, for the user. */
class ThemeFileError(message: String) : Exception(message)

/**
 * Custom bodies from CSS-like theme files (see assets/monopod-theme-template.css): kept in
 * files/themes, listed before the built-in bodies.
 */
object CustomBodies {
    const val TEMPLATE = "monopod-theme-template.css"
    const val ID_PREFIX = "custom:"

    private var dir: File? = null
    private const val THEME_CSS = "theme.css"
    private const val MAX_THEME_BYTES = 20 * 1024 * 1024
    private val PICTURES = setOf("png", "jpg", "jpeg", "webp")
    val bodies = mutableStateListOf<CustomBody>()

    fun init(context: Context) {
        val d = File(context.filesDir, "themes").apply { mkdirs() }
        dir = d
        bodies.clear()
        d.listFiles()?.sortedBy { it.lastModified() }?.forEach { f ->
            val css = if (f.isDirectory) File(f, THEME_CSS) else f.takeIf { it.extension == "css" } ?: return@forEach
            val id = ID_PREFIX + f.nameWithoutExtension
            runCatching { load(css, if (f.isDirectory) f else null, id) }.getOrNull()?.let { bodies += it }
        }
    }

    fun find(id: String?): CustomBody? = bodies.firstOrNull { it.body.id == id }

    /**
     * Reads a picked theme: a .css file, or a .zip with one .css and the pictures it names.
     * [replace] updates that body instead of adding a new one.
     */
    fun add(context: Context, uri: Uri, replace: CustomBody? = null): CustomBody {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ThemeFileError("cannot read the file")
        if (bytes.size > MAX_THEME_BYTES) throw ThemeFileError("the file is over 20 MB")
        val root = dir ?: throw ThemeFileError("not ready")
        // A fresh folder, swapped in only once everything checks out
        val id = replace?.body?.id?.removePrefix(ID_PREFIX) ?: UUID.randomUUID().toString()
        val staging = File(root, ".new-$id").apply { deleteRecursively(); mkdirs() }
        try {
            if (isZip(bytes)) unzip(bytes, staging) else File(staging, THEME_CSS).writeBytes(bytes)
            val custom = load(File(staging, THEME_CSS), staging, ID_PREFIX + id)
            val target = File(root, id)
            File(root, "$id.css").delete()
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw ThemeFileError("cannot save")
            val saved = load(File(target, THEME_CSS), target, ID_PREFIX + id)
            val i = bodies.indexOfFirst { it.body.id == saved.body.id }
            if (i >= 0) bodies[i] = saved else bodies += saved
            return saved
        } finally {
            staging.deleteRecursively()
        }
    }

    fun delete(custom: CustomBody) {
        custom.file.deleteRecursively()
        bodies.remove(custom)
    }

    /** A theme's text plus, for a folder, its pictures by the names the text gives. */
    private fun load(css: File, folder: File?, id: String): CustomBody {
        val (parsed, settings) = parse(css.readText(), id)
        fun picture(name: String?): String? {
            name ?: return null
            val file = folder?.let { File(it, File(name).name) }
            if (file == null || !file.exists()) throw ThemeFileError("picture not in the archive: $name")
            return file.path
        }
        val body = parsed.copy(bodyImage = picture(parsed.bodyImage), menuImage = picture(parsed.menuImage))
        return CustomBody(body, settings, folder ?: css)
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** The archive's .css (as theme.css) and pictures, by file name only: no folders, nothing else. */
    private fun unzip(bytes: ByteArray, to: File) {
        var css = false
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = File(entry.name).name
                val ext = name.substringAfterLast('.', "").lowercase()
                val out = when {
                    ext == "css" && !css -> File(to, THEME_CSS).also { css = true }
                    ext in PICTURES -> File(to, name)
                    else -> continue
                }
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        if (!css) throw ThemeFileError("no .css file in the archive")
    }

    /** Saves the template into Downloads; returns the file name it got there. */
    fun downloadTemplate(context: Context): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, TEMPLATE)
            put(MediaStore.Downloads.MIME_TYPE, "text/css")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw ThemeFileError("no Downloads")
        resolver.openOutputStream(uri)?.use { out -> context.assets.open(TEMPLATE).use { it.copyTo(out) } }
        return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: TEMPLATE
    }

    /** Settings part 2 understands, and the values each takes (lowercase). */
    val SETTINGS = mapOf(
        "style" to setOf("ipod", "material"),
        "colors" to setOf("cover", "system", "multicolor", "blue", "purple", "pink", "red", "orange", "yellow", "green", "graphite"),
        // "full" is the dropped full-screen layout; it now means big
        "layout" to setOf("classic", "big", "full"),
        "effect" to setOf("off", "lcd", "crt", "mono", "gameboy"),
        "effect-strength" to emptySet(),
        "dark-screen" to setOf("off", "on", "system"),
        "font" to setOf("classic", "pixel", "mono"),
        "hide-status-bar" to setOf("true", "false"),
    )

    private val BODY_KEYS = setOf(
        "name", "body-top", "body-bottom", "finish", "rim", "bezel", "screen-frame", "wheel-top", "wheel-bottom",
        "wheel-edge", "wheel-label", "center-top", "center-bottom", "center-edge", "system-icons",
        "body-image", "menu-image", "menu-image-veil",
    )

    /** The body and the part-2 settings of a theme file's text. */
    fun parse(text: String, id: String): Pair<BodyTheme, Map<String, String>> {
        val clean = text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        val block = Regex("""monopod-theme\s*\{(.*)\}""", RegexOption.DOT_MATCHES_ALL).find(clean)?.groupValues?.get(1)
            ?: throw ThemeFileError("no monopod-theme { … } block")
        val values = LinkedHashMap<String, String>()
        for (line in block.split(';')) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val key = t.substringBefore(':').trim().removePrefix("--").lowercase()
            if (!t.contains(':')) throw ThemeFileError("not a key: value line: $t")
            // url("file.png") or a plain value, quotes optional
            val raw = t.substringAfter(':').trim()
            val value = (Regex("""url\(\s*["']?([^"')]+)["']?\s*\)""").find(raw)?.groupValues?.get(1) ?: raw).trim('"', '\'')
            if (key !in BODY_KEYS && key !in SETTINGS) throw ThemeFileError("unknown key: $key")
            values[key] = value
        }
        val name = values["name"]?.takeIf { it.isNotBlank() } ?: throw ThemeFileError("name is missing")
        fun color(key: String): Color? = values[key]?.let { v ->
            if (v.equals("none", true)) null else parseColor(v) ?: throw ThemeFileError("$key: not a color: $v")
        }
        val top = color("body-top") ?: throw ThemeFileError("body-top is missing")
        val bottom = color("body-bottom") ?: throw ThemeFileError("body-bottom is missing")
        val light = when (values["system-icons"]?.lowercase()) {
            "dark" -> true
            "light" -> false
            null, "auto" -> lerp(top, bottom, 0.5f).luminance() > 0.5f
            else -> throw ThemeFileError("system-icons: dark | light | auto")
        }
        // Missing wheel colors follow the body: lighter on dark bodies, darker on light ones
        val shade = if (light) Color.Black else Color.White
        val wheelTop = color("wheel-top") ?: lerp(top, shade, 0.08f)
        val wheelBottom = color("wheel-bottom") ?: lerp(bottom, shade, 0.08f)
        val edge = color("wheel-edge") ?: lerp(bottom, Color.Black, 0.35f)
        val finish = when (values["finish"]?.lowercase()) {
            null, "gloss" -> Finish.Gloss
            "brushed" -> Finish.Brushed
            "matte" -> Finish.Matte
            else -> throw ThemeFileError("finish: gloss | brushed | matte")
        }
        val settings = LinkedHashMap<String, String>()
        for ((key, allowed) in SETTINGS) {
            val v = values[key]?.lowercase() ?: continue
            if (key == "effect-strength") {
                v.removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 0..1000 } ?: throw ThemeFileError("effect-strength: 0% … 100%")
            } else if (v !in allowed) throw ThemeFileError("$key: ${allowed.joinToString(" | ")}")
            settings[key] = v
        }
        val body = BodyTheme(
            id = id, name = 0, title = name,
            bodyTop = top, bodyBottom = bottom,
            wheelTop = wheelTop, wheelBottom = wheelBottom, wheelEdge = edge,
            label = color("wheel-label") ?: lerp(wheelBottom, if (light) Color.Black else Color.White, 0.55f),
            centerTop = color("center-top") ?: lerp(top, shade, 0.04f),
            centerBottom = color("center-bottom") ?: lerp(bottom, shade, 0.04f),
            centerEdge = color("center-edge") ?: edge,
            lightBody = light, finish = finish,
            bezel = color("bezel") ?: Color(0xFF1B1B1E),
            screenFrame = color("screen-frame"),
            rim = color("rim"),
            // File names here; the store turns them into the theme folder's files
            bodyImage = values["body-image"]?.takeIf { !it.equals("none", true) },
            menuImage = values["menu-image"]?.takeIf { !it.equals("none", true) },
            menuVeil = values["menu-image-veil"]?.let { v ->
                v.removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 0..100 }?.div(100f)
                    ?: throw ThemeFileError("menu-image-veil: 0% … 100%")
            } ?: 0.35f,
        )
        return body to settings
    }

    /** #RGB, #RRGGBB or #AARRGGBB. */
    fun parseColor(v: String): Color? {
        val hex = v.trim().removePrefix("#")
        if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val argb = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        return Color(argb.toLong(16))
    }
}
