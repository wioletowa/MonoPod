package io.github.whyoolw.monopod.ui.menu

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/** Rows on the classic 4:3 screen; bigger layouts fit more, see [PageState.visibleRows]. */
const val VISIBLE_ROWS = 7

/** Lists this long speed up when the wheel spins fast. */
const val ACCEL_MIN_ITEMS = 20

/** Lists this long show the big letter while flying through them. */
const val LETTER_MIN_ITEMS = 40

/** How long the wheel speed is measured over, and how long the big letter stays after. */
private const val ACCEL_WINDOW_MS = 300L
private const val LETTER_SHOWN_MS = 800L

/** Up to this many clicks a second every click is exactly one row. */
private const val CALM_CLICKS_PER_SECOND = 6f

/**
 * Rows per click at this wheel speed: exactly one while turning calmly, then growing in step
 * with the speed, so a faster turn always scrolls faster. [boost] scales the growth (0 = off).
 */
fun scrollSpeed(clicksPerSecond: Float, boost: Float = 1f): Float =
    (1f + (clicksPerSecond - CALM_CLICKS_PER_SECOND).coerceAtLeast(0f) * 0.3f * boost).coerceAtMost(16f)

/** The letter shown while flying through a list: first letter, "The " skipped, digits as "#". */
fun indexLetter(label: String): String {
    val t = label.trim().let { if (it.startsWith("the ", ignoreCase = true)) it.substring(4) else it }
    val c = t.firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (c.isDigit()) "#" else c.uppercase()
}

interface Page {
    val title: String
}

/**
 * A menu list. Items come from [provider] so pages like the folder list can follow state.
 * [split] shows the list on the left half and the selected row's [MenuItem.art] on the right,
 * like the iPod Classic's album lists.
 */
class ListPage(override val title: String, val split: Boolean = false, private val provider: () -> List<MenuItem>) : Page {
    constructor(title: String, items: List<MenuItem>, split: Boolean = false) : this(title, split, { items })
    constructor(title: String, provider: () -> List<MenuItem>) : this(title, false, provider)

    val items: List<MenuItem> get() = provider()
}

/** A plain text screen, used for things that are not built yet. */
class MessagePage(override val title: String, val text: String) : Page

/** A value the wheel turns, like the effect strength; the center button confirms. */
class SliderPage(
    override val title: String,
    val max: Int,
    val get: () -> Int,
    val set: (Int) -> Unit,
    /** How the value reads on screen; a percentage by default. */
    val format: (Int) -> String = { "${it * 100 / max}%" },
) : Page

class MenuItem(
    val label: String,
    /** Shows the › chevron: selecting it opens another page. */
    val opensPage: Boolean = true,
    /** For option lists: whether this option is the active one (read from snapshot state). */
    val checked: (() -> Boolean)? = null,
    /** For settings: the current value shown on the right, e.g. "On". */
    val value: (() -> String)? = null,
    /** Reserve a square for album art left of the label; [cover] null shows a placeholder. */
    val withCover: Boolean = false,
    val cover: String? = null,
    /** Covers shown on the right half of a split list while this row is selected; several take turns. */
    val art: List<String?> = emptyList(),
    /** A small picture left of the label, e.g. the green Spotify mark. */
    val icon: Int? = null,
    /** Leave this page after the item ran, e.g. a "Remove" confirmation. */
    val backAfter: Boolean = false,
    /** Holding the center button on this row: a page of actions (Update, Delete…). */
    val onHold: (() -> Page?)? = null,
    /** Runs on select; a returned page is pushed onto the stack. */
    val onSelect: () -> Page?,
)

class PageState(val page: Page, val depth: Int) {
    var selected by mutableIntStateOf(0)
    var firstVisible by mutableIntStateOf(0)
    /** Set by the list view from the screen height. */
    var visibleRows by mutableIntStateOf(VISIBLE_ROWS)
    /** Uptime until which the big letter is shown. */
    var letterUntil by mutableLongStateOf(0L)
}

/** iPod-style navigation: a stack of pages, the wheel moves the selection, MENU pops. */
class MenuController(root: Page) {
    val stack = mutableStateListOf(PageState(root, 0))
    /** Recent wheel clicks (time, count) for the speed-up. */
    private val recent = ArrayDeque<Pair<Long, Int>>()
    /** Fractions of a row left over from sped-up clicks. */
    private var carry = 0f
    /** How strongly fast turns speed up lists (developer setting). */
    var accelBoost = 1f
    val current: PageState get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun scroll(steps: Int) {
        val s = current
        (s.page as? SliderPage)?.let { it.set((it.get() + steps).coerceIn(0, it.max)); return }
        val items = (s.page as? ListPage)?.items ?: return
        var move = steps
        val now = SystemClock.uptimeMillis()
        recent.addLast(now to kotlin.math.abs(steps))
        while (recent.isNotEmpty() && now - recent.first().first > ACCEL_WINDOW_MS) recent.removeFirst()
        if (items.size >= ACCEL_MIN_ITEMS) {
            val rate = recent.sumOf { it.second } * 1000f / ACCEL_WINDOW_MS
            val speed = scrollSpeed(rate, accelBoost)
            // Keep the fraction for the next click, so speeding up and slowing down are smooth
            val exact = steps * speed + carry
            move = exact.toInt()
            carry = exact - move
            if (move == 0) move = steps.coerceIn(-1, 1).also { carry = 0f }
            if (speed >= 2f && items.size >= LETTER_MIN_ITEMS) s.letterUntil = now + LETTER_SHOWN_MS
        } else {
            carry = 0f
        }
        s.selected = (s.selected + move).coerceIn(0, items.lastIndex)
        if (s.selected < s.firstVisible) s.firstVisible = s.selected
        if (s.selected >= s.firstVisible + s.visibleRows) s.firstVisible = s.selected - s.visibleRows + 1
    }

    fun select() {
        if (current.page is SliderPage) { back(); return }
        val page = current.page as? ListPage ?: return
        val item = page.items.getOrNull(current.selected) ?: return
        val next = item.onSelect()
        if (item.backAfter) back()
        next?.let { stack.add(PageState(it, stack.size)) }
    }

    /** Holding the center button: the selected row's actions, if it has any. */
    fun selectLong() {
        val page = current.page as? ListPage ?: return
        page.items.getOrNull(current.selected)?.onHold?.invoke()?.let { stack.add(PageState(it, stack.size)) }
    }

    fun push(page: Page) {
        stack.add(PageState(page, stack.size))
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun home() {
        while (canGoBack) stack.removeAt(stack.lastIndex)
    }
}
