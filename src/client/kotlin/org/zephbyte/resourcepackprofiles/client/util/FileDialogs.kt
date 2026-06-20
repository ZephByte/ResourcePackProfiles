package org.zephbyte.resourcepackprofiles.client.util

import org.lwjgl.BufferUtils
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.tinyfd.TinyFileDialogs

/**
 * Thin wrapper around TinyFileDialogs that hides the manual LWJGL filter-buffer construction.
 * These calls block, so invoke them off the render thread.
 */
object FileDialogs {

    private fun filterBuffer(patterns: Array<String>): PointerBuffer {
        val buf = BufferUtils.createPointerBuffer(patterns.size)
        for (pattern in patterns) buf.put(MemoryUtil.memUTF8(pattern))
        buf.flip()
        return buf
    }

    /** Opens a file-open dialog. Returns the chosen path, or null if cancelled. */
    fun openFile(title: String, patterns: Array<String>, description: String): String? =
        TinyFileDialogs.tinyfd_openFileDialog(title, null, filterBuffer(patterns), description, false)

    /** Opens a file-save dialog. Returns the chosen path, or null if cancelled. */
    fun saveFile(title: String, defaultName: String, patterns: Array<String>, description: String): String? =
        TinyFileDialogs.tinyfd_saveFileDialog(title, defaultName, filterBuffer(patterns), description)
}
