package org.zephbyte.resourcepackprofiles.client.ui

/**
 * File-browser style selection over a list of ids: a click picks one, Ctrl+click adds or removes
 * one, Shift+click picks the range from the [anchor] (Ctrl+Shift adds the range to the rest).
 *
 * Only ids are held. The list order is passed in each time it matters, because the lists this
 * selects from are rebuilt often.
 */
class MultiSelection {
    private val ids = linkedSetOf<String>()

    /** Where a Shift+click range starts: the id last picked without Shift. */
    var anchor: String? = null
        private set

    val size: Int get() = ids.size

    fun isEmpty(): Boolean = ids.isEmpty()

    operator fun contains(id: String): Boolean = id in ids

    /** Whether [id] is one of several picked ids, so an action on it applies to all of them. */
    fun isOneOfMany(id: String): Boolean = ids.size > 1 && id in ids

    fun clear() {
        ids.clear()
        anchor = null
    }

    /** Picks just [id] and starts ranges from it. With [pick] false, only the range start moves. */
    fun only(id: String, pick: Boolean = true) {
        ids.clear()
        if (pick) ids.add(id)
        anchor = id
    }

    /** Adds [id] if it isn't picked, removes it if it is, and starts ranges from it. */
    fun toggle(id: String) {
        if (!ids.remove(id)) ids.add(id)
        anchor = id
    }

    /**
     * Picks every id in [order] between the anchor and [id]. With [add] the range joins what is
     * already picked; otherwise it replaces it. Returns false (and changes nothing) when there is
     * no anchor in [order] to start from.
     */
    fun extend(id: String, order: List<String>, add: Boolean): Boolean {
        val start = anchor?.takeIf { it in order } ?: return false
        val a = order.indexOf(start)
        val b = order.indexOf(id)
        if (!add) ids.clear()
        ids.addAll(order.subList(minOf(a, b), maxOf(a, b) + 1))
        return true
    }

    /** Picks all of [order]. */
    fun setAll(order: List<String>) {
        ids.clear()
        ids.addAll(order)
    }

    /** The picked ids, in [order]. */
    fun inOrder(order: List<String>): List<String> = order.filter { it in ids }

    /** Forgets picks (and the anchor) that are no longer among [shown]. */
    fun retain(shown: Set<String>) {
        ids.retainAll(shown)
        if (anchor !in shown) anchor = null
    }
}
