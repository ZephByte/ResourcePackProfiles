package org.zephbyte.resourcepackprofiles.client.util

//? if >=26.3 {
import net.minecraft.client.Minecraft
import org.lwjgl.sdl.SDLDialog
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDL_DialogFileCallback
import org.lwjgl.sdl.SDL_DialogFileFilter
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

//?} else {
/*import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
*///?}

//? if >=26.3 {
/**
 * Native file dialogs through SDL, which is what Minecraft 26.3 uses for its window. (Java's own
 * AWT dialogs cannot be used: the game runs with `java.awt.headless=true`, so they throw.)
 *
 * These calls block until the user closes the dialog, so invoke them off the render thread.
 * Calling them on the render thread would deadlock, because SDL shows the dialog from there.
 */
object FileDialogs {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")

    /** Opens a file-open dialog. Returns the chosen path, or null if cancelled. */
    fun openFile(title: String, patterns: Array<String>, description: String): String? =
        showDialog(null, patterns, description)

    /** Opens a file-save dialog. Returns the chosen path, or null if cancelled. */
    fun saveFile(title: String, defaultName: String, patterns: Array<String>, description: String): String? {
        val path = showDialog(defaultName, patterns, description) ?: return null
        // Not every platform appends the extension when the user types a bare name.
        val extension = defaultName.substringAfterLast('.', "")
        return if (extension.isEmpty() || path.endsWith(".$extension", ignoreCase = true)) path else "$path.$extension"
    }

    /** Shows a save dialog when [defaultName] is given, otherwise an open dialog. */
    private fun showDialog(defaultName: String?, patterns: Array<String>, description: String): String? {
        val result = CompletableFuture<String?>()

        // SDL wants extensions as "png;jpg" rather than "*.png", and keeps pointers to these
        // strings until the dialog closes, so they live on the heap and are freed afterwards.
        val nameBuffer = MemoryUtil.memUTF8(description)
        val patternBuffer = MemoryUtil.memUTF8(patterns.joinToString(";") { it.removePrefix("*.") })
        val locationBuffer: ByteBuffer? = defaultName?.let {
            MemoryUtil.memUTF8(Path.of(System.getProperty("user.home"), it).toString())
        }
        val filters = SDL_DialogFileFilter.calloc(1)
        filters[0].name(nameBuffer).pattern(patternBuffer)

        // fileList is a null-terminated array of C strings: null means an error, an empty array
        // means the user cancelled. SDL may call this from a thread of its own.
        val callback = SDL_DialogFileCallback.create { _, fileList, _ ->
            val path = try {
                if (fileList == 0L) {
                    logger.error("File dialog failed: {}", SDLError.SDL_GetError())
                    null
                } else {
                    val first = MemoryUtil.memGetAddress(fileList)
                    if (first == 0L) null else MemoryUtil.memUTF8(first)
                }
            } catch (e: Throwable) {
                logger.error("Could not read file dialog result", e)
                null
            }
            result.complete(path)
        }

        // SDL requires dialogs to be opened from the main thread. No parent window is passed, so
        // the dialog is not modal to the game.
        Minecraft.getInstance().execute {
            try {
                if (defaultName != null) {
                    SDLDialog.SDL_ShowSaveFileDialog(callback, 0L, 0L, filters, locationBuffer)
                } else {
                    SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, 0L, filters, null as ByteBuffer?, false)
                }
            } catch (e: Throwable) {
                logger.error("Could not open file dialog", e)
                result.complete(null)
            }
        }

        val path = try {
            result.get()
        } catch (e: Exception) {
            null
        }
        // The callback's native stub must outlive the call that completed the future, so give
        // that call a moment to return before freeing it.
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {
        }
        callback.free()
        filters.free()
        MemoryUtil.memFree(nameBuffer)
        MemoryUtil.memFree(patternBuffer)
        locationBuffer?.let { MemoryUtil.memFree(it) }
        return path
    }
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
