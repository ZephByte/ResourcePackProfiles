package org.zephbyte.resourcepackprofiles.client.screen

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.repository.Pack
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.RefreshablePackScreen
import org.zephbyte.resourcepackprofiles.client.profile.AutoApply
import org.zephbyte.resourcepackprofiles.client.profile.BackupChoice
import org.zephbyte.resourcepackprofiles.client.profile.ImportResult
import org.zephbyte.resourcepackprofiles.client.profile.PackIcons
import org.zephbyte.resourcepackprofiles.client.profile.PackTrash
import org.zephbyte.resourcepackprofiles.client.profile.ProfileIconManager
import org.zephbyte.resourcepackprofiles.client.profile.ModSettings
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.profile.ResourcePackProfile
import org.zephbyte.resourcepackprofiles.client.ui.DragReorder
import org.zephbyte.resourcepackprofiles.client.ui.FlatButton
import org.zephbyte.resourcepackprofiles.client.ui.FlatSelectionList
import org.zephbyte.resourcepackprofiles.client.ui.StatusPill
import org.zephbyte.resourcepackprofiles.client.ui.Icon
import org.zephbyte.resourcepackprofiles.client.ui.MenuItem
import org.zephbyte.resourcepackprofiles.client.ui.MultiSelection
import org.zephbyte.resourcepackprofiles.client.ui.PopupMenu
import org.zephbyte.resourcepackprofiles.client.ui.Theme
import org.zephbyte.resourcepackprofiles.client.update.DownloadProgress
import org.zephbyte.resourcepackprofiles.client.update.PackCheck
import org.zephbyte.resourcepackprofiles.client.update.PackSession
import org.zephbyte.resourcepackprofiles.client.update.PackUpdate
import org.zephbyte.resourcepackprofiles.client.update.ProjectHit
import org.zephbyte.resourcepackprofiles.client.update.UpdateChecker
import org.zephbyte.resourcepackprofiles.client.update.VersionChoice
import org.zephbyte.resourcepackprofiles.client.util.DynamicTextureCache
import org.zephbyte.resourcepackprofiles.client.util.FileDialogs
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import org.zephbyte.resourcepackprofiles.client.util.runInBackground
import org.zephbyte.resourcepackprofiles.client.util.setGuiScreen
import org.zephbyte.resourcepackprofiles.client.util.showScreen
import org.zephbyte.resourcepackprofiles.client.util.t
import java.nio.file.Path

/**
 * The mod's single screen: profiles on the left, the selected profile's packs and actions on the
 * right. Edits save immediately (with a one-step undo) so there is no separate edit screen.
 *
 * All UI state lives in fields rather than widgets, because [init] reruns on every resize and
 * whenever a sub-screen (confirm dialog) returns here.
 */
