package org.zephbyte.resourcepackprofiles.client.update

/**
 * Live state of a batch of downloads. The download thread writes it and the screen reads it every
 * frame, so every field is volatile and nothing here blocks.
 */
class DownloadProgress(updates: List<PackUpdate>) {
    enum class State { WAITING, ACTIVE, DONE, FAILED }

    /**
     * One file. [sourceId] is the pack id it was downloaded for (a missing pack, or the pack being
     * updated); [resultId] is the pack id of the finished file, set just before it turns DONE.
     */
    class Item(val sourceId: String, val name: String, val size: Long) {
        @Volatile var state = State.WAITING
        @Volatile var bytes = 0L
        @Volatile var resultId: String? = null
    }

    val items: List<Item> = updates.map { Item(it.packId, it.fileName, it.size) }

    val total: Int get() = items.size

    /** Downloads that are over, successfully or not. */
    val finished: Int get() = items.count { it.state == State.DONE || it.state == State.FAILED }

    /**
     * Overall progress from 0 to 1, weighted by file size so one large pack doesn't count the same
     * as a tiny one. Files of unknown size count as one unit each and only advance when finished.
     */
    fun fraction(): Float {
        if (items.isEmpty()) return 0f
        var weight = 0.0
        var done = 0.0
        for (item in items) {
            val w = if (item.size > 0) item.size.toDouble() else 1.0
            weight += w
            done += when (item.state) {
                State.DONE, State.FAILED -> w
                State.ACTIVE -> if (item.size > 0) item.bytes.coerceAtMost(item.size).toDouble() else 0.0
                State.WAITING -> 0.0
            }
        }
        return (done / weight).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * What came of a batch: [replacements] maps each pack id that was asked for to the pack id of the
 * file now on disk (they differ when the file was saved under another name), and [failed] lists
 * the downloads that did not complete.
 */
class DownloadOutcome(val replacements: Map<String, String>, val failed: List<PackUpdate>)
