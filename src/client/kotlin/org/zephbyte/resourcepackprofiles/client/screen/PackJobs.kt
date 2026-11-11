package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.ui.Theme
import org.zephbyte.resourcepackprofiles.client.update.DownloadOutcome
import org.zephbyte.resourcepackprofiles.client.update.DownloadProgress
import org.zephbyte.resourcepackprofiles.client.update.PackCheck
import org.zephbyte.resourcepackprofiles.client.update.PackIdentity
import org.zephbyte.resourcepackprofiles.client.update.PackSession
import org.zephbyte.resourcepackprofiles.client.update.PackUpdate
import org.zephbyte.resourcepackprofiles.client.update.UpdateChecker
import org.zephbyte.resourcepackprofiles.client.util.runInBackground
import org.zephbyte.resourcepackprofiles.client.util.t

/**
 * The slow pack jobs a screen can start: checking for updates, downloading updates, switching a
 * profile to updated packs, and downloading a profile's missing packs. Each runs in the
 * background, one at a time, then records what it changed (profiles, [PackSession]) and reports
 * back through the [Host] on the game thread.
 *
 * The screen only decides what to run and how to show it: [busy] for the status line, and
 * [progress] for the download bar.
 */
class PackJobs(private val host: Host) {

    /** What a job needs from the screen that started it. */
    interface Host {
        /** The name to show for a pack. */
        fun packName(packId: String): String

        /** The resourcepacks folder changed: rescan it. */
        fun reloadPacks()

        /** Profiles or update results changed: refresh what is shown. */
        fun refresh()

        fun showStatus(text: Component, color: Int = Theme.TEXT_SECONDARY)

        fun showError(text: Component)

        /** Makes [action] the one-step undo. */
        fun setUndo(action: () -> Unit)
    }

    /** What a missing-pack download found: how it went, and which versions Modrinth no longer has. */
    private class MissingOutcome(val outcome: DownloadOutcome, val gone: Set<String>)

    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")

    /** What the running job is doing, for the status line; null when idle. */
    var busy: Component? = null
        private set

    /** Per-file state of the running download batch, if any. Written by the worker thread. */
    @Volatile
    var progress: DownloadProgress? = null
        private set

    val isBusy: Boolean get() = busy != null

    /**
     * Checks [ids] against Modrinth. With [single], the status line reports on that one pack by
     * name instead of giving totals. With [quiet] (the automatic check), it only reports when
     * updates were found and stays silent on failure.
     *
     * [pending] decides which found updates the status line counts as updates, so it matches
     * what the screen shows. "All Packs" passes a filter that drops updates whose newer file is
     * already downloaded, since its rows show nothing for those; they count as up to date.
     */
    fun check(ids: List<String>, single: Boolean = false, quiet: Boolean = false, pending: (PackUpdate) -> Boolean = { true }) {
        if (isBusy || ids.isEmpty()) return
        busy = t("status.resourcepackprofiles.checking")
        runInBackground("RPP-UpdateCheck", { UpdateChecker.check(ids) }) { result ->
            busy = null
            result.onSuccess { report ->
                PackSession.checks.putAll(report.checks)
                ProfileManager.recordSources(report.identities, report.projects)
                host.refresh()
                fun isPending(check: PackCheck?) = check is PackCheck.UpdateAvailable && pending(check.update)
                if (single) {
                    val name = host.packName(ids.first())
                    val check = report.checks[ids.first()]
                    when {
                        isPending(check) -> host.showStatus(t("status.resourcepackprofiles.pack_update_found", name), Theme.SUCCESS)
                        check is PackCheck.UpToDate || check is PackCheck.UpdateAvailable -> host.showStatus(t("status.resourcepackprofiles.pack_up_to_date", name), Theme.SUCCESS)
                        check is PackCheck.NoCompatibleVersion -> host.showStatus(t("status.resourcepackprofiles.pack_no_version", name))
                        else -> host.showStatus(t("status.resourcepackprofiles.pack_not_on_modrinth", name))
                    }
                    return@onSuccess
                }
                val results = report.checks.values
                val found = results.count { isPending(it) }
                val current = results.count { it is PackCheck.UpToDate || (it is PackCheck.UpdateAvailable && !isPending(it)) }
                val noVersion = results.count { it is PackCheck.NoCompatibleVersion }
                val unknown = results.count { it is PackCheck.NotOnModrinth || it is PackCheck.Unavailable }
                val summary =
                    if (noVersion > 0) t("status.resourcepackprofiles.check_summary_no_version", found, current, unknown, noVersion)
                    else t("status.resourcepackprofiles.check_summary", found, current, unknown)
                if (!quiet) host.showStatus(summary, if (found > 0) Theme.SUCCESS else Theme.TEXT_SECONDARY)
                else if (found > 0) host.showStatus(t("status.resourcepackprofiles.updates_available", found), Theme.SUCCESS)
            }.onFailure {
                logger.error("Update check failed", it)
                if (!quiet) host.showError(t("status.resourcepackprofiles.updates_failed"))
            }
        }
    }

    /**
     * Downloads [updates] next to the existing files without touching any profile. Profiles keep
     * the version they have until they are updated, which then reuses these downloads.
     */
    fun downloadOnly(updates: List<PackUpdate>) {
        download(updates) { result ->
            host.reloadPacks()
            host.refresh()
            result.onSuccess { outcome ->
                val done = outcome.replacements.size
                if (outcome.failed.isEmpty()) host.showStatus(t("status.resourcepackprofiles.updates_downloaded", done), Theme.SUCCESS)
                else host.showError(t("status.resourcepackprofiles.downloaded_partial", done, outcome.failed.size))
            }
        }
    }

