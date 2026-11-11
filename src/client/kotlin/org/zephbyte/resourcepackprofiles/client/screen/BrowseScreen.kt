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
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.ui.FlatButton
import org.zephbyte.resourcepackprofiles.client.ui.FlatCheckbox
import org.zephbyte.resourcepackprofiles.client.ui.FlatSelectionList
import org.zephbyte.resourcepackprofiles.client.ui.Icon
import org.zephbyte.resourcepackprofiles.client.ui.StatusPill
import org.zephbyte.resourcepackprofiles.client.ui.Theme
import org.zephbyte.resourcepackprofiles.client.update.DownloadProgress
import org.zephbyte.resourcepackprofiles.client.update.ModrinthApi
import org.zephbyte.resourcepackprofiles.client.update.PackIdentity
import org.zephbyte.resourcepackprofiles.client.update.PackUpdate
import org.zephbyte.resourcepackprofiles.client.update.ProjectHit
import org.zephbyte.resourcepackprofiles.client.update.UpdateChecker
import org.zephbyte.resourcepackprofiles.client.update.VersionChoice
import org.zephbyte.resourcepackprofiles.client.util.RemoteImages
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import org.zephbyte.resourcepackprofiles.client.util.openInBrowser
import org.zephbyte.resourcepackprofiles.client.util.showScreen
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Browse and install resource packs from Modrinth: filters on the left, search results in the
 * middle, the selected pack's details and versions on the right. In a narrow window the details
 * replace the results instead of sitting beside them.
 *
 * @param profileName the profile the browser was opened from, if any. Installs can be added to it.
 */
