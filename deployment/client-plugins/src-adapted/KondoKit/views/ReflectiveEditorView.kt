package KondoKit.views

import KondoKit.pluginmanager.ReflectiveEditorPlugin
import KondoKit.util.attachPopupMenu
import KondoKit.ui.components.*
import KondoKit.pluginmanager.PluginInfoWithStatus
import KondoKit.plugin.Companion.VIEW_BACKGROUND_COLOR
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.plugin.Companion.WRENCH_ICON
import KondoKit.plugin.Companion.secondaryColor
import KondoKit.util.setFixedSize
import KondoKit.ui.BaseView
import KondoKit.ui.View
import KondoKit.ui.ViewConstants
import KondoKit.util.Helpers.showToast
import KondoKit.views.ViewLayoutHelpers.createSearchFieldSection
import plugin.Plugin
import plugin.PluginInfo
import plugin.PluginRepository
import java.awt.*
import java.io.File
import java.util.*
import javax.swing.*
import kotlin.math.ceil

/*
  This is used for the runtime editing of plugin variables.
  To expose fields add the @Exposed annotation.
  When they are applied this will trigger an invoke of OnKondoValueUpdated()
  if it is implemented. Check GroundItems plugin for an example.
 */

object ReflectiveEditorView : View {
    var reflectiveEditorView: JPanel? = null
    const val VIEW_NAME = "REFLECTIVE_EDITOR_VIEW"
    const val PLUGIN_LIST_VIEW = "PLUGIN_LIST"
    const val PLUGIN_DETAIL_VIEW = "PLUGIN_DETAIL"
    
    private val reflectiveEditorPlugin = ReflectiveEditorPlugin()
    
    private var searchField: SearchField? = null

    private fun isKondoKit(pluginName: String): Boolean {
        return pluginName == "KondoKit"
    }

    private lateinit var cardLayout: CardLayout
    private lateinit var mainPanel: JPanel
    private var currentPluginInfo: PluginInfo? = null
    private var currentPlugin: Plugin? = null

    private var pluginSearchText: String = ""
    private var searchFieldWrapper: JPanel? = null
    private var pluginListContentPanel: JPanel? = null
    private var pluginListScrollablePanel: ScrollablePanel? = null

    override val name: String = VIEW_NAME
    override val iconSpriteId: Int = WRENCH_ICON
    override val panel: JPanel
        get() = reflectiveEditorView ?: JPanel()

    override fun createView() {
        reflectiveEditorPlugin.Init()
        createReflectiveEditorView()
    }

    override fun registerFunctions() {
    }

    fun createReflectiveEditorView() {
        cardLayout = CardLayout()
        mainPanel = JPanel(cardLayout)
        mainPanel.background = VIEW_BACKGROUND_COLOR
        mainPanel.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        mainPanel.isDoubleBuffered = true

        val pluginListView = createPluginListView()
        pluginListView.name = PLUGIN_LIST_VIEW
        mainPanel.add(pluginListView, PLUGIN_LIST_VIEW)

        val pluginDetailView = BaseView(PLUGIN_DETAIL_VIEW).apply {
            layout = BorderLayout()
            background = VIEW_BACKGROUND_COLOR
        }
        mainPanel.add(pluginDetailView, PLUGIN_DETAIL_VIEW)

        reflectiveEditorView = mainPanel

        cardLayout.show(mainPanel, PLUGIN_LIST_VIEW)
    }

    private fun createPluginListView(): JPanel {
        val panel = BaseView(PLUGIN_LIST_VIEW, addDefaultSpacing = false).apply {
            background = VIEW_BACKGROUND_COLOR
        }

        val searchSection = createSearchFieldSection(
            parent = panel,
            placeholderText = "Search plugins...",
            viewName = VIEW_NAME,
            initialText = pluginSearchText
        ) { searchText ->
            pluginSearchText = searchText
            SwingUtilities.invokeLater {
                addPlugins(reflectiveEditorView!!)
            }
        }
        this.searchField = searchSection.searchField
        searchFieldWrapper = searchSection.wrapper

        panel.add(Box.createVerticalStrut(5))
        panel.add(searchSection.wrapper)
        panel.add(Box.createVerticalStrut(10))

        pluginListContentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = VIEW_BACKGROUND_COLOR
            alignmentX = Component.CENTER_ALIGNMENT
        }
        panel.add(pluginListContentPanel)