    /**
     * Downloads [updates] and switches [profileName] to them in place. Old pack files stay on
     * disk, so other profiles keep working and each pack can be switched back. With [backup], a
     * "(backup)" copy of the profile is saved first. [onSwitched] gets the pack ids that were
     * replaced, once the profile has changed.
     */
    fun applyUpdates(profileName: String, updates: List<PackUpdate>, backup: Boolean = false, onSwitched: (Set<String>) -> Unit = {}) {
        download(updates) { result ->
            result.onSuccess { outcome ->
                // Packs that downloaded are switched; any that failed are left as they were.
                val replacements = outcome.replacements
                if (replacements.isEmpty()) {
                    host.showError(t("status.resourcepackprofiles.download_failed"))
                    return@onSuccess
                }
                val before = ProfileManager.getProfile(profileName) ?: return@onSuccess
                val backupName = if (backup) ProfileManager.duplicateProfile(profileName, " (backup)") else null
                host.reloadPacks()
                val changed = replacements.filter { it.key != it.value }
                val done = updates.filter { it.packId in replacements }
                val newSources = done.associate { replacements.getValue(it.packId) to it.versionId }
                val newProjects = done.filter { it.projectId.isNotEmpty() }.associate { replacements.getValue(it.packId) to it.projectId }
                ProfileManager.replacePackIds(profileName, changed, newSources, newProjects, rememberPrevious = true, carrySources = false)
                host.setUndo {
                    ProfileManager.putProfile(before)
                    backupName?.let { ProfileManager.deleteProfile(it) }
                }
                onSwitched(replacements.keys)
                host.refresh()
                val only = updates.singleOrNull()
                when {
                    outcome.failed.isNotEmpty() ->
                        host.showError(t("status.resourcepackprofiles.packs_updated_partial", replacements.size, outcome.failed.size))
                    only != null -> host.showStatus(t("status.resourcepackprofiles.pack_switched", only.projectTitle, only.newVersion), Theme.SUCCESS)
                    backupName != null -> host.showStatus(t("status.resourcepackprofiles.packs_updated_backup", updates.size, backupName), Theme.SUCCESS)
                    else -> host.showStatus(t("status.resourcepackprofiles.packs_updated", updates.size), Theme.SUCCESS)
                }
            }
        }
    }

    /**
     * Installs one version from Modrinth (the browser) and hands the new pack's id to
     * [onInstalled] once the pack list has been rescanned.
     */
    fun install(update: PackUpdate, onInstalled: (String) -> Unit) {
        download(listOf(update)) { result ->
            if (result.isFailure) return@download
            val newId = result.getOrNull()?.replacements?.values?.firstOrNull()
            if (newId == null) {
                host.showError(t("status.resourcepackprofiles.download_failed"))
                return@download
            }
            host.reloadPacks()
            // Installed straight from Modrinth, so there is no need to look the file up later.
            PackSession.identified[newId] = PackIdentity(update.versionId, update.projectId)
            onInstalled(newId)
        }
    }

    /**
     * Downloads missing packs for [profileNames]. [sources] maps each missing pack id to its
     * recorded Modrinth version. Files saved under another name are renamed in the profiles, and
     * versions Modrinth no longer has are forgotten.
     */
    fun downloadMissing(profileNames: List<String>, sources: Map<String, String>) {
        if (isBusy || sources.isEmpty()) return
        busy = t("status.resourcepackprofiles.downloading_missing", sources.size)
        val work = {
            // The progress bar needs file names and sizes, so it starts once versions are resolved.
            val found = UpdateChecker.resolveMissing(sources)
            val batch = DownloadProgress(found)
            progress = batch
            val outcome = UpdateChecker.downloadAll(found, batch)
            MissingOutcome(outcome, sources.keys - found.map { it.packId }.toSet())
        }
        runInBackground("RPP-MissingDownload", work) { result ->
            busy = null
            progress = null
            result.onSuccess { r ->
                host.reloadPacks()
                // A file saved under a different name gets a different pack id.
                val renamed = r.outcome.replacements.filter { it.key != it.value }
                profileNames.forEach { name ->
                    if (renamed.isNotEmpty()) ProfileManager.replacePackIds(name, renamed)
                    // Versions Modrinth no longer has can never be downloaded: forget them, so
                    // "Download N Missing" stops counting them. The project is kept, which is
                    // what lets another version be picked from the pack's row.
                    if (r.gone.isNotEmpty()) ProfileManager.dropSources(name, r.gone)
                }
                host.refresh()
                val downloaded = r.outcome.replacements.size
                val problems = r.gone.size + r.outcome.failed.size
                if (problems == 0) host.showStatus(t("status.resourcepackprofiles.missing_downloaded", downloaded), Theme.SUCCESS)
                else host.showError(t("status.resourcepackprofiles.missing_partial", downloaded, problems))
            }.onFailure {
                logger.error("Failed to download missing packs", it)
                host.showError(t("status.resourcepackprofiles.download_failed"))
            }
        }
    }

    /**
     * Downloads [updates] with a progress bar, then hands the outcome to [onDone]. A failed batch
     * is logged and reported here, after [onDone] has run.
     */
    private fun download(updates: List<PackUpdate>, onDone: (Result<DownloadOutcome>) -> Unit) {
        if (isBusy || updates.isEmpty()) return
        val batch = DownloadProgress(updates)
        progress = batch
        busy = t("status.resourcepackprofiles.downloading", updates.size)
        runInBackground("RPP-UpdateDownload", { UpdateChecker.downloadAll(updates, batch) }) { result ->
            busy = null
            progress = null
            onDone(result)
            result.onFailure {
                logger.error("Failed to download pack updates", it)
                host.showError(t("status.resourcepackprofiles.download_failed"))
            }
        }
    }
}
