package org.zephbyte.resourcepackprofiles.client.screen

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
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
import net.minecraft.server.packs.repository.PackCompatibility
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.RefreshablePackScreen
import org.zephbyte.resourcepackprofiles.client.profile.AutoApply
import org.zephbyte.resourcepackprofiles.client.profile.BackupChoice
import org.zephbyte.resourcepackprofiles.client.profile.ImportResult
import org.zephbyte.resourcepackprofiles.client.profile.PackIcons
import org.zephbyte.resourcepackprofiles.client.profile.PackTrash
import org.zephbyte.resourcepackprofiles.client.profile.ProfileIconManager
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.profile.ResourcePackProfile
import org.zephbyte.resourcepackprofiles.client.ui.FlatButton
import org.zephbyte.resourcepackprofiles.client.ui.Icon
import org.zephbyte.resourcepackprofiles.client.ui.Theme
import org.zephbyte.resourcepackprofiles.client.update.PackCheck
import org.zephbyte.resourcepackprofiles.client.update.PackUpdate
import org.zephbyte.resourcepackprofiles.client.update.UpdateChecker
import org.zephbyte.resourcepackprofiles.client.util.DynamicTextureCache
import org.zephbyte.resourcepackprofiles.client.util.FileDialogs
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import org.zephbyte.resourcepackprofiles.client.util.activeScreen
import org.zephbyte.resourcepackprofiles.client.util.setGuiScreen
import org.zephbyte.resourcepackprofiles.client.util.showScreen
import java.nio.file.Path
import kotlin.math.abs

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

    private class MenuItem(
        val label: Component,
        val danger: Boolean = false,
        /** Small right-aligned annotation, e.g. "current" in the version list. */
        val note: Component? = null,
        val muted: Boolean = false,
        /** Disabled items are shown but do nothing and keep the menu open. */
        val enabled: Boolean = true,
        val action: () -> Unit
    )

    /** What a row of the right-hand list represents. */
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

    private enum class RowMode {
        /** A pack in the selected profile's load order. */
        PROFILE,
        /** An installed pack that can be added to the selected profile. */
        ADD,
        /** An installed pack in the "All packs" view. */
        LIBRARY
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
    private val checks = SESSION_CHECKS
    /** Non-null while a background check or download runs; shown in place of the status line. */
    private var busy: Component? = null

    private var status: Component? = null
    private var statusColor = Theme.TEXT_SECONDARY
    private var statusUntil = 0L
    private var undo: (() -> Unit)? = null

    // Drag-to-reorder: the display-order ids being rearranged, committed on mouse release.
    private var dragIds: MutableList<String>? = null
    private var dragIndex = -1
    private var dragStartY = 0.0
    private var dragMoved = false

    /** Packs whose available update is ticked, i.e. included in "Update N Selected". None by default. */
    private val tickedUpdates = mutableSetOf<String>()
    /** Whether the first-run question was already shown by this screen. */
    private var firstRunShown = false

    private var menu: List<MenuItem>? = null
    private var menuX = 0
    private var menuY = 0
    private var menuWidth = 0
    private var menuScroll = 0
    /** Pack id whose version list the open menu shows, so a late result can't fill another menu. */
    private var menuOwner: String? = null

    // Recomputed once per frame and shared by every row.
    private var packsById: Map<String, Pack> = emptyMap()
    private var activeName: String? = null
    private var packUsage: Map<String, Int> = emptyMap()
    private var enabledNow: Set<String> = emptySet()

    private val packTextures = DynamicTextureCache("pack_icon")
    private val packsWithoutIcon = mutableSetOf<String>()

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
    private val searchW get() = leftW - 16 - 40
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
    private val filterW get() = (if (libraryMode) sortButton.x - 4 else rightX + rightW - 8) - filterX

    private fun t(key: String, vararg args: Any): Component = Component.translatable(key, *args)

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
        libraryDownloadButton = FlatButton(rightX + 8, footerY, 100, 16, t("button.resourcepackprofiles.download_updates", 0), style = FlatButton.Style.SUCCESS) { downloadOnly(libraryUpdates()) }
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
        menu = null
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
    private fun displayIds(): List<String> = dragIds ?: selected?.packIds?.reversed() ?: emptyList()

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
        if (menu != null) {
            menu = null
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
                userPacks()
                    .filter { query.isEmpty() || packName(it.id).lowercase().contains(query) }
                    .sortedWith(libraryComparator().thenBy { packName(it.id).lowercase() })
                    .map { PackRow(it.id, RowMode.LIBRARY) }
            }
            profile == null -> emptyList()
            addMode -> {
                val inProfile = profile.packIds.toSet()
                val query = packFilter.trim().lowercase()
                minecraft.resourcePackRepository.availablePacks
                    .filter { it.packSource.shouldAddAutomatically() && !it.isRequired && it.id !in inProfile }
                    .filter { query.isEmpty() || packName(it.id).lowercase().contains(query) }
                    .sortedBy { packName(it.id).lowercase() }
                    .map { PackRow(it.id, RowMode.ADD) }
            }
            else -> displayIds().map { PackRow(it, RowMode.PROFILE) }
        }
        packList.replaceEntries(rows)
        packList.setScrollAmount(scroll)
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
        // Don't clobber the name while it is being typed, unless the selection itself changed.
        if (profile != null && (forceName || !nameBox.isFocused)) nameBox.value = profile.name
        if (filterBox.value != packFilter) filterBox.value = packFilter
        addButton.setMessage(if (addMode) CommonComponents.GUI_DONE else t("button.resourcepackprofiles.add_packs"))
        rebuildPackList()
    }

    private fun setAddMode(enabled: Boolean) {
        addMode = enabled
        packFilter = ""
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
        Thread {
            val ok = ProfileManager.exportProfile(profile)
            Minecraft.getInstance().execute {
                if (ok) showStatus(t("status.resourcepackprofiles.exported", profile.name))
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun onImport() {
        Thread {
            val path = ProfileManager.pickProfileToImport() ?: return@Thread
            Minecraft.getInstance().execute { importProfiles(listOf(Path.of(path))) }
        }.also { it.isDaemon = true }.start()
    }

    private fun importProfiles(paths: List<Path>): Int {
        var imported = 0
        var last: String? = null
        for (path in paths) {
            val result = ProfileManager.importProfileFromPath(path, renameOnConflict = true)
            if (result is ImportResult.Imported) {
                imported++
                last = result.name
            }
        }
        if (last != null) {
            searchBox.value = ""
            select(last)
            rebuildProfileList()
        }
        return imported
    }

    private fun onChangeIcon() {
        val name = selectedName ?: return
        Thread {
            val path = FileDialogs.openFile(
                "Select Profile Icon",
                arrayOf("*.png", "*.jpg", "*.jpeg"),
                "Image Files (*.png, *.jpg)"
            ) ?: return@Thread
            Minecraft.getInstance().execute {
                try {
                    if (ProfileManager.hasProfile(name)) ProfileIconManager.importCustomIcon(name, Path.of(path))
                } catch (e: Exception) {
                    logger.error("Failed to set custom icon for '{}'", name, e)
                    showError(t("status.resourcepackprofiles.icon_failed"))
                }
            }
        }.also { it.isDaemon = true }.start()
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

    private fun addPack(packId: String) {
        val profile = selected ?: return
        // Appended last = loaded last = highest priority, i.e. the top of the displayed list.
        commitPacks(profile.packIds + packId, t("status.resourcepackprofiles.pack_added", packName(packId)))
    }

    private fun removePack(packId: String) {
        val profile = selected ?: return
        commitPacks(profile.packIds - packId, t("status.resourcepackprofiles.pack_removed", packName(packId)))
    }

    /** Turns a pack off or on inside the profile without changing its place in the order. */
    private fun togglePack(packId: String) {
        val profile = selected ?: return
        val disable = !profile.isDisabled(packId)
        ProfileManager.setPackDisabled(profile.name, packId, disable)
        undo = { ProfileManager.putProfile(profile) }
        val key = if (disable) "status.resourcepackprofiles.pack_disabled" else "status.resourcepackprofiles.pack_enabled"
        showStatus(t(key, packName(packId)))
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
            minecraft.resourcePackRepository.reload()
            undo = {
                trashed.forEach { PackTrash.restore(it) }
                minecraft.resourcePackRepository.reload()
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
        if (libraryMode) checkPacks(allCheckableIds()) else selected?.let { checkUpdates(listOf(it)) }
    }

    private fun onUpdateSelected() {
        val profile = selected ?: return
        val updates = selectedUpdates(profile)
        if (updates.isEmpty()) return
        val name = profile.name
        // A single pack can simply be switched back, so the backup question is for bulk updates.
        val choice = if (updates.size < 2) BackupChoice.NEVER else ProfileManager.backupOnUpdateAll()
        when (choice) {
            BackupChoice.ALWAYS -> applyUpdates(name, updates, backup = true)
            BackupChoice.NEVER -> applyUpdates(name, updates)
            BackupChoice.ASK -> minecraft.showScreen(
                BackupPromptScreen(this, name, updates.size) { backup -> applyUpdates(name, updates, backup) }
            )
        }
    }

    private fun checkUpdates(profiles: List<ResourcePackProfile>) =
        checkPacks(profiles.flatMap { it.packIds }.distinct())

    /** Pack files that can be looked up on Modrinth, across everything installed. */
    private fun allCheckableIds(): List<String> = userPacks().map { it.id }.filter { isPackFile(it) }

    /**
     * Checks [ids] against Modrinth in the background. With [single], the status line reports on
     * that one pack by name instead of giving totals. With [quiet] (the automatic check), it
     * only reports when updates were found and stays silent on failure.
     */
    private fun checkPacks(ids: List<String>, single: Boolean = false, quiet: Boolean = false) {
        if (busy != null || ids.isEmpty()) return
        busy = t("status.resourcepackprofiles.checking")
        Thread {
            val result = try {
                Result.success(UpdateChecker.check(ids))
            } catch (e: Exception) {
                logger.error("Update check failed", e)
                Result.failure(e)
            }
            Minecraft.getInstance().execute {
                busy = null
                result.onSuccess { report ->
                    checks.putAll(report.checks)
                    ProfileManager.recordSources(report.identities)
                    if (libraryMode) rebuildPackList()
                    if (single) {
                        val name = packName(ids.first())
                        when (report.checks[ids.first()]) {
                            is PackCheck.UpdateAvailable -> showStatus(t("status.resourcepackprofiles.pack_update_found", name), Theme.SUCCESS)
                            is PackCheck.UpToDate -> showStatus(t("status.resourcepackprofiles.pack_up_to_date", name), Theme.SUCCESS)
                            is PackCheck.NoCompatibleVersion -> showStatus(t("status.resourcepackprofiles.pack_no_version", name))
                            else -> showStatus(t("status.resourcepackprofiles.pack_not_on_modrinth", name))
                        }
                        return@onSuccess
                    }
                    val results = report.checks.values
                    val found = results.count { it is PackCheck.UpdateAvailable }
                    val current = results.count { it is PackCheck.UpToDate }
                    val noVersion = results.count { it is PackCheck.NoCompatibleVersion }
                    val unknown = results.count { it is PackCheck.NotOnModrinth || it is PackCheck.Unavailable }
                    val summary =
                        if (noVersion > 0) t("status.resourcepackprofiles.check_summary_no_version", found, current, unknown, noVersion)
                        else t("status.resourcepackprofiles.check_summary", found, current, unknown)
                    if (!quiet) showStatus(summary, if (found > 0) Theme.SUCCESS else Theme.TEXT_SECONDARY)
                    else if (found > 0) showStatus(t("status.resourcepackprofiles.updates_available", found), Theme.SUCCESS)
                }.onFailure {
                    if (!quiet) showError(t("status.resourcepackprofiles.updates_failed"))
                }
            }
        }.also { it.name = "RPP-UpdateCheck"; it.isDaemon = true }.start()
    }

    /** Whether the file an update would download is already in the resourcepacks folder. */
    private fun isInstalled(update: PackUpdate): Boolean = "file/${update.fileName}" in packsById

    /** Updates found for installed packs whose newer file has not been downloaded yet. */
    private fun libraryUpdates(): List<PackUpdate> =
        userPacks().mapNotNull { (checks[it.id] as? PackCheck.UpdateAvailable)?.update }.filter { !isInstalled(it) }

    /**
     * Downloads [updates] next to the existing files without touching any profile. Profiles keep
     * the version they have until they are updated, which then reuses these downloads.
     */
    private fun downloadOnly(updates: List<PackUpdate>) {
        if (busy != null || updates.isEmpty()) return
        busy = t("status.resourcepackprofiles.downloading", updates.size)
        Thread {
            val result = try {
                updates.forEach { UpdateChecker.download(it) }
                Result.success(updates.size)
            } catch (e: Exception) {
                logger.error("Failed to download pack updates", e)
                Result.failure(e)
            }
            Minecraft.getInstance().execute {
                busy = null
                minecraft.resourcePackRepository.reload()
                refreshPackCache()
                rebuildPackList()
                result.onSuccess { count ->
                    showStatus(t("status.resourcepackprofiles.updates_downloaded", count), Theme.SUCCESS)
                }.onFailure {
                    showError(t("status.resourcepackprofiles.download_failed"))
                }
            }
        }.also { it.name = "RPP-UpdateDownload"; it.isDaemon = true }.start()
    }

    /**
     * Downloads [updates] and switches [profileName] to them in place. Old pack files stay on
     * disk, so other profiles keep working and each pack can be switched back. With [backup], a
     * "(backup)" copy of the profile is saved first.
     */
    private fun applyUpdates(profileName: String, updates: List<PackUpdate>, backup: Boolean = false) {
        if (busy != null || updates.isEmpty()) return
        busy = t("status.resourcepackprofiles.downloading", updates.size)
        Thread {
            val result = try {
                Result.success(updates.associate { it.packId to UpdateChecker.download(it) })
            } catch (e: Exception) {
                logger.error("Failed to download pack updates", e)
                Result.failure(e)
            }
            Minecraft.getInstance().execute {
                busy = null
                result.onSuccess { replacements ->
                    val before = ProfileManager.getProfile(profileName) ?: return@onSuccess
                    val backupName = if (backup) ProfileManager.duplicateProfile(profileName, " (backup)") else null
                    minecraft.resourcePackRepository.reload()
                    val changed = replacements.filter { it.key != it.value }
                    val newSources = updates.mapNotNull { u -> replacements[u.packId]?.let { it to u.versionId } }.toMap()
                    ProfileManager.replacePackIds(profileName, changed, newSources, rememberPrevious = true, carrySources = false)
                    undo = {
                        ProfileManager.putProfile(before)
                        backupName?.let { ProfileManager.deleteProfile(it) }
                    }
                    // The updated packs have new ids now, so their ticks are spent.
                    tickedUpdates.removeAll(replacements.keys)
                    refreshPackCache()
                    rebuildProfileList()
                    rebuildPackList()
                    val only = updates.singleOrNull()
                    when {
                        only != null -> showStatus(t("status.resourcepackprofiles.pack_switched", only.projectTitle, only.newVersion), Theme.SUCCESS)
                        backupName != null -> showStatus(t("status.resourcepackprofiles.packs_updated_backup", updates.size, backupName), Theme.SUCCESS)
                        else -> showStatus(t("status.resourcepackprofiles.packs_updated", updates.size), Theme.SUCCESS)
                    }
                }.onFailure {
                    showError(t("status.resourcepackprofiles.download_failed"))
                }
            }
        }.also { it.name = "RPP-UpdateDownload"; it.isDaemon = true }.start()
    }

    /** Switches a pack back to the file it replaced in the selected profile. */
    private fun revertPack(current: String, previous: String) {
        val profile = selected ?: return
        ProfileManager.replacePackIds(profile.name, mapOf(current to previous), rememberPrevious = true, carrySources = false)
        undo = { ProfileManager.putProfile(profile) }
        refreshPackCache()
        rebuildPackList()
        showStatus(t("status.resourcepackprofiles.pack_reverted", packName(previous)))
    }

    /**
     * Opens the version list for a pack, right-aligned at ([right], [top]). The list appears
     * immediately with a loading line (and the switch-back entry, which needs no network) and is
     * filled in when Modrinth answers.
     */
    private fun openVersions(packId: String, right: Int, top: Int) {
        val profile = selected ?: return
        val base = mutableListOf<MenuItem>()
        val previous = profile.previousPacks?.get(packId)
        if (previous != null && previous in packsById) {
            base.add(MenuItem(t("menu.resourcepackprofiles.revert_to", packName(previous))) { revertPack(packId, previous) })
        }
        openMenuAt(base + MenuItem(t("menu.resourcepackprofiles.versions_loading"), muted = true, enabled = false) {}, right, top)
        menuOwner = packId

        val known = profile.packSources?.get(packId)
        Thread {
            val result = try {
                Result.success(UpdateChecker.listVersions(packId, known))
            } catch (e: Exception) {
                logger.error("Failed to list versions for {}", packId, e)
                Result.failure(e)
            }
            Minecraft.getInstance().execute {
                // The user may have closed this menu, or opened another, while we were waiting.
                if (menu == null || menuOwner != packId) return@execute
                val items = base.toMutableList()
                val choices = result.getOrNull()
                when {
                    result.isFailure ->
                        items.add(MenuItem(t("menu.resourcepackprofiles.versions_failed"), muted = true, enabled = false) {})
                    choices == null ->
                        items.add(MenuItem(t("menu.resourcepackprofiles.versions_unknown"), muted = true, enabled = false) {})
                    else -> for (choice in choices) {
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
                openMenuAt(items, right, top)
                menuOwner = packId
            }
        }.also { it.name = "RPP-Versions"; it.isDaemon = true }.start()
    }

    /** Downloads the selected profile's missing packs that Modrinth still has. */
    private fun downloadMissing() {
        val profile = selected ?: return
        val sources = fetchableMissing(profile)
        if (busy != null || sources.isEmpty()) return
        val name = profile.name
        busy = t("status.resourcepackprofiles.downloading_missing", sources.size)
        Thread {
            val result = try {
                val downloads = UpdateChecker.resolveMissing(sources)
                Result.success(downloads.associate { it.packId to UpdateChecker.download(it) })
            } catch (e: Exception) {
                logger.error("Failed to download missing packs", e)
                Result.failure(e)
            }
            Minecraft.getInstance().execute {
                busy = null
                result.onSuccess { replacements ->
                    minecraft.resourcePackRepository.reload()
                    // A file saved under a different name gets a different pack id.
                    val renamed = replacements.filter { it.key != it.value }
                    if (renamed.isNotEmpty()) ProfileManager.replacePackIds(name, renamed)
                    refreshPackCache()
                    rebuildPackList()
                    val unavailable = sources.size - replacements.size
                    if (unavailable == 0) showStatus(t("status.resourcepackprofiles.missing_downloaded", replacements.size), Theme.SUCCESS)
                    else showError(t("status.resourcepackprofiles.missing_partial", replacements.size, unavailable))
                }.onFailure {
                    showError(t("status.resourcepackprofiles.download_failed"))
                }
            }
        }.also { it.name = "RPP-MissingDownload"; it.isDaemon = true }.start()
    }

    // --- Overflow menu ---

    private fun toggleMenu() {
        if (menu != null) {
            menu = null
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
        if (menu != null) {
            menu = null
            return
        }
        val items = SHORTCUTS.map { (keys, action) ->
            MenuItem(t("shortcut.resourcepackprofiles.$action"), note = Component.literal(keys), enabled = false) {}
        }
        openMenu(items, helpButton)
    }

    private fun openSettings() {
        menu = null
        commitRename()
        val vanilla = parent
        val openVanilla: (() -> Unit)? = if (vanilla is RefreshablePackScreen) ({ openVanillaScreen(vanilla) }) else null
        minecraft.showScreen(SettingsScreen(this, openVanilla))
    }

    /** Shows [items] as a popup below [anchor], right-aligned to it. */
    private fun openMenu(items: List<MenuItem>, anchor: FlatButton) =
        openMenuAt(items, anchor.x + anchor.getWidth(), anchor.y + anchor.getHeight() + 2)

    /** Shows [items] as a popup whose top-right corner is at ([right], [top]), kept on screen. */
    private fun openMenuAt(items: List<MenuItem>, right: Int, top: Int) {
        menu = items
        menuOwner = null
        menuScroll = 0
        menuWidth = items.maxOf { font.width(it.label) + (it.note?.let { note -> font.width(note) + 12 } ?: 0) } + 16
        if (items.size > MENU_MAX_VISIBLE) menuWidth += 4
        menuX = (right - menuWidth).coerceAtLeast(pad)
        menuY = top.coerceAtMost(height - 4 - menuHeight(items)).coerceAtLeast(4)
    }

    private fun isOverMenu(mouseX: Int, mouseY: Int): Boolean {
        val items = menu ?: return false
        return mouseX >= menuX && mouseX < menuX + menuWidth && mouseY >= menuY && mouseY < menuY + menuHeight(items)
    }

    private fun menuHeight(items: List<MenuItem>) = minOf(items.size, MENU_MAX_VISIBLE) * MENU_ITEM_HEIGHT + 4

    /** Switches to the vanilla resource pack screen, bypassing replacement this once. */
    private fun openVanillaScreen(vanilla: RefreshablePackScreen) {
        commitRename()
        ProfileIconManager.cleanup()
        if (replacesVanilla) vanilla.rpp_discard()
        ProfileManager.allowVanillaScreenOnce()
        // A fresh instance, so it reflects any packs applied, installed or deleted here.
        minecraft.showScreen(vanilla.rpp_createRefreshedScreen())
    }

    private fun menuItemAt(mx: Double, my: Double): MenuItem? {
        val items = menu ?: return null
        if (mx < menuX || mx >= menuX + menuWidth) return null
        val row = ((my - menuY - 2) / MENU_ITEM_HEIGHT).toInt()
        if (my < menuY + 2 || row >= MENU_MAX_VISIBLE) return null
        return items.getOrNull(row + menuScroll)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val items = menu ?: return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        // An open menu captures the wheel, so the list behind it doesn't scroll away.
        val maxScroll = (items.size - MENU_MAX_VISIBLE).coerceAtLeast(0)
        menuScroll = (menuScroll - scrollY.toInt().coerceIn(-3, 3)).coerceIn(0, maxScroll)
        return true
    }

    // --- Input ---

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (menu != null) {
            // An open menu captures the click: run the item under the cursor, or just dismiss.
            val item = menuItemAt(event.x(), event.y())
            if (item != null && !item.enabled) return true
            menu = null
            menuOwner = null
            item?.action?.invoke()
            return true
        }
        // Clicking the large profile icon is a shortcut for "Change icon".
        if (selected != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            val ix = rightX + 8
            val iy = headerY
            if (event.x() >= ix && event.x() < ix + HEADER_ICON && event.y() >= iy && event.y() < iy + HEADER_ICON) {
                onChangeIcon()
                return true
            }
        }
        return super.mouseClicked(event, doubled)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val ids = dragIds ?: return super.mouseDragged(event, dx, dy)
        val my = event.y()
        if (!dragMoved && abs(my - dragStartY) < 3) return true
        dragMoved = true

        val rows = packList.children()
        if (rows.isEmpty() || dragIndex !in ids.indices) return true
        var target = dragIndex
        when {
            my < rows.first().y -> target = 0
            my >= rows.last().y + rows.last().height -> target = rows.lastIndex
            else -> rows.forEachIndexed { i, row -> if (my >= row.y && my < row.y + row.height) target = i }
        }
        if (target != dragIndex) {
            val id = ids.removeAt(dragIndex)
            ids.add(target, id)
            dragIndex = target
            rebuildPackList()
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val ids = dragIds ?: return super.mouseReleased(event)
        val moved = dragMoved
        val index = dragIndex
        dragIds = null
        dragIndex = -1
        dragMoved = false
        if (moved) {
            commitPacks(ids.reversed(), t("status.resourcepackprofiles.reordered"))
            rebuildPackList()
            packList.children().getOrNull(index)?.let { packList.setSelected(it) }
        }
        // Still let the screen clear its own drag state.
        super.mouseReleased(event)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (menu != null && event.isEscape) {
            menu = null
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
                InputConstants.KEY_Z -> if (!typing) {
                    runUndo()
                    return true
                }
                InputConstants.KEY_U -> if (!typing) {
                    if (libraryMode) checkPacks(allCheckableIds()) else selected?.let { checkUpdates(listOf(it)) }
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
            minecraft.resourcePackRepository.reload()
            refreshPackCache()
            rebuildPackList()
        }
        val imported = importProfiles(profiles)

        when {
            imported == 0 && installed == 0 -> showError(t("status.resourcepackprofiles.drop_nothing"))
            else -> showStatus(t("status.resourcepackprofiles.dropped", imported, installed), Theme.SUCCESS)
        }
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

    private fun packIcon(packId: String): Identifier {
        if (packId in packsWithoutIcon) return UNKNOWN_PACK
        val id = packTextures.getOrRegister(packId, UNKNOWN_PACK) { PackIcons.read(packId) }
        // A miss is not cached by the texture cache; remember it so the zip isn't reopened each frame.
        if (id == UNKNOWN_PACK) packsWithoutIcon.add(packId)
        return id
    }

    /** Per-frame work: caches shared by the rows, blur-commit of the name field, button state. */
    private fun updateFrameState() {
        refreshPackCache()
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
            updateAllButton.active = busy == null
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

            val fetchable = fetchableMissing(profile).size
            missingButton.visible = !addMode && fetchable > 0
            missingButton.active = busy == null
            if (fetchable > 0) {
                val missingLabel = t("button.resourcepackprofiles.download_missing", fetchable)
                missingButton.setMessage(missingLabel)
                missingButton.setWidth(font.width(missingLabel) + 16)
                missingButton.setX(footerRight + 4)
            }
        }
        refreshButton.active = busy == null
        if (libraryMode) {
            val unused = unusedPackIds().size
            val unusedLabel = t("button.resourcepackprofiles.delete_unused", unused)
            unusedButton.visible = unused > 0
            unusedButton.setMessage(unusedLabel)
            unusedButton.setWidth(font.width(unusedLabel) + 16)
            unusedButton.setX(rightX + rightW - 8 - unusedButton.getWidth())

            val pending = libraryUpdates().size
            libraryDownloadButton.visible = pending > 0
            libraryDownloadButton.active = busy == null
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
        val footerInUse = listOf(selectAllButton, updateAllButton, missingButton, trashButton, libraryDownloadButton).any { it.visible }
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
        val blocked = isOverMenu(mouseX, mouseY)
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

        extractBottomBar(context)
        extractMenu(context, mouseX, mouseY)
    }

    private fun extractDetail(context: GuiGraphicsExtractor, profile: ResourcePackProfile, mouseX: Int, mouseY: Int) {
        val iconX = rightX + 8
        val iconY = headerY
        context.blit(RenderPipelines.GUI_TEXTURED, ProfileIconManager.getIconId(profile), iconX, iconY, 0f, 0f, HEADER_ICON, HEADER_ICON, HEADER_ICON, HEADER_ICON)
        // The icon is clickable (it opens the image picker), so say so on hover: dim it, outline
        // it and show a folder. Not while a menu is open, since the menu would take the click.
        if (menu == null && mouseX >= iconX && mouseX < iconX + HEADER_ICON && mouseY >= iconY && mouseY < iconY + HEADER_ICON) {
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

    private fun extractBottomBar(context: GuiGraphicsExtractor) {
        val right = (if (undoButton.visible) undoButton.x else doneButton.x) - 6
        val now = System.currentTimeMillis()
        val busyText = busy
        val statusText = status
        when {
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

    /**
     * A status message on its own small panel, in line with the Undo/Done buttons. It sits over
     * the world or menu background, so bare text would be unreadable on bright scenery.
     */
    private fun statusPill(context: GuiGraphicsExtractor, text: Component, color: Int, alpha: Float, right: Int) {
        val padX = 6
        val maxText = right - pad - padX * 2
        if (maxText <= 0) return
        val shown = ScreenUtils.truncate(font, text.string, maxText)
        val x = pad
        val y = height - 24
        val w = font.width(shown) - 1 + padX * 2
        val h = 18
        context.fill(x, y, x + w, y + h, Theme.withAlpha(Theme.RAISED_FILL, alpha))
        context.text(font, shown, x + padX, y + (h - 7) / 2, Theme.withAlpha(color, alpha), true)
    }

    private fun extractMenu(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val items = menu ?: return
        // A new stratum guarantees the menu draws above the widgets it overlaps.
        context.nextStratum()
        val menuHeight = menuHeight(items)
        Theme.raised(context, menuX, menuY, menuWidth, menuHeight)

        val scrollable = items.size > MENU_MAX_VISIBLE
        val contentRight = menuX + menuWidth - (if (scrollable) 5 else 1)
        val hovered = menuItemAt(mouseX.toDouble(), mouseY.toDouble())
        val visible = items.drop(menuScroll).take(MENU_MAX_VISIBLE)
        visible.forEachIndexed { i, item ->
            val iy = menuY + 2 + i * MENU_ITEM_HEIGHT
            if (item === hovered && item.enabled) context.fill(menuX + 1, iy, contentRight, iy + MENU_ITEM_HEIGHT, Theme.CONTROL_HOVER)
            val color = when {
                item.danger -> Theme.DANGER
                item.muted -> Theme.TEXT_MUTED
                else -> Theme.TEXT
            }
            context.text(font, item.label, menuX + 8, iy + 3, color, true)
            item.note?.let { context.text(font, it, contentRight - 7 - font.width(it), iy + 3, Theme.TEXT_MUTED, true) }
        }
        if (scrollable) {
            // Thin scroll indicator: track plus a thumb sized to the visible fraction.
            val trackTop = menuY + 2
            val trackHeight = menuHeight - 4
            val thumbHeight = (trackHeight * MENU_MAX_VISIBLE / items.size).coerceAtLeast(6)
            val maxScroll = items.size - MENU_MAX_VISIBLE
            val thumbTop = trackTop + (trackHeight - thumbHeight) * menuScroll / maxScroll
            context.fill(menuX + menuWidth - 4, trackTop, menuX + menuWidth - 2, trackTop + trackHeight, Theme.DIVIDER)
            context.fill(menuX + menuWidth - 4, thumbTop, menuX + menuWidth - 2, thumbTop + thumbHeight, Theme.TEXT_SECONDARY)
        }
    }

    override fun tick() {
        super.tick()
        val setting = ProfileManager.autoCheckSetting()
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
                    onCancel = { ProfileManager.setAutoCheckUpdates(false) }
                ) { ProfileManager.setAutoCheckUpdates(true) })
            }
            return
        }
        if (setting && !sessionAutoChecked && busy == null) {
            sessionAutoChecked = true
            checkPacks(allCheckableIds(), quiet = true)
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

    /** Vanilla selection list (scrolling, keyboard navigation, narration) with flat chrome. */
    private abstract inner class FlatList<E : ObjectSelectionList.Entry<E>>(w: Int, h: Int, top: Int, itemHeight: Int) :
        ObjectSelectionList<E>(minecraft, w, h, top, itemHeight) {

        override fun getRowWidth(): Int = width - 10

        override fun scrollBarX(): Int = x + width - scrollbarWidth()

        // Vanilla pads only the top of the contents (4px). Pad the end by the same amount, so a
        // list scrolled to the bottom ends with the same space it starts with.
        override fun contentHeight(): Int = super.contentHeight() + 4

        // Thin flat scrollbar in place of the vanilla sprites, matching the popups. The vanilla
        // hit area (its full width) is unchanged, so dragging and clicking the track still work.
        override fun extractScrollbar(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
            // Still run the original, but clipped to an empty area so its sprites don't show.
            // Other mods hook this method (Smooth Scrolling animates the wheel from it), so
            // skipping it broke wheel scrolling for anyone with such a mod installed.
            context.enableScissor(0, 0, 0, 0)
            try {
                super.extractScrollbar(context, mouseX, mouseY)
            } finally {
                context.disableScissor()
            }
            if (!scrollable()) return
            val barWidth = 2
            val barX = scrollBarX() + scrollbarWidth() - barWidth - 1
            // Track inset by the same 2px at the top and bottom, with the thumb mapped into it.
            val inset = 2
            val trackTop = y + inset
            val trackHeight = height - inset * 2
            context.fill(barX, trackTop, barX + barWidth, trackTop + trackHeight, Theme.DIVIDER)
            val hot = isOverScrollbar(mouseX.toDouble(), mouseY.toDouble())
            val thumbHeight = scrollerHeight().coerceAtMost(trackHeight)
            val travel = (height - scrollerHeight()).coerceAtLeast(1)
            val thumbTop = trackTop + (scrollBarY() - y) * (trackHeight - thumbHeight) / travel
            context.fill(barX, thumbTop, barX + barWidth, thumbTop + thumbHeight, if (hot) Theme.TEXT else Theme.TEXT_SECONDARY)
        }

        // The panel behind the list is the background; rows draw their own highlight.
        override fun extractListBackground(context: GuiGraphicsExtractor) {}
        override fun extractListSeparators(context: GuiGraphicsExtractor) {}
        override fun extractSelection(context: GuiGraphicsExtractor, entry: E, color: Int) {}

        // Give the selected entry first crack at the key, then fall back to list navigation.
        override fun keyPressed(event: KeyEvent): Boolean {
            val sel = selected
            if (sel != null && sel.keyPressed(event)) return true
            return super.keyPressed(event)
        }
    }

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
            menu = null
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

    private inner class PackList(w: Int, h: Int, top: Int) : FlatList<PackRow>(w, h, top, 22)

    /**
     * One pack in the right-hand list. What it shows and does depends on [mode]:
     * - [RowMode.PROFILE]: drag to reorder, tick box to switch it off, x to remove, a status label
     *   (missing, or made for another game version), and a version button: "Update" when a newer
     *   version is known, with a chevron that opens the list of all versions.
     * - [RowMode.ADD]: click to add the pack to the profile.
     * - [RowMode.LIBRARY]: shows how many profiles use the pack; x moves its file to the trash.
     *
     * Profile and library rows of pack files also get a check-for-updates button on hover.
     */
    private inner class PackRow(val packId: String, private val mode: RowMode) : ObjectSelectionList.Entry<PackRow>() {

        override fun getNarration(): Component = Component.literal(packName(packId))

        private val index get() = packList.children().indexOf(this)
        private val update get() = if (mode == RowMode.ADD) null else (checks[packId] as? PackCheck.UpdateAvailable)?.update

        // Right-hand side: the row's own content (the update version and tick, a status or usage
        // label, the Download button) sits against the row's right edge. The hover icons (versions,
        // check for updates, remove) appear just left of that content on the hovered row, so no
        // row reserves empty space for them.
        private fun rowRight() = x + width - 8
        private fun toggleX() = x + 13

        /** Left edge of the right-hand content, or the row's edge when there is none. */
        private fun contentLeft(): Int {
            val pending = update
            return when (mode) {
                RowMode.PROFILE -> when {
                    versioned && pending != null -> updateTextX(pending)
                    packId !in packsById -> labelX(t("label.resourcepackprofiles.pack_missing"))
                    else -> rowRight() + 8
                }
                RowMode.LIBRARY -> if (pending != null && !isInstalled(pending)) downloadX() else labelX(usageLabel().first)
                RowMode.ADD -> rowRight() + 8
            }
        }
        private fun removeX() = contentLeft() - 10 - Icon.CLOSE.width
        private fun checkX() = removeX() - 9 - Icon.REFRESH.width
        private fun versionsX() = checkX() - 9 - VERSIONS_SLOT

        private fun overRemove(mx: Double) = mx >= removeX() - 5 && mx < removeX() + Icon.CLOSE.width + 4
        private fun overCheck(mx: Double) = mx >= checkX() - 4 && mx < removeX() - 5
        private fun overVersions(mx: Double) = mx >= versionsX() - 4 && mx < checkX() - 4

        /** Only pack files can be deleted; built-in and mod-provided packs have no file. */
        private val deletable get() = mode == RowMode.LIBRARY && isPackFile(packId)

        /** Only installed pack files can be looked up on Modrinth. */
        private val checkable get() = mode != RowMode.ADD && isPackFile(packId) && packId in packsById

        /** Whether this row has the version button (profile rows of installed pack files). */
        private val versioned get() = mode == RowMode.PROFILE && checkable

        // A row with an update shows the new version and a tick box at the row's right edge.
        // Ticked packs are the ones "Update N Selected" will update.
        private fun tickX() = rowRight() - TOGGLE_SIZE
        private fun updateText(pending: PackUpdate): String =
            ScreenUtils.truncate(font, pending.newVersion, UPDATE_LABEL_MAX)
        private fun updateTextX(pending: PackUpdate) = tickX() - 5 - font.width(updateText(pending))
        private fun overUpdateTick(mx: Double, pending: PackUpdate) = mx >= updateTextX(pending) - 2 && mx < rowRight() + 3

        /** Left edge of a label right-aligned at the row's edge. */
        private fun labelX(label: Component) = rowRight() - font.width(label)

        /** Why the pack is flagged for a different game version, or null if it is compatible. */
        private fun compatibilityWarning(): Component? =
            packsById[packId]?.compatibility?.takeIf { !it.isCompatible }?.description

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

        private fun usageLabel(): Pair<Component, Int> {
            val uses = packUsage[packId] ?: 0
            return when {
                uses == 1 -> t("label.resourcepackprofiles.used_by_one") to Theme.TEXT_MUTED
                uses > 1 -> t("label.resourcepackprofiles.used_by", uses) to Theme.TEXT_MUTED
                packId in enabledNow -> t("label.resourcepackprofiles.enabled_now") to Theme.TEXT_MUTED
                !isPackFile(packId) -> t("label.resourcepackprofiles.built_in") to Theme.TEXT_MUTED
                else -> t("label.resourcepackprofiles.unused") to Theme.ACTIVE
            }
        }

        // In the library the usage label comes first and the download button sits to its left.
        private fun downloadWord(): Component = t("button.resourcepackprofiles.download")
        private fun downloadRight() = labelX(usageLabel().first) - 8
        private fun downloadX() = downloadRight() - font.width(downloadWord()) - 9

        override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
            val mx = event.x()
            if (mode == RowMode.ADD) {
                addPack(packId)
                return true
            }
            if (checkable && overCheck(mx)) {
                checkPacks(listOf(packId), single = true)
                return true
            }
            val pending = update
            if (mode == RowMode.LIBRARY) {
                when {
                    deletable && overRemove(mx) -> trashPacks(listOf(packId))
                    pending != null && !isInstalled(pending) && mx >= downloadX() - 1 && mx < downloadRight() + 1 ->
                        downloadOnly(listOf(pending))
                    else -> packList.setSelected(this)
                }
                return true
            }

            if (overRemove(mx)) {
                removePack(packId)
                return true
            }
            if (mx >= toggleX() - 2 && mx < toggleX() + TOGGLE_SIZE + 2) {
                togglePack(packId)
                return true
            }
            if (versioned && overVersions(mx)) {
                openVersions(packId, versionsX() + VERSIONS_SLOT, y + height / 2 + 8)
                return true
            }
            if (versioned && pending != null && overUpdateTick(mx, pending)) {
                if (!tickedUpdates.remove(packId)) tickedUpdates.add(packId)
                return true
            }
            packList.setSelected(this)
            // Arm a drag; it only becomes a reorder once the mouse actually moves.
            dragIds = displayIds().toMutableList()
            dragIndex = index
            dragStartY = event.y()
            dragMoved = false
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            val key = event.key()
            val isDelete = key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE
            when (mode) {
                RowMode.ADD -> {
                    if (event.isConfirmation || event.isSelection) {
                        addPack(packId)
                        return true
                    }
                    return false
                }
                RowMode.LIBRARY -> {
                    if (isDelete && deletable) {
                        trashPacks(listOf(packId))
                        return true
                    }
                    return false
                }
                RowMode.PROFILE -> {}
            }
            if (isDelete) {
                removePack(packId)
                return true
            }
            // Shift+Up/Down reorders the focused pack (plain Up/Down navigates the list)
            if (event.hasShiftDown() && event.isUp) return movePack(index, -1)
            if (event.hasShiftDown() && event.isDown) return movePack(index, 1)
            // Space or Enter switches the focused pack off and on.
            if (event.isSelection) {
                togglePack(packId)
                return true
            }
            return false
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val inProfile = mode == RowMode.PROFILE
            val dragging = inProfile && dragMoved && dragIndex == index
            val isSelected = mode != RowMode.ADD && packList.selected === this
            // The row whose version list is open counts as hovered, so it keeps its highlight and
            // hover icons while the pointer is over the list.
            val menuAnchor = menu != null && menuOwner == packId
            val rowHot = hovered || menuAnchor
            when {
                dragging -> context.fill(x, y, x + width, y + height, Theme.ROW_SELECTED)
                rowHot || isSelected -> context.fill(x, y, x + width, y + height, Theme.ROW_HOVER)
            }

            val centerY = y + height / 2
            val mx = mouseX.toDouble()
            val disabled = inProfile && selected?.isDisabled(packId) == true
            var cursor = x + 5
            if (inProfile) {
                Icon.GRIP.draw(context, cursor, centerY - Icon.GRIP.height / 2, if (rowHot || dragging) Theme.TEXT else Theme.TEXT_MUTED)

                // Tick box: filled when the pack is switched on in this profile. Neutral on purpose,
                // so it can't be mistaken for the green update tick on the right.
                val tx = toggleX()
                val ty = centerY - TOGGLE_SIZE / 2
                val overToggle = hovered && mouseX >= tx - 2 && mouseX < tx + TOGGLE_SIZE + 2
                // Always drawn: hiding it until hover made the feature too hard to discover.
                Theme.border(context, tx, ty, TOGGLE_SIZE, TOGGLE_SIZE, if (overToggle) Theme.TEXT else Theme.CONTROL_BORDER)
                if (!disabled) {
                    Icon.TICK.drawCentered(context, tx, ty, TOGGLE_SIZE, TOGGLE_SIZE, if (overToggle) Theme.TEXT else Theme.TEXT_SECONDARY)
                }
                if (overToggle) {
                    val key = if (disabled) "tooltip.resourcepackprofiles.enable_pack" else "tooltip.resourcepackprofiles.disable_pack"
                    context.setTooltipForNextFrame(t(key), mouseX, mouseY)
                }
                cursor = tx + TOGGLE_SIZE + 5
            }
            if (disabled) {
                context.blit(RenderPipelines.GUI_TEXTURED, packIcon(packId), cursor, centerY - 8, 0f, 0f, 16, 16, 16, 16, DISABLED_TINT)
            } else {
                context.blit(RenderPipelines.GUI_TEXTURED, packIcon(packId), cursor, centerY - 8, 0f, 0f, 16, 16, 16, 16)
            }
            cursor += 16 + 5

            val missing = inProfile && packId !in packsById
            val compat = compatibilityWarning()
            val pending = update
            var rightEdge = x + width - 4
            var nameColor = when {
                missing -> Theme.DANGER
                disabled -> Theme.TEXT_MUTED
                else -> Theme.TEXT
            }

            // Hover buttons shared by profile and library rows.
            val showRemove = rowHot && (inProfile || deletable)
            val showCheck = rowHot && checkable
            val hotRemove = showRemove && overRemove(mx)
            val hotCheck = showCheck && overCheck(mx)
            if (showRemove) {
                Icon.CLOSE.draw(context, removeX(), centerY - Icon.CLOSE.height / 2, if (hotRemove) Theme.DANGER else Theme.TEXT_SECONDARY)
                if (hotRemove && !inProfile) context.setTooltipForNextFrame(t("tooltip.resourcepackprofiles.trash_pack"), mouseX, mouseY)
            }
            if (showCheck) {
                Icon.REFRESH.draw(context, checkX(), centerY - Icon.REFRESH.height / 2, if (hotCheck) Theme.TEXT else Theme.TEXT_SECONDARY)
                if (hotCheck) {
                    val tip = checkResult() ?: t("tooltip.resourcepackprofiles.check_pack")
                    context.setTooltipForNextFrame(tip, mouseX, mouseY)
                }
            }
            val overButtons = hotRemove || hotCheck
            val chipY = centerY - CHIP_HEIGHT / 2

            when (mode) {
                RowMode.ADD -> {
                    Icon.PLUS.draw(context, rightEdge - Icon.PLUS.width, centerY - Icon.PLUS.height / 2, if (hovered) Theme.TEXT else Theme.TEXT_MUTED)
                    rightEdge -= Icon.PLUS.width + 6
                }
                RowMode.LIBRARY -> {
                    val (usage, usageColor) = usageLabel()
                    val ux = labelX(usage)
                    context.text(font, usage, ux, centerY - 4, usageColor, true)
                    rightEdge = ux - 6

                    if (pending != null) {
                        // Once the newer file is downloaded the row shows nothing extra: the Download
                        // button going away is the signal, and profiles pick the file up when updated.
                        if (!isInstalled(pending)) {
                            val dx = downloadX()
                            val dr = downloadRight()
                            val over = hovered && mouseX >= dx - 1 && mouseX < dr + 1
                            context.fill(dx, chipY, dr, chipY + CHIP_HEIGHT, Theme.withAlpha(Theme.SUCCESS, if (over) 0.38f else 0.22f))
                            context.text(font, downloadWord(), dx + 5, chipY + 2, Theme.SUCCESS, true)
                            if (over) {
                                val tip = t("tooltip.resourcepackprofiles.download_update", pending.currentVersion, pending.newVersion)
                                context.setTooltipForNextFrame(tip, mouseX, mouseY)
                            }
                            rightEdge = dx - 6
                        }
                    }
                }
                RowMode.PROFILE -> {
                    if (versioned && rowHot) {
                        // The version list (any version, or switch back) is a hover icon. It stays
                        // lit while its list is open.
                        val hot = overVersions(mx) || menuAnchor
                        Icon.CHEVRON.draw(
                            context,
                            versionsX() + (VERSIONS_SLOT - Icon.CHEVRON.width) / 2,
                            centerY - Icon.CHEVRON.height / 2,
                            if (hot) Theme.TEXT else Theme.TEXT_SECONDARY
                        )
                        if (hot && !menuAnchor) context.setTooltipForNextFrame(t("tooltip.resourcepackprofiles.choose_version"), mouseX, mouseY)
                    }
                    if (versioned && pending != null) {
                        val ticked = packId in tickedUpdates
                        val tx = tickX()
                        val ty = centerY - TOGGLE_SIZE / 2
                        val lx = updateTextX(pending)
                        val over = hovered && overUpdateTick(mx, pending)
                        // Green whether ticked or not: an unticked update is still an available one.
                        context.text(font, updateText(pending), lx, centerY - 4, Theme.SUCCESS, true)
                        val boxColor = if (over) Theme.TEXT else Theme.SUCCESS
                        Theme.border(context, tx, ty, TOGGLE_SIZE, TOGGLE_SIZE, boxColor)
                        if (ticked) context.fill(tx + 2, ty + 2, tx + TOGGLE_SIZE - 2, ty + TOGGLE_SIZE - 2, Theme.SUCCESS)
                        if (over) {
                            val key = if (ticked) "tooltip.resourcepackprofiles.update_tick_on" else "tooltip.resourcepackprofiles.update_tick_off"
                            context.setTooltipForNextFrame(t(key, pending.currentVersion, pending.newVersion), mouseX, mouseY)
                        }
                        rightEdge = lx - 6
                    }
                    if (missing) {
                        val text = t("label.resourcepackprofiles.pack_missing")
                        val lx = labelX(text)
                        context.text(font, text, lx, centerY - 4, Theme.DANGER, true)
                        rightEdge = lx - 6
                    }
                }
            }

            rightEdge = minOf(rightEdge, contentLeft() - 6)
            if (rowHot) {
                val leftmostIcon = when {
                    versioned -> versionsX()
                    showCheck -> checkX()
                    showRemove -> removeX()
                    else -> null
                }
                if (leftmostIcon != null) rightEdge = minOf(rightEdge, leftmostIcon - 6)
            }

            // Outdated packs are shown like vanilla does it: the name is coloured, nothing is added
            // to the row, and hovering the name gives Minecraft's own explanation. (A warning icon
            // was tried; with most packs flagged it only made the lists busier.)
            val outdated = compat != null && !missing
            if (outdated && !disabled) nameColor = Theme.WARNING
            val name = ScreenUtils.truncate(font, packName(packId), rightEdge - cursor)
            context.text(font, name, cursor, centerY - 4, nameColor, true)
            if (compat != null && outdated && hovered && mouseX >= cursor && mouseX < cursor + font.width(name)) {
                context.setTooltipForNextFrame(compat, mouseX, mouseY)
            }
        }
    }

    private companion object {
        const val STATUS_MILLIS = 6000L
        const val MENU_ITEM_HEIGHT = 14
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
        const val MENU_MAX_VISIBLE = 12

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
            "Drop files" to "drop"
        )
        const val CHIP_HEIGHT = 12
        /** Longest version label before it is cut off, so names keep some room. */
        const val UPDATE_LABEL_MAX = 90

        // Shared by every open of the screen during one game session.
        val SESSION_CHECKS = mutableMapOf<String, PackCheck>()
        var sessionAutoChecked = false
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
