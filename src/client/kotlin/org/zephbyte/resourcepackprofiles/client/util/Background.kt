package org.zephbyte.resourcepackprofiles.client.util

import net.minecraft.client.Minecraft

/**
 * Runs [work] on a daemon thread named [name] (network calls, file dialogs, downloads), then hands
 * its result to [onDone] back on the game thread, where screens and game state may be touched.
 * An exception thrown by [work] arrives as a failed [Result]; logging it is up to the caller.
 */
fun <T> runInBackground(name: String, work: () -> T, onDone: (Result<T>) -> Unit) {
    Thread {
        val result = try {
            Result.success(work())
        } catch (e: Exception) {
            Result.failure(e)
        }
        Minecraft.getInstance().execute { onDone(result) }
    }.apply {
        this.name = name
        isDaemon = true
    }.start()
}
