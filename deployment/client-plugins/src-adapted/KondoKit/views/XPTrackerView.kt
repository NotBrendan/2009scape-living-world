package KondoKit.views

import KondoKit.util.Helpers
import KondoKit.util.Helpers.formatHtmlLabelText
import KondoKit.util.Helpers.formatNumber
import KondoKit.util.Helpers.getProgressBarColor
import KondoKit.util.Helpers.getSpriteId
import KondoKit.util.SpriteToBufferedImage.getBufferedImageFromSprite
import KondoKit.ui.BaseView
import KondoKit.ui.OnUpdateCallback
import KondoKit.ui.OnXPUpdateCallback
import KondoKit.ui.ViewConstants
import KondoKit.ui.View
import KondoKit.util.setFixedSize
import KondoKit.util.attachPopupMenu
import KondoKit.util.XPTable
import KondoKit.ui.components.PopupMenuComponent
import KondoKit.ui.components.ProgressBar
import KondoKit.ui.components.LabelComponent
import KondoKit.ui.components.WidgetPanel
import KondoKit.ui.components.XPWidget
import KondoKit.plugin.Companion.IMAGE_SIZE
import KondoKit.plugin.Companion.LVL_ICON
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.plugin
import KondoKit.plugin.Companion.playerXPMultiplier
import KondoKit.plugin.Companion.primaryColor
import KondoKit.plugin.Companion.secondaryColor
import KondoKit.plugin.StateManager.focusedView
import KondoKit.util.JsonParser
import plugin.api.API
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*
import javax.swing.SwingConstants

object XPTrackerView : View, OnUpdateCallback, OnXPUpdateCallback {
    private val COMBAT_SKILLS = intArrayOf(0,1,2,3,4)
    val xpWidgets: MutableMap<Int, XPWidget> = HashMap()
    var totalXPWidget: XPWidget? = null
    val initialXP: MutableMap<Int, Int> = HashMap()
    var xpTrackerView: JPanel? = null
    const val VIEW_NAME = "XP_TRACKER_VIEW"
    override val name: String = VIEW_NAME
    override val iconSpriteId: Int = LVL_ICON
    private val skillIconCache: MutableMap<Int, BufferedImage> = HashMap()


    val npcHitpointsMap: Map<Int, Int> = try {
        val json = Helpers.readResourceText("res/npc_hitpoints_map.json") ?: "{}"
        val parsed: Map<String, Int> = JsonParser.fromJson(json)
        parsed.mapNotNull { (key, value) ->
            key.toIntOrNull()?.let { it to value }
        }.toMap()
    } catch (e: Exception) {
        println("XPTracker Error parsing NPC HP: ${e.message}")
        emptyMap()
    }

    private val widgetFont = ViewConstants.FONT_RUNESCAPE_SMALL_16

