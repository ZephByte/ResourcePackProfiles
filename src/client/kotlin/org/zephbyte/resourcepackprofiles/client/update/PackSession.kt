package org.zephbyte.resourcepackprofiles.client.update

/**
 * What this game session has learned about installed packs from Modrinth. Nothing here is saved;
 * it is shared by every screen so that reopening one doesn't ask Modrinth the same things again.
 * Only read and written on the game thread.
 */
object PackSession {
    /** The latest update check result for each pack id. */
    val checks: MutableMap<String, PackCheck> = mutableMapOf()

    /** Whether the automatic once-per-session update check has run. */
    var autoChecked = false

    /** Installed pack files that Modrinth recognised, by pack id. */
    val identified: MutableMap<String, PackIdentity> = mutableMapOf()

    /** Installed pack files Modrinth doesn't know, so they aren't looked up again. */
    val unidentified: MutableSet<String> = mutableSetOf()
}
