package KondoKit.views

import KondoKit.util.Helpers
import KondoKit.util.Helpers.formatHtmlLabelText
import KondoKit.util.ImageCanvas
import KondoKit.util.SpriteToBufferedImage.getBufferedImageFromSprite
import KondoKit.ui.BaseView
import KondoKit.ui.OnKillingBlowNPCCallback
import KondoKit.ui.OnPostClientTickCallback
import KondoKit.ui.ViewConstants
import KondoKit.ui.View
import KondoKit.util.attachPopupMenu
import KondoKit.ui.components.XPWidget
import KondoKit.util.setFixedSize
import KondoKit.views.XPTrackerView.wrappedWidget
import KondoKit.ui.components.PopupMenuComponent
import KondoKit.ui.components.ProgressBar
import KondoKit.ui.components.LabelComponent
import KondoKit.ui.components.WidgetPanel
import KondoKit.plugin.Companion.TITLE_BAR_COLOR
import KondoKit.plugin.Companion.TOOLTIP_BACKGROUND
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.plugin.Companion.primaryColor
import KondoKit.plugin.Companion.registerDrawAction
import KondoKit.plugin.Companion.secondaryColor
import KondoKit.plugin.Companion.useLiveGEPrices
import KondoKit.plugin.StateManager.focusedView
import plugin.api.API
import rt4.*
import java.awt.*
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import KondoKit.util.GEPriceService
import java.text.DecimalFormat
import javax.swing.*
import kotlin.math.ceil

object LootTrackerView : View, OnPostClientTickCallback, OnKillingBlowNPCCallback {
    private const val SNAPSHOT_LIFESPAN = 10
    const val BAG_ICON = 900
    const val OPEN_BAG = 777
    val npcDeathSnapshots = mutableMapOf<Int, GroundSnapshot>()
    var gePriceMap = loadGEPrices()
    const val VIEW_NAME = "LOOT_TRACKER_VIEW"
    private val lootItemPanels = mutableMapOf<String, MutableMap<Int, Int>>()
    private val npcKillCounts = mutableMapOf<String, Int>()
    private var totalTrackerWidget: XPWidget? = null
    var lastConfirmedKillNpcId = -1
    private var customToolTipWindow: JWindow? = null
    var lootTrackerView: JPanel? = null
    override val name: String = VIEW_NAME
    override val iconSpriteId: Int = OPEN_BAG

    override val panel: JPanel
        get() = lootTrackerView ?: JPanel()

    override fun createView() {
        createLootTrackerView()
    }

    override fun registerFunctions() {
        KondoKit.plugin.registerPostClientTickCallback(this)
        KondoKit.plugin.registerKillingBlowNPCCallback(this)
    }

    override fun onPostClientTick() {
        lootTrackerView?.let { onPostClientTick(it) }
    }

    override fun onKillingBlowNPC(npcID: Int, x: Int, z: Int) {
        val preDeathSnapshot = takeGroundSnapshot(Pair(x,z))
        npcDeathSnapshots[npcID] = GroundSnapshot(preDeathSnapshot, Pair(x, z), 0)
    }

    fun loadGEPrices(): Map<String, String> {
        return if (useLiveGEPrices) {
            println("LootTracker: Loading Remote GE Prices")
            GEPriceService.loadRemotePrices()
        } else {
            println("LootTracker: Loading Local GE Prices")
            GEPriceService.loadLocalPrices { Helpers.readResourceText("res/item_configs.json") }
        }
    }



    fun createLootTrackerView() {
        lootTrackerView = BaseView(VIEW_NAME, addDefaultSpacing = false).apply {
            add(Box.createVerticalStrut(5))
            totalTrackerWidget = createTotalLootWidget()

            val wrapped = wrappedWidget(totalTrackerWidget!!.container, padding = 0)
            val popupMenu = resetLootTrackerMenu()
            wrapped.attachPopupMenu(popupMenu, includeChildren = true)
            add(wrapped)
            add(Box.createVerticalStrut(8))
            revalidate()
            if(focusedView == VIEW_NAME)
                repaint()
        }
    }