class ProfileScreen @JvmOverloads constructor(
    private val parent: Screen?,
    /**
     * True when this screen was opened in place of the vanilla resource pack screen ([parent]),
     * which is then never shown: closing returns to wherever the vanilla screen would have.
     */
    private val replacesVanilla: Boolean = false
) : Screen(Component.translatable("screen.resourcepackprofiles.title")) {

    /** How the "All Packs" list is ordered. Ties are always broken by name. */
    private enum class LibrarySort(val id: String) {
        /** Packs with an update available first. */
        UPDATES("updates"),
        /** Packs made for another game version first. */
        OUTDATED("outdated"),
        /** Pack files no profile uses first. */
        UNUSED("unused"),
        /** Packs used by the most profiles first. */
        MOST_USED("most_used"),
        NAME("name")
    }

    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")

    // --- State that survives init() ---
    private var selectedName: String? = null
    private var selectionInitialized = false
    private var searchText = ""
    private var packFilter = ""
    /** When true the right-hand list shows packs that can be added instead of the load order. */
    private var addMode = false
    /** When true the right pane lists every installed pack instead of showing a profile. */
    private var libraryMode = false
    private var trashCount = 0
    // Whether a profile was applied this session, so onClose can return to a refreshed pack
    // screen instead of a stale one that would overwrite the applied packs.
    private var profileApplied = false

    /** Latest Modrinth result per pack id; shared by every profile that contains the pack. */
    private val checks = PackSession.checks
    /** Update checks and downloads; only one runs at a time. */
    private val jobs = PackJobs(object : PackJobs.Host {
        override fun packName(packId: String) = this@ProfileScreen.packName(packId)
        override fun reloadPacks() = this@ProfileScreen.reloadPacks()
        override fun refresh() {
            refreshPackCache()
            rebuildProfileList()
            rebuildPackList()
        }
        override fun showStatus(text: Component, color: Int) = this@ProfileScreen.showStatus(text, color)
        override fun showError(text: Component) = this@ProfileScreen.showError(text)
        override fun setUndo(action: () -> Unit) {
            undo = action
        }
    })

    private var status: Component? = null
    private var statusColor = Theme.TEXT_SECONDARY
    private var statusUntil = 0L
    private var undo: (() -> Unit)? = null

    /** Drag-to-reorder of the profile's packs; the new order is saved when the mouse is released. */
    private val drag = DragReorder(PACK_ROW_HEIGHT, object : DragReorder.View {
        override val top get() = packList.y
        override val bottom get() = packList.y + packList.height
        override fun rowTops() = packList.children().map { it.y }
        override fun showOrder() = rebuildPackList()
        override fun scrollBy(amount: Double): Boolean {
            val before = packList.scrollAmount()
            packList.setScrollAmount(before + amount)
            return packList.scrollAmount() != before
        }
    })

    /** Packs whose available update is ticked, i.e. included in "Update N Selected". None by default. */
    private val tickedUpdates = mutableSetOf<String>()

    /**
     * Packs picked in the pack list, file-browser style. An action on a row that is one of
     * several picked applies to all of them.
     */
    private val picks = MultiSelection()
    /** Set while a pack row handles a click, so the list's select-on-focus leaves the picks alone. */
    private var rowClicking = false
    /** A picked row pressed without modifiers: the picks narrow to it on release, unless dragged. */
    private var collapseTo: String? = null
    /** Whether the first-run question was already shown by this screen. */
    private var firstRunShown = false

    /** The one popup (sort, overflow, shortcuts, or a pack's version list) that can be open. */
    private val menu = PopupMenu()

    // Recomputed once per frame and shared by every row.
    private var packsById: Map<String, Pack> = emptyMap()
    private var activeName: String? = null
    private var packUsage: Map<String, Int> = emptyMap()
    private var enabledNow: Set<String> = emptySet()

    private val packTextures = DynamicTextureCache("pack_icon")
    private val packsWithoutIcon = mutableSetOf<String>()
    /** Missing packs of the running batch whose downloaded icon is already shown. */
    private val iconsShown = mutableSetOf<String>()

    // --- Widgets ---
    private lateinit var searchBox: EditBox
    private lateinit var nameBox: EditBox
    private lateinit var filterBox: EditBox
    private lateinit var profileList: ProfileList
    private lateinit var packList: PackList
    private lateinit var applyButton: FlatButton
    private lateinit var moreButton: FlatButton
    private lateinit var duplicateButton: FlatButton
    private lateinit var exportButton: FlatButton
    private lateinit var deleteButton: FlatButton
    private lateinit var addButton: FlatButton
    private lateinit var selectAllButton: FlatButton
    private lateinit var refreshButton: FlatButton
    private lateinit var browseAddButton: FlatButton
    private lateinit var addPickedButton: FlatButton
    private lateinit var sortButton: FlatButton
    private lateinit var missingButton: FlatButton
    private lateinit var unusedButton: FlatButton
    private lateinit var trashButton: FlatButton
    private lateinit var updateAllButton: FlatButton
    private lateinit var libraryDownloadButton: FlatButton
    private lateinit var undoButton: FlatButton
    private lateinit var doneButton: FlatButton
    private lateinit var settingsButton: FlatButton
    private lateinit var helpButton: FlatButton

    // --- Layout ---
    private val pad = 8
    private val panelTop = 24
    private val panelBottom get() = height - 30
    private val leftX get() = pad
    private val leftW get() = (width * 36 / 100).coerceIn(140, 230)
    private val rightX get() = leftX + leftW + 6
    private val rightW get() = width - pad - rightX
    // Spacing: 8px padding inside each header block (all sides), 8px between header rows, 16px
    // tall controls, and HEADER_GAP between a header block and the list beneath it.
    private val searchY get() = panelTop + 8
    private val leftHeaderBottom get() = searchY + 16 + 8
    private val searchW get() = leftW - 16 - 60
    // Header grid: a HEADER_ICON-sized icon at headerY; beside it the name over the pack count,
    // filling the icon's height; the action buttons centred on the icon at the right edge.
    private val headerY get() = panelTop + 8
    // Two rows beside the icon, aligned to its top and bottom halves: the name and the profile
    // actions on top; the pack count (or, while adding packs, the filter) and refresh / Add Packs
    // below. There is no separate section row under the icon.
    // Beside the icon, right-aligned and centred on it: the profile actions (duplicate, export,
    // delete, ...). Under the icon, left-aligned on a row of their own: Apply, Add Packs and
    // refresh. (One long row of everything, and two right-aligned rows that formed a grid, were
    // both tried and read as cluttered.)
    private val headerButtonsY get() = headerY + (HEADER_ICON - 16) / 2
    private val moreX get() = rightX + rightW - 8 - 20
    private val duplicateX get() = moreX - 3 * (4 + 18)
    /** Left edge of the header buttons beside the name. */
    private val headerButtonsLeft get() = duplicateX
    private val actionRowY get() = headerY + HEADER_ICON + 8
    /** Add Packs follows Apply, which is hidden while the profile is applied. */
    private val addX get() = if (applyButton.visible) rightX + 8 + 80 + 4 else rightX + 8
    private val refreshX get() = addX + 62 + 4

    // Name with the pack count as a subtitle just under it, the pair centred on the icon.
    private val nameTextY get() = headerY + (HEADER_ICON - 18) / 2
    private val countY get() = nameTextY + 10
    private val nameFieldX get() = rightX + 8 + HEADER_ICON + 8
    /** The name field (drawn on hover/focus) hugs the name so it never covers the count. */
    private val nameFieldY get() = nameTextY - 3
    /** Room for the name field: it stops short of the header buttons. */
    private val nameFieldMaxW get() = headerButtonsLeft - 12 - nameFieldX
    /**
     * The name field has a fixed width, narrower only when the window is. (Sizing it to the name
     * fought the field's own scrolling while typing.)
     */
    private val nameFieldW get() = nameFieldMaxW.coerceAtMost(NAME_FIELD_WIDTH)
    // "All Packs" has no icon: title with the count under it, and the filter row beneath.
    private val libraryCountY get() = headerY + 12
    private val librarySectionY get() = headerY + 26
    private val rightHeaderBottom get() = if (libraryMode) librarySectionY + 16 + 8 else actionRowY + 16 + 8
    /** Pack filter: its own row in All Packs; while adding packs, on the action row after Done. */
    private val filterX get() = if (libraryMode) rightX + 8 else addX + 62 + 8
    private val filterY get() = if (libraryMode) librarySectionY else actionRowY
    private val packTop get() = rightHeaderBottom + HEADER_GAP
    private val footerY get() = panelBottom - 22
    /** Width of the filter field: up to "Done" in add mode, up to the sort button in All Packs. */
    private val filterW get() = (if (libraryMode) sortButton.x - 4 else browseAddButton.x - 8) - filterX


    /** The profile shown on the right, or null when none is selected or "All packs" is open. */
    private val selected: ResourcePackProfile?
        get() = if (libraryMode) null else selectedName?.let { ProfileManager.getProfile(it) }

    override fun init() {
        if (!selectionInitialized) {
            selectionInitialized = true
            selectedName = ProfileManager.getProfiles()
                .firstOrNull { ProfileManager.isActiveProfile(it) }?.name
                ?: ProfileManager.getProfiles().firstOrNull()?.name
        }

        val settingsLabel = t("button.resourcepackprofiles.settings")
        val settingsWidth = font.width(settingsLabel) + 12
        settingsButton = FlatButton(width - pad - settingsWidth, 5, settingsWidth, 15, settingsLabel, backdrop = true) { openSettings() }
        addRenderableWidget(settingsButton)
        helpButton = FlatButton(settingsButton.x - 4 - 15, 5, 15, 15, Component.literal("?"), backdrop = true) { toggleHelpMenu() }
            .withTooltip(t("tooltip.resourcepackprofiles.shortcuts"))
        addRenderableWidget(helpButton)

        // Left pane: search, import, new, profile list
        searchBox = EditBox(font, leftX + 8 + 15, searchY + 4, searchW - 19, 10, t("field.resourcepackprofiles.search"))
        searchBox.setBordered(false)
        searchBox.setMaxLength(64)
        searchBox.setHint(t("field.resourcepackprofiles.search"))
        searchBox.value = searchText
        addRenderableWidget(searchBox)

        addRenderableWidget(
            FlatButton(leftX + leftW - 8 - 58, searchY, 18, 16, t("tooltip.resourcepackprofiles.browse"), Icon.GLOBE) { openBrowser() }
                .withTooltip(t("tooltip.resourcepackprofiles.browse"))
        )
        addRenderableWidget(
            FlatButton(leftX + leftW - 8 - 38, searchY, 18, 16, t("tooltip.resourcepackprofiles.import"), Icon.IMPORT) { onImport() }
                .withTooltip(t("tooltip.resourcepackprofiles.import"))
        )
        addRenderableWidget(
            FlatButton(leftX + leftW - 8 - 18, searchY, 18, 16, t("tooltip.resourcepackprofiles.new"), Icon.PLUS) { onNew() }
                .withTooltip(t("tooltip.resourcepackprofiles.new"))
        )

        val profileTop = leftHeaderBottom + HEADER_GAP
        profileList = ProfileList(leftW - 2, panelBottom - profileTop, profileTop)
        profileList.updateSizeAndPosition(leftW - 2, panelBottom - profileTop, leftX + 1, profileTop)
        addRenderableWidget(profileList)

        // Right pane: name, apply, more, pack list, updates
        val rowY = headerButtonsY
        moreButton = FlatButton(moreX, rowY, 20, 16, t("tooltip.resourcepackprofiles.more"), Icon.DOTS) { toggleMenu() }
            .withTooltip(t("tooltip.resourcepackprofiles.more"))
        // The everyday profile actions sit in the header; rarer ones stay in the ... menu.
        deleteButton = FlatButton(moreButton.x - 4 - 18, rowY, 18, 16, t("tooltip.resourcepackprofiles.delete"), Icon.TRASH) { onDelete() }
            .withTooltip(t("tooltip.resourcepackprofiles.delete"))
        exportButton = FlatButton(deleteButton.x - 4 - 18, rowY, 18, 16, t("tooltip.resourcepackprofiles.export"), Icon.EXPORT) { onExport() }
            .withTooltip(t("tooltip.resourcepackprofiles.export"))
        duplicateButton = FlatButton(exportButton.x - 4 - 18, rowY, 18, 16, t("tooltip.resourcepackprofiles.duplicate"), Icon.DUPLICATE) { onDuplicate() }
            .withTooltip(t("tooltip.resourcepackprofiles.duplicate"))
        applyButton = FlatButton(rightX + 8, actionRowY, 80, 16, t("button.resourcepackprofiles.apply"), style = FlatButton.Style.PRIMARY) { applySelected() }
        nameBox = EditBox(font, nameFieldX + 4, nameFieldY + 2, nameFieldW - 8, 10, t("field.resourcepackprofiles.profile_name"))
        nameBox.setBordered(false)
        nameBox.setMaxLength(64)
        addRenderableWidget(nameBox)

        addRenderableWidget(applyButton)
        addRenderableWidget(duplicateButton)
        addRenderableWidget(exportButton)
        addRenderableWidget(deleteButton)
        addRenderableWidget(moreButton)

        filterBox = EditBox(font, filterX + 15, filterY + 4, 100, 10, t("field.resourcepackprofiles.filter_packs"))
        filterBox.setBordered(false)
        filterBox.setMaxLength(64)
        filterBox.setHint(t("field.resourcepackprofiles.filter_packs"))
        filterBox.value = packFilter
        addRenderableWidget(filterBox)

        addButton = FlatButton(addX, actionRowY, 62, 16, t("button.resourcepackprofiles.add_packs")) { setAddMode(!addMode) }
        addRenderableWidget(addButton)

        // Add mode: adds every picked pack at once.
        addPickedButton = FlatButton(rightX + 8, footerY, 100, 16, t("button.resourcepackprofiles.add_picked", 0), style = FlatButton.Style.PRIMARY) {
            addPacks(pickedInOrder())
        }
        addRenderableWidget(addPickedButton)

        packList = PackList(rightW - 2, footerY - 4 - packTop, packTop)
        packList.updateSizeAndPosition(rightW - 2, footerY - 4 - packTop, rightX + 1, packTop)
        addRenderableWidget(packList)

        // Shown once updates are known. Nothing is ticked to begin with, so updating is always
        // a deliberate choice: tick packs one by one, or all at once here.
        selectAllButton = FlatButton(rightX + 8, footerY, 100, 16, t("button.resourcepackprofiles.select_all_updates")) { onSelectAll() }
        addRenderableWidget(selectAllButton)
        updateAllButton = FlatButton(rightX + 8, footerY, 100, 16, t("button.resourcepackprofiles.update_selected", 0), style = FlatButton.Style.SUCCESS) { onUpdateSelected() }
            .withTooltip(t("tooltip.resourcepackprofiles.update_selected"))
        addRenderableWidget(updateAllButton)

        // All Packs only: choose what floats to the top of the list.
        sortButton = FlatButton(rightX + 8, librarySectionY, 100, 16, t("button.resourcepackprofiles.sort", "")) { toggleSortMenu() }
            .withTooltip(t("tooltip.resourcepackprofiles.sort"))
        addRenderableWidget(sortButton)

        // Checks happen automatically; this asks again on demand. It sits at the right of the
        // row above the list, left of "Add Packs" in a profile.
        refreshButton = FlatButton(refreshX, actionRowY, 18, 16, t("tooltip.resourcepackprofiles.refresh_updates"), Icon.REFRESH) { onRefreshUpdates() }
            .withTooltip(t("tooltip.resourcepackprofiles.refresh_updates"))
        addRenderableWidget(refreshButton)
        // While adding packs: find new ones on Modrinth, at the end of the action row.
        val browseLabel = t("button.resourcepackprofiles.browse_modrinth")
        val browseWidth = font.width(browseLabel) + 16
        browseAddButton = FlatButton(rightX + rightW - 8 - browseWidth, actionRowY, browseWidth, 16, browseLabel) { openBrowser() }
        addRenderableWidget(browseAddButton)

        missingButton = FlatButton(rightX + 8, footerY, 100, 16, t("button.resourcepackprofiles.download_missing", 0), style = FlatButton.Style.SUCCESS) { downloadMissing() }
            .withTooltip(t("tooltip.resourcepackprofiles.download_missing"))
        addRenderableWidget(missingButton)

        // "All packs" view
        unusedButton = FlatButton(rightX + 8, headerY + 3, 100, 16, t("button.resourcepackprofiles.delete_unused", 0), style = FlatButton.Style.DANGER) { onDeleteUnused() }
            .withTooltip(t("tooltip.resourcepackprofiles.delete_unused"))
        addRenderableWidget(unusedButton)
        trashButton = FlatButton(rightX + 8, footerY, 100, 16, t("button.resourcepackprofiles.empty_trash", 0)) { onEmptyTrash() }
            .withTooltip(t("tooltip.resourcepackprofiles.empty_trash"))
        addRenderableWidget(trashButton)
        trashCount = PackTrash.count()
        libraryDownloadButton = FlatButton(rightX + 8, footerY, 100, 16, t("button.resourcepackprofiles.download_updates", 0), style = FlatButton.Style.SUCCESS) { jobs.downloadOnly(libraryUpdates()) }
            .withTooltip(t("tooltip.resourcepackprofiles.library_download"))
        addRenderableWidget(libraryDownloadButton)

        // Bottom bar
        doneButton = FlatButton(width - pad - 60, height - 24, 60, 18, CommonComponents.GUI_DONE, backdrop = true) { onClose() }
        undoButton = FlatButton(doneButton.x - 4 - 44, height - 24, 44, 18, t("button.resourcepackprofiles.undo"), backdrop = true) { runUndo() }
        addRenderableWidget(undoButton)
        addRenderableWidget(doneButton)

        // Responders are attached last so restoring field values above doesn't fire them.
        searchBox.setResponder {
            searchText = it
            rebuildProfileList()
        }
        filterBox.setResponder {
            packFilter = it
            rebuildPackList()
        }

        refreshPackCache()
        rebuildProfileList()
        syncDetail()
    }

    // Keyboard users start in the profile list rather than the search box, so the single-key
    // shortcuts (Enter, Delete, F2) work straight away.
    override fun setInitialFocus() {
        setInitialFocus(profileList)
    }

    // --- State sync ---

    private fun refreshPackCache() {
        packsById = minecraft.resourcePackRepository.availablePacks.associateBy { it.id }
        packUsage = ProfileManager.packUsage()
        enabledNow = minecraft.options.resourcePacks.toSet()
        val lastApplied = ProfileManager.lastAppliedName()?.let { ProfileManager.getProfile(it) }
        activeName = lastApplied?.takeIf { ProfileManager.isActiveProfile(it) }?.name
    }

    private fun select(name: String?) {
        if (selectedName == name && !libraryMode) return
        // Settle any half-typed rename against the old profile before switching away from it.
        commitRename()
        libraryMode = false
        selectedName = name
        addMode = false
        packFilter = ""
        menu.close()
        clearPicks()
        rebuildProfileList()
        syncDetail(forceName = true)
    }

    private fun matchesSearch(profile: ResourcePackProfile): Boolean {
        val query = searchText.trim().lowercase()
        if (query.isEmpty()) return true
        if (profile.name.lowercase().contains(query)) return true
        return profile.packIds.any { packName(it).lowercase().contains(query) }
    }

    private fun rebuildProfileList() {
        val scroll = profileList.scrollAmount()
        // The pinned "All packs" entry comes first and is never filtered out.
        val rows = listOf(ProfileRow(null)) +
            ProfileManager.getProfiles().filter { matchesSearch(it) }.map { ProfileRow(it) }
        profileList.replaceEntries(rows)
        profileList.selectSilently(rows.firstOrNull {
            if (libraryMode) it.profile == null else it.profile?.name == selectedName
        })
        profileList.setScrollAmount(scroll)
    }

    /** Pack ids of the selected profile in display order (top = highest priority). */
    private fun displayIds(): List<String> = drag.order ?: selected?.packIds?.reversed() ?: emptyList()

    /** Orders packs by the chosen [librarySort]; false sorts before true, so "first" groups lead. */
    private fun libraryComparator(): Comparator<Pack> = when (librarySort) {
        LibrarySort.UPDATES -> compareBy { checks[it.id] !is PackCheck.UpdateAvailable }
        LibrarySort.OUTDATED -> compareBy { it.compatibility.isCompatible }
        LibrarySort.UNUSED -> {
            val unused = unusedPackIds().toSet()
            compareBy { it.id !in unused }
        }
        LibrarySort.MOST_USED -> compareByDescending { packUsage[it.id] ?: 0 }
        LibrarySort.NAME -> compareBy { 0 }
    }

    private fun toggleSortMenu() {
        if (menu.isOpen) {
            menu.close()
            return
        }
        val items = LibrarySort.entries.map { sort ->
            MenuItem(
                t("sort.resourcepackprofiles." + sort.id),
                note = if (sort == librarySort) t("label.resourcepackprofiles.sort_current") else null
            ) {
                librarySort = sort
                syncDetail()
            }
        }
        openMenu(items, sortButton)
    }

    /** Installed packs the user can manage (excludes built-in and required packs). */
    private fun userPacks(): List<Pack> =
        minecraft.resourcePackRepository.availablePacks.filter { it.packSource.shouldAddAutomatically() && !it.isRequired }

    /** Pack files that no profile contains and that aren't enabled right now. */
    private fun unusedPackIds(): List<String> =
        userPacks().map { it.id }.filter { isPackFile(it) && (packUsage[it] ?: 0) == 0 && it !in enabledNow }

    /** Missing packs of [profile] that can be downloaded again: pack id -> Modrinth version id. */
    private fun fetchableMissing(profile: ResourcePackProfile): Map<String, String> =
        profile.packSources.orEmpty().filterKeys { it in profile.packIds && it !in packsById }

    private fun rebuildPackList() {
        val profile = selected
        val scroll = packList.scrollAmount()
        val rows = when {
            libraryMode -> {
                val query = packFilter.trim().lowercase()
                sectioned(
                    userPacks()
                        .filter { query.isEmpty() || packName(it.id).lowercase().contains(query) }
                        .sortedWith(libraryComparator().thenBy { packName(it.id).lowercase() })
                ) { LibraryPackRow(it) }
            }
            profile == null -> emptyList()
            addMode -> {
                val inProfile = profile.packIds.toSet()
                val query = packFilter.trim().lowercase()
                sectioned(
                    minecraft.resourcePackRepository.availablePacks
                        .filter { it.packSource.shouldAddAutomatically() && !it.isRequired && it.id !in inProfile }
                        .filter { query.isEmpty() || packName(it.id).lowercase().contains(query) }
                        .sortedBy { packName(it.id).lowercase() }
                ) { AddPackRow(it) }
            }
            else -> displayIds().map { ProfilePackRow(it) }
        }
        packList.replaceEntries(rows)
        packList.setScrollAmount(scroll)
        picks.retain(rows.filterIsInstance<PackRow>().mapTo(HashSet()) { it.packId })
    }

    // --- Picking several packs ---

    private fun clearPicks() {
        picks.clear()
        collapseTo = null
    }

    /** Ids of the pack rows shown, in list order (section headings left out). */
    private fun shownPackIds(): List<String> = packList.children().filterIsInstance<PackRow>().map { it.packId }

    /** The row showing [packId], if the list shows it. */
    private fun rowFor(packId: String): PackRow? = packList.children().filterIsInstance<PackRow>().firstOrNull { it.packId == packId }

    /** The picked packs in list order. */
    private fun pickedInOrder(): List<String> = picks.inOrder(shownPackIds())

    /** The packs an action on [packId]'s row applies to: all picked packs if it is picked, else just it. */
    private fun targetsOf(packId: String): List<String> =
        if (packId in picks) pickedInOrder() else listOf(packId)

    /** Picks just [row]. Add mode only picks on purpose (Ctrl/Shift), since a plain click adds the pack. */
    private fun pickOnly(row: PackRow) = picks.only(row.packId, pick = row.clickPicks)

    /** Moves the list's cursor to [row]. Unless [keepPicks], the picks become just that row. */
    private fun selectRow(row: PackRow, keepPicks: Boolean = false) {
        if (!keepPicks) pickOnly(row)
        val was = rowClicking
        rowClicking = true
        try {
            packList.setSelected(row)
        } finally {
            rowClicking = was
        }
    }

    /** Ctrl+click toggles [row]; Shift+click picks the range from the anchor (Ctrl+Shift adds it). */
    private fun pickRow(row: PackRow, range: Boolean, toggle: Boolean) {
        when {
            range && picks.extend(row.packId, shownPackIds(), add = toggle) -> {}
            toggle -> picks.toggle(row.packId)
            else -> picks.only(row.packId)
        }
        selectRow(row, keepPicks = true)
    }

    /**
     * Rows for [packs], keeping their order: pack files first, then the built-in packs (from the
     * game or mods) in their own section, so the rows themselves don't need to say so.
     */
    private fun sectioned(packs: List<Pack>, row: (String) -> PackRow): List<PackListEntry> {
        val (files, builtIns) = packs.partition { isPackFile(it.id) }
        val rows = files.map { row(it.id) }
        if (builtIns.isEmpty()) return rows
        return rows + SectionRow() + builtIns.map { row(it.id) }
    }

    /** Shows/hides the detail widgets for the current selection and reloads their contents. */
    private fun syncDetail(forceName: Boolean = false) {
        val profile = selected
        val has = profile != null
        nameBox.visible = has
        applyButton.visible = has
        moreButton.visible = has
        duplicateButton.visible = has
        exportButton.visible = has
        deleteButton.visible = has
        addButton.visible = has
        packList.visible = has || libraryMode
        selectAllButton.visible = false
        // In add mode the filter field takes this space.
        refreshButton.visible = (has && !addMode) || libraryMode
        browseAddButton.visible = has && addMode
        refreshButton.setX(if (libraryMode) rightX + rightW - 8 - 18 else refreshX)
        refreshButton.setY(if (libraryMode) librarySectionY else actionRowY)
        // The sort button hugs the refresh button, and the filter field fills what is left, so
        // the row has even 4px gaps whatever the sort label's length.
        val sortLabel = t("button.resourcepackprofiles.sort", t("sort.resourcepackprofiles." + librarySort.id))
        sortButton.visible = libraryMode
        sortButton.setMessage(sortLabel)
        sortButton.setWidth(font.width(sortLabel) + 16)
        sortButton.setX(refreshButton.x - 4 - sortButton.getWidth())
        filterBox.setX(filterX + 15)
        filterBox.setY(filterY + 4)
        filterBox.setWidth(filterW - 19)
        filterBox.visible = (has && addMode) || libraryMode
        // Refined every frame in updateFrameState, which also hides them when they have nothing to do.
        missingButton.visible = false
        unusedButton.visible = libraryMode
        trashButton.visible = libraryMode
        updateAllButton.visible = false
        libraryDownloadButton.visible = false
        addPickedButton.visible = false
        // Don't clobber the name while it is being typed, unless the selection itself changed.
        if (profile != null && (forceName || !nameBox.isFocused)) nameBox.value = profile.name
        if (filterBox.value != packFilter) filterBox.value = packFilter
        addButton.setMessage(if (addMode) CommonComponents.GUI_DONE else t("button.resourcepackprofiles.add_packs"))
        rebuildPackList()
    }

    private fun setAddMode(enabled: Boolean) {
        addMode = enabled
        packFilter = ""
        clearPicks()
        syncDetail()
        if (enabled) setFocused(filterBox)
    }

    // --- Status line and undo ---

    private fun showStatus(text: Component, color: Int = Theme.TEXT_SECONDARY) {
        status = text
        statusColor = color
        statusUntil = System.currentTimeMillis() + STATUS_MILLIS
    }

    private fun showError(text: Component) = showStatus(text, Theme.DANGER)

    private fun runUndo() {
        val action = undo ?: return
        undo = null
        action()
        trashCount = PackTrash.count()
        refreshPackCache()
        rebuildProfileList()
        syncDetail(forceName = true)
        showStatus(t("status.resourcepackprofiles.undone"))
    }

    // --- Profile actions ---

    private fun applySelected() {
        val profile = selected ?: return
        if (activeName == profile.name) return
        val missing = ProfileManager.applyProfile(profile)
        profileApplied = true
        refreshPackCache()
        if (missing.isEmpty()) {
            showStatus(t("status.resourcepackprofiles.applied", profile.name), Theme.ACTIVE)
        } else {
            showError(t("status.resourcepackprofiles.applied_missing", profile.name, missing.size))
        }
    }

    private fun onNew() {
        val name = ProfileManager.createFromCurrent()
        undo = {
            ProfileManager.deleteProfile(name)
            if (selectedName == name) selectedName = null
        }
        searchBox.value = ""
        select(name)
        showStatus(t("status.resourcepackprofiles.created", name))
        focusName()
    }

    /** Focuses the name field with its text selected, so typing replaces the name. */
    private fun focusName() {
        if (selected == null) return
        setFocused(nameBox)
        nameBox.moveCursorToEnd(false)
        nameBox.setHighlightPos(0)
    }

    /** Applies the name field to the selected profile. Returns true if the profile was renamed. */
    private fun commitRename(): Boolean {
        val profile = selected ?: return false
        val newName = nameBox.value.trim()
        val oldName = profile.name
        if (newName == oldName) {
            if (nameBox.value != oldName) nameBox.value = oldName
            return false
        }
        if (newName.isEmpty() || !ProfileManager.renameProfile(oldName, newName)) {
            nameBox.value = oldName
            showError(
                if (newName.isEmpty()) t("status.resourcepackprofiles.name_empty")
                else t("status.resourcepackprofiles.name_taken", newName)
            )
            return false
        }
        selectedName = newName
        nameBox.value = newName
        undo = {
            if (ProfileManager.renameProfile(newName, oldName) && selectedName == newName) selectedName = oldName
        }
        rebuildProfileList()
        showStatus(t("status.resourcepackprofiles.renamed", newName))
        return true
    }

    private fun onDuplicate() {
        val source = selected ?: return
        val copy = ProfileManager.duplicateProfile(source.name) ?: return
        undo = {
            ProfileManager.deleteProfile(copy)
            if (selectedName == copy) selectedName = source.name
        }
        select(copy)
        showStatus(t("status.resourcepackprofiles.duplicated", copy))
    }

    private fun onDelete(target: ResourcePackProfile? = selected) {
        val profile = target ?: return
        minecraft.showScreen(ConfirmDialogScreen(
            this,
            t("screen.resourcepackprofiles.delete.title"),
            t("screen.resourcepackprofiles.delete.message", profile.name),
            t("button.resourcepackprofiles.delete_profile")
        ) { deleteProfile(profile) })
    }

    private fun deleteProfile(profile: ResourcePackProfile) {
        val iconBytes = ProfileManager.readCustomIcon(profile)
        val neighbours = ProfileManager.getProfiles().map { it.name }
        val index = neighbours.indexOf(profile.name)
        ProfileManager.deleteProfile(profile.name)
        undo = {
            ProfileManager.restoreProfile(profile, iconBytes)
            selectedName = profile.name
        }
        if (selectedName == profile.name) {
            selectedName = neighbours.getOrNull(index + 1) ?: neighbours.getOrNull(index - 1)
        }
        showStatus(t("status.resourcepackprofiles.deleted", profile.name))
    }

    private fun onExport() {
        val profile = selected ?: return
        runInBackground("RPP-Export", { ProfileManager.exportProfile(profile) }) { result ->
            if (result.getOrDefault(false)) showStatus(t("status.resourcepackprofiles.exported", profile.name))
        }
    }

    /** Opens the Modrinth browser; installs can be added to the profile being viewed, if any. */
    private fun openBrowser() {
        menu.close()
        commitRename()
        minecraft.showScreen(BrowseScreen(this, selected?.name))
    }

    private fun onImport() {
        runInBackground("RPP-Import", { ProfileManager.pickProfileToImport() }) { result ->
            val path = result.getOrNull() ?: return@runInBackground
            offerMissingPacks(importProfiles(listOf(Path.of(path))))
        }
    }

    /** Imports each profile file and returns the names of the profiles that were added. */
    private fun importProfiles(paths: List<Path>): List<String> {
        val imported = mutableListOf<String>()
        for (path in paths) {
            val result = ProfileManager.importProfileFromPath(path, renameOnConflict = true)
            if (result is ImportResult.Imported) imported.add(result.name)
        }
        imported.lastOrNull()?.let {
            searchBox.value = ""
            select(it)
            rebuildProfileList()
        }
        return imported
    }

    /**
     * After an import: offers to download the packs the new profiles use but this install lacks.
     * Packs Modrinth can supply are listed in a dialog; packs it can't are only counted, since
     * they must be added by hand. With nothing to download, a status line explains the gap.
     */
    private fun offerMissingPacks(profileNames: List<String>) {
        if (profileNames.isEmpty()) return
        refreshPackCache()
        val profiles = profileNames.mapNotNull { ProfileManager.getProfile(it) }
        val fetchable = profiles.flatMap { fetchableMissing(it).keys }.distinct()
        val manual = profiles
            .flatMap { p -> p.enabledPackIds().filter { it !in packsById && it !in p.packSources.orEmpty() } }
            .distinct()
        if (fetchable.isEmpty()) {
            if (manual.isNotEmpty()) {
                showError(t("status.resourcepackprofiles.import_missing_manual", packCount(manual.size)))
            }
            return
        }

        val intro =
            if (profiles.size == 1) t("screen.resourcepackprofiles.missing_download.message_one", profiles[0].name)
            else t("screen.resourcepackprofiles.missing_download.message_many")
        val message = Component.empty().append(intro)
        if (manual.isNotEmpty()) {
            message.append(Component.literal(" ")).append(t("screen.resourcepackprofiles.missing_download.manual", packCount(manual.size)))
        }
        minecraft.showScreen(ConfirmDialogScreen(
            this,
            t("screen.resourcepackprofiles.missing_download.title"),
            message,
            t("button.resourcepackprofiles.download_n", packCount(fetchable.size)),
            items = fetchable.map { it.removePrefix("file/") }.sortedBy { it.lowercase() },
            destructive = false,
            cancelLabel = t("button.resourcepackprofiles.not_now")
        ) { downloadMissing(profileNames) })
    }

    private fun onChangeIcon() {
        val name = selectedName ?: return
        val pick = { FileDialogs.openFile("Select Profile Icon", arrayOf("*.png", "*.jpg", "*.jpeg", "*.webp"), "Image Files (*.png, *.jpg, *.webp)") }
        runInBackground("RPP-IconPicker", pick) { result ->
            val path = result.getOrNull() ?: return@runInBackground
            try {
                if (ProfileManager.hasProfile(name)) ProfileIconManager.importCustomIcon(name, Path.of(path))
            } catch (e: Exception) {
                logger.error("Failed to set custom icon for '{}'", name, e)
                showError(t("status.resourcepackprofiles.icon_failed"))
            }
        }
    }

    private fun onRemoveIcon() {
        val profile = selected ?: return
        ProfileIconManager.deleteCustomIcon(profile)
        ProfileManager.setCustomIcon(profile.name, null)
        ProfileIconManager.invalidate(profile.name)
    }

    // --- Pack edits (saved immediately) ---

    private fun commitPacks(newIds: List<String>, message: Component) {
        val profile = selected ?: return
        val oldIds = profile.packIds
        if (oldIds == newIds) return
        val name = profile.name
        ProfileManager.updateProfile(name, newIds)
        // Restores the whole profile, so the disabled flags of removed packs come back too.
        undo = { ProfileManager.putProfile(profile) }
        refreshPackCache()
        rebuildPackList()
        showStatus(message)
    }

    /** "Added Foo" for one pack, "Added 3 packs" for several. */
    private fun packsMessage(ids: List<String>, one: String, many: String): Component =
        if (ids.size == 1) t("status.resourcepackprofiles.$one", packName(ids[0])) else t("status.resourcepackprofiles.$many", ids.size)

    /** Adds [ids] (in list order) to the profile; they end up at the top, in the same order. */
    private fun addPacks(ids: List<String>) {
        val profile = selected ?: return
        val fresh = ids.filter { it !in profile.packIds }
        if (fresh.isEmpty()) return
        // Appended last = loaded last = highest priority, i.e. the top of the displayed list.
        commitPacks(profile.packIds + fresh.reversed(), packsMessage(fresh, "pack_added", "packs_added"))
    }

    private fun removePacks(ids: List<String>) {
        val profile = selected ?: return
        commitPacks(profile.packIds - ids.toSet(), packsMessage(ids, "pack_removed", "packs_removed"))
    }

    /**
     * Turns [ids] off or on inside the profile without changing their place in the order. They
     * all follow [clicked]: off if it is on, on if it is off.
     */
    private fun togglePacks(ids: List<String>, clicked: String) {
        val profile = selected ?: return
        val disable = !profile.isDisabled(clicked)
        ProfileManager.setPacksDisabled(profile.name, ids, disable)
        undo = { ProfileManager.putProfile(profile) }
        showStatus(
            if (disable) packsMessage(ids, "pack_disabled", "packs_disabled")
            else packsMessage(ids, "pack_enabled", "packs_enabled")
        )
    }

    // --- All packs: deleting pack files ---

    /** Moves pack files to the trash folder. Packs that are enabled right now are left alone. */
    private fun trashPacks(ids: List<String>) {
        val blocked = ids.filter { it in enabledNow }.toSet()
        val trashed = mutableListOf<PackTrash.Trashed>()
        for (id in ids) {
            if (id in blocked) continue
            try {
                trashed.add(PackTrash.trash(id))
            } catch (e: Exception) {
                logger.error("Failed to move pack {} to trash", id, e)
            }
        }
        if (trashed.isNotEmpty()) {
            reloadPacks()
            undo = {
                trashed.forEach { PackTrash.restore(it) }
                reloadPacks()
            }
        }
        trashCount = PackTrash.count()
        refreshPackCache()
        rebuildPackList()
        when {
            trashed.isNotEmpty() -> showStatus(t("status.resourcepackprofiles.trashed", trashed.size))
            blocked.isNotEmpty() -> showError(t("status.resourcepackprofiles.pack_in_use"))
            else -> showError(t("status.resourcepackprofiles.trash_failed"))
        }
    }

    /** Trashes [ids] from a row's x or Delete; several at once are confirmed with the list. */
    private fun trashFromRow(ids: List<String>) {
        if (ids.size <= 1) {
            trashPacks(ids)
            return
        }
        minecraft.showScreen(ConfirmDialogScreen(
            this,
            t("screen.resourcepackprofiles.trash_picked.title"),
            t("screen.resourcepackprofiles.trash_picked.message", ids.size),
            t("button.resourcepackprofiles.move_to_trash"),
            items = ids.map { packName(it) }
        ) { trashPacks(ids) })
    }

    private fun onDeleteUnused() {
        val ids = unusedPackIds()
        if (ids.isEmpty()) return
        minecraft.showScreen(ConfirmDialogScreen(
            this,
            t("screen.resourcepackprofiles.delete_unused.title"),
            t("screen.resourcepackprofiles.delete_unused.message", ids.size),
            t("button.resourcepackprofiles.move_to_trash"),
            items = ids.map { packName(it) }.sortedBy { it.lowercase() }
        ) { trashPacks(ids) })
    }

    private fun onEmptyTrash() {
        if (trashCount == 0) return
        // Listed from disk at the moment of asking, so the dialog shows exactly what will go.
        val contents = PackTrash.list()
        minecraft.showScreen(ConfirmDialogScreen(
            this,
            t("screen.resourcepackprofiles.empty_trash.title"),
            t("screen.resourcepackprofiles.empty_trash.message", contents.size),
            t("button.resourcepackprofiles.delete_permanently"),
            items = contents
        ) {
            val removed = PackTrash.empty()
            // Any pending undo may point at files that no longer exist.
            undo = null
            showStatus(t("status.resourcepackprofiles.trash_emptied", removed))
        })
    }

    /**
     * Moves every picked pack one row up ([delta] -1) or down (1), keeping them picked. Packs
     * already against the end of the list stay, and the ones behind them close up.
     */
    private fun movePicked(delta: Int, focusId: String): Boolean {
        val ids = displayIds().toMutableList()
        val order: IntProgression = if (delta < 0) ids.indices else ids.indices.reversed()
        var moved = false
        for (i in order) {
            val j = i + delta
            if (ids[i] in picks && j in ids.indices && ids[j] !in picks) {
                ids[j] = ids[i].also { ids[i] = ids[j] }
                moved = true
            }
        }
        if (moved) {
            commitPacks(ids.reversed(), t("status.resourcepackprofiles.reordered"))
            rowFor(focusId)?.let {
                selectRow(it, keepPicks = true)
                packList.setFocused(it)
            }
        }
        // Consumed either way, so the list doesn't also move its cursor and drop the picks.
        return true
    }

    /** Moves the pack at display [index] by [delta] rows and keeps it selected. */
    private fun movePack(index: Int, delta: Int): Boolean {
        val ids = displayIds().toMutableList()
        val target = index + delta
        if (index !in ids.indices || target !in ids.indices) return false
        val id = ids.removeAt(index)
        ids.add(target, id)
        commitPacks(ids.reversed(), t("status.resourcepackprofiles.reordered"))
        packList.children().getOrNull(target)?.let {
            packList.setSelected(it)
            packList.setFocused(it)
        }
        return true
    }

    // --- Updates ---

    private fun updatesFor(profile: ResourcePackProfile): List<PackUpdate> =
        profile.packIds.mapNotNull { (checks[it] as? PackCheck.UpdateAvailable)?.update }

    /** The profile's available updates that are ticked. */
    private fun selectedUpdates(profile: ResourcePackProfile): List<PackUpdate> =
        updatesFor(profile).filter { it.packId in tickedUpdates }

    /** Ticks every available update in the profile, or unticks them all if they already are. */
    private fun onSelectAll() {
        val profile = selected ?: return
        val ids = updatesFor(profile).map { it.packId }
        if (tickedUpdates.containsAll(ids)) tickedUpdates.removeAll(ids.toSet()) else tickedUpdates.addAll(ids)
    }

    /** Asks Modrinth again: for the open profile's packs, or for everything in "All Packs". */
    private fun onRefreshUpdates() {
        if (libraryMode) jobs.check(allCheckableIds()) else selected?.let { checkUpdates(listOf(it)) }
    }

    private fun onUpdateSelected() {
        val profile = selected ?: return
        val updates = selectedUpdates(profile)
        if (updates.isEmpty()) return
        val name = profile.name
        // A single pack can simply be switched back, so the backup question is for bulk updates.
        val choice = if (updates.size < 2) BackupChoice.NEVER else ModSettings.backupChoice
        when (choice) {
            BackupChoice.ALWAYS -> applyUpdates(name, updates, backup = true)
            BackupChoice.NEVER -> applyUpdates(name, updates)
            BackupChoice.ASK -> minecraft.showScreen(
                BackupPromptScreen(this, name, updates.size) { backup -> applyUpdates(name, updates, backup) }
            )
        }
    }

    private fun checkUpdates(profiles: List<ResourcePackProfile>) =
        jobs.check(profiles.flatMap { it.packIds }.distinct())

    /** Pack files that can be looked up on Modrinth, across everything installed. */
    private fun allCheckableIds(): List<String> = userPacks().map { it.id }.filter { isPackFile(it) }

    /** Whether the file an update would download is already in the resourcepacks folder. */
    private fun isInstalled(update: PackUpdate): Boolean = "file/${update.fileName}" in packsById

    /** Updates found for installed packs whose newer file has not been downloaded yet. */
    private fun libraryUpdates(): List<PackUpdate> =
        userPacks().mapNotNull { (checks[it.id] as? PackCheck.UpdateAvailable)?.update }.filter { !isInstalled(it) }

    /** Updates [profileName] to [updates]. The ticks of the packs it replaced are spent. */
    private fun applyUpdates(profileName: String, updates: List<PackUpdate>, backup: Boolean = false) =
        jobs.applyUpdates(profileName, updates, backup) { tickedUpdates.removeAll(it) }

    /** Switches a pack back to the file it replaced in the selected profile. */
    private fun revertPack(current: String, previous: String) {
        val profile = selected ?: return
        ProfileManager.replacePackIds(profile.name, mapOf(current to previous), rememberPrevious = true, carrySources = false)
        undo = { ProfileManager.putProfile(profile) }
        refreshPackCache()
        rebuildPackList()
        showStatus(t("status.resourcepackprofiles.pack_reverted", packName(previous)))
    }

    /** What the worker found when a version list was opened: versions, or possible matches to pick from. */
    private sealed interface VersionLookup {
        class Versions(val choices: List<VersionChoice>?) : VersionLookup
        class Matches(val hits: List<ProjectHit>) : VersionLookup
    }

    /**
     * Opens the version list for a pack, right-aligned at ([right], [top]). The list appears
     * immediately with a loading line (and the switch-back entry, which needs no network) and is
     * filled in when Modrinth answers.
     */
    private fun openVersions(packId: String, right: Int, top: Int, pickedProject: String? = null) {
        val profile = selected ?: return
        val base = mutableListOf<MenuItem>()
        val previous = profile.previousPacks?.get(packId)
        if (previous != null && previous in packsById) {
            base.add(MenuItem(t("menu.resourcepackprofiles.revert_to", packName(previous))) { revertPack(packId, previous) })
        }
        openMenuAt(base + MenuItem(t("menu.resourcepackprofiles.versions_loading"), muted = true, enabled = false) {}, right, top, owner = packId)

        val missing = packId !in packsById
        // A project chosen from search results replaces whatever the profile remembers.
        val knownVersion = if (pickedProject != null) null else profile.packSources?.get(packId)
        val project = pickedProject ?: profile.packProjects?.get(packId)
        val lookUp = {
            val versions = VersionLookup.Versions(UpdateChecker.listVersions(packId, knownVersion, project))
            // A missing pack that can't be tied to a project (an older export, or a version that
            // is gone and was never linked) is searched for by its name instead.
            if (missing && pickedProject == null && versions.choices == null) VersionLookup.Matches(UpdateChecker.searchProjects(packId))
            else versions
        }
        runInBackground("RPP-Versions", lookUp) { result ->
            result.exceptionOrNull()?.let { logger.error("Failed to list versions for {}", packId, it) }
            // The user may have closed this menu, or opened another, while we were waiting.
            if (!menu.isOpen || menu.owner != packId) return@runInBackground
            val items = base.toMutableList()
            val lookup = result.getOrNull()
            when {
                lookup == null ->
                    items.add(MenuItem(t("menu.resourcepackprofiles.versions_failed"), muted = true, enabled = false) {})
                lookup is VersionLookup.Matches -> {
                    if (lookup.hits.isEmpty()) {
                        items.add(MenuItem(t("menu.resourcepackprofiles.no_matches"), muted = true, enabled = false) {})
                    } else {
                        items.add(MenuItem(t("menu.resourcepackprofiles.search_results", UpdateChecker.guessName(packId)), muted = true, enabled = false) {})
                        for (hit in lookup.hits) {
                            items.add(MenuItem(Component.literal(hit.title)) { openVersions(packId, right, top, hit.projectId) })
                        }
                    }
                }
                lookup is VersionLookup.Versions && lookup.choices == null ->
                    items.add(MenuItem(t("menu.resourcepackprofiles.versions_unknown"), muted = true, enabled = false) {})
                lookup is VersionLookup.Versions -> for (choice in lookup.choices.orEmpty()) {
                    val noteKey = when {
                        choice.current -> "label.resourcepackprofiles.version_current"
                        isInstalled(choice.update) -> "label.resourcepackprofiles.version_installed"
                        !choice.compatible -> "label.resourcepackprofiles.version_other_game"
                        else -> null
                    }
                    items.add(MenuItem(
                        Component.literal(choice.update.newVersion),
                        note = noteKey?.let { t(it) },
                        muted = choice.current || !choice.compatible,
                        enabled = !choice.current
                    ) { selectedName?.let { applyUpdates(it, listOf(choice.update)) } })
                }
            }
            openMenuAt(items, right, top, owner = packId)
        }
    }

    /** Downloads the missing packs of [profileNames] (the selected profile by default) that Modrinth still has. */
    private fun downloadMissing(profileNames: List<String> = listOfNotNull(selected?.name)) {
        // Pack id -> Modrinth version id, across every profile asked about.
        val sources = profileNames.mapNotNull { ProfileManager.getProfile(it) }
            .flatMap { fetchableMissing(it).entries }
            .associate { it.key to it.value }
        if (jobs.isBusy || sources.isEmpty()) return
        iconsShown.clear()
        jobs.downloadMissing(profileNames, sources)
    }

    // --- Overflow menu ---

    private fun toggleMenu() {
        if (menu.isOpen) {
            menu.close()
            return
        }
        val profile = selected ?: return
        // Duplicate, export and delete have header buttons, and the icon itself changes the
        // icon, so this menu holds only the occasional and situational actions.
        val items = mutableListOf<MenuItem>()
        if (profile.customIcon != null) {
            items.add(MenuItem(t("menu.resourcepackprofiles.remove_icon")) { onRemoveIcon() })
        }
        // Automatic switching is opt-in: these only appear while in a world or on a server.
        val context = AutoApply.currentContext(minecraft)
        if (context != null) {
            val bound = ProfileManager.autoApplyProfile(context.key)?.name == profile.name
            val labelKey = if (bound) "menu.resourcepackprofiles.auto_apply_off" else "menu.resourcepackprofiles.auto_apply_on"
            items.add(MenuItem(t(labelKey, context.label)) {
                ProfileManager.setAutoApply(context.key, if (bound) null else profile.name)
                val statusKey = if (bound) "status.resourcepackprofiles.auto_apply_off" else "status.resourcepackprofiles.auto_apply_on"
                showStatus(t(statusKey, profile.name, context.label))
            })
        }
        val bindings = ProfileManager.autoApplyContexts(profile.name).size
        if (bindings > 0) {
            items.add(MenuItem(t("menu.resourcepackprofiles.auto_apply_clear", bindings)) {
                ProfileManager.clearAutoApply(profile.name)
                showStatus(t("status.resourcepackprofiles.auto_apply_cleared", profile.name))
            })
        }
        items.add(MenuItem(t("menu.resourcepackprofiles.check_all")) { checkUpdates(ProfileManager.getProfiles()) })
        openMenu(items, moreButton)
    }

    /** Lists every keyboard and mouse shortcut. The entries are information, not actions. */
    private fun toggleHelpMenu() {
        if (menu.isOpen) {
            menu.close()
            return
        }
        val items = SHORTCUTS.map { (keys, action) ->
            MenuItem(t("shortcut.resourcepackprofiles.$action"), note = Component.literal(keys), enabled = false) {}
        }
        openMenu(items, helpButton)
    }

    private fun openSettings() {
        menu.close()
        commitRename()
        val vanilla = parent
        val openVanilla: (() -> Unit)? = if (vanilla is RefreshablePackScreen) ({ openVanillaScreen(vanilla) }) else null
        minecraft.showScreen(SettingsScreen(this, openVanilla))
    }

    /** Shows [items] as a popup below [anchor], right-aligned to it. */
    private fun openMenu(items: List<MenuItem>, anchor: FlatButton) = menu.openBelow(font, items, anchor, height, pad)

    /** Shows [items] as a popup whose top-right corner is at ([right], [top]), kept on screen. */
    private fun openMenuAt(items: List<MenuItem>, right: Int, top: Int, owner: String? = null) =
        menu.open(font, items, right, top, height, pad, owner)

    /** Switches to the vanilla resource pack screen, bypassing replacement this once. */
    private fun openVanillaScreen(vanilla: RefreshablePackScreen) {
        commitRename()
        ProfileIconManager.cleanup()
        if (replacesVanilla) vanilla.rpp_discard()
        ModSettings.allowVanillaScreenOnce()
        // A fresh instance, so it reflects any packs applied, installed or deleted here.
        minecraft.showScreen(vanilla.rpp_createRefreshedScreen())
    }

    // An open menu captures the wheel, so the list behind it doesn't scroll away.
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        menu.scroll(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

    // --- Input ---

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        // An open menu captures the click: it runs the item under the cursor, or just closes.
        if (menu.click(event.x(), event.y())) return true
        // Clicking the large profile icon is a shortcut for "Change icon".
        if (selected != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            val ix = rightX + 8
            val iy = headerY
            if (event.x() >= ix && event.x() < ix + HEADER_ICON && event.y() >= iy && event.y() < iy + HEADER_ICON) {
                onChangeIcon()
                return true
            }
        }
        return try {
            super.mouseClicked(event, doubled)
        } finally {
            rowClicking = false
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (!drag.isActive) return super.mouseDragged(event, dx, dy)
        drag.drag(event.y())
        return true
    }

    /** The dragged rows, popped out of the list and drawn over it where the pointer holds them. */
    private fun extractLiftedRows(context: GuiGraphicsExtractor) {
        if (!drag.isLifting) return
        val rows = packList.children()
        val lifted = drag.lifted().mapNotNull { id -> rows.filterIsInstance<ProfilePackRow>().firstOrNull { it.packId == id } }
        val first = lifted.firstOrNull() ?: return
        val top = drag.liftedTop()
        val left = first.x
        val right = first.x + first.width
        val bottom = top + lifted.size * PACK_ROW_HEIGHT
        context.nextStratum()
        // Raised card: a soft shadow below, a solid fill and an outline. The shadow is clipped to
        // the list, so it never spills past the list's edge when the card is at the bottom.
        context.enableScissor(packList.x, packList.y, packList.x + packList.width, packList.y + packList.height)
        context.fill(left + 1, top + 2, right + 1, bottom + 3, 0x50000000)
        context.disableScissor()
        context.fill(left, top, right, bottom, Theme.RAISED_FILL)
        lifted.forEachIndexed { i, row ->
            context.pose().pushMatrix()
            context.pose().translate(0f, (top + i * PACK_ROW_HEIGHT - row.y).toFloat())
            row.extractLifted(context)
            context.pose().popMatrix()
        }
        Theme.border(context, left, top, right - left, bottom - top, Theme.CONTROL_BORDER)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (!drag.isActive) return super.mouseReleased(event)
        val collapse = collapseTo
        collapseTo = null
        val drop = drag.release()
        if (drop != null) {
            commitPacks(drop.order.reversed(), t("status.resourcepackprofiles.reordered"))
            rebuildPackList()
            (packList.children().getOrNull(drop.leadIndex) as? PackRow)?.let { selectRow(it, keepPicks = drop.group.size > 1) }
        } else if (collapse != null) {
            // A plain click on one of several picked packs, without dragging: pick just that one.
            rowFor(collapse)?.let { selectRow(it) }
        }
        // Still let the screen clear its own drag state.
        super.mouseReleased(event)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (menu.isOpen && event.isEscape) {
            menu.close()
            return true
        }
        if (nameBox.isFocused) {
            if (event.isConfirmation) {
                commitRename()
                setFocused(profileList)
                return true
            }
            if (event.isEscape) {
                selected?.let { nameBox.value = it.name }
                setFocused(profileList)
                return true
            }
        }
        if (event.isEscape && (picks.size > 1 || (addMode && !picks.isEmpty()))) {
            clearPicks()
            return true
        }
        if (event.isEscape && addMode) {
            setAddMode(false)
            return true
        }

        val key = event.key()
        val typing = searchBox.isFocused || nameBox.isFocused || filterBox.isFocused
        if (event.hasControlDown()) {
            when (key) {
                InputConstants.KEY_F -> {
                    setFocused(if (addMode) filterBox else searchBox)
                    return true
                }
                InputConstants.KEY_D -> if (!typing) {
                    onDuplicate()
                    return true
                }
                InputConstants.KEY_A -> if (!typing && packList.visible && packList.children().isNotEmpty()) {
                    picks.setAll(shownPackIds())
                    return true
                }
                InputConstants.KEY_Z -> if (!typing) {
                    runUndo()
                    return true
                }
                InputConstants.KEY_U -> if (!typing) {
                    if (libraryMode) jobs.check(allCheckableIds()) else selected?.let { checkUpdates(listOf(it)) }
                    return true
                }
            }
        } else if (!typing) {
            when {
                key == InputConstants.KEY_F2 -> {
                    focusName()
                    return true
                }
                // In the pack list these keys act on the focused pack row instead.
                key == InputConstants.KEY_DELETE && focused !== packList -> {
                    onDelete()
                    return true
                }
                event.isConfirmation && focused === profileList -> {
                    applySelected()
                    return true
                }
            }
        }
        return super.keyPressed(event)
    }

    override fun onFilesDrop(paths: List<Path>) {
        val profiles = paths.filter { it.fileName.toString().lowercase().endsWith(".rpprofile") }
        val zips = paths.filter { it.fileName.toString().lowercase().endsWith(".zip") }

        var installed = 0
        for (zip in zips) {
            try {
                if (UpdateChecker.installPack(zip) != null) installed++
            } catch (e: Exception) {
                logger.error("Failed to install dropped pack {}", zip, e)
            }
        }
        if (installed > 0) {
            reloadPacks()
            refreshPackCache()
            rebuildPackList()
        }
        val importedNames = importProfiles(profiles)
        val imported = importedNames.size

        when {
            imported == 0 && installed == 0 -> showError(t("status.resourcepackprofiles.drop_nothing"))
            else -> showStatus(t("status.resourcepackprofiles.dropped", imported, installed), Theme.SUCCESS)
        }
        offerMissingPacks(importedNames)
    }

    // --- Rendering ---

    /**
     * Display name of a pack with any legacy formatting codes removed. Packs can embed colour
     * codes in their names, which would override our own colours and skew sorting and search.
     */
    private fun packName(packId: String): String =
        (packsById[packId]?.title?.string ?: packId.removePrefix("file/")).replace(FORMATTING_CODE, "").trim()

    /** Whether a pack is a file in the resourcepacks folder, as opposed to built in or from a mod. */
    private fun isPackFile(packId: String): Boolean = packId.startsWith("file/")

    /** "1 pack" / "N packs". */
    private fun packCount(count: Int): Component =
        if (count == 1) t("label.resourcepackprofiles.pack_count_one") else t("label.resourcepackprofiles.pack_count", count)

    /**
     * Rescans the resource packs folder. Icons that failed to load before (a pack that was missing
     * or not downloaded yet) are retried afterwards; without this a freshly downloaded pack kept
     * its placeholder icon until the screen was reopened.
     */
    private fun reloadPacks() {
        minecraft.resourcePackRepository.reload()
        packsWithoutIcon.clear()
    }

    /**
     * Shows each downloaded pack's icon as soon as its file is complete, instead of when the whole
     * batch ends and the pack list is reloaded. The icon is read straight from the new file and
     * registered under the row's (still missing) pack id. Packs that already have an icon, such as
     * ones being updated, keep theirs.
     */
    private fun showDownloadedIcons() {
        val batch = jobs.progress ?: return
        for (item in batch.items) {
            val newId = item.resultId ?: continue
            if (item.state != DownloadProgress.State.DONE || item.sourceId in packsById || !iconsShown.add(item.sourceId)) continue
            packTextures.getOrRegister(item.sourceId, UNKNOWN_PACK) { UpdateChecker.packFile(newId)?.let { PackIcons.readFile(it) } }
        }
    }

    /** A missing pack whose download in the running batch has finished. */
    private fun justDownloaded(packId: String): Boolean =
        jobs.progress?.items?.any { it.sourceId == packId && it.state == DownloadProgress.State.DONE } == true

    private fun packIcon(packId: String): Identifier {
        if (packId in packsWithoutIcon) return UNKNOWN_PACK
        val id = packTextures.getOrRegister(packId, UNKNOWN_PACK) { PackIcons.read(packId) }
        // A miss is not cached by the texture cache; remember it so the zip isn't reopened each
        // frame. Only for packs that are installed: a missing pack may be downloaded at any moment.
        if (id == UNKNOWN_PACK && packId in packsById) packsWithoutIcon.add(packId)
        return id
    }

    /** Per-frame work: caches shared by the rows, blur-commit of the name field, button state. */
    private fun updateFrameState() {
        refreshPackCache()
        showDownloadedIcons()
        drag.frame()
        val profile = selected

        if (profile != null && !nameBox.isFocused && nameBox.value != profile.name) commitRename()

        if (profile != null) {
            // Hidden while this profile is applied: the badge on its icon already says so.
            applyButton.visible = activeName != profile.name
            addButton.setX(addX)
            refreshButton.setX(refreshX)
            if (addMode) {
                filterBox.setX(filterX + 15)
                filterBox.setWidth(filterW - 19)
            }
            // Only changes when Apply appears or disappears in a window too narrow for the full width.
            if (nameBox.getWidth() != nameFieldW - 8) nameBox.setWidth(nameFieldW - 8)
            applyButton.setMessage(
                if (ProfileManager.lastAppliedName() == profile.name) t("button.resourcepackprofiles.apply_changes")
                else t("button.resourcepackprofiles.apply")
            )

            val available = updatesFor(profile)
            val chosen = selectedUpdates(profile).size
            val showUpdates = !addMode && available.isNotEmpty()
            selectAllButton.visible = showUpdates
            // Hidden until something is ticked, so there is never a dead "Update 0 Selected".
            updateAllButton.visible = showUpdates && chosen > 0
            updateAllButton.active = !jobs.isBusy
            var footerRight = rightX + 8 - 4
            if (showUpdates) {
                val allTicked = chosen == available.size
                val selectLabel = t(
                    if (allTicked) "button.resourcepackprofiles.deselect_all_updates"
                    else "button.resourcepackprofiles.select_all_updates"
                )
                selectAllButton.setMessage(selectLabel)
                selectAllButton.setWidth(font.width(selectLabel) + 16)

                val label = t("button.resourcepackprofiles.update_selected", chosen)
                updateAllButton.setMessage(label)
                updateAllButton.setWidth(font.width(label) + 16)
                updateAllButton.setX(selectAllButton.x + selectAllButton.getWidth() + 4)
                footerRight =
                    if (chosen > 0) updateAllButton.x + updateAllButton.getWidth()
                    else selectAllButton.x + selectAllButton.getWidth()
            }

            addPickedButton.visible = addMode && !picks.isEmpty()
            if (addPickedButton.visible) {
                val pickedLabel = t("button.resourcepackprofiles.add_picked", picks.size)
                addPickedButton.setMessage(pickedLabel)
                addPickedButton.setWidth(font.width(pickedLabel) + 16)
            }

            val fetchable = fetchableMissing(profile).size
            missingButton.visible = !addMode && fetchable > 0
            missingButton.active = !jobs.isBusy
            if (fetchable > 0) {
                val missingLabel = t("button.resourcepackprofiles.download_missing", fetchable)
                missingButton.setMessage(missingLabel)
                missingButton.setWidth(font.width(missingLabel) + 16)
                missingButton.setX(footerRight + 4)
            }
        }
        refreshButton.active = !jobs.isBusy
        if (libraryMode) {
            val unused = unusedPackIds().size
            val unusedLabel = t("button.resourcepackprofiles.delete_unused", unused)
            unusedButton.visible = unused > 0
            unusedButton.setMessage(unusedLabel)
            unusedButton.setWidth(font.width(unusedLabel) + 16)
            unusedButton.setX(rightX + rightW - 8 - unusedButton.getWidth())

            val pending = libraryUpdates().size
            libraryDownloadButton.visible = pending > 0
            libraryDownloadButton.active = !jobs.isBusy
            var libraryRight = rightX + 8 - 4
            if (pending > 0) {
                val downloadLabel = t("button.resourcepackprofiles.download_updates", pending)
                libraryDownloadButton.setMessage(downloadLabel)
                libraryDownloadButton.setWidth(font.width(downloadLabel) + 16)
                libraryRight = libraryDownloadButton.x + libraryDownloadButton.getWidth()
            }

            val trashLabel = t("button.resourcepackprofiles.empty_trash", trashCount)
            trashButton.visible = trashCount > 0
            trashButton.setMessage(trashLabel)
            trashButton.setWidth(font.width(trashLabel) + 16)
            trashButton.setX(libraryRight + 4)
        }
        undoButton.visible = undo != null

        // The pack list runs to the bottom of the panel unless the footer has a button to show,
        // so there is no empty strip where a button would be.
        val footerInUse = listOf(selectAllButton, updateAllButton, missingButton, trashButton, libraryDownloadButton, addPickedButton).any { it.visible }
        val listHeight = (if (footerInUse) footerY - 4 else panelBottom) - packTop
        if (packList.getHeight() != listHeight || packList.getY() != packTop) {
            packList.updateSizeAndPosition(rightW - 2, listHeight, rightX + 1, packTop)
        }
    }

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
        val panelHeight = panelBottom - panelTop
        Theme.panel(context, leftX, panelTop, leftW, panelHeight)
        Theme.panel(context, rightX, panelTop, rightW, panelHeight)
        // Header blocks: the search bar on the left; on the right the profile header and the
        // section row. A shade step, not a line, separates them from the lists below.
        Theme.header(context, leftX, panelTop, leftW, leftHeaderBottom - panelTop)
        if (libraryMode || selected != null) Theme.header(context, rightX, panelTop, rightW, rightHeaderBottom - panelTop)

        Theme.field(context, leftX + 8, searchY, searchW, 16, searchBox.isFocused)
        Icon.SEARCH.draw(context, leftX + 8 + 4, searchY + 4, Theme.TEXT_MUTED)

        if (libraryMode) {
            Theme.field(context, filterX, filterY, filterW, 16, filterBox.isFocused)
            Icon.SEARCH.draw(context, filterX + 4, filterY + 4, Theme.TEXT_MUTED)
            return
        }
        if (selected == null) return
        // The name reads as plain text until hovered or focused, when it shows as a field.
        val overName = mouseX >= nameFieldX && mouseX < nameFieldX + nameFieldW && mouseY >= nameFieldY && mouseY < nameFieldY + NAME_FIELD_HEIGHT
        if (nameBox.isFocused || overName) {
            Theme.field(context, nameFieldX, nameFieldY, nameFieldW, NAME_FIELD_HEIGHT, nameBox.isFocused)
        }
        if (addMode) {
            Theme.field(context, filterX, filterY, filterW, 16, filterBox.isFocused)
            Icon.SEARCH.draw(context, filterX + 4, filterY + 4, Theme.TEXT_MUTED)
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        updateFrameState()
        // While the pointer is over an open menu, nothing underneath it may react: widgets and
        // rows get an off-screen position, so they draw no hover state and show no tooltips.
        val blocked = menu.isOver(mouseX, mouseY)
        val mx = if (blocked) -1 else mouseX
        val my = if (blocked) -1 else mouseY
        super.extractRenderState(context, mx, my, delta)

        context.text(font, title, pad, 9, Theme.TEXT, true)

        // Only the pinned "All packs" row is left when there are no (matching) profiles.
        if (profileList.children().size <= 1) {
            val key = if (searchText.isBlank()) "label.resourcepackprofiles.no_profiles" else "label.resourcepackprofiles.no_matches"
            context.centeredText(font, t(key), leftX + leftW / 2, leftHeaderBottom + HEADER_GAP + 46, Theme.TEXT_MUTED)
        }

        val profile = selected
        if (libraryMode) {
            extractLibrary(context)
        } else if (profile == null) {
            val cx = rightX + rightW / 2
            val cy = (panelTop + panelBottom) / 2
            context.centeredText(font, t("label.resourcepackprofiles.empty_title"), cx, cy - 10, Theme.TEXT_SECONDARY)
            context.centeredText(font, t("label.resourcepackprofiles.empty_hint"), cx, cy + 2, Theme.TEXT_MUTED)
        } else {
            extractDetail(context, profile, mx, my)
        }

        extractLiftedRows(context)
        extractBottomBar(context, mx, my)
        menu.draw(context, font, mouseX, mouseY)
    }

    private fun extractDetail(context: GuiGraphicsExtractor, profile: ResourcePackProfile, mouseX: Int, mouseY: Int) {
        val iconX = rightX + 8
        val iconY = headerY
        context.blit(RenderPipelines.GUI_TEXTURED, ProfileIconManager.getIconId(profile), iconX, iconY, 0f, 0f, HEADER_ICON, HEADER_ICON, HEADER_ICON, HEADER_ICON)
        // The icon is clickable (it opens the image picker), so say so on hover: dim it, outline
        // it and show a folder. Not while a menu is open, since the menu would take the click.
        if (!menu.isOpen && mouseX >= iconX && mouseX < iconX + HEADER_ICON && mouseY >= iconY && mouseY < iconY + HEADER_ICON) {
            context.fill(iconX, iconY, iconX + HEADER_ICON, iconY + HEADER_ICON, 0xA0000000.toInt())
            Theme.border(context, iconX, iconY, HEADER_ICON, HEADER_ICON, Theme.CONTROL_BORDER_FOCUS)
            Icon.FOLDER.drawCentered(context, iconX, iconY, HEADER_ICON, HEADER_ICON, Theme.TEXT)
        }
        if (activeName == profile.name) Theme.appliedBadge(context, iconX, iconY, HEADER_ICON)

        val missing = profile.packIds.count { it !in packsById }
        val (subText, subColor) = when {
            missing > 0 -> t("label.resourcepackprofiles.pack_count_missing", packCount(profile.packIds.size), missing) to Theme.DANGER
            else -> packCount(profile.packIds.size) to Theme.TEXT_SECONDARY
        }
        context.text(font, ScreenUtils.truncate(font, subText.string, nameFieldMaxW - 4), nameFieldX + 4, countY, subColor, true)

        if (packList.children().isEmpty()) {
            val key = when {
                !addMode -> "label.resourcepackprofiles.no_packs"
                packFilter.isBlank() -> "label.resourcepackprofiles.nothing_to_add"
                else -> "label.resourcepackprofiles.no_matches"
            }
            context.centeredText(font, t(key), rightX + rightW / 2, packTop + 14, Theme.TEXT_MUTED)
        }
    }

    private fun extractLibrary(context: GuiGraphicsExtractor) {
        val total = userPacks().size
        val unused = unusedPackIds().size
        context.text(font, t("label.resourcepackprofiles.all_packs"), rightX + 8, headerY + 2, Theme.TEXT, true)
        val sub =
            if (unused > 0) t("label.resourcepackprofiles.library_count_unused", total, unused)
            else t("label.resourcepackprofiles.library_count", total)
        val subWidth = (if (unusedButton.visible) unusedButton.x - 6 else rightX + rightW - 8) - (rightX + 8)
        context.text(font, ScreenUtils.truncate(font, sub.string, subWidth), rightX + 8, libraryCountY, Theme.TEXT_SECONDARY, true)

        if (packList.children().isEmpty()) {
            val key = if (packFilter.isBlank()) "label.resourcepackprofiles.no_installed_packs" else "label.resourcepackprofiles.no_matches"
            context.centeredText(font, t(key), rightX + rightW / 2, packTop + 14, Theme.TEXT_MUTED)
        }
    }

    private fun extractBottomBar(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val right = (if (undoButton.visible) undoButton.x else doneButton.x) - 6
        val now = System.currentTimeMillis()
        val busyText = jobs.busy
        val statusText = status
        val batch = jobs.progress
        when {
            // Downloads: a bar along the bottom of the message, and the details on hover.
            busyText != null && batch != null ->
                StatusPill.drawProgress(context, font, batch, pad, height - 24, right, mouseX, mouseY)
            busyText != null -> statusPill(context, busyText, Theme.ACTIVE, 1f, right)
            statusText != null && now < statusUntil -> {
                // Fade out over the last half second.
                val alpha = ((statusUntil - now) / 500f).coerceAtMost(1f)
                // Very low alpha text is skipped by the font renderer; stop drawing just before.
                if (alpha > 0.1f) statusPill(context, statusText, statusColor, alpha, right)
            }
            // Idle: nothing. Shortcuts live behind the "?" button.
            else -> {}
        }
    }

    /** A status message in line with the Undo/Done buttons; see [StatusPill]. */
    private fun statusPill(context: GuiGraphicsExtractor, text: Component, color: Int, alpha: Float, right: Int, fraction: Float? = null): IntArray? =
        StatusPill.draw(context, font, text, color, alpha, pad, height - 24, right, fraction)

    override fun tick() {
        super.tick()
        val setting = ModSettings.autoCheckUpdates
        if (setting == null) {
            // Asked once per screen; pressing Esc leaves it undecided, to be asked next time.
            if (!firstRunShown) {
                firstRunShown = true
                minecraft.showScreen(ConfirmDialogScreen(
                    this,
                    t("screen.resourcepackprofiles.auto_check.title"),
                    t("screen.resourcepackprofiles.auto_check.message"),
                    t("button.resourcepackprofiles.auto_check_on"),
                    destructive = false,
                    cancelLabel = t("button.resourcepackprofiles.auto_check_off"),
                    onCancel = { ModSettings.autoCheckUpdates = false }
                ) { ModSettings.autoCheckUpdates = true })
            }
            return
        }
        if (setting && !PackSession.autoChecked && !jobs.isBusy) {
            PackSession.autoChecked = true
            jobs.check(allCheckableIds(), quiet = true)
        }
    }

    override fun removed() {
        packTextures.cleanup()
        packsWithoutIcon.clear()
        super.removed()
    }

    override fun onClose() {
        commitRename()
        ProfileIconManager.cleanup()
        val returnTo = parent
        if (replacesVanilla && returnTo is RefreshablePackScreen) {
            // The vanilla screen was never shown. Close "through" it so the game runs its normal
            // save-and-go-back logic, but via a fresh instance: the original holds a snapshot of
            // the packs from before this screen opened and would write that back.
            returnTo.rpp_discard()
            returnTo.rpp_createRefreshedScreen().onClose()
            return
        }
        // If we came from the vanilla pack selection screen and applied a profile, its model holds
        // a stale snapshot that would wipe the applied packs on close. Return to a refreshed
        // instance whose model reflects the packs we just applied instead.
        if (profileApplied && returnTo is RefreshablePackScreen) {
            minecraft.showScreen(returnTo.rpp_createRefreshedScreen())
        } else {
            minecraft.setGuiScreen(returnTo)
        }
    }

    // --- Lists ---

    private abstract inner class FlatList<E : ObjectSelectionList.Entry<E>>(w: Int, h: Int, top: Int, itemHeight: Int) :
        FlatSelectionList<E>(minecraft, w, h, top, itemHeight)

    private inner class ProfileList(w: Int, h: Int, top: Int) : FlatList<ProfileRow>(w, h, top, 28) {
        private var silent = false

        /** Marks [row] selected without treating it as the user choosing a profile. */
        fun selectSilently(row: ProfileRow?) {
            silent = true
            try {
                super.setSelected(row)
            } finally {
                silent = false
            }
        }

        // Called for clicks and for keyboard navigation alike.
        override fun setSelected(entry: ProfileRow?) {
            val target = entry?.profile
            val unchanged = entry == null ||
                (if (target == null) libraryMode else !libraryMode && target.name == selectedName)
            if (silent || unchanged) {
                super.setSelected(entry)
                return
            }
            // Settle any half-typed rename against the old profile before switching away from it.
            val renamed = commitRename()
            libraryMode = target == null
            if (target != null) selectedName = target.name
            addMode = false
            packFilter = ""
            menu.close()
            clearPicks()
            // A rename rebuilt the rows, so [entry] is stale; reselect by name instead.
            if (renamed) rebuildProfileList() else super.setSelected(entry)
            syncDetail(forceName = true)
        }
    }

    /** A profile, or the pinned "All packs" entry when [profile] is null. */
    private inner class ProfileRow(private val initial: ResourcePackProfile?) : ObjectSelectionList.Entry<ProfileRow>() {

        /**
         * Looked up live rather than kept from when the row was built: profiles are immutable
         * values that are replaced on every edit, so a stored one would show stale pack counts.
         */
        val profile: ResourcePackProfile?
            get() = initial?.let { ProfileManager.getProfile(it.name) ?: it }

        override fun getNarration(): Component =
            profile?.let {
                if (activeName == it.name) t("narration.resourcepackprofiles.applied_profile", it.name)
                else Component.literal(it.name)
            } ?: t("label.resourcepackprofiles.all_packs")

        private fun deleteX() = x + width - 8 - Icon.CLOSE.width
        private fun starX() = deleteX() - 10 - Icon.STAR.width

        override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
            val target = profile
            if (target != null) {
                if (event.x() >= deleteX() - 5) {
                    onDelete(target)
                    return true
                }
                if (event.x() >= starX() - 4) {
                    ProfileManager.toggleFavorite(target.name)
                    rebuildProfileList()
                    return true
                }
            }
            profileList.setSelected(this)
            if (doubled && target != null) applySelected()
            return true
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val target = profile
            val isSelected = if (target == null) libraryMode else !libraryMode && target.name == selectedName
            if (isSelected) {
                context.fill(x, y, x + width, y + height, Theme.ROW_SELECTED)
                context.fill(x, y, x + 2, y + height, Theme.ACCENT)
            } else if (hovered) {
                context.fill(x, y, x + width, y + height, Theme.ROW_HOVER)
            }

            val centerY = y + height / 2
            val textX = x + 31
            if (target == null) {
                extractLibraryRow(context, centerY, textX)
                return
            }

            context.blit(RenderPipelines.GUI_TEXTURED, ProfileIconManager.getIconId(target), x + 6, centerY - 10, 0f, 0f, 20, 20, 20, 20)
            if (activeName == target.name) Theme.appliedBadge(context, x + 6, centerY - 10, 20)

            val showStar = target.favorite || hovered
            val textRight = when {
                hovered -> starX() - 4
                showStar -> starX() - 4
                else -> x + width - 4
            }
            val maxWidth = textRight - textX
            context.text(font, ScreenUtils.truncate(font, target.name, maxWidth), textX, centerY - 9, Theme.TEXT, true)

            val count = target.packIds.size
            val missing = target.packIds.count { it !in packsById }
            val updates = updatesFor(target).size
            val (sub, subColor) = when {
                missing > 0 -> t("label.resourcepackprofiles.pack_count_missing", packCount(count), missing) to Theme.DANGER
                updates == 1 -> t("label.resourcepackprofiles.pack_count_update_one", packCount(count)) to Theme.TEXT_SECONDARY
                updates > 1 -> t("label.resourcepackprofiles.pack_count_updates", packCount(count), updates) to Theme.TEXT_SECONDARY
                else -> packCount(count) to Theme.TEXT_SECONDARY
            }
            context.text(font, ScreenUtils.truncate(font, sub.string, maxWidth), textX, centerY + 1, subColor, true)

            if (showStar) {
                Theme.outlined(context, Icon.STAR, starX(), centerY - Icon.STAR.height / 2, if (target.favorite) Theme.ACTIVE else Theme.TEXT_MUTED)
            }
            if (hovered) {
                val overDelete = mouseX >= deleteX() - 5
                Icon.CLOSE.draw(context, deleteX(), centerY - Icon.CLOSE.height / 2, if (overDelete) Theme.DANGER else Theme.TEXT_SECONDARY)
                if (overDelete) context.setTooltipForNextFrame(t("tooltip.resourcepackprofiles.delete"), mouseX, mouseY)
            }
        }

        private fun extractLibraryRow(context: GuiGraphicsExtractor, centerY: Int, textX: Int) {
            // "Stack of packs" glyph in place of a profile icon.
            for (i in 0 until 3) {
                val barY = centerY - 7 + i * 6
                context.fill(x + 8, barY, x + 24, barY + 3, Theme.TEXT_SECONDARY)
            }
            val maxWidth = x + width - 4 - textX
            context.text(font, t("label.resourcepackprofiles.all_packs"), textX, centerY - 9, Theme.TEXT, true)
            val total = userPacks().size
            val unused = unusedPackIds().size
            val sub =
                if (unused > 0) t("label.resourcepackprofiles.library_count_unused", total, unused)
                else t("label.resourcepackprofiles.library_count", total)
            context.text(font, ScreenUtils.truncate(font, sub.string, maxWidth), textX, centerY + 1, Theme.TEXT_SECONDARY, true)
            // Separates the pinned entry from the profiles below it.
            context.fill(x, y + height - 1, x + width, y + height, Theme.DIVIDER)
        }
    }

    private inner class PackList(w: Int, h: Int, top: Int) : FlatList<PackListEntry>(w, h, top, PACK_ROW_HEIGHT) {
        // Called for clicks and keyboard navigation alike. Moving with the keys picks the row
        // moved to, as in a file browser; row clicks manage the picks themselves.
        override fun setSelected(entry: PackListEntry?) {
            super.setSelected(entry)
            if (rowClicking || entry == null) return
            if (entry is PackRow) pickOnly(entry) else picks.clear()
        }
    }

    /** An entry of the right-hand list: a [PackRow], or a [SectionRow] heading. */
    private abstract inner class PackListEntry : ObjectSelectionList.Entry<PackListEntry>()

    /** The heading above the built-in packs in the All Packs and Add Packs lists. Not selectable. */
    private inner class SectionRow : PackListEntry() {
        private val label get() = t("label.resourcepackprofiles.built_in_section")

        override fun getNarration(): Component = label

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            // A small heading with a rule after it, sitting low in the row so it reads as the
            // start of the rows below rather than one of them.
            val ty = y + height - 11
            context.text(font, label, x + 6, ty, Theme.TEXT_MUTED, true)
            val ruleX = x + 6 + font.width(label) + 6
            context.fill(ruleX, ty + 4, x + width - 8, ty + 5, Theme.DIVIDER)
        }
    }

    /**
     * One pack in the right-hand list. This base class handles what every kind of pack row shares:
     * Ctrl/Shift-click picking, the highlight, the icon and name (orange, with vanilla's tooltip,
     * for packs made for another game version), and the remove (x) and check-for-updates icons
     * that appear on hover just left of the row's own content at its right edge.
     *
     * The kinds: [ProfilePackRow] (a pack in the profile's load order), [AddPackRow] (a pack that
     * can be added to it) and [LibraryPackRow] (a pack in the All Packs view).
     */
    private abstract inner class PackRow(val packId: String) : PackListEntry() {

        override fun getNarration(): Component = Component.literal(packName(packId))

        /** Whether a plain click picks the row. Add rows add the pack instead, and pick only on purpose. */
        open val clickPicks: Boolean get() = true

        /** Whether the row offers the remove (x) icon on hover. */
        protected open val removable: Boolean get() = false

        /** Only installed pack files can be looked up on Modrinth. */
        protected open val checkable: Boolean get() = isPackFile(packId) && packId in packsById

        /** A pack the profile uses but that isn't installed. */
        protected open val missingFile: Boolean get() = false

        /** Drawn faded: a pack switched off in the profile. */
        protected open val dimmed: Boolean get() = false

        /** Drawn as part of the lifted card while being dragged. */
        protected open val lifted: Boolean get() = false

        /** The update found for this pack, if any. */
        protected val update: PackUpdate? get() = (checks[packId] as? PackCheck.UpdateAvailable)?.update

        /** Whether this row is one of several picked, so its actions apply to all of them. */
        protected val many: Boolean get() = picks.isOneOfMany(packId)

        /** The row whose version list is open counts as hovered, so it stays lit under the list. */
        protected val menuAnchor: Boolean get() = menu.isOpen && menu.owner == packId

        protected val centerY: Int get() = y + height / 2

        // The row's own content (labels, the update tick, the Download chip) sits against its
        // right edge. Hover icons appear just left of it, so no row reserves empty space for them.
        protected fun rowRight() = x + width - 8

        /** Left edge of a label right-aligned at the row's edge. */
        protected fun labelX(label: Component) = rowRight() - font.width(label)

        /** Left edge of the right-hand content, or the row's edge when there is none. */
        protected open fun contentLeft(): Int = rowRight() + 8

        protected fun removeX() = contentLeft() - 10 - Icon.CLOSE.width
        protected fun checkX() = removeX() - 9 - Icon.REFRESH.width
        protected fun overRemove(mx: Double) = mx >= removeX() - 5 && mx < removeX() + Icon.CLOSE.width + 4
        protected fun overCheck(mx: Double) = mx >= checkX() - 4 && mx < removeX() - 5

        /** Picked packs (or just this one) that can be checked on Modrinth. */
        private fun checkTargets() = targetsOf(packId).filter { isPackFile(it) && it in packsById }

        final override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
            // The row decides what the click does to the picks, so the list must not reset them.
            rowClicking = true
            val toggle = event.hasControlDownWithQuirk()
            if (toggle || event.hasShiftDown()) {
                pickRow(this, range = event.hasShiftDown(), toggle = toggle)
                return true
            }
            if (checkable && overCheck(event.x())) {
                val ids = checkTargets()
                jobs.check(ids, single = ids.size == 1)
                return true
            }
            return onClick(event)
        }

        /** A plain left click that no shared control took. */
        protected abstract fun onClick(event: MouseButtonEvent): Boolean

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) =
            extractRow(context, mouseX, mouseY, hovered)

        protected fun extractRow(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean) {
            val rowHot = hovered || menuAnchor
            // Picks are only marked when they mean something: several of them, or any in add mode.
            val picked = packId in picks && (picks.size > 1 || !clickPicks)
            val isSelected = clickPicks && packList.selected === this
            when {
                lifted || picked -> context.fill(x, y, x + width, y + height, Theme.ROW_SELECTED)
                rowHot || isSelected -> context.fill(x, y, x + width, y + height, Theme.ROW_HOVER)
            }
            if (picked) context.fill(x, y, x + 2, y + height, Theme.ACCENT)

            var cursor = extractLeading(context, mouseX, mouseY, hovered, rowHot)
            val icon = packIcon(packId)
            if (dimmed) context.blit(RenderPipelines.GUI_TEXTURED, icon, cursor, centerY - 8, 0f, 0f, 16, 16, 16, 16, DISABLED_TINT)
            else context.blit(RenderPipelines.GUI_TEXTURED, icon, cursor, centerY - 8, 0f, 0f, 16, 16, 16, 16)
            cursor += 16 + 5

            val mx = mouseX.toDouble()
            val showRemove = rowHot && removable
            val showCheck = rowHot && checkable
            if (showRemove) {
                val hot = overRemove(mx)
                Icon.CLOSE.draw(context, removeX(), centerY - Icon.CLOSE.height / 2, if (hot) Theme.DANGER else Theme.TEXT_SECONDARY)
                if (hot) removeTooltip()?.let { context.setTooltipForNextFrame(it, mouseX, mouseY) }
            }
            if (showCheck) {
                val hot = overCheck(mx)
                Icon.REFRESH.draw(context, checkX(), centerY - Icon.REFRESH.height / 2, if (hot) Theme.TEXT else Theme.TEXT_SECONDARY)
                if (hot) {
                    val tip = if (many) t("tooltip.resourcepackprofiles.check_picked") else checkResult() ?: t("tooltip.resourcepackprofiles.check_pack")
                    context.setTooltipForNextFrame(tip, mouseX, mouseY)
                }
            }

            var rightEdge = minOf(extractTrailing(context, mouseX, mouseY, hovered, rowHot), contentLeft() - 6)
            leftmostHoverIcon(rowHot, showCheck, showRemove)?.let { rightEdge = minOf(rightEdge, it - 6) }

            // Outdated packs are shown like vanilla does it: the name is coloured, nothing is added
            // to the row, and hovering the name gives Minecraft's own explanation. (A warning icon
            // was tried; with most packs flagged it only made the lists busier.)
            val compat = packsById[packId]?.compatibility?.takeIf { !it.isCompatible }?.description
            val outdated = compat != null && !missingFile
            val nameColor = when {
                missingFile && !justDownloaded(packId) -> Theme.DANGER
                dimmed -> Theme.TEXT_MUTED
                outdated -> Theme.WARNING
                else -> Theme.TEXT
            }
            val name = ScreenUtils.truncate(font, packName(packId), rightEdge - cursor)
            context.text(font, name, cursor, centerY - 4, nameColor, true)
            if (outdated && hovered && mouseX >= cursor && mouseX < cursor + font.width(name)) {
                context.setTooltipForNextFrame(compat, mouseX, mouseY)
            }
        }

        /** Draws whatever comes before the icon and returns where the icon goes. */
        protected open fun extractLeading(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, rowHot: Boolean): Int = x + 5

        /** Draws the row's own content at its right edge and returns where the name must end. */
        protected abstract fun extractTrailing(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, rowHot: Boolean): Int

        /** Left edge of the leftmost hover icon being shown, which the name must stay clear of. */
        protected open fun leftmostHoverIcon(rowHot: Boolean, showCheck: Boolean, showRemove: Boolean): Int? = when {
            !rowHot -> null
            showCheck -> checkX()
            showRemove -> removeX()
            else -> null
        }

        protected open fun removeTooltip(): Component? = null

        /**
         * Why the last update check offered nothing for this pack, or null if it was never checked
         * or has an update. Shown in the check button's tooltip rather than on the row: with
         * automatic checks every row would otherwise carry a label all the time.
         */
        private fun checkResult(): Component? {
            val key = when (checks[packId]) {
                is PackCheck.UpToDate -> "up_to_date"
                is PackCheck.NotOnModrinth -> "not_on_modrinth"
                is PackCheck.NoCompatibleVersion -> "no_version"
                is PackCheck.Unavailable -> "unavailable"
                else -> return null
            }
            return t("tooltip.resourcepackprofiles.check_$key")
        }
    }

    /**
     * A pack in the selected profile's load order: drag to reorder, tick box to switch it off,
     * x to remove, a status label (missing, or built in), and a version list on hover. A pack
     * with a newer version shows it with a green tick box; ticked ones are what "Update N
     * Selected" updates.
     */
    private inner class ProfilePackRow(packId: String) : PackRow(packId) {
        private val index get() = packList.children().indexOf(this)

        /** Set while this row is drawn as part of the lifted card. */
        private var drawingLifted = false

        override val removable get() = true
        override val missingFile get() = packId !in packsById
        override val dimmed get() = selected?.isDisabled(packId) == true
        override val lifted get() = drawingLifted

        /** Pack files have a version list: switch versions, or find one for a missing pack. */
        private val versioned get() = isPackFile(packId)

        /** A pack file the profile uses but that isn't installed. Its version list is how to replace it. */
        private val findable get() = versioned && missingFile

        /** Comes with the game or a mod: it has no updates and can't be deleted, so the row says so. */
        private val builtIn get() = !isPackFile(packId) && packId in packsById

        private fun toggleX() = x + 13
        // Missing packs have no refresh icon, so the versions icon takes its place.
        private fun versionsX() = (if (checkable) checkX() else removeX()) - 9 - VERSIONS_SLOT
        private fun overVersions(mx: Double) = mx >= versionsX() - 4 && mx < (if (checkable) checkX() - 4 else removeX() - 5)
        private fun overToggle(mx: Double) = mx >= toggleX() - 2 && mx < toggleX() + TOGGLE_SIZE + 2

        private fun tickX() = rowRight() - TOGGLE_SIZE
        private fun updateText(pending: PackUpdate): String = ScreenUtils.truncate(font, pending.newVersion, UPDATE_LABEL_MAX)
        private fun updateTextX(pending: PackUpdate) = tickX() - 5 - font.width(updateText(pending))
        private fun overUpdateTick(mx: Double, pending: PackUpdate) = mx >= updateTextX(pending) - 2 && mx < rowRight() + 3

        /** "Missing", or "Downloaded" once this batch has fetched the file (before the list reloads). */
        private fun missingLabel(): Pair<Component, Int> =
            if (justDownloaded(packId)) t("label.resourcepackprofiles.pack_downloaded") to Theme.SUCCESS
            else t("label.resourcepackprofiles.pack_missing") to Theme.DANGER

        private fun builtInLabel(): Component = t("label.resourcepackprofiles.built_in")

        override fun contentLeft(): Int {
            val pending = update
            return when {
                versioned && pending != null -> updateTextX(pending)
                missingFile -> labelX(missingLabel().first)
                builtIn -> labelX(builtInLabel())
                else -> super.contentLeft()
            }
        }

        override fun onClick(event: MouseButtonEvent): Boolean {
            val mx = event.x()
            val pending = update
            when {
                overRemove(mx) -> removePacks(targetsOf(packId))
                overToggle(mx) -> togglePacks(targetsOf(packId), packId)
                versioned && overVersions(mx) -> openVersions(packId, versionsX() + VERSIONS_SLOT, y + height / 2 + 8)
                versioned && pending != null && overUpdateTick(mx, pending) -> {
                    // Every picked pack with an update follows this one's tick.
                    val ids = targetsOf(packId).filter { checks[it] is PackCheck.UpdateAvailable }
                    if (packId in tickedUpdates) tickedUpdates.removeAll(ids.toSet()) else tickedUpdates.addAll(ids)
                }
                else -> pressToDrag(event)
            }
            return true
        }

        /**
         * Selects the row and arms a drag, which only becomes a reorder once the mouse moves.
         * Pressing one of several picked packs keeps them all picked, so they can be dragged
         * together; releasing without dragging narrows the picks to this one.
         */
        private fun pressToDrag(event: MouseButtonEvent) {
            val group = if (many) pickedInOrder() else listOf(packId)
            if (group.size > 1) {
                collapseTo = packId
                selectRow(this, keepPicks = true)
            } else {
                selectRow(this)
            }
            drag.press(displayIds(), index, group, event.y(), y)
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            val key = event.key()
            if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
                removePacks(targetsOf(packId))
                return true
            }
            // Shift+Up/Down reorders the focused pack, or all picked ones (plain Up/Down navigates)
            if (event.hasShiftDown() && (event.isUp || event.isDown)) {
                val delta = if (event.isUp) -1 else 1
                return if (many) movePicked(delta, packId) else movePack(index, delta)
            }
            // Space or Enter switches the focused pack (or all picked ones) off and on.
            if (event.isSelection) {
                togglePacks(targetsOf(packId), packId)
                return true
            }
            return false
        }

        /** Draws the row as it looks while dragged: no hover state, at its own position. */
        fun extractLifted(context: GuiGraphicsExtractor) {
            drawingLifted = true
            try {
                extractRow(context, -1, -1, false)
            } finally {
                drawingLifted = false
            }
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            if (drag.isLifted(packId)) {
                // The slot the lifted row will drop into.
                context.fill(x, y, x + width, y + height, 0x30000000)
                Theme.border(context, x, y, width, height, Theme.DIVIDER)
                return
            }
            val shift = drag.shiftOf(packId)
            if (shift == 0f) {
                extractRow(context, mouseX, mouseY, hovered)
                return
            }
            context.pose().pushMatrix()
            context.pose().translate(0f, shift)
            try {
                // Hover follows the slot, not the drawn position, so it is left out mid-slide.
                extractRow(context, -1, -1, false)
            } finally {
                context.pose().popMatrix()
            }
        }

        override fun extractLeading(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, rowHot: Boolean): Int {
            Icon.GRIP.draw(context, x + 5, centerY - Icon.GRIP.height / 2, if (rowHot || lifted) Theme.TEXT else Theme.TEXT_MUTED)

            // Tick box: ticked when the pack is switched on in this profile. Neutral on purpose, so
            // it can't be mistaken for the green update tick on the right. Always drawn: hiding it
            // until hover made the feature too hard to discover.
            val tx = toggleX()
            val ty = centerY - TOGGLE_SIZE / 2
            val hot = hovered && overToggle(mouseX.toDouble())
            Theme.border(context, tx, ty, TOGGLE_SIZE, TOGGLE_SIZE, if (hot) Theme.TEXT else Theme.CONTROL_BORDER)
            if (!dimmed) Icon.TICK.drawCentered(context, tx, ty, TOGGLE_SIZE, TOGGLE_SIZE, if (hot) Theme.TEXT else Theme.TEXT_SECONDARY)
            if (hot) {
                val tip = when {
                    many && dimmed -> t("tooltip.resourcepackprofiles.enable_picked")
                    many -> t("tooltip.resourcepackprofiles.disable_picked")
                    dimmed -> t("tooltip.resourcepackprofiles.enable_pack")
                    else -> t("tooltip.resourcepackprofiles.disable_pack")
                }
                context.setTooltipForNextFrame(tip, mouseX, mouseY)
            }
            return tx + TOGGLE_SIZE + 5
        }

        override fun extractTrailing(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, rowHot: Boolean): Int {
            val mx = mouseX.toDouble()
            var rightEdge = x + width - 4
            // Shown on hover, and always on a missing pack so the way to replace it is visible.
            if (versioned && (rowHot || findable)) {
                // The version list (any version, or switch back) is a hover icon. It stays lit
                // while its list is open.
                val hot = overVersions(mx) || menuAnchor
                Icon.CHEVRON.draw(
                    context,
                    versionsX() + (VERSIONS_SLOT - Icon.CHEVRON.width) / 2,
                    centerY - Icon.CHEVRON.height / 2,
                    if (hot) Theme.TEXT else Theme.TEXT_SECONDARY
                )
                if (hot && !menuAnchor) {
                    val tip = if (findable) "tooltip.resourcepackprofiles.find_version" else "tooltip.resourcepackprofiles.choose_version"
                    context.setTooltipForNextFrame(t(tip), mouseX, mouseY)
                }
            }
            val pending = update
            if (versioned && pending != null) {
                val ticked = packId in tickedUpdates
                val tx = tickX()
                val ty = centerY - TOGGLE_SIZE / 2
                val lx = updateTextX(pending)
                val over = hovered && overUpdateTick(mx, pending)
                // Green whether ticked or not: an unticked update is still an available one.
                context.text(font, updateText(pending), lx, centerY - 4, Theme.SUCCESS, true)
                Theme.border(context, tx, ty, TOGGLE_SIZE, TOGGLE_SIZE, if (over) Theme.TEXT else Theme.SUCCESS)
                if (ticked) context.fill(tx + 2, ty + 2, tx + TOGGLE_SIZE - 2, ty + TOGGLE_SIZE - 2, Theme.SUCCESS)
                if (over) {
                    val key = if (ticked) "tooltip.resourcepackprofiles.update_tick_on" else "tooltip.resourcepackprofiles.update_tick_off"
                    context.setTooltipForNextFrame(t(key, pending.currentVersion, pending.newVersion), mouseX, mouseY)
                }
                rightEdge = lx - 6
            }
            val label = when {
                missingFile -> missingLabel()
                builtIn -> builtInLabel() to Theme.TEXT_MUTED
                else -> null
            }
            if (label != null) {
                val lx = labelX(label.first)
                context.text(font, label.first, lx, centerY - 4, label.second, true)
                rightEdge = lx - 6
            }
            return rightEdge
        }

        override fun leftmostHoverIcon(rowHot: Boolean, showCheck: Boolean, showRemove: Boolean): Int? =
            if (versioned && (rowHot || findable)) versionsX() else super.leftmostHoverIcon(rowHot, showCheck, showRemove)

        override fun removeTooltip(): Component? = if (many) t("tooltip.resourcepackprofiles.remove_picked") else null
    }

    /** An installed pack that can be added to the selected profile: click it (or press Enter) to add it. */
    private inner class AddPackRow(packId: String) : PackRow(packId) {
        override val clickPicks get() = false
        override val checkable get() = false

        override fun onClick(event: MouseButtonEvent): Boolean {
            addPacks(targetsOf(packId))
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            if (!event.isConfirmation && !event.isSelection) return false
            addPacks(targetsOf(packId))
            return true
        }

        override fun extractTrailing(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, rowHot: Boolean): Int {
            val right = x + width - 4
            Icon.PLUS.draw(context, right - Icon.PLUS.width, centerY - Icon.PLUS.height / 2, if (hovered) Theme.TEXT else Theme.TEXT_MUTED)
            return right - Icon.PLUS.width - 6
        }
    }

    /**
     * A pack in the All Packs view: how many profiles use it, a Download chip when a newer version
     * is found but not downloaded yet, and x to move its file to the trash.
     */
    private inner class LibraryPackRow(packId: String) : PackRow(packId) {
        /** Only pack files can be deleted; built-in and mod-provided packs have no file. */
        override val removable get() = isPackFile(packId)

        /** A newer version whose file isn't in the resourcepacks folder yet. */
        private val downloadable: PackUpdate? get() = update?.takeIf { !isInstalled(it) }

        /** How the pack is used, or null for an unused built-in pack (it can't be cleaned up anyway). */
        private fun usageLabel(): Pair<Component, Int>? {
            val uses = packUsage[packId] ?: 0
            return when {
                uses == 1 -> t("label.resourcepackprofiles.used_by_one") to Theme.TEXT_MUTED
                uses > 1 -> t("label.resourcepackprofiles.used_by", uses) to Theme.TEXT_MUTED
                packId in enabledNow -> t("label.resourcepackprofiles.enabled_now") to Theme.TEXT_MUTED
                !isPackFile(packId) -> null
                else -> t("label.resourcepackprofiles.unused") to Theme.ACTIVE
            }
        }

        /** Left edge of the usage label, or the row's edge when there is none. */
        private fun usageX(): Int = usageLabel()?.let { labelX(it.first) } ?: (rowRight() + 8)

        // The usage label comes last and the Download chip sits to its left.
        private fun downloadWord(): Component = t("button.resourcepackprofiles.download")
        private fun downloadRight() = usageX() - 8
        private fun downloadX() = downloadRight() - font.width(downloadWord()) - 9
        private fun overDownload(mx: Double) = mx >= downloadX() - 1 && mx < downloadRight() + 1

        /** Updates of the picked packs (or just this one) whose files aren't downloaded yet. */
        private fun downloadTargets() =
            targetsOf(packId).mapNotNull { (checks[it] as? PackCheck.UpdateAvailable)?.update }.filter { !isInstalled(it) }

        override fun contentLeft(): Int = if (downloadable != null) downloadX() else usageX()

        override fun onClick(event: MouseButtonEvent): Boolean {
            val mx = event.x()
            when {
                removable && overRemove(mx) -> trashFromRow(targetsOf(packId).filter { isPackFile(it) })
                downloadable != null && overDownload(mx) -> jobs.downloadOnly(downloadTargets())
                else -> selectRow(this)
            }
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            val key = event.key()
            if (!removable || (key != InputConstants.KEY_DELETE && key != InputConstants.KEY_BACKSPACE)) return false
            trashFromRow(targetsOf(packId).filter { isPackFile(it) })
            return true
        }

        override fun extractTrailing(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, rowHot: Boolean): Int {
            var rightEdge = x + width - 4
            usageLabel()?.let { (usage, usageColor) ->
                val ux = labelX(usage)
                context.text(font, usage, ux, centerY - 4, usageColor, true)
                rightEdge = ux - 6
            }
            // Once the newer file is downloaded the row shows nothing extra: the chip going away is
            // the signal, and profiles pick the file up when they are updated.
            val pending = downloadable ?: return rightEdge
            val dx = downloadX()
            val dr = downloadRight()
            val chipY = centerY - CHIP_HEIGHT / 2
            val over = hovered && overDownload(mouseX.toDouble())
            context.fill(dx, chipY, dr, chipY + CHIP_HEIGHT, Theme.withAlpha(Theme.SUCCESS, if (over) 0.38f else 0.22f))
            context.text(font, downloadWord(), dx + 5, chipY + 2, Theme.SUCCESS, true)
            if (over) {
                val tip =
                    if (many) t("tooltip.resourcepackprofiles.download_picked")
                    else t("tooltip.resourcepackprofiles.download_update", pending.currentVersion, pending.newVersion)
                context.setTooltipForNextFrame(tip, mouseX, mouseY)
            }
            return dx - 6
        }

        override fun removeTooltip(): Component =
            if (many) t("tooltip.resourcepackprofiles.trash_picked") else t("tooltip.resourcepackprofiles.trash_pack")
    }

    private companion object {
        const val STATUS_MILLIS = 6000L
        /** Size of the profile icon in the header; the header's two rows are built around it. */
        const val HEADER_ICON = 40
        /** The name field hugs the name text: 8px glyphs plus a little room. */
        const val NAME_FIELD_HEIGHT = 13
        /**
         * Gap between a panel's header block and the list below it. Zero because the vanilla list
         * already pads the top of its contents by 4px; adding more doubled that gap.
         */
        const val HEADER_GAP = 0
        const val NAME_FIELD_WIDTH = 180

        /** Keys -> translation key suffix, in the order shown by the "?" button. */
        val SHORTCUTS = listOf(
            "Enter" to "apply",
            "F2" to "rename",
            "Ctrl+F" to "search",
            "Ctrl+D" to "duplicate",
            "Del" to "delete",
            "Ctrl+Z" to "undo",
            "Ctrl+U" to "check",
            "Space" to "toggle_pack",
            "Shift+Up/Down" to "move_pack",
            "Drag" to "drag_pack",
            "Ctrl+Click" to "pick_pack",
            "Shift+Click" to "pick_range",
            "Ctrl+A" to "pick_all",
            "Drop files" to "drop"
        )
        const val CHIP_HEIGHT = 12
        const val PACK_ROW_HEIGHT = 22
        /** Longest version label before it is cut off, so names keep some room. */
        const val UPDATE_LABEL_MAX = 90

        /** Kept for the session, so All Packs reopens in the order it was left in. */
        var librarySort = LibrarySort.UPDATES
        /** Width reserved for the versions hover icon, matching the other hover icons. */
        const val VERSIONS_SLOT = 9
        const val TOGGLE_SIZE = 9
        /** Tint that fades the icon of a pack that is switched off. */
        const val DISABLED_TINT = 0x66FFFFFF
        /** A legacy formatting code: the section sign followed by one character. */
        val FORMATTING_CODE = Regex("\u00A7.")
        val UNKNOWN_PACK: Identifier = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png")
    }
}
