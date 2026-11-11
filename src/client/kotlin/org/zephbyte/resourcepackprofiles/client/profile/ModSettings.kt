package org.zephbyte.resourcepackprofiles.client.profile

/** Whether updating several packs at once saves a backup copy of the profile first. */
enum class BackupChoice(val id: String) {
    ASK("ask"), ALWAYS("always"), NEVER("never")
}

/**
 * The mod's options, as set on the Settings screen and by the first-run question. They are
 * stored in the same config file as the profiles, so changing one saves through [ProfileManager].
 */
object ModSettings {
    private var replace = true
    private var backup = BackupChoice.ASK
    private var autoCheck: Boolean? = null

    /** One-shot escape hatch so the vanilla screen can still be opened on request. */
    private var allowVanillaOnce = false

    /** Whether the profile screen opens in place of the vanilla resource pack screen. */
    var replaceVanillaScreen: Boolean
        get() = replace
        set(value) {
            replace = value
            ProfileManager.save()
        }

    /** Whether updating several packs at once backs the profile up first. */
    var backupChoice: BackupChoice
        get() = backup
        set(value) {
            backup = value
            ProfileManager.save()
        }

    /** Whether packs are checked for updates automatically; null until the first-run question is answered. */
    var autoCheckUpdates: Boolean?
        get() = autoCheck
        set(value) {
            autoCheck = value
            ProfileManager.save()
        }

    /** Lets the next vanilla resource pack screen open normally even when replacement is on. */
    fun allowVanillaScreenOnce() {
        allowVanillaOnce = true
    }

    /**
     * Called when a vanilla resource pack screen opens: true if the profile screen should take
     * its place. Consumes the one-shot allowance set by [allowVanillaScreenOnce].
     */
    fun consumeReplaceVanillaScreen(): Boolean {
        if (allowVanillaOnce) {
            allowVanillaOnce = false
            return false
        }
        return replace
    }

    /** Takes the stored values from the config file, without saving it again. */
    internal fun load(replaceVanillaScreen: Boolean?, backupChoice: String?, autoCheckUpdates: Boolean?) {
        replace = replaceVanillaScreen ?: true
        backup = BackupChoice.entries.firstOrNull { it.id == backupChoice } ?: BackupChoice.ASK
        autoCheck = autoCheckUpdates
    }
}
