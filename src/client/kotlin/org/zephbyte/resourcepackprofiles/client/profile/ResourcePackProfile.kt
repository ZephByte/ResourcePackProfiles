package org.zephbyte.resourcepackprofiles.client.profile

data class ResourcePackProfile(
    val name: String,
    val packIds: List<String>,
    val customIcon: String? = null,
    val favorite: Boolean = false,
    // The fields below were added in 2.0. They are nullable because Gson leaves fields that
    // are absent from older config files as null, ignoring Kotlin defaults.
    /** Packs kept in [packIds] (so they hold their place in the order) but skipped when applying. */
    val disabledPackIds: List<String>? = null,
    /** Pack id -> Modrinth version id, for packs known to Modrinth. Lets missing packs be fetched. */
    val packSources: Map<String, String>? = null,
    /** Pack id -> Modrinth project id. Unlike a version, a project survives the version being deleted. */
    val packProjects: Map<String, String>? = null,
    /** Pack id -> the pack id it replaced in this profile, so a version switch can be reverted. */
    val previousPacks: Map<String, String>? = null
) {
    fun isDisabled(packId: String): Boolean = disabledPackIds?.contains(packId) == true

    /** Pack ids that are applied: [packIds] without the disabled ones. */
    fun enabledPackIds(): List<String> {
        val disabled = disabledPackIds?.toSet() ?: return packIds
        return packIds.filter { it !in disabled }
    }
}