    private fun updateTotalKills() {
        totalTrackerWidget?.let {
            it.totalXpGained += 1
            it.xpGainedLabel.text = formatHtmlLabelText("Total Count: ", primaryColor, it.totalXpGained.toString(), secondaryColor)
            it.xpGainedLabel.repaint()
        }
    }

    private fun updateTotalValue(newVal: Int) {
        totalTrackerWidget?.let {
            it.previousXp += newVal
            it.xpPerHourLabel.text = formatHtmlLabelText("Total Value: ", primaryColor, formatValue(it.previousXp) + " gp", secondaryColor)
            it.container.repaint()
        }
    }

    private fun createTotalLootWidget(): XPWidget {
        val bufferedImageSprite = getBufferedImageFromSprite(API.GetSprite(BAG_ICON))
        val l1 = createLabel(formatHtmlLabelText("Total Value: ", primaryColor, "0" + " gp", secondaryColor))
        val l2 = createLabel(formatHtmlLabelText("Total Count: ", primaryColor, "0", secondaryColor))
        return XPWidget(
            skillId = -1,
            container = createWidgetPanel(bufferedImageSprite,l2,l1),
            xpGainedLabel = l2,
            xpLeftLabel = JLabel(),
            actionsRemainingLabel = JLabel(),
            xpPerHourLabel = l1,
            progressBar = ProgressBar(0.0, Color(0,0,0)), // unused.
            totalXpGained = 0,
            startTime = System.currentTimeMillis(),
            previousXp = 0
        )
    }

    private fun createWidgetPanel(bufferedImageSprite: BufferedImage, l1 : JLabel, l2 : JLabel): WidgetPanel {
        val (_, imageContainer) = Helpers.createImageCanvasComponents(
            bufferedImageSprite,
            borderInsets = Insets(-2, 0, 0, 0)
        )

        return WidgetPanel(
            widgetWidth = ViewConstants.DEFAULT_WIDGET_SIZE.width,
            widgetHeight = ViewConstants.TOTAL_XP_WIDGET_SIZE.height,
            addDefaultPadding = false,
            paddingTop = 10,
            paddingBottom = 10,
            paddingRight = 10,
            paddingLeft = 10
        ).apply {
            layout = BorderLayout(5, 0)
            setFixedSize(
                ViewConstants.DEFAULT_WIDGET_SIZE.width,
                ViewConstants.TOTAL_XP_WIDGET_SIZE.height
            )
            add(imageContainer, BorderLayout.WEST)
            add(createTextPanel(l1,l2), BorderLayout.CENTER)
        }
    }

    private fun createTextPanel(l1 : JLabel, l2: JLabel): JPanel {
        return JPanel(GridLayout(2, 1, 5, 0)).apply {
            background = WIDGET_COLOR
            add(l1)
            add(l2)
        }
    }

    private fun createLabel(text: String): LabelComponent =
        LabelComponent(text, alignment = JLabel.LEFT)

    private fun addItemToLootPanel(lootTrackerView: JPanel, drop: Item, npcName: String) {
        findLootItemsPanel(lootTrackerView, npcName)?.let { lootPanel ->
            val itemQuantities = lootItemPanels.getOrPut(npcName) { mutableMapOf() }
            val newQuantity = itemQuantities.merge(drop.id, drop.quantity, Int::plus) ?: drop.quantity

            lootPanel.components.filterIsInstance<JPanel>().find { it.getClientProperty("itemId") == drop.id }
                ?.let { updateItemPanelIcon(it, drop.id, newQuantity) }
                ?: addNewItemToPanel(lootPanel, drop, newQuantity)

            val totalItems = lootItemPanels[npcName]?.size ?: 0
            val rowsNeeded = ceil(totalItems / 6.0).toInt()
            val lootPanelHeight = rowsNeeded * (40)

            val size = Dimension(lootPanel.width, lootPanelHeight + 32)
            (lootPanel.parent as? Component)?.setFixedSize(size)
            lootPanel.parent.revalidate()
            lootPanel.parent.repaint()
            lootPanel.revalidate()

            if(focusedView == VIEW_NAME)
                lootPanel.repaint()
        }
    }