        val scrollablePanel = ScrollablePanel(panel)
        pluginListScrollablePanel = scrollablePanel

        val container = JPanel(BorderLayout())
        container.background = VIEW_BACKGROUND_COLOR
        container.add(scrollablePanel, BorderLayout.CENTER)

        populatePluginListContent()

        SwingUtilities.invokeLater {
            resetScrollablePanel(scrollablePanel)
        }

        return container
    }

    private fun populatePluginListContent() {
        val contentPanel = pluginListContentPanel ?: return

        val treeLock = contentPanel.treeLock
            synchronized(treeLock) {
            contentPanel.removeAll()

            try {
                val loadedPluginsField = PluginRepository::class.java.getDeclaredField("loadedPlugins")
                loadedPluginsField.isAccessible = true
                val loadedPlugins = loadedPluginsField.get(null) as HashMap<*, *>

                val pluginsWithExposed = mutableListOf<Pair<PluginInfo, Plugin>>()
                val pluginsWithoutExposed = mutableListOf<Pair<PluginInfo, Plugin>>()

                for ((pluginInfo, plugin) in loadedPlugins) {
                    val exposedFields = (plugin as Plugin).javaClass.declaredFields.filter { field ->
                        field.annotations.any { annotation ->
                            annotation.annotationClass.simpleName == "Exposed"
                        }
                    }

                    val pluginName = plugin.javaClass.`package`.name
                    if (pluginSearchText.isBlank() || pluginName.contains(pluginSearchText, ignoreCase = true)) {
                        if (exposedFields.isNotEmpty()) {
                            pluginsWithExposed.add(pluginInfo as PluginInfo to plugin)
                        } else {
                            pluginsWithoutExposed.add(pluginInfo as PluginInfo to plugin)
                        }
                    }
                }

                pluginsWithExposed.sortWith(compareBy(
                    { if (isKondoKit(it.second.javaClass.`package`.name)) 0 else 1 },
                    { it.second.javaClass.`package`.name }
                ))
                pluginsWithoutExposed.sortWith(compareBy(
                    { if (isKondoKit(it.second.javaClass.`package`.name)) 0 else 1 },
                    { it.second.javaClass.`package`.name }
                ))

                for ((pluginInfo, plugin) in pluginsWithExposed) {
                    val pluginPanel = createPluginItemPanel(pluginInfo, plugin)
                    contentPanel.add(pluginPanel)
                    contentPanel.add(Box.createVerticalStrut(5))
                }

                if (pluginsWithExposed.isNotEmpty() && pluginsWithoutExposed.isNotEmpty()) {
                    val separator = JPanel()
                    separator.background = VIEW_BACKGROUND_COLOR
                    separator.preferredSize = Dimension(Int.MAX_VALUE, 1)
                    separator.maximumSize = Dimension(Int.MAX_VALUE, 1)
                    contentPanel.add(separator)
                    contentPanel.add(Box.createVerticalStrut(5))
                }

                for ((pluginInfo, plugin) in pluginsWithoutExposed) {
                    val pluginPanel = createPluginItemPanel(pluginInfo, plugin)
                    contentPanel.add(pluginPanel)
                    contentPanel.add(Box.createVerticalStrut(5))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val disabledDir = File(reflectiveEditorPlugin.getPluginsDirectory(), "disabled")
            if (disabledDir.exists() && disabledDir.isDirectory) {
                val disabledPlugins = disabledDir.listFiles { file -> file.isDirectory } ?: arrayOf()

                for (pluginDir in disabledPlugins.sortedBy { it.name }) {
                    if (isKondoKit(pluginDir.name)) {
                        continue
                    }
                    if (pluginSearchText.isBlank() || pluginDir.name.contains(pluginSearchText, ignoreCase = true)) {
                        val pluginPanel = createDisabledPluginItemPanel(pluginDir.name)
                        contentPanel.add(pluginPanel)
                        contentPanel.add(Box.createVerticalStrut(5))
                    }
                }
            }

            if (pluginSearchText.isNotBlank()) {
                val matchingPluginStatuses = reflectiveEditorPlugin.getPluginStatuses().filter { pluginStatus ->
                    !pluginStatus.isInstalled &&
                        !isKondoKit(pluginStatus.name) &&
                        (pluginStatus.name.contains(pluginSearchText, ignoreCase = true) ||
                                (pluginStatus.description?.contains(pluginSearchText, ignoreCase = true) ?: false))
                }

                if (matchingPluginStatuses.isNotEmpty()) {
                    val separator = JPanel().apply {
                        background = VIEW_BACKGROUND_COLOR
                        preferredSize = Dimension(Int.MAX_VALUE, 1)
                        maximumSize = preferredSize
                    }
                    contentPanel.add(Box.createVerticalStrut(10))
                    contentPanel.add(separator)
                    contentPanel.add(Box.createVerticalStrut(5))

                    val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                        background = VIEW_BACKGROUND_COLOR
                        add(JLabel("Available Plugins").apply {
                            foreground = secondaryColor
                            font = ViewConstants.FONT_RUNESCAPE_SMALL_BOLD_16
                        })
                    }
                    contentPanel.add(headerPanel)
                    contentPanel.add(Box.createVerticalStrut(5))

                    matchingPluginStatuses.forEach { pluginStatus ->
                        val pluginPanel = createPluginStatusItemPanel(pluginStatus)
                        contentPanel.add(pluginPanel)
                        contentPanel.add(Box.createVerticalStrut(5))
                    }
                }
            }
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createPluginItemPanel(pluginInfo: PluginInfo, plugin: Plugin): JPanel {
        val panel = createPluginWidgetPanel().apply {
            layout = BorderLayout()
        }

        val packageName = plugin.javaClass.`package`.name
        val nameLabel = JLabel(packageName)
        nameLabel.foreground = secondaryColor
        nameLabel.font = ViewConstants.FONT_RUNESCAPE_SMALL_PLAIN_16

        val exposedFields = plugin.javaClass.declaredFields.filter { field ->
            field.annotations.any { annotation ->
                annotation.annotationClass.simpleName == "Exposed"
            }
        }

        val editButton = exposedFields.takeIf { it.isNotEmpty() }?.let {
            UiStyler.button(text = "Edit") {
                showPluginDetails(pluginInfo, plugin)
            }
        }

        val toggleSwitch = if (!isKondoKit(packageName)) {
            createToggleSwitch(plugin, pluginInfo)
        } else {
            val placeholder = JPanel()
            placeholder.background = WIDGET_COLOR
            placeholder.setFixedSize(ViewConstants.TOGGLE_PLACEHOLDER_SIZE)
            placeholder.isVisible = false
            placeholder
        }

        val infoPanel = JPanel(BorderLayout())
        infoPanel.background =  WIDGET_COLOR
        infoPanel.add(nameLabel, BorderLayout.WEST)

        val controlsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, -3))
        controlsPanel.background =  WIDGET_COLOR

        editButton?.let { controlsPanel.add(it) }

        if (!isKondoKit(packageName)) {
            controlsPanel.add(toggleSwitch)
        }

        panel.add(infoPanel, BorderLayout.CENTER)
        panel.add(controlsPanel, BorderLayout.EAST)

        if (!isKondoKit(packageName)) {
            val popupMenu = createPluginContextMenu(plugin, pluginInfo, packageName, false)
            panel.attachPopupMenu(popupMenu)
        }

        return panel
    }

    private fun createDisabledPluginItemPanel(pluginName: String): JPanel {
        val panel = createPluginWidgetPanel().apply {
            layout = BorderLayout()
        }

        val nameLabel = JLabel(pluginName)
        nameLabel.foreground = secondaryColor
        nameLabel.font = ViewConstants.FONT_RUNESCAPE_SMALL_PLAIN_16

        val toggleSwitch = ToggleSwitch()
        toggleSwitch.setActivated(false)
        toggleSwitch.onToggleListener = { activated ->
            if (activated) {
                reflectiveEditorPlugin.enablePlugin(pluginName, mainPanel ?: JPanel())
            }
            else {
                toggleSwitch.setActivated(false)
            }
        }

        val infoPanel = JPanel(BorderLayout())
        infoPanel.background = WIDGET_COLOR
        infoPanel.add(nameLabel, BorderLayout.WEST)

        val controlsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        controlsPanel.background = WIDGET_COLOR
        controlsPanel.add(toggleSwitch)

        panel.add(infoPanel, BorderLayout.CENTER)
        panel.add(controlsPanel, BorderLayout.EAST)

        if (!isKondoKit(pluginName)) {
            val popupMenu = createPluginContextMenu(null, null, pluginName, true)
            panel.attachPopupMenu(popupMenu)
        }

        return panel
    }
    
    private fun createPluginStatusItemPanel(pluginStatus: PluginInfoWithStatus): JPanel {
        val panel = createPluginWidgetPanel().apply {
            layout = BorderLayout()
        }

        val nameLabel = JLabel(pluginStatus.name)
        nameLabel.foreground = secondaryColor
        nameLabel.font = ViewConstants.FONT_RUNESCAPE_SMALL_PLAIN_16

        val actionButton = UiStyler.button()
        val progressBar = JProgressBar(0, 100)
        progressBar.isStringPainted = true
        progressBar.isVisible = false
        progressBar.string = "Downloading..."
        
        if (pluginStatus.isDownloading) {
            actionButton.isVisible = false
            progressBar.isVisible = true
            progressBar.value = pluginStatus.downloadProgress
        } else if (pluginStatus.isInstalled) {
            if (pluginStatus.needsUpdate) {
                actionButton.text = "Update"
                actionButton.addActionListener {
                    startPluginDownload(pluginStatus)
                }
            } else {
                actionButton.text = "Installed"
                actionButton.isEnabled = false
            }
        } else {
            actionButton.text = "Download"
            actionButton.addActionListener {
                startPluginDownload(pluginStatus)
            }
        }

        val toggleSwitch = ToggleSwitch()

        // Skip the toggle switch for KondoKit since it should always be enabled
        if (isKondoKit(pluginStatus.name)) {
            toggleSwitch.isVisible = false
        } else {
            toggleSwitch.setActivated(pluginStatus.isInstalled && !pluginStatus.needsUpdate)
            toggleSwitch.isEnabled = pluginStatus.isInstalled && !pluginStatus.needsUpdate
            
            if (pluginStatus.isInstalled && !pluginStatus.needsUpdate) {
                toggleSwitch.onToggleListener = { activated ->
                    val loadedPluginsField = PluginRepository::class.java.getDeclaredField("loadedPlugins")
                    loadedPluginsField.isAccessible = true
                    val loadedPlugins = loadedPluginsField.get(null) as HashMap<*, *>
                    
                    var foundPlugin: Plugin? = null
                    var foundPluginInfo: PluginInfo? = null
                    
                    for ((pluginInfo, plugin) in loadedPlugins) {
                        if (getPluginDirName(plugin as Plugin) == pluginStatus.name) {
                            foundPlugin = plugin
                            foundPluginInfo = pluginInfo as PluginInfo
                            break
                        }
                    }
                    
                    if (foundPlugin != null && foundPluginInfo != null) {
                        reflectiveEditorPlugin.togglePlugin(foundPlugin, foundPluginInfo, toggleSwitch, activated, mainPanel ?: JPanel())
                    } else {
                        val pluginDirName = pluginStatus.name
                        val sourceDir = if (activated) {
                            File(File(reflectiveEditorPlugin.getPluginsDirectory(), "disabled"), pluginDirName)
                        } else {
                            File(reflectiveEditorPlugin.getPluginsDirectory(), pluginDirName)
                        }
                        
                        val destDir = if (activated) {
                            File(reflectiveEditorPlugin.getPluginsDirectory(), pluginDirName)
                        } else {
                            val disabledDir = File(reflectiveEditorPlugin.getPluginsDirectory(), "disabled")
                            if (!disabledDir.exists()) {
                                disabledDir.mkdirs()
                            }
                            File(disabledDir, pluginDirName)
                        }
                        
                        if (!sourceDir.exists()) {
                            showToast(mainPanel, "Plugin directory not found: ${sourceDir.absolutePath}", JOptionPane.ERROR_MESSAGE)
                            toggleSwitch.setActivated(!activated)
                        } else {
                            if (sourceDir.renameTo(destDir)) {
                                showToast(mainPanel, if (activated) "Plugin enabled" else "Plugin disabled")

                                reflectiveEditorPlugin.setReloadPlugins(true) // Schedule plugin reload to avoid crashes

                                SwingUtilities.invokeLater {
                                    addPlugins(reflectiveEditorView!!)
                                }
                            } else {
                                showToast(mainPanel, "Failed to ${if (activated) "enable" else "disable"} plugin", JOptionPane.ERROR_MESSAGE)
                                toggleSwitch.setActivated(!activated) // Reset toggle to previous state on failure
                            }
                        }
                    }
                }
            } else {
                toggleSwitch.isVisible = false
            }
        }

        val infoPanel = JPanel(BorderLayout())
        infoPanel.background = WIDGET_COLOR
        infoPanel.add(nameLabel, BorderLayout.WEST)

        val controlsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        controlsPanel.background = WIDGET_COLOR
        controlsPanel.add(actionButton)
        controlsPanel.add(progressBar)
        if (toggleSwitch.isVisible) {
            controlsPanel.add(toggleSwitch)
        }
        
        if (!pluginStatus.isInstalled) {
            val tooltipText = buildInstallableTooltip(pluginStatus)
            panel.toolTipText = tooltipText
            actionButton.toolTipText = tooltipText
            progressBar.toolTipText = tooltipText
        } else {
            panel.toolTipText = null
            actionButton.toolTipText = null
            progressBar.toolTipText = null
        }

        panel.add(infoPanel, BorderLayout.CENTER)
        panel.add(controlsPanel, BorderLayout.EAST)
        
        if (!isKondoKit(pluginStatus.name) && pluginStatus.isInstalled) {
            val popupMenu = createPluginContextMenu(null, null, pluginStatus.name, !pluginStatus.needsUpdate)
            panel.attachPopupMenu(popupMenu)
        }
        
        return panel
    }

    private fun createPluginWidgetPanel(): WidgetPanel {
        return WidgetPanel(
            widgetWidth = ViewConstants.PLUGIN_LIST_ITEM_SIZE.width,
            widgetHeight = ViewConstants.PLUGIN_LIST_ITEM_SIZE.height,
            addDefaultPadding = false,
            paddingTop = 10,
            paddingLeft = 10,
            paddingBottom = 10,
            paddingRight = 10
        )
    }

    private fun buildInstallableTooltip(pluginStatus: PluginInfoWithStatus): String {
        val lines = mutableListOf<String>()
        pluginStatus.remoteVersion?.takeIf { it.isNotBlank() }?.let {
            lines += "<b>Version:</b> ${escapeHtml(it)}"
        }
        pluginStatus.author?.takeIf { it.isNotBlank() }?.let {
            lines += "<b>Author:</b> ${escapeHtml(it)}"
        }
        pluginStatus.description?.takeIf { it.isNotBlank() }?.let {
            lines += escapeHtml(it).replace("\n", "<br>")
        }

        if (lines.isEmpty()) {
            lines += "Click Download to install this plugin."
        }

        return "<html>${lines.joinToString("<br>")}</html>"
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
    

    
    private fun createToggleSwitch(plugin: Plugin, pluginInfo: PluginInfo): ToggleSwitch {
        val toggleSwitch = ToggleSwitch()

        toggleSwitch.setActivated(reflectiveEditorPlugin.isPluginEnabled(plugin, pluginInfo))
        toggleSwitch.onToggleListener = { activated ->
            reflectiveEditorPlugin.togglePlugin(plugin, pluginInfo, toggleSwitch, activated, mainPanel ?: JPanel())
        }
        
        return toggleSwitch
    }
    
    private fun getPluginDirName(plugin: Plugin): String {
        // The package name is typically like "GroundItems.plugin" so we take the first part
        val packageName = plugin.javaClass.`package`.name
        return packageName.substringBefore(".")
    }
    
    private fun showPluginDetails(pluginInfo: PluginInfo, plugin: Plugin) {
        currentPluginInfo = pluginInfo
        currentPlugin = plugin

        searchFieldWrapper?.isVisible = false
        
        val existingDetailView = mainPanel.components.find { it.name == PLUGIN_DETAIL_VIEW }
        if (existingDetailView != null) {
            mainPanel.remove(existingDetailView)
        }
        
        val detailView = BaseView(PLUGIN_DETAIL_VIEW)
        detailView.layout = BorderLayout()
        detailView.background = VIEW_BACKGROUND_COLOR
        
        val packageName = plugin.javaClass.`package`.name

        val headerPanel = ViewHeader("$packageName v${pluginInfo.version}", 40).apply {
            border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
        }
        
        val backButton = ButtonPanel(FlowLayout.LEFT).addButton("Back") {
            SwingUtilities.invokeLater {
                // Find the ScrollablePanel in the plugin list view and reset its scroll position
                val listView = mainPanel.components.find { it.name == PLUGIN_LIST_VIEW }
                if (listView != null && listView is Container) {
                    findAndResetScrollablePanel(listView)
                }
            }
            searchFieldWrapper?.isVisible = true
            cardLayout.show(mainPanel, PLUGIN_LIST_VIEW)
        }

        
        headerPanel.add(backButton, BorderLayout.WEST)
        
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.background = VIEW_BACKGROUND_COLOR
        contentPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        
        val infoPanel = JPanel(BorderLayout())
        infoPanel.background =  WIDGET_COLOR
        infoPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)


        val infoText = """
              <html>
                  Version: ${(pluginInfo.version)}<br>
                  Author: ${(pluginInfo.author ?: "").trim('\'')}<br>
                  Description: ${(pluginInfo.description ?: "").trim('\'')}
              </html>
          """.trimIndent()

        
        val infoFont = ViewConstants.FONT_RUNESCAPE_SMALL_14
        val infoLabel = LabelComponent(infoText, isHtml = true).apply {
            font = infoFont
        }
        infoPanel.add(infoLabel, BorderLayout.CENTER)
        
        val fm = infoLabel.getFontMetrics(infoFont)
        
        val avgCharWidth = fm.stringWidth("x")
        val availableWidth = 200
        val charsPerLine = availableWidth / avgCharWidth
        
        val textContent = infoText.replace("<[^>]*>".toRegex(), "")
        val lines = textContent.split('\n').filter { it.isNotBlank() }
        
        var totalLines = 0
        for (line in lines) {
            val lineLength = line.length
            totalLines += kotlin.math.ceil(lineLength.toDouble() / charsPerLine).toInt()
        }
        
        val lineHeight = fm.height
        val estimatedHeight = (totalLines * lineHeight) + 20
        
        val finalHeight = kotlin.math.max(60, kotlin.math.min(estimatedHeight, 150))
        infoPanel.maximumSize = Dimension(Int.MAX_VALUE, finalHeight)
        infoPanel.preferredSize = Dimension(ViewConstants.DEFAULT_WIDGET_SIZE.width, finalHeight)
        

        
        contentPanel.add(infoPanel)
        contentPanel.add(Box.createVerticalStrut(10))
        
        addPluginSettings(contentPanel, plugin)
        
        val scrollablePanel = ScrollablePanel(contentPanel)
        
        SwingUtilities.invokeLater {
            resetScrollablePanel(scrollablePanel)
        }
        
        detailView.add(headerPanel, BorderLayout.NORTH)
        detailView.add(scrollablePanel, BorderLayout.CENTER)

        mainPanel.add(detailView, PLUGIN_DETAIL_VIEW)

        mainPanel.revalidate()
        mainPanel.repaint()

        cardLayout.show(mainPanel, PLUGIN_DETAIL_VIEW)
    }
    
    private fun addPluginSettings(contentPanel: JPanel, plugin: Plugin) {
        val settingsPanel = SettingsPanel(plugin)
        settingsPanel.addSettingsFromPlugin()
        contentPanel.add(settingsPanel)
    }

    fun addPlugins(reflectiveEditorView: JPanel) {
        // Ensure we run on the EDT; if not, reschedule and return
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { addPlugins(reflectiveEditorView) }
            return
        }

        // Check if we need to reload plugins
        if (reflectiveEditorPlugin.shouldReloadPlugins()) {
            PluginRepository.reloadPlugins()
            reflectiveEditorPlugin.setReloadPlugins(false)
        }

        // Update plugin statuses to reflect current loaded plugins
        reflectiveEditorPlugin.updatePluginStatuses()

        val contentPanel = pluginListContentPanel
        if (contentPanel == null) {
            // Fallback path: rebuild the list view if it was not initialized yet
            val existingListView = mainPanel.components.find { it.name == PLUGIN_LIST_VIEW }
            val pluginListView = createPluginListView()
            pluginListView.name = PLUGIN_LIST_VIEW
            if (existingListView != null) {
                mainPanel.remove(existingListView)
            }
            mainPanel.add(pluginListView, PLUGIN_LIST_VIEW)
        } else {
            contentPanel.isVisible = false
            try {
                populatePluginListContent()
            } finally {
                contentPanel.isVisible = true
            }
        }

        searchFieldWrapper?.isVisible = true

        cardLayout.show(mainPanel, PLUGIN_LIST_VIEW)

        pluginListScrollablePanel?.let { scrollPanel ->
            SwingUtilities.invokeLater {
                resetScrollablePanel(scrollPanel)
            }
        }

        mainPanel.revalidate()
        mainPanel.repaint()
    }
    
    private fun findAndResetScrollablePanel(container: Component) {
        if (container is ScrollablePanel) {
            resetScrollablePanel(container)
        } else if (container is Container) {
            for (child in container.components) {
                findAndResetScrollablePanel(child)
            }
        }
    }

    private fun startPluginDownload(pluginStatus: PluginInfoWithStatus) {
        reflectiveEditorPlugin.startPluginDownload(pluginStatus, mainPanel ?: JPanel())
    }

    private fun createPluginContextMenu(plugin: Plugin?, pluginInfo: PluginInfo?, pluginName: String, isDisabled: Boolean): JPopupMenu {
        val popupMenu = PopupMenuComponent()

        popupMenu.addMenuItem("Delete Plugin") {
            deletePlugin(pluginName, isDisabled)
        }

        return popupMenu
    }

    private fun deletePlugin(pluginName: String, isDisabled: Boolean) {
        reflectiveEditorPlugin.deletePlugin(pluginName, isDisabled, mainPanel ?: JPanel())
    }

    private fun resetScrollablePanel(scrollablePanel: ScrollablePanel) {
        try {
            val contentField = ScrollablePanel::class.java.getDeclaredField("content")
            contentField.isAccessible = true
            val contentPanel = contentField.get(scrollablePanel) as JPanel
            
            // Reset the location to the top
            contentPanel.setLocation(0, 0)
            
            // Force a repaint
            scrollablePanel.revalidate()
            scrollablePanel.repaint()
        } catch (e: Exception) {
            // If reflection fails, at least try to repaint
            scrollablePanel.revalidate()
            scrollablePanel.repaint()
        }
    }
}
