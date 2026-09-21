package org.zephbyte.resourcepackprofiles.client.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

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