    private fun BufferedImage.ensureOpaque(): BufferedImage {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = getRGB(x, y)
                if (color != 0) {
                    setRGB(x, y, color or (0xFF shl 24))
                }
            }
        }
        return this
    }

    private fun createIconContainer(image: BufferedImage): JPanel {
        val processed = image.ensureOpaque()
        val icon = ImageIcon(processed)
        val label = JLabel(icon).apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            isOpaque = false
        }

        return JPanel(BorderLayout()).apply {
            background = WIDGET_COLOR
            val wrapperSize = Dimension(IMAGE_SIZE)
            preferredSize = wrapperSize
            minimumSize = wrapperSize
            maximumSize = wrapperSize
            add(label, BorderLayout.CENTER)
        }
    }

    private fun createMetricLabel(title: String, initialValue: String = "0", topPadding: Int = 0): LabelComponent =
        LabelComponent(alignment = JLabel.LEFT).apply {
            updateHtmlText("$title ", primaryColor, initialValue, secondaryColor)
            if (topPadding > 0) {
                border = BorderFactory.createEmptyBorder(topPadding, 0, 0, 0)
            }
        }

    private fun getSkillIcon(skillId: Int): BufferedImage {
        return skillIconCache[skillId] ?: getBufferedImageFromSprite(API.GetSprite(getSpriteId(skillId))).also {
            skillIconCache[skillId] = it
        }
    }

    private fun createTotalWidgetContainer(popupMenu: JPopupMenu): Container {
        totalXPWidget = createTotalXPWidget()
        return wrappedWidget(totalXPWidget!!.container, padding = 0).also {
            it.attachPopupMenu(popupMenu, includeChildren = true)
        }
    }

    override val panel: JPanel
        get() = xpTrackerView ?: JPanel()

    override fun createView() {
        createXPTrackerView()
    }

    override fun registerFunctions() {
        plugin.registerUpdateCallback(this)
        plugin.registerXPUpdateCallback(this)
    }

    override fun onUpdate() {
        val widgets = xpWidgets.values
        val totalXP = totalXPWidget

        widgets.forEach { xpWidget ->
            val elapsedTime = (System.currentTimeMillis() - xpWidget.startTime) / 1000.0 / 60.0 / 60.0
            val xpPerHour = if (elapsedTime > 0) (xpWidget.totalXpGained / elapsedTime).toInt() else 0
            val formattedXpPerHour = formatNumber(xpPerHour)
            xpWidget.xpPerHourLabel.text =
                formatHtmlLabelText("XP /hr: ", primaryColor, formattedXpPerHour, secondaryColor)
            xpWidget.container.repaint()
        }

        totalXP?.let { widget ->
            val elapsedTime = (System.currentTimeMillis() - widget.startTime) / 1000.0 / 60.0 / 60.0
            val totalXPPerHour = if (elapsedTime > 0) (widget.totalXpGained / elapsedTime).toInt() else 0
            val formattedTotalXpPerHour = formatNumber(totalXPPerHour)
            widget.xpPerHourLabel.text =
                formatHtmlLabelText("XP /hr: ", primaryColor, formattedTotalXpPerHour, secondaryColor)
            widget.container.repaint()
        }
    }

    override fun onXPUpdate(skillId: Int, xp: Int) {
        if (!initialXP.containsKey(skillId)) {
            initialXP[skillId] = xp
            return
        }
        val previousXpSnapshot = initialXP[skillId] ?: xp
        if (xp == initialXP[skillId]) return

        val ensureOnEdt = Runnable {
            var xpWidget = xpWidgets[skillId]
            if (xpWidget != null) {
                updateWidget(xpWidget, xp)
            } else {
                xpWidget = createXPWidget(skillId, previousXpSnapshot)
                xpWidgets[skillId] = xpWidget

                val wrapped = wrappedWidget(xpWidget.container, padding = 0, innerPadding = 6)
                val popupMenu = removeXPWidgetMenu(wrapped, skillId)
                wrapped.attachPopupMenu(popupMenu, includeChildren = true)

                xpTrackerView?.add(wrapped)
                xpTrackerView?.add(Box.createVerticalStrut(5))

                if(focusedView == VIEW_NAME) {
                    xpTrackerView?.revalidate()
                    xpTrackerView?.repaint()
                }

                updateWidget(xpWidget, xp)
            }
        }

        if (SwingUtilities.isEventDispatchThread()) {
            ensureOnEdt.run()
        } else {
            SwingUtilities.invokeLater(ensureOnEdt)
        }
    }

    fun updateWidget(xpWidget: XPWidget, xp: Int) {
        val (currentLevel, xpGainedSinceLastLevel) = XPTable.getLevelForXp(xp)

        var xpGainedSinceLastUpdate = xp - xpWidget.previousXp
        xpWidget.totalXpGained += xpGainedSinceLastUpdate
        updateTotalXPWidget(xpGainedSinceLastUpdate)

        val progress: Double
        if (currentLevel >= 99) {
            progress = 100.0 // Set progress to 100% if the level is 99 or above
            xpWidget.xpLeftLabel.text = "" // Hide XP Left when level is 99
            xpWidget.actionsRemainingLabel.text = ""
        } else {
            val nextLevelXp = XPTable.getXpRequiredForLevel(currentLevel + 1)
            val xpLeft = nextLevelXp - xp
            progress = xpGainedSinceLastLevel.toDouble() / (nextLevelXp - XPTable.getXpRequiredForLevel(currentLevel)) * 100
            val xpLeftstr = formatNumber(xpLeft)
            xpWidget.xpLeftLabel.text = formatHtmlLabelText("XP Left: ", primaryColor, xpLeftstr, secondaryColor)
            if(COMBAT_SKILLS.contains(xpWidget.skillId)) {
                if(LootTrackerView.lastConfirmedKillNpcId != -1 && npcHitpointsMap.isNotEmpty()) {
                    val npcHP = npcHitpointsMap[LootTrackerView.lastConfirmedKillNpcId]
                    val xpPerKill = when (xpWidget.skillId) {
                        3 -> playerXPMultiplier * (npcHP ?: 1) // Hitpoints
                        else -> playerXPMultiplier * (npcHP ?: 1) * 4 // Combat XP for other skills
                    }
                    val remainingKills = xpLeft / xpPerKill
                    xpWidget.actionsRemainingLabel.text = formatHtmlLabelText("Kills: ", primaryColor, remainingKills.toString(), secondaryColor)
                }
            } else {
                if(xpGainedSinceLastUpdate == 0)
                    xpGainedSinceLastUpdate = 1 // Avoid possible divide by 0
                val remainingActions = (xpLeft / xpGainedSinceLastUpdate).coerceAtLeast(1)
                xpWidget.actionsRemainingLabel.text = formatHtmlLabelText("Actions: ", primaryColor, remainingActions.toString(), secondaryColor)
            }
        }

        val formattedXp = formatNumber(xpWidget.totalXpGained)
        xpWidget.xpGainedLabel.text = formatHtmlLabelText("XP Gained: ", primaryColor, formattedXp, secondaryColor)

        xpWidget.progressBar.updateProgress(progress, currentLevel, if (currentLevel < 99) currentLevel + 1 else 99, focusedView == VIEW_NAME)

        xpWidget.previousXp = xp
        if (focusedView == VIEW_NAME)
            xpWidget.container.repaint()
    }


    private fun updateTotalXPWidget(xpGainedSinceLastUpdate: Int) {
        val totalXPWidget = totalXPWidget ?: return
        totalXPWidget.totalXpGained += xpGainedSinceLastUpdate
        val formattedXp = formatNumber(totalXPWidget.totalXpGained)
        totalXPWidget.xpGainedLabel.text = formatHtmlLabelText("Gained: ", primaryColor, formattedXp, secondaryColor)
        if (focusedView == VIEW_NAME)
            totalXPWidget.container.repaint()
    }


    fun resetXPTracker(xpTrackerView: JPanel) {
        xpTrackerView.removeAll()
        val popupMenu = createResetMenu()

        xpTrackerView.add(Box.createVerticalStrut(5))
        xpTrackerView.add(createTotalWidgetContainer(popupMenu))
        xpTrackerView.add(Box.createVerticalStrut(5))

        initialXP.clear()
        xpWidgets.clear()

        xpTrackerView.revalidate()
        if (focusedView == VIEW_NAME) {
            xpTrackerView.repaint()
        }
    }

    fun createTotalXPWidget(): XPWidget {
        val widgetPanel = WidgetPanel(
            widgetWidth = ViewConstants.DEFAULT_WIDGET_SIZE.width,
            widgetHeight = ViewConstants.TOTAL_XP_WIDGET_SIZE.height,
            addDefaultPadding = false,
            paddingTop = 10,
            paddingBottom = 10,
            paddingRight = 10,
            paddingLeft = 10
        )

        val bufferedImageSprite = getBufferedImageFromSprite(API.GetSprite(LVL_ICON))
        val (_, imageContainer) = Helpers.createImageCanvasComponents(
            bufferedImageSprite,
            borderInsets = Insets(0, 0, 0, 5)
        )


        val textPanel = JPanel(GridLayout(2, 1, 5, 0)).apply {
            background = WIDGET_COLOR
        }

        val xpGainedLabel = createMetricLabel("Gained:")
        val xpPerHourLabel = createMetricLabel("XP /hr:")

        textPanel.add(xpGainedLabel)
        textPanel.add(xpPerHourLabel)

        widgetPanel.setFixedSize(
            ViewConstants.DEFAULT_WIDGET_SIZE.width,
            ViewConstants.TOTAL_XP_WIDGET_SIZE.height
        )

        widgetPanel.add(imageContainer, BorderLayout.WEST)
        widgetPanel.add(textPanel, BorderLayout.CENTER)

        return XPWidget(
            skillId = -1,
            container = widgetPanel,
            xpGainedLabel = xpGainedLabel,
            xpLeftLabel = createMetricLabel("XP Left:"),
            xpPerHourLabel = xpPerHourLabel,
            progressBar = ProgressBar(0.0, Color.BLACK), // Unused
            totalXpGained = 0,
            startTime = System.currentTimeMillis(),
            previousXp = 0,
            actionsRemainingLabel = JLabel().apply { font = widgetFont },
        )
    }


    fun createXPTrackerView() {
        val widgetViewPanel = BaseView(VIEW_NAME, addDefaultSpacing = false).apply {
            add(Box.createVerticalStrut(5))
        }

        val popupMenu = createResetMenu()
        widgetViewPanel.add(createTotalWidgetContainer(popupMenu))
        widgetViewPanel.add(Box.createVerticalStrut(5))

        xpTrackerView = widgetViewPanel

        // Preload skill icons to avoid first-drop lag
        try {
            for (i in 0 until 24) {
                getSkillIcon(i)
            }
        } catch (_: Exception) { }
    }


    fun createResetMenu(): JPopupMenu {
        val popupMenu = PopupMenuComponent()
        popupMenu.addMenuItem("Reset Tracker") {
            plugin.registerDrawAction { resetXPTracker(xpTrackerView!!) }
        }
        return popupMenu
    }

    fun removeXPWidgetMenu(toRemove: Container, skillId: Int): JPopupMenu {
        val popupMenu = PopupMenuComponent()

        popupMenu.addMenuItem("Reset") {
            xpWidgets[skillId]?.let { widget ->
                // Baseline at current XP and clear per-widget counters
                initialXP[skillId] = widget.previousXp
                widget.totalXpGained = 0
                widget.startTime = System.currentTimeMillis()

                // Recompute labels/progress for current XP without adding totals
                updateWidget(widget, widget.previousXp)
            }
        }

        popupMenu.addMenuItem("Remove") {
            // Reset the per-skill baseline to the current XP so next widget starts fresh
            xpWidgets[skillId]?.let { widget ->
                initialXP[skillId] = widget.previousXp
            }
            // Remove widget container and following spacer if present
            xpTrackerView?.let { parent ->
                val components = parent.components
                val toRemoveIndex = components.indexOf(toRemove)
                if (toRemoveIndex >= 0 && toRemoveIndex < components.size - 1) {
                    val nextComponent = components[toRemoveIndex + 1]
                    if (nextComponent is Box.Filler) {
                        parent.remove(nextComponent)
                    }
                }
                parent.remove(toRemove)
                xpWidgets.remove(skillId)
                parent.revalidate()
                if (focusedView == VIEW_NAME) parent.repaint()
            }
        }
        return popupMenu
    }


    fun createXPWidget(skillId: Int, previousXp: Int): XPWidget {
        val widgetPanel = WidgetPanel(
            widgetWidth = ViewConstants.DEFAULT_WIDGET_SIZE.width,
            widgetHeight = 56,
            addDefaultPadding = false
        )

        val iconContainer = createIconContainer(getSkillIcon(skillId))

        val textPanel = JPanel(GridLayout(2, 2, 5, 0)).apply {
            background = WIDGET_COLOR
        }

        val xpGainedLabel = createMetricLabel("XP Gained:")
        val xpLeftLabel = createMetricLabel("XP Left:", "0K")
        val xpPerHourLabel = createMetricLabel("XP /hr:")
        val actionsTitle = if (COMBAT_SKILLS.contains(skillId)) "Kills:" else "Actions:"
        val actionsLabel = createMetricLabel(actionsTitle)

        val progressBar = ProgressBar(0.0, getProgressBarColor(skillId)).apply {
            setFixedSize(160, 22)
        }

        val progressPanel = JPanel(BorderLayout()).apply {
            background = WIDGET_COLOR
            add(progressBar, BorderLayout.CENTER)
        }

        textPanel.add(xpGainedLabel)
        textPanel.add(xpLeftLabel)
        textPanel.add(xpPerHourLabel)
        textPanel.add(actionsLabel)

        widgetPanel.add(iconContainer, BorderLayout.WEST)
        widgetPanel.add(textPanel, BorderLayout.CENTER)
        widgetPanel.add(progressPanel, BorderLayout.SOUTH)

        widgetPanel.revalidate()
        if(focusedView == VIEW_NAME)
            widgetPanel.repaint()

        return XPWidget(
            skillId = skillId,
            container = widgetPanel,
            xpGainedLabel = xpGainedLabel,
            xpLeftLabel = xpLeftLabel,
            xpPerHourLabel = xpPerHourLabel,
            progressBar = progressBar,
            totalXpGained = 0,
            actionsRemainingLabel = actionsLabel,
            startTime = System.currentTimeMillis(),
            previousXp = previousXp
        )
    }

    fun wrappedWidget(component: Component, padding: Int = 7, innerPadding: Int = 0): Container {
        val outerPanelSize = Dimension(
                component.preferredSize.width + 2 * padding,
                component.preferredSize.height + 2 * padding
        )
        val outerPanel = JPanel(GridBagLayout()).apply {
            background = WIDGET_COLOR
            setFixedSize(outerPanelSize)
        }
        val innerPanel = JPanel(BorderLayout()).apply {
            background = WIDGET_COLOR
            setFixedSize(component.preferredSize)
            if (innerPadding > 0) {
                border = BorderFactory.createEmptyBorder(innerPadding, innerPadding, innerPadding, innerPadding)
            }
            add(component, BorderLayout.CENTER)
        }
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.CENTER
        }
        outerPanel.add(innerPanel, gbc)
        return outerPanel
    }
}
