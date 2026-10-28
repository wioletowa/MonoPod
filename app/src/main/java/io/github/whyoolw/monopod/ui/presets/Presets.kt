package io.github.whyoolw.monopod.ui.presets

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.whyoolw.monopod.AppState
import io.github.whyoolw.monopod.Prefs
import io.github.whyoolw.monopod.ui.menu.Page
import io.github.whyoolw.monopod.ui.stickers.StickerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A saved look: the Appearance settings, the stickers where they were, and a snapshot of the app. */
class LookPreset(val look: JSONObject, val stickers: JSONArray, val thumb: String) {
    /** Two looks are the same when settings and stickers match; the picture does not count. */
    val signature: String get() = look.toString() + stickers.toString()
}

/** Six slots of looks, like the HOME Menu layouts of a 3DS. */
class PresetStore(context: Context, private val app: AppState, private val stickers: StickerStore) {
    private val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "presets").apply { mkdirs() }
    private val scope = MainScope()

    val slots = mutableStateListOf<LookPreset?>().apply { addAll(load()) }

    /** Takes a picture of the app as it is on screen; set by the activity. */
    var capture: (onDone: (Bitmap?) -> Unit) -> Unit = { it(null) }

    init {
        stickers.inUseElsewhere = { slots.filterNotNull().flatMap(::stickerFiles).toSet() }
    }

    private fun current() = LookPreset(app.exportLook(), stickers.toJson(stickers.stickers.toList()), "")

    /** True when what is on screen now is already in one of the slots. */
    fun currentIsSaved(): Boolean {
        val now = current().signature
        return slots.any { it?.signature == now }
    }

    /** Saves the look on screen into [slot]; [then] runs once the picture is taken. */
    fun save(slot: Int, then: () -> Unit = {}) {
        val now = current()
        capture { bitmap ->
            scope.launch {
                val thumb = File(dir, "${UUID.randomUUID()}.png")
                withContext(Dispatchers.IO) {
                    // A quarter of the screen is plenty for a slot
                    bitmap?.let { Bitmap.createScaledBitmap(it, it.width / 4, it.height / 4, true) }
                        ?.let { b -> thumb.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                val old = slots[slot]
                slots[slot] = LookPreset(now.look, now.stickers, thumb.path)
                persist()
                old?.let(::forget)
                then()
            }
        }
    }

    fun apply(slot: Int) {
        val p = slots[slot] ?: return
        app.importLook(p.look)
        stickers.replaceAll(stickers.fromJson(p.stickers))
    }

    fun delete(slot: Int) {
        val old = slots[slot] ?: return
        slots[slot] = null
        persist()
        forget(old)
    }

    /** Drops a preset's picture and the sticker files only it used. */
    private fun forget(p: LookPreset) {
        File(p.thumb).delete()
        stickerFiles(p).forEach(stickers::deleteIfUnused)
    }

    private fun stickerFiles(p: LookPreset) = (0 until p.stickers.length()).map { p.stickers.getJSONObject(it).getString("file") }

    private fun persist() {
        val array = JSONArray()
        slots.forEach { p ->
            array.put(p?.let { JSONObject().put("look", it.look).put("stickers", it.stickers).put("thumb", it.thumb) } ?: JSONObject.NULL)
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private fun load(): List<LookPreset?> {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return List(SLOTS) { i ->
            array.optJSONObject(i)?.let { LookPreset(it.getJSONObject("look"), it.getJSONArray("stickers"), it.getString("thumb")) }
        }
    }

    companion object {
        const val SLOTS = 6
        private const val KEY = "look_presets"
    }
}

/**
 * The presets screen: 3 × 2 slots. Center on an empty slot saves the current look there, on a
 * full one applies it (asking first if the current look is not saved anywhere). Holding the
 * center opens Overwrite / Delete.
 */
class PresetsPage(override val title: String, private val store: PresetStore) : Page {
    enum class Mode { Grid, Actions, Ask, PickSave }

    var selected by mutableIntStateOf(0)
        private set
    var mode by mutableStateOf(Mode.Grid)
        private set
    /** Highlighted line of the actions popup or the yes/no question. */
    var choice by mutableIntStateOf(0)
        private set
    /** The preset to apply once the current look has been saved. */
    private var pending: Int? = null

    fun scroll(steps: Int) {
        when (mode) {
            Mode.Grid, Mode.PickSave -> selected = (selected + steps).coerceIn(0, PresetStore.SLOTS - 1)
            Mode.Actions, Mode.Ask -> choice = (choice + steps).coerceIn(0, 1)
        }
    }

    fun select() {
        when (mode) {
            Mode.Grid -> when {
                store.slots[selected] == null -> store.save(selected)
                store.currentIsSaved() -> store.apply(selected)
                else -> { pending = selected; choice = 0; mode = Mode.Ask }
            }
            Mode.Ask -> {
                if (choice == 0) {
                    mode = Mode.PickSave
                } else {
                    pending?.let(store::apply)
                    pending = null
                    mode = Mode.Grid
                }
            }
            Mode.PickSave -> {
                // Put on the picked look only after the old one is saved, picture included
                val target = pending?.takeIf { it != selected }
                pending = null
                store.save(selected) { target?.let(store::apply) }
                mode = Mode.Grid
            }
            Mode.Actions -> {
                if (choice == 0) store.save(selected) else store.delete(selected)
                mode = Mode.Grid
            }
        }
    }

    fun selectLong() {
        if (mode == Mode.Grid && store.slots[selected] != null) {
            choice = 0
            mode = Mode.Actions
        }
    }

    /** MENU: closes a popup first; false when the page itself should close. */
    fun back(): Boolean {
        if (mode == Mode.Grid) return false
        pending = null
        mode = Mode.Grid
        return true
    }
}
