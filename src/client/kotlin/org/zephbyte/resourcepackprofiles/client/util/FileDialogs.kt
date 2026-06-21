package org.zephbyte.resourcepackprofiles.client.util

import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs

/**
 * Thin wrapper around TinyFileDialogs using a MemoryStack so filter-string memory is
 * freed automatically when the call returns. These calls block, so invoke them off the
 * render thread.
 */
object FileDialogs {

    /** Opens a file-open dialog. Returns the chosen path, or null if cancelled. */
    fun openFile(title: String, patterns: Array<String>, description: String): String? =
        MemoryStack.stackPush().use { stack ->
            val buf = stack.mallocPointer(patterns.size)
            for (pattern in patterns) buf.put(stack.UTF8(pattern))
            buf.flip()
            TinyFileDialogs.tinyfd_openFileDialog(title, null, buf, description, false)
        }

    /** Opens a file-save dialog. Returns the chosen path, or null if cancelled. */
    fun saveFile(title: String, defaultName: String, patterns: Array<String>, description: String): String? =
        MemoryStack.stackPush().use { stack ->
            val buf = stack.mallocPointer(patterns.size)
            for (pattern in patterns) buf.put(stack.UTF8(pattern))
            buf.flip()
            TinyFileDialogs.tinyfd_saveFileDialog(title, defaultName, buf, description)
        }
}
