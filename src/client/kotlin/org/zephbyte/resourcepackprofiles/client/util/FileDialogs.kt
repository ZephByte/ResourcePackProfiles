package org.zephbyte.resourcepackprofiles.client.util

//? if >=26.3 {
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

//?} else {
/*import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
*///?}

//? if >=26.3 {
/**
 * Thin wrapper around Java's native file dialog. These calls block, so invoke them off the
 * render thread.
 */
object FileDialogs {

    /** Opens a file-open dialog. Returns the chosen path, or null if cancelled. */
    fun openFile(title: String, patterns: Array<String>, description: String): String? =
        showDialog(title, FileDialog.LOAD, null, patterns, description)

    /** Opens a file-save dialog. Returns the chosen path, or null if cancelled. */
    fun saveFile(title: String, defaultName: String, patterns: Array<String>, description: String): String? =
        showDialog(title, FileDialog.SAVE, defaultName, patterns, description)

    private fun showDialog(
        title: String,
        mode: Int,
        defaultName: String?,
        patterns: Array<String>,
        _description: String
    ): String? = runCatching {
        val dialog = FileDialog(null as Frame?, title, mode)
        try {
            dialog.file = defaultName
            dialog.filenameFilter = { _, name -> patterns.any { it.matchesGlob(name) } }
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it).absolutePath }
        } finally {
            dialog.dispose()
        }
    }.getOrNull()

    private fun String.matchesGlob(filename: String): Boolean =
        filename.matches(Regex(replace(".", "\\.").replace("*", ".*"), RegexOption.IGNORE_CASE))
}
//?} else {
/*/**
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
*///?}
