package io.github.whyoolw.monopod.ui.presets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.ui.menu.CoverCache
import io.github.whyoolw.monopod.ui.screen.Lcd
import io.github.whyoolw.monopod.ui.screen.LocalLcdFont

@Composable
fun PresetsView(page: PresetsPage, store: PresetStore, modifier: Modifier = Modifier) {
    val lcd = Lcd
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            val hint = if (page.mode == PresetsPage.Mode.PickSave) R.string.presets_pick_slot else R.string.presets_hint
            BasicText(
                stringResource(hint),
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 11.sp, color = if (page.mode == PresetsPage.Mode.PickSave) lcd.accent else lcd.dimText, textAlign = TextAlign.Center),
            )
            // Slots shaped like the phone, so each shows the whole player
            val config = LocalConfiguration.current
            val aspect = config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val gap = 8.dp
                val h = minOf((maxHeight - gap) / 2, ((maxWidth - gap * 2) / 3) / aspect)
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (col in 0 until 3) {
                                val i = row * 3 + col
                                Slot(store.slots[i]?.thumb, selected = i == page.selected, Modifier.size(h * aspect, h))
                            }
                        }
                    }
                }
            }
        }
        when (page.mode) {
            PresetsPage.Mode.Actions -> Popup(
                null, listOf(stringResource(R.string.presets_overwrite), stringResource(R.string.presets_delete)), page.choice, vertical = true,
            )
            PresetsPage.Mode.Ask -> Popup(
                stringResource(R.string.presets_save_current), listOf(stringResource(R.string.yes), stringResource(R.string.no)), page.choice, vertical = false,
            )
            else -> Unit
        }
    }
}

@Composable
private fun Slot(thumb: String?, selected: Boolean, modifier: Modifier) {
    val lcd = Lcd
    val shape = RoundedCornerShape(6.dp)
    val image by produceState(CoverCache.peek(thumb, full = true), thumb) { value = CoverCache.load(thumb, full = true) }
    Box(
        modifier
            .border(if (selected) 3.dp else 1.dp, if (selected) lcd.accent else lcd.barLine.copy(alpha = 0.4f), shape)
            .padding(if (selected) 3.dp else 1.dp)
            .clip(shape)
            .background(lcd.placeholder),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (thumb != null && img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            // An empty slot: the big "+" that saves the current look
            BasicText("+", style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Light, color = lcd.placeholderText))
        }
    }
}

/** A small dialog over the grid: the actions of a slot, or a yes/no question. */
@Composable
private fun Popup(question: String?, options: List<String>, choice: Int, vertical: Boolean) {
    val lcd = Lcd
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(220.dp).clip(RoundedCornerShape(10.dp)).background(lcd.background).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (question != null) BasicText(
                question,
                Modifier.padding(6.dp),
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = lcd.text, textAlign = TextAlign.Center),
            )
            val item: @Composable (Int, Modifier) -> Unit = { i, mod ->
                val on = i == choice
                Box(
                    mod.clip(RoundedCornerShape(6.dp))
                        .then(if (on) Modifier.background(Brush.verticalGradient(listOf(lcd.selectionTop, lcd.selectionBottom))) else Modifier)
                        .padding(vertical = 6.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        options[i],
                        style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) lcd.onSelection else lcd.text),
                    )
                }
            }
            if (vertical) options.indices.forEach { item(it, Modifier.fillMaxWidth()) }
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.indices.forEach { item(it, Modifier.weight(1f)) }
            }
        }
    }
}