    private fun addNewItemToPanel(lootPanel: JPanel, drop: Item, newQuantity: Int) {
        val itemPanel = createItemPanel(drop.id, newQuantity)
        lootPanel.add(itemPanel)
    }

    private fun createItemPanel(itemId: Int, quantity: Int): JPanel {
        val bufferedImageSprite = getBufferedImageFromSprite(API.GetObjSprite(itemId, quantity, true, 1, 3153952))

        val itemPanel = FixedSizePanel(Dimension(36, 32)).apply {
            background = WIDGET_COLOR

            val imageCanvas = ImageCanvas(bufferedImageSprite).apply {
                setFixedSize(36, 32)
                background = WIDGET_COLOR
            }

            add(imageCanvas, BorderLayout.CENTER)

            putClientProperty("itemId", itemId)

            imageCanvas.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    showCustomToolTip(e.point, itemId,quantity,imageCanvas)
                }

                override fun mouseExited(e: MouseEvent) {
                    hideCustomToolTip()
                }
            })
        }

        return itemPanel
    }

    fun showCustomToolTip(location: Point, itemId: Int, quantity: Int, parentComponent: ImageCanvas) {
        val itemDef = ObjTypeList.get(itemId)
        val gePricePerItem = gePriceMap[itemDef.id.toString()]?.toInt() ?: 0
        val totalGePrice = gePricePerItem * quantity
        val totalHaPrice = itemDef.cost * quantity
        val geText = if (quantity > 1) " (${formatValue(gePricePerItem)} ea)" else ""
        val haText = if (quantity > 1) " (${formatValue(itemDef.cost)} ea)" else ""
        val bgColor = Helpers.colorToHex(TOOLTIP_BACKGROUND)
        val textColor = Helpers.colorToHex(secondaryColor)
        val text = "<html><div style='color: "+textColor+"; background-color: "+bgColor+"; padding: 3px;'>" +
                "${itemDef.name} x $quantity<br>" +
                "GE: ${formatValue(totalGePrice)} ${geText}<br>" +
                "HA: ${formatValue(totalHaPrice)} ${haText}</div></html>"

        val tooltipFont = ViewConstants.FONT_RUNESCAPE_SMALL_16
        if (customToolTipWindow == null) {
            customToolTipWindow = JWindow().apply {
                contentPane = JLabel(text).apply {
                    border = BorderFactory.createLineBorder(Color.BLACK)
                    isOpaque = true
                    background = TOOLTIP_BACKGROUND
                    foreground = Color.WHITE
                    font = tooltipFont
                }
                pack()
            }
        }

        val screenLocation = parentComponent.locationOnScreen
        customToolTipWindow!!.setLocation(screenLocation.x + location.x, screenLocation.y + location.y + 20)
        customToolTipWindow!!.isVisible = true
    }

    fun hideCustomToolTip() {
        customToolTipWindow?.isVisible = false
        customToolTipWindow = null  // Nullify the global instance
    }


    private fun updateItemPanelIcon(panel: JPanel, itemId: Int, quantity: Int) {
        panel.removeAll()
        panel.add(createItemPanel(itemId, quantity).components[0], BorderLayout.CENTER)
        panel.revalidate()
        if(focusedView == VIEW_NAME)
            panel.repaint()
    }

    private fun updateKillCountLabel(lootTrackerPanel: JPanel, npcName: String) {
        lootTrackerPanel.components.filterIsInstance<JPanel>().forEach { childFramePanel ->
            (childFramePanel.components.firstOrNull { it is JPanel } as? JPanel)?.components
                ?.filterIsInstance<JLabel>()?.find { it.name == "killCountLabel_$npcName" }
                ?.apply {
                    text = formatHtmlLabelText(npcName, secondaryColor, " x ${npcKillCounts[npcName]}", primaryColor)
                    revalidate()
                    repaint()
                }
        }
    }

    private fun updateValueLabel(lootTrackerPanel: JPanel, valueOfNewDrops: String, npcName: String) {
        lootTrackerPanel.components.filterIsInstance<JPanel>().forEach { childFramePanel ->
            (childFramePanel.components.firstOrNull { it is JPanel } as? JPanel)?.components
                ?.filterIsInstance<JLabel>()?.find { it.name == "valueLabel_$npcName" }
                ?.apply {
                    val newValue = (getClientProperty("val") as? Int ?: 0) + valueOfNewDrops.toInt()
                    text = "${formatValue(newValue)} gp"
                    foreground = primaryColor
                    putClientProperty("val", newValue)
                    revalidate()
                    if(focusedView == VIEW_NAME)
                        repaint()
                }
        }
    }

    private fun formatValue(value: Int): String {
        return when {
            value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
            value >= 10_000 -> "%.1fK".format(value / 1_000.0)
            else -> DecimalFormat("#,###").format(value)
        }
    }

    private fun findLootItemsPanel(container: Container, npcName: String): JPanel? {
        return container.components.filterIsInstance<JPanel>().firstOrNull { it.name == "LOOT_ITEMS_$npcName" }
            ?: container.components.filterIsInstance<Container>().mapNotNull { findLootItemsPanel(it, npcName) }.firstOrNull()
    }


    fun onPostClientTick(lootTrackerView: JPanel) {
        val toRemove = mutableListOf<Int>()

        npcDeathSnapshots.entries.forEach { entry ->
            val (npcId, snapshot) = entry
            val postDeathSnapshot = takeGroundSnapshot(Pair(snapshot.location.first, snapshot.location.second))
            val newDrops = postDeathSnapshot.subtract(snapshot.items)

            if (newDrops.isNotEmpty()) {
                val npcName = NpcTypeList.get(npcId).name
                lastConfirmedKillNpcId = npcId
                handleNewDrops(npcName.toString(), newDrops, lootTrackerView)
                toRemove.add(npcId)
            } else if (snapshot.age >= SNAPSHOT_LIFESPAN) {
                toRemove.add(npcId)
            } else {
                snapshot.age++
            }
        }

        toRemove.forEach { npcId ->
            npcDeathSnapshots.remove(npcId)
        }
    }


    fun takeGroundSnapshot(location: Pair<Int, Int>): Set<Item> {
        return getGroundItemsAt(location.first, location.second).toSet()
    }

    private fun getGroundItemsAt(x: Int, z: Int): List<Item> {
        val objstacknodeLL = SceneGraph.objStacks[Player.plane][x][z] ?: return emptyList()
        val items = mutableListOf<Item>()
        var itemNode = objstacknodeLL.head() as ObjStackNode?
        while (itemNode != null) {
            items.add(Item(itemNode.value.type, itemNode.value.amount))
            itemNode = objstacknodeLL.next() as ObjStackNode?
        }
        return items
    }

    private fun handleNewDrops(npcName: String, newDrops: Set<Item>, lootTrackerView: JPanel) {
        findLootItemsPanel(lootTrackerView, npcName)?.let {
        } ?: run {
            lootTrackerView.add(createLootFrame(npcName))
            lootTrackerView.add(Box.createVerticalStrut(8))
            lootTrackerView.revalidate()
            if(focusedView == VIEW_NAME)
                lootTrackerView.repaint()
        }

        npcKillCounts[npcName] = npcKillCounts.getOrDefault(npcName, 0) + 1
        updateKillCountLabel(lootTrackerView, npcName)
        updateTotalKills()
        newDrops.forEach { drop ->
            val geValue = (gePriceMap[drop.id.toString()]?.toInt() ?: 0) * drop.quantity
            updateValueLabel(lootTrackerView, geValue.toString(), npcName)
            registerDrawAction {  addItemToLootPanel(lootTrackerView, drop, npcName) }
            updateTotalValue(geValue)
        }
    }

    private fun createLootFrame(npcName: String): JPanel {
        val childFramePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = WIDGET_COLOR
            minimumSize = Dimension(ViewConstants.DEFAULT_WIDGET_SIZE.width, 0)
            maximumSize = Dimension(ViewConstants.DEFAULT_WIDGET_SIZE.width, 700)
            name = "HELLO_WORLD"
        }

        val labelPanel = JPanel(BorderLayout()).apply {
            background = TITLE_BAR_COLOR
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            setFixedSize(ViewConstants.DEFAULT_WIDGET_SIZE.width, 24)
        }

        val killCount = npcKillCounts.getOrPut(npcName) { 0 }
        val countLabel = JLabel(formatHtmlLabelText(npcName, secondaryColor, " x $killCount", primaryColor)).apply {
            foreground = secondaryColor
            font = ViewConstants.FONT_RUNESCAPE_SMALL_16
            horizontalAlignment = JLabel.LEFT
            name = "killCountLabel_$npcName"
        }

        val valueLabel = JLabel("0 gp").apply {
            foreground = secondaryColor
            font = ViewConstants.FONT_RUNESCAPE_SMALL_16
            horizontalAlignment = JLabel.RIGHT
            name = "valueLabel_$npcName"
        }

        labelPanel.add(countLabel, BorderLayout.WEST)
        labelPanel.add(valueLabel, BorderLayout.EAST)

        val lootPanel = JPanel().apply {
            background = WIDGET_COLOR
            border = BorderFactory.createLineBorder(WIDGET_COLOR, 5)
            name = "LOOT_ITEMS_$npcName"
            layout = GridLayout(0, 6, 1, 1) // 6 columns, rows will be added dynamically
        }

        lootItemPanels[npcName] = mutableMapOf()

        childFramePanel.add(labelPanel)
        childFramePanel.add(lootPanel)

        val popupMenu = removeLootFrameMenu(childFramePanel, npcName)
        labelPanel.attachPopupMenu(popupMenu)
        return childFramePanel
    }

    private fun removeLootFrameMenu(toRemove: JPanel, npcName: String): JPopupMenu {
        val popupMenu = PopupMenuComponent()
        popupMenu.addMenuItem("Remove") {
            lootItemPanels[npcName]?.clear()
            npcKillCounts[npcName] = 0
            lootTrackerView?.let { parent ->
                val components = parent.components
                val toRemoveIndex = components.indexOf(toRemove)
                if (toRemoveIndex >= 0 && toRemoveIndex < components.size - 1) {
                    val nextComponent = components[toRemoveIndex + 1]
                    if (nextComponent is Box.Filler) {
                        parent.remove(nextComponent)
                    }
                }
                parent.remove(toRemove)
                parent.revalidate()
                parent.repaint()
            }
        }
        return popupMenu
    }


    private fun resetLootTrackerMenu(): JPopupMenu {
        val popupMenu = PopupMenuComponent()

        popupMenu.addMenuItem("Reset Loot Tracker") {
            registerDrawAction {
                resetLootTracker()
            }
        }
        return popupMenu
    }

    private fun resetLootTracker(){
        lootTrackerView?.removeAll()
        npcKillCounts.clear()
        lootItemPanels.clear()
        totalTrackerWidget = createTotalLootWidget()

        val wrapped = wrappedWidget(totalTrackerWidget!!.container, padding = 0)
        val _popupMenu = resetLootTrackerMenu()
        wrapped.attachPopupMenu(_popupMenu, includeChildren = true)
        lootTrackerView?.add(Box.createVerticalStrut(5))
        lootTrackerView?.add(wrapped)
        lootTrackerView?.add(Box.createVerticalStrut(8))
        lootTrackerView?.revalidate()
        lootTrackerView?.repaint()
    }

    class FixedSizePanel(private val fixedSize: Dimension) : JPanel() {
        override fun getPreferredSize(): Dimension {
            return fixedSize
        }

        override fun getMinimumSize(): Dimension {
            return fixedSize
        }

        override fun getMaximumSize(): Dimension {
            return fixedSize
        }
    }

    data class GroundSnapshot(val items: Set<Item>, val location: Pair<Int, Int>, var age: Int)
    data class Item(val id: Int, val quantity: Int)
}