class BrowseScreen(
    private val parent: Screen,
    private val profileName: String?
) : Screen(Component.translatable("screen.resourcepackprofiles.browse.title")) {

    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")

    // --- Search state (kept in fields: init() reruns on resize and after dialogs) ---
    private var query = ""
    private var sortIndex = 0
    private var compatibleOnly = true
    private val categories = linkedSetOf<String>()
    private val results = mutableListOf<ProjectHit>()
    private var totalHits = 0
    private var loading = false
    private var searchFailed = false
    /** Bumped by every new search, so answers to an older one are ignored. */
    private var generation = 0
    /** When typing pauses, the search runs; 0 means none pending. */
    private var searchDueAt = 0L
    private var started = false

    // --- Selected pack ---
    private var selectedHit: ProjectHit? = null
    private var versions: List<VersionChoice>? = null
    private var versionsFailed = false
    private var chosen: VersionChoice? = null
    private var detailGeneration = 0

    // --- Installing ---
    private var busy: Component? = null
    @Volatile private var progress: DownloadProgress? = null
    private var status: Component? = null
    private var statusColor = Theme.TEXT_SECONDARY
    private var statusUntil = 0L
    private var installedIds: Set<String> = emptySet()
    private var installedProjects: Set<String> = emptySet()
    /** Installed Modrinth version id -> the installed pack id of that file. */
    private var installedVersions: Map<String, String> = emptyMap()

    private val icons = RemoteImages("browse_icon", 48)
    /** Set while one of our dialogs is open, so leaving for it doesn't drop the loaded icons. */
    private var openingChild = false

    // --- Widgets ---
    private lateinit var searchBox: EditBox
    private lateinit var sortButton: FlatButton
    private lateinit var filterList: FilterList
    private lateinit var resultList: ResultList
    private lateinit var versionList: VersionList
    private lateinit var primaryButton: FlatButton
    private lateinit var secondaryButton: FlatButton
    private lateinit var pageButton: FlatButton
    private lateinit var backButton: FlatButton
    private lateinit var doneButton: FlatButton

    // --- Layout ---
    private val pad = 8
    private val panelTop = 24
    private val panelBottom get() = height - 30
    private val contentX get() = pad + SIDEBAR_WIDTH + 6
    private val contentW get() = width - pad - contentX
    /** Too narrow for results and details side by side. */
    private val narrow get() = contentW < 420
    private val resultsW get() = if (narrow) contentW else contentW * 55 / 100
    private val detailX get() = if (narrow) contentX else contentX + resultsW + 6
    private val detailW get() = if (narrow) contentW else contentW - resultsW - 6
    private val showResults get() = !narrow || selectedHit == null
    private val showDetail get() = selectedHit != null

    // Header blocks: sidebar (sort + filter toggle), results (search), details (icon row + actions).
    private val sidebarHeaderBottom get() = panelTop + 8 + 16 + 6 + 11 + 8
    private val resultsHeaderBottom get() = panelTop + 8 + 16 + 8
    private val detailHeaderY get() = panelTop + 8
    private val actionRowY get() = detailHeaderY + DETAIL_ICON + 8
    private val detailHeaderBottom get() = actionRowY + 16 + 8
    private val summaryY get() = detailHeaderBottom + 6
    private val versionsY get() = summaryY + SUMMARY_LINES * 10 + 6

    private fun t(key: String, vararg args: Any): Component = Component.translatable(key, *args)

    override fun init() {
        openingChild = false
        refreshInstalled()

        // Results column header: search
        searchBox = EditBox(font, contentX + 8 + 15, panelTop + 12, resultsW - 16 - 19, 10, t("field.resourcepackprofiles.browse_search"))
        searchBox.setBordered(false)
        searchBox.setMaxLength(80)
        searchBox.setHint(t("field.resourcepackprofiles.browse_search"))
        searchBox.value = query
        addRenderableWidget(searchBox)

        // Sidebar: sort, compatibility, then the category toggles
        sortButton = FlatButton(pad + 8, panelTop + 8, SIDEBAR_WIDTH - 16, 16, sortLabel()) {
            sortIndex = (sortIndex + 1) % SORTS.size
            it.setMessage(sortLabel())
            runSearch()
        }
        addRenderableWidget(sortButton)
        addRenderableWidget(FlatCheckbox(
            pad + 8, panelTop + 8 + 16 + 6, font,
            t("label.resourcepackprofiles.browse_compatible", UpdateChecker.gameVersion()), compatibleOnly
        ) {
            compatibleOnly = it
            runSearch()
        })
        filterList = FilterList(SIDEBAR_WIDTH, panelBottom - sidebarHeaderBottom, sidebarHeaderBottom)
        filterList.updateSizeAndPosition(SIDEBAR_WIDTH, panelBottom - sidebarHeaderBottom, pad, sidebarHeaderBottom)
        addRenderableWidget(filterList)

        resultList = ResultList(resultsW, panelBottom - resultsHeaderBottom, resultsHeaderBottom)
        resultList.updateSizeAndPosition(resultsW, panelBottom - resultsHeaderBottom, contentX, resultsHeaderBottom)
        addRenderableWidget(resultList)

        // Details: actions, then the versions
        primaryButton = FlatButton(detailX + 8, actionRowY, 100, 16, t("button.resourcepackprofiles.browse_install"), style = FlatButton.Style.PRIMARY) {
            onPrimary()
        }
        secondaryButton = FlatButton(detailX + 8, actionRowY, 100, 16, t("button.resourcepackprofiles.browse_install_only")) {
            install(addToProfile = false)
        }
        val pageLabel = t("button.resourcepackprofiles.browse_page")
        val pageWidth = font.width(pageLabel) + 16
        pageButton = FlatButton(detailX + detailW - 8 - pageWidth, actionRowY, pageWidth, 16, pageLabel) { openPage() }
            .withTooltip(t("tooltip.resourcepackprofiles.browse_page"))
        backButton = FlatButton(detailX + detailW - 8 - 20, detailHeaderY + (DETAIL_ICON - 16) / 2, 20, 16, t("button.resourcepackprofiles.browse_back"), Icon.CHEVRON_LEFT) {
            closeDetail()
        }.withTooltip(t("button.resourcepackprofiles.browse_back"))
        addRenderableWidget(primaryButton)
        addRenderableWidget(secondaryButton)
        addRenderableWidget(pageButton)
        addRenderableWidget(backButton)
        versionList = VersionList(detailW, panelBottom - versionsY, versionsY)
        versionList.updateSizeAndPosition(detailW, panelBottom - versionsY, detailX, versionsY)
        addRenderableWidget(versionList)

        doneButton = FlatButton(width - pad - 60, height - 24, 60, 18, CommonComponents.GUI_DONE, backdrop = true) { onClose() }
        addRenderableWidget(doneButton)

        // Responder last, so restoring the text above doesn't trigger a search.
        searchBox.setResponder {
            query = it
            searchDueAt = System.currentTimeMillis() + SEARCH_DELAY_MS
        }

        rebuildFilters()
        rebuildResults()
        rebuildVersions()
        syncWidgets()
        if (!started) {
            started = true
            identifyInstalled()
            runSearch()
        }
    }

    // ================= Search =================

    private fun sortLabel(): Component =
        t("button.resourcepackprofiles.browse_sort", t("label.resourcepackprofiles.browse_sort_" + SORTS[sortIndex]))

    /** Modrinth facet groups: groups are ANDed, entries in a group ORed. */
    private fun facets(): List<List<String>> {
        val groups = mutableListOf(listOf("project_type:resourcepack"))
        if (compatibleOnly) groups.add(listOf("versions:${UpdateChecker.gameVersion()}"))
        // Resolutions are alternatives (any of them); categories each narrow the results further.
        val resolutions = categories.filter { it in RESOLUTIONS }
        if (resolutions.isNotEmpty()) groups.add(resolutions.map { "categories:$it" })
        categories.filter { it !in RESOLUTIONS }.forEach { groups.add(listOf("categories:$it")) }
        return groups
    }

    private fun runSearch() {
        searchDueAt = 0
        val gen = ++generation
        results.clear()
        totalHits = 0
        searchFailed = false
        rebuildResults()
        resultList.setScrollAmount(0.0)
        fetchPage(gen, 0)
    }

    private fun fetchPage(gen: Int, offset: Int) {
        loading = true
        val q = query.trim()
        val facets = facets()
        val index = SORTS[sortIndex]
        Thread {
            val page = try {
                ModrinthApi.browse(q, facets, index, offset, PAGE_SIZE)
            } catch (e: Exception) {
                logger.warn("Modrinth search failed: {}", e.message)
                null
            }
            Minecraft.getInstance().execute {
                if (gen != generation) return@execute
                loading = false
                if (page == null) {
                    searchFailed = true
                    return@execute
                }
                results.addAll(page.hits)
                totalHits = page.totalHits
                rebuildResults()
            }
        }.also { it.name = "RPP-Browse"; it.isDaemon = true }.start()
    }

    override fun tick() {
        super.tick()
        if (searchDueAt != 0L && System.currentTimeMillis() >= searchDueAt) runSearch()
        // Endless scrolling: fetch the next page as the end of the list comes into view.
        if (!loading && !searchFailed && results.size < totalHits && resultList.nearEnd()) {
            fetchPage(generation, results.size)
        }
    }

    // ================= Selection and details =================

    private fun select(hit: ProjectHit) {
        if (hit.projectId == selectedHit?.projectId) return
        selectedHit = hit
        versions = null
        versionsFailed = false
        chosen = null
        rebuildVersions()
        syncWidgets()
        val gen = ++detailGeneration
        Thread {
            val list = try {
                UpdateChecker.projectVersions(hit.projectId, hit.title)
            } catch (e: Exception) {
                logger.warn("Could not load versions of {}: {}", hit.title, e.message)
                null
            }
            Minecraft.getInstance().execute {
                if (gen != detailGeneration) return@execute
                if (list == null) {
                    versionsFailed = true
                } else {
                    versions = list
                    // Default to the newest version that works here.
                    chosen = list.firstOrNull { it.compatible } ?: list.firstOrNull()
                }
                rebuildVersions()
            }
        }.also { it.name = "RPP-BrowseVersions"; it.isDaemon = true }.start()
    }

    private fun closeDetail() {
        selectedHit = null
        detailGeneration++
        versions = null
        chosen = null
        resultList.selectSilently(null)
        rebuildVersions()
        syncWidgets()
    }

    private fun syncWidgets() {
        searchBox.visible = showResults
        resultList.visible = showResults
        val detail = showDetail
        versionList.visible = detail
        primaryButton.visible = detail
        secondaryButton.visible = detail && profileName != null
        pageButton.visible = detail
        backButton.visible = detail && narrow
    }

    private fun refreshInstalled() {
        installedIds = minecraft.resourcePackRepository.availablePacks.map { it.id }.toSet()
        val known = IDENTIFIED.filterKeys { it in installedIds }
        // A project counts as installed if one of its files is: identified by fingerprint, or
        // linked to it by a profile.
        installedProjects = known.values.map { it.projectId }.toSet() + ProfileManager.getProfiles()
            .flatMap { p -> p.packProjects.orEmpty().filterKeys { it in installedIds }.values }
        installedVersions = known.entries.associate { it.value.versionId to it.key }
    }

    /**
     * Identifies installed pack files that haven't been yet this session, by fingerprint, so packs
     * installed any way at all (not only through this mod) show as installed. One Modrinth request
     * for all of them; results are kept for the session.
     */
    private fun identifyInstalled() {
        val unknown = installedIds.filter { it.startsWith("file/") && !IDENTIFIED.containsKey(it) && it !in UNIDENTIFIED }
        if (unknown.isEmpty()) return
        Thread {
            val found = try {
                UpdateChecker.identify(unknown)
            } catch (e: Exception) {
                logger.warn("Could not identify installed packs: {}", e.message)
                null
            }
            Minecraft.getInstance().execute {
                if (found == null) return@execute
                IDENTIFIED.putAll(found)
                UNIDENTIFIED.addAll(unknown.filter { it !in found })
                // Profiles that use these packs learn their Modrinth links too.
                ProfileManager.recordSources(found.mapValues { it.value.versionId }, found.mapValues { it.value.projectId })
                refreshInstalled()
            }
        }.also { it.name = "RPP-Identify"; it.isDaemon = true }.start()
    }

    /** The installed pack id of exactly this version, if it is installed. */
    private fun installedId(update: PackUpdate): String? =
        "file/${update.fileName}".takeIf { it in installedIds } ?: installedVersions[update.versionId]

    private fun isInstalled(update: PackUpdate) = installedId(update) != null

    /** Primary action for the chosen version, depending on what is already installed. */
    private fun onPrimary() {
        val update = chosen?.update ?: return
        val existing = installedId(update)
        if (existing != null) {
            if (profileName != null) addToProfile(existing, update)
            return
        }
        install(addToProfile = profileName != null)
    }

    private fun install(addToProfile: Boolean) {
        val choice = chosen ?: return
        val hit = selectedHit ?: return
        if (busy != null) return
        val update = choice.update
        val existing = installedId(update)
        if (existing != null) {
            if (addToProfile) addToProfile(existing, update)
            else showStatus(t("status.resourcepackprofiles.browse_already", hit.title))
            return
        }
        val batch = DownloadProgress(listOf(update))
        progress = batch
        busy = t("status.resourcepackprofiles.downloading", 1)
        Thread {
            val result = try {
                Result.success(UpdateChecker.downloadAll(listOf(update), batch))
            } catch (e: Exception) {
                logger.error("Failed to install {}", hit.title, e)
                Result.failure(e)
            }
            Minecraft.getInstance().execute {
                busy = null
                progress = null
                val newId = result.getOrNull()?.replacements?.values?.firstOrNull()
                if (newId == null) {
                    showError(t("status.resourcepackprofiles.download_failed"))
                    return@execute
                }
                minecraft.resourcePackRepository.reload()
                IDENTIFIED[newId] = PackIdentity(update.versionId, update.projectId)
                refreshInstalled()
                if (addToProfile) addToProfile(newId, update)
                else showStatus(t("status.resourcepackprofiles.browse_installed", hit.title), Theme.SUCCESS)
            }
        }.also { it.name = "RPP-BrowseInstall"; it.isDaemon = true }.start()
    }

    /** Adds an installed pack to the top of the profile the browser was opened from. */
    private fun addToProfile(packId: String, update: PackUpdate) {
        val name = profileName ?: return
        val profile = ProfileManager.getProfile(name) ?: return
        val title = selectedHit?.title ?: update.projectTitle
        if (packId in profile.packIds) {
            showStatus(t("status.resourcepackprofiles.browse_already_in", title, name))
            return
        }
        // Appended last = loaded last = highest priority, the top of the load order.
        ProfileManager.updateProfile(name, profile.packIds + packId)
        ProfileManager.recordSources(mapOf(packId to update.versionId), mapOf(packId to update.projectId))
        showStatus(t("status.resourcepackprofiles.browse_added", title, name), Theme.SUCCESS)
    }

    private fun openPage() {
        val hit = selectedHit ?: return
        val uri = URI("https://modrinth.com/resourcepack/" + hit.slug.ifEmpty { hit.projectId })
        openingChild = true
        minecraft.showScreen(ConfirmDialogScreen(
            this,
            t("screen.resourcepackprofiles.browse_open.title"),
            t("screen.resourcepackprofiles.browse_open.message", uri.toString()),
            t("button.resourcepackprofiles.browse_open"),
            destructive = false
        ) { openInBrowser(uri) })
    }

    // ================= Status =================

    private fun showStatus(text: Component, color: Int = Theme.TEXT_SECONDARY) {
        status = text
        statusColor = color
        statusUntil = System.currentTimeMillis() + 6000L
    }

    private fun showError(text: Component) = showStatus(text, Theme.DANGER)

    // ================= Input =================

    override fun keyPressed(event: KeyEvent): Boolean {
        if (searchBox.isFocused && event.isConfirmation) {
            runSearch()
            return true
        }
        if (event.hasControlDown() && event.key() == InputConstants.KEY_F && showResults) {
            setFocused(searchBox)
            return true
        }
        if (event.isEscape && narrow && selectedHit != null) {
            closeDetail()
            return true
        }
        return super.keyPressed(event)
    }

    // ================= Rendering =================

    private fun rebuildFilters() {
        val rows = mutableListOf<FilterRow>()
        rows.add(FilterRow(null, "label.resourcepackprofiles.browse_resolution"))
        RESOLUTIONS.forEach { rows.add(FilterRow(it, null)) }
        rows.add(FilterRow(null, "label.resourcepackprofiles.browse_categories"))
        FEATURES.forEach { rows.add(FilterRow(it, null)) }
        val scroll = filterList.scrollAmount()
        filterList.replaceEntries(rows)
        filterList.setScrollAmount(scroll)
    }

    private fun rebuildResults() {
        val scroll = resultList.scrollAmount()
        val rows = results.map { ResultRow(it) }
        resultList.replaceEntries(rows)
        resultList.selectSilently(rows.firstOrNull { it.hit.projectId == selectedHit?.projectId })
        resultList.setScrollAmount(scroll)
    }

    private fun rebuildVersions() {
        val rows = versions.orEmpty().map { VersionRow(it) }
        versionList.replaceEntries(rows)
        versionList.setScrollAmount(0.0)
    }

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
        val panelHeight = panelBottom - panelTop
        Theme.panel(context, pad, panelTop, SIDEBAR_WIDTH, panelHeight)
        Theme.header(context, pad, panelTop, SIDEBAR_WIDTH, sidebarHeaderBottom - panelTop)
        if (showResults) {
            Theme.panel(context, contentX, panelTop, resultsW, panelHeight)
            Theme.header(context, contentX, panelTop, resultsW, resultsHeaderBottom - panelTop)
            Theme.field(context, contentX + 8, panelTop + 8, resultsW - 16, 16, searchBox.isFocused)
            Icon.SEARCH.draw(context, contentX + 8 + 4, panelTop + 12, Theme.TEXT_MUTED)
        }
        if (!narrow || showDetail) {
            Theme.panel(context, detailX, panelTop, detailW, panelHeight)
            if (showDetail) Theme.header(context, detailX, panelTop, detailW, detailHeaderBottom - panelTop)
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        updateButtons()
        super.extractRenderState(context, mouseX, mouseY, delta)
        context.text(font, title, pad, 9, Theme.TEXT, true)

        if (showResults) extractResultsState(context)
        val hit = selectedHit
        if (hit != null) {
            extractDetail(context, hit)
        } else if (!narrow) {
            val cx = detailX + detailW / 2
            val cy = (panelTop + panelBottom) / 2
            context.centeredText(font, t("label.resourcepackprofiles.browse_pick"), cx, cy - 4, Theme.TEXT_MUTED)
        }
        extractStatus(context, mouseX, mouseY)
    }

    /** Labels and visibility of the action buttons for the chosen version. */
    private fun updateButtons() {
        val update = chosen?.update
        val installed = update != null && isInstalled(update)
        val inProfile = installed && profileName != null &&
            ProfileManager.getProfile(profileName)?.packIds?.contains(installedId(update)) == true
        val label = when {
            update == null -> t("button.resourcepackprofiles.browse_install")
            inProfile -> t("button.resourcepackprofiles.browse_in_profile")
            installed && profileName != null -> t("button.resourcepackprofiles.browse_add")
            installed -> t("button.resourcepackprofiles.browse_is_installed")
            profileName != null -> t("button.resourcepackprofiles.browse_install_add")
            else -> t("button.resourcepackprofiles.browse_install")
        }
        primaryButton.setMessage(label)
        primaryButton.setWidth(font.width(label) + 16)
        primaryButton.active = busy == null && update != null && !inProfile && !(installed && profileName == null)
        secondaryButton.visible = showDetail && profileName != null && !installed
        secondaryButton.active = busy == null && update != null
        secondaryButton.setX(primaryButton.x + primaryButton.getWidth() + 4)
        val secondaryLabel = t("button.resourcepackprofiles.browse_install_only")
        secondaryButton.setMessage(secondaryLabel)
        secondaryButton.setWidth(font.width(secondaryLabel) + 16)
    }

    private fun extractResultsState(context: GuiGraphicsExtractor) {
        val cx = contentX + resultsW / 2
        val y = resultsHeaderBottom + 20
        when {
            searchFailed && results.isEmpty() ->
                context.centeredText(font, t("label.resourcepackprofiles.browse_failed"), cx, y, Theme.DANGER)
            loading && results.isEmpty() ->
                context.centeredText(font, t("label.resourcepackprofiles.browse_loading"), cx, y, Theme.TEXT_MUTED)
            !loading && results.isEmpty() ->
                context.centeredText(font, t("label.resourcepackprofiles.browse_none"), cx, y, Theme.TEXT_MUTED)
        }
    }

    private fun extractDetail(context: GuiGraphicsExtractor, hit: ProjectHit) {
        val x = detailX + 8
        val iconId = icons.get(hit.iconUrl) ?: UNKNOWN_PACK
        context.blit(RenderPipelines.GUI_TEXTURED, iconId, x, detailHeaderY, 0f, 0f, DETAIL_ICON, DETAIL_ICON, DETAIL_ICON, DETAIL_ICON)

        val textX = x + DETAIL_ICON + 8
        val textRight = (if (backButton.visible) backButton.x else detailX + detailW) - 8
        val nameY = detailHeaderY + (DETAIL_ICON - 18) / 2
        context.text(font, ScreenUtils.truncate(font, hit.title, textRight - textX), textX, nameY, Theme.TEXT, true)
        val sub = t("label.resourcepackprofiles.browse_by", hit.author, compact(hit.downloads)).string
        context.text(font, ScreenUtils.truncate(font, sub, textRight - textX), textX, nameY + 10, Theme.TEXT_SECONDARY, true)

        // Summary, then the categories, within a fixed number of lines so the version list stays put.
        val width = detailW - 16
        val lines = font.split(Component.literal(hit.description), width).take(SUMMARY_LINES - 1)
        lines.forEachIndexed { i, line -> context.text(font, line, x, summaryY + i * 10, Theme.TEXT_SECONDARY, true) }
        // "minecraft" is the loader, listed on every resource pack; it says nothing about this one.
        val shownCategories = hit.categories.filter { it != "minecraft" }
        if (shownCategories.isNotEmpty()) {
            val cats = shownCategories.joinToString("  ") { categoryLabel(it) }
            context.text(font, ScreenUtils.truncate(font, cats, width), x, summaryY + lines.size * 10, Theme.ACCENT, true)
        }

        val listMessage = when {
            versionsFailed -> "label.resourcepackprofiles.browse_versions_failed"
            versions == null -> "label.resourcepackprofiles.browse_versions_loading"
            versions!!.isEmpty() -> "label.resourcepackprofiles.browse_versions_none"
            else -> null
        }
        listMessage?.let { context.centeredText(font, t(it), detailX + detailW / 2, versionsY + 12, Theme.TEXT_MUTED) }
    }

    private fun extractStatus(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val right = doneButton.x - 6
        val now = System.currentTimeMillis()
        val batch = progress
        val busyText = busy
        val statusText = status
        when {
            busyText != null && batch != null -> StatusPill.drawProgress(context, font, batch, pad, height - 24, right, mouseX, mouseY)
            busyText != null -> StatusPill.draw(context, font, busyText, Theme.ACTIVE, 1f, pad, height - 24, right)
            statusText != null && now < statusUntil -> {
                val alpha = ((statusUntil - now) / 500f).coerceAtMost(1f)
                if (alpha > 0.1f) StatusPill.draw(context, font, statusText, statusColor, alpha, pad, height - 24, right)
            }
        }
    }

    override fun removed() {
        // Leaving for our own dialog keeps the icons; leaving for good releases their textures.
        if (!openingChild) icons.cleanup()
        super.removed()
    }

    override fun onClose() {
        minecraft.showScreen(parent)
    }

    // ================= Lists =================

    /** Sidebar: section headers ([key] set) and category toggles ([category] set). */
    private inner class FilterList(w: Int, h: Int, top: Int) : FlatSelectionList<FilterRow>(minecraft, w, h, top, 13)

    private inner class FilterRow(val category: String?, private val headerKey: String?) : ObjectSelectionList.Entry<FilterRow>() {

        override fun getNarration(): Component =
            if (category != null) Component.literal(categoryLabel(category)) else t(headerKey!!)

        override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || category == null) return false
            toggle()
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            if (category == null || !(event.isSelection || event.isConfirmation)) return false
            toggle()
            return true
        }

        private fun toggle() {
            val c = category ?: return
            if (!categories.remove(c)) categories.add(c)
            runSearch()
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val c = category
            val textY = y + (height - 8) / 2
            if (c == null) {
                context.text(font, t(headerKey!!), x + 4, textY + 1, Theme.ACCENT, true)
                return
            }
            if (hovered) context.fill(x, y, x + width, y + height, Theme.ROW_HOVER)
            val on = c in categories
            val box = 9
            val bx = x + 4
            val by = y + (height - box) / 2
            Theme.border(context, bx, by, box, box, if (hovered) Theme.TEXT else Theme.CONTROL_BORDER)
            if (on) Icon.TICK.drawCentered(context, bx, by, box, box, Theme.TEXT)
            context.text(font, ScreenUtils.truncate(font, categoryLabel(c), width - 20), bx + box + 5, textY, if (on) Theme.TEXT else Theme.TEXT_SECONDARY, true)
        }
    }

    private inner class ResultList(w: Int, h: Int, top: Int) : FlatSelectionList<ResultRow>(minecraft, w, h, top, 30) {
        private var silent = false

        fun selectSilently(row: ResultRow?) {
            silent = true
            try {
                super.setSelected(row)
            } finally {
                silent = false
            }
        }

        // Clicks and keyboard navigation both select through here.
        override fun setSelected(entry: ResultRow?) {
            super.setSelected(entry)
            if (!silent && entry != null) select(entry.hit)
        }
    }

    private inner class ResultRow(val hit: ProjectHit) : ObjectSelectionList.Entry<ResultRow>() {

        override fun getNarration(): Component = Component.literal(hit.title)

        override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
            resultList.setSelected(this)
            return true
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val isSelected = hit.projectId == selectedHit?.projectId
            if (isSelected) {
                context.fill(x, y, x + width, y + height, Theme.ROW_SELECTED)
                context.fill(x, y, x + 2, y + height, Theme.ACCENT)
            } else if (hovered) {
                context.fill(x, y, x + width, y + height, Theme.ROW_HOVER)
            }
            val centerY = y + height / 2
            val iconId = icons.get(hit.iconUrl) ?: UNKNOWN_PACK
            context.blit(RenderPipelines.GUI_TEXTURED, iconId, x + 5, centerY - 11, 0f, 0f, 22, 22, 22, 22)

            val textX = x + 33
            val right = x + width - 4
            val downloads = compact(hit.downloads)
            val downloadsX = right - font.width(downloads)
            context.text(font, downloads, downloadsX, centerY - 9, Theme.TEXT_MUTED, true)
            val installed = hit.projectId in installedProjects
            var subRight = right
            if (installed) {
                val tag = t("label.resourcepackprofiles.browse_installed_tag")
                subRight = right - font.width(tag) - 6
                context.text(font, tag, right - font.width(tag), centerY + 1, Theme.SUCCESS, true)
            }
            val titleText = ScreenUtils.truncate(font, hit.title, downloadsX - 6 - textX)
            context.text(font, titleText, textX, centerY - 9, Theme.TEXT, true)
            val by = t("label.resourcepackprofiles.browse_author", hit.author).string
            val authorX = textX + font.width(titleText) + 4
            if (authorX < downloadsX - 30) {
                context.text(font, ScreenUtils.truncate(font, by, downloadsX - 6 - authorX), authorX, centerY - 9, Theme.TEXT_MUTED, true)
            }
            context.text(font, ScreenUtils.truncate(font, hit.description, subRight - textX), textX, centerY + 1, Theme.TEXT_SECONDARY, true)
        }
    }

    private inner class VersionList(w: Int, h: Int, top: Int) : FlatSelectionList<VersionRow>(minecraft, w, h, top, 14)

    private inner class VersionRow(val choice: VersionChoice) : ObjectSelectionList.Entry<VersionRow>() {

        override fun getNarration(): Component = Component.literal(choice.update.newVersion)

        override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
            chosen = choice
            versionList.setSelected(this)
            if (doubled) onPrimary()
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            if (!event.isConfirmation) return false
            chosen = choice
            onPrimary()
            return true
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val isChosen = chosen === choice
            if (isChosen) {
                context.fill(x, y, x + width, y + height, Theme.ROW_SELECTED)
                context.fill(x, y, x + 2, y + height, Theme.ACCENT)
            } else if (hovered) {
                context.fill(x, y, x + width, y + height, Theme.ROW_HOVER)
            }
            val textY = y + (height - 8) / 2
            val right = x + width - 4
            val note = when {
                isInstalled(choice.update) -> t("label.resourcepackprofiles.version_installed") to Theme.SUCCESS
                else -> Component.literal(gameRange(choice.gameVersions)) to Theme.TEXT_MUTED
            }
            val noteX = right - font.width(note.first)
            context.text(font, note.first, noteX, textY, note.second, true)
            val color = if (choice.compatible) Theme.TEXT else Theme.TEXT_MUTED
            context.text(font, ScreenUtils.truncate(font, choice.update.newVersion, noteX - 6 - (x + 6)), x + 6, textY, color, true)
        }
    }

    // ================= Formatting =================

    private fun categoryLabel(id: String): String = when (id) {
        "8x-" -> t("label.resourcepackprofiles.browse_res_low").string
        "512x+" -> t("label.resourcepackprofiles.browse_res_high").string
        "gui" -> "GUI"
        else -> id.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    /** "26.1 - 26.3" for a version that spans several game versions. */
    private fun gameRange(list: List<String>): String = when (list.size) {
        0 -> ""
        1 -> list[0]
        else -> "${list.first()} - ${list.last()}"
    }

    private fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.ROOT, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    private companion object {
        const val SIDEBAR_WIDTH = 120
        const val DETAIL_ICON = 40
        const val SUMMARY_LINES = 4
        const val PAGE_SIZE = 20
        const val SEARCH_DELAY_MS = 350L
        val SORTS = listOf("relevance", "downloads", "follows", "newest", "updated")
        val RESOLUTIONS = listOf("8x-", "16x", "32x", "48x", "64x", "128x", "256x", "512x+")
        val FEATURES = listOf(
            "audio", "blocks", "combat", "core-shaders", "cursed", "decoration", "entities", "environment",
            "equipment", "fonts", "gui", "items", "locale", "modded", "models", "realistic", "simplistic",
            "themed", "tweaks", "utility", "vanilla-like"
        )
        val UNKNOWN_PACK: Identifier = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png")

        // Installed pack files already looked up this session (by pack id), so reopening the
        // browser doesn't fingerprint every pack again.
        val IDENTIFIED = ConcurrentHashMap<String, PackIdentity>()
        val UNIDENTIFIED: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
