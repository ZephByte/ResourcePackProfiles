package org.zephbyte.resourcepackprofiles.client.ui

import kotlin.math.abs
import kotlin.math.exp

/**
 * Drag-to-reorder for a list of equal-height rows, kept "on rails": the pressed row (with any
 * others dragged along with it) lifts out of the list and follows the pointer up and down, never
 * sideways and never past the list's ends. The rows it passes slide aside, the list scrolls when
 * the pointer nears an edge, and on release the lifted rows glide into their new slots.
 *
 * This class holds the order being dragged and the sliding offsets. The list it works on is
 * described by a [View]; drawing the lifted rows is left to the owner, using [lifted] and
 * [liftedTop], while every row draws itself [shiftOf] its slot.
 */
class DragReorder(private val rowHeight: Int, private val view: View) {

    /** The list being reordered, as the drag needs to see it. */
    interface View {
        /** Top of the list's visible area. */
        val top: Int

        /** Bottom of the list's visible area. */
        val bottom: Int

        /** Screen y of each row's slot, in the order of the ids being dragged. */
        fun rowTops(): List<Int>

        /** Shows the rows in the dragged order (it changed mid-drag). */
        fun showOrder()

        /** Scrolls the list by [amount] pixels. Returns whether it actually moved. */
        fun scrollBy(amount: Double): Boolean
    }

    /** The result of a drag that moved something: the new order, and where the pressed row ended up. */
    class Drop(val order: List<String>, val leadIndex: Int, val group: List<String>)

    private var ids: MutableList<String>? = null
    private var leadIndex = -1
    private var group: List<String> = emptyList()
    private var startY = 0.0
    private var mouseY = 0.0
    private var grabOffset = 0.0
    private var moved = false

    /** Rows sliding to their slot, drawn this many pixels from it. Shrinks to zero every frame. */
    private val shifts = mutableMapOf<String, Float>()
    private var lastFrameNanos = System.nanoTime()

    /** The ids in their dragged order while a drag is armed, else null. */
    val order: List<String>? get() = ids

    /** A row was pressed and the button is still down (it may not have moved yet). */
    val isActive: Boolean get() = ids != null

    /** The pressed rows have moved far enough to be lifted out of the list. */
    val isLifting: Boolean get() = ids != null && moved

    /**
     * Arms a drag of [order]: the row at [leadIndex] was pressed at [pointerY], its slot starting
     * at [rowTop]. [group] (in list order, including the pressed row) is dragged along with it.
     */
    fun press(order: List<String>, leadIndex: Int, group: List<String>, pointerY: Double, rowTop: Int) {
        ids = order.toMutableList()
        this.leadIndex = leadIndex
        this.group = group
        startY = pointerY
        mouseY = pointerY
        val lead = order.getOrNull(leadIndex)
        grabOffset = pointerY - rowTop + (group.indexOf(lead).coerceAtLeast(0)) * rowHeight
        moved = false
    }

    /** Follows the pointer. The rows lift once it has moved a few pixels from where it pressed. */
    fun drag(pointerY: Double) {
        if (ids == null) return
        mouseY = pointerY
        if (!moved && abs(mouseY - startY) < LIFT_DISTANCE) return
        moved = true
        retarget()
    }

    /** Per frame: lets sliding rows settle, and scrolls while the pointer is held near an edge. */
    fun frame() {
        settle()
        if (!isLifting) return
        val edge = 16.0
        val step = when {
            mouseY < view.top + edge -> -(1 + (view.top + edge - mouseY) / edge * 5)
            mouseY > view.bottom - edge -> 1 + (mouseY - (view.bottom - edge)) / edge * 5
            else -> return
        }
        if (view.scrollBy(step)) retarget()
    }

    /**
     * Ends the drag. If the rows were lifted, returns the new order and they glide from where
     * they were dropped into their slots; a press that never moved returns null.
     */
    fun release(): Drop? {
        val order = ids ?: return null
        val drop = if (moved) {
            val top = liftedTop()
            val tops = view.rowTops()
            lifted().forEachIndexed { i, id ->
                tops.getOrNull(order.indexOf(id))?.let { slot -> shifts[id] = (top + i * rowHeight - slot).toFloat() }
            }
            Drop(order.toList(), leadIndex, group)
        } else {
            null
        }
        ids = null
        leadIndex = -1
        group = emptyList()
        moved = false
        return drop
    }

    /** The ids being dragged, in list order: the group, or just the pressed row. */
    fun lifted(): List<String> {
        val order = ids ?: return emptyList()
        return group.ifEmpty { listOfNotNull(order.getOrNull(leadIndex)) }
    }

    /** Whether [id]'s row is lifted out of the list, leaving an empty slot behind. */
    fun isLifted(id: String): Boolean = isLifting && id in lifted()

    /** Top of the lifted rows: under the pointer where it grabbed them, kept within the list. */
    fun liftedTop(): Int {
        val bottom = view.bottom - lifted().size * rowHeight
        return (mouseY - grabOffset).toInt().coerceIn(view.top, maxOf(view.top, bottom))
    }

    /** How far from its slot [id]'s row is drawn while it slides into place. */
    fun shiftOf(id: String): Float = shifts[id] ?: 0f

    /**
     * Moves the dragged rows to the slot under the middle of the lifted pressed row. The rest of
     * the group gathers around it in list order; rows that have to make way slide aside.
     */
    private fun retarget() {
        val order = ids ?: return
        val tops = view.rowTops()
        if (tops.isEmpty() || leadIndex !in order.indices) return
        val lead = order[leadIndex]
        val dragged = lifted()
        val probe = liftedTop() + dragged.indexOf(lead) * rowHeight + rowHeight / 2
        val target = when {
            probe < tops.first() -> 0
            probe >= tops.last() + rowHeight -> tops.lastIndex
            else -> tops.indexOfLast { probe >= it }.coerceAtLeast(0)
        }
        val rest = order.filter { it !in dragged }
        val start = (target - dragged.indexOf(lead)).coerceIn(0, rest.size)
        val reordered = rest.subList(0, start) + dragged + rest.subList(start, rest.size)
        if (reordered != order) {
            val before = order.withIndex().associate { it.value to it.index }
            order.clear()
            order.addAll(reordered)
            reordered.forEachIndexed { i, id ->
                val was = before[id] ?: i
                if (was != i && id !in dragged) shifts[id] = shiftOf(id) + (was - i) * rowHeight
            }
            view.showOrder()
        }
        leadIndex = order.indexOf(lead)
    }

    private fun settle() {
        val now = System.nanoTime()
        val seconds = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        if (shifts.isEmpty()) return
        val keep = exp(-seconds * SLIDE_RATE)
        val it = shifts.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.setValue(entry.value * keep)
            if (abs(entry.value) < 0.5f) it.remove()
        }
    }

    private companion object {
        /** How far the pointer must move before a pressed row lifts; less is still a click. */
        const val LIFT_DISTANCE = 3.0
        /** How quickly sliding rows settle: larger is snappier. */
        const val SLIDE_RATE = 18f
    }
}
