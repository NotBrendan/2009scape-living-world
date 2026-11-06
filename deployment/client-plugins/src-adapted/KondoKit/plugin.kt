package KondoKit

import KondoKit.util.Helpers.getSpriteId
import KondoKit.util.Helpers.showAlert
import KondoKit.views.*
import KondoKit.ui.OnUpdateCallback
import KondoKit.ui.OnDrawCallback
import KondoKit.ui.OnXPUpdateCallback
import KondoKit.ui.OnKillingBlowNPCCallback
import KondoKit.ui.OnPostClientTickCallback
import KondoKit.util.AltCanvas
import KondoKit.util.SpriteToBufferedImage.getBufferedImageFromSprite
import KondoKit.util.ImageCanvas
import KondoKit.util.setFixedSize
import KondoKit.ui.ViewConstants
import KondoKit.ui.theme.Themes.Theme
import KondoKit.ui.theme.Themes.ThemeType
import KondoKit.ui.theme.Themes.getTheme
import KondoKit.ui.components.ScrollablePanel
import plugin.Plugin
import plugin.api.*
import plugin.api.API.*
import plugin.api.FontColor.fromColor
import rt4.*
import rt4.DisplayMode
import rt4.GameShell.canvas
import rt4.GameShell.frame
import rt4.client.js5Archive8
import rt4.client.mainLoadState
import java.awt.*
import java.awt.Font
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import KondoKit.ui.View
import KondoKit.util.Helpers


@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Exposed(val description: String = "")

class plugin : Plugin() {
    companion object {
        val IMAGE_SIZE = Dimension(25, 23)

        // Default Theme Colors
        var WIDGET_COLOR = Color(30, 30, 30)
        var TITLE_BAR_COLOR = Color(21, 21, 21)
        var VIEW_BACKGROUND_COLOR = Color(40, 40, 40)
        var primaryColor = Color(165, 165, 165)   // Color for "XP Gained:"
        var secondaryColor = Color(255, 255, 255) // Color for "0"
        var POPUP_BACKGROUND = Color(45, 45, 45)
        var POPUP_FOREGROUND = Color(220, 220, 220)
        var TOOLTIP_BACKGROUND = Color(50,50,50)
        var SCROLL_BAR_COLOR = Color(64, 64, 64)
        var PROGRESS_BAR_FILL = Color(61, 56, 49)
        var NAV_TINT: Color? = null
        var NAV_GREYSCALE = false
        var BOOST = 1f

        var appliedTheme = ThemeType.RUNELITE

        @Exposed("Theme colors for KondoKit, requires a relaunch to apply.")
        var theme = ThemeType.RUNELITE

        @Exposed("Default: true, Use Local JSON or the prices from the Live/Stable server API")
        var useLiveGEPrices = true

        @Exposed("Used to calculate Combat Actions until next level.")
        var playerXPMultiplier = 5

        @Exposed("Start minimized/collapsed by default")
        var launchMinimized = false

        @Exposed("Default 16 on Windows, 0 Linux/macOS. If Kondo is not " +
                "perfectly snapped to the edge of the game due to window chrome you can update this to fix it")
        var uiOffset = 0

        @Exposed("Stretched/Scaled Fixed Mode Support")
        var useScaledFixed = false

        const val FIXED_WIDTH = 765
        const val FIXED_HEIGHT = 503
        private const val NAVBAR_WIDTH = 30
        private const val MAIN_CONTENT_WIDTH = 242
        const val WRENCH_ICON = 907
        const val LOOT_ICON = 777
        const val MAG_SPRITE = 1423
        const val LVL_ICON = 898
        private lateinit var cardLayout: CardLayout
        private lateinit var mainContentPanel: JPanel
        private var rightPanelWrapper: JScrollPane? = null
        private var accumulatedTime = 0L
        private var reloadInterfaces = false
        private const val TICK_INTERVAL = 600L
        private var pluginsReloaded = false
        private var loginScreen = 160
        private var lastLogin = ""
        private var initialized = false
        private var lastClickTime = 0L
        private var lastUIOffset = 0
        private var themeName = "RUNELITE"
        private const val HIDDEN_VIEW = "HIDDEN"
        private var altCanvas: AltCanvas? = null
        private val drawActions = mutableListOf<() -> Unit>()
        private val views = mutableListOf<View>()
        private val updateCallbacks = mutableListOf<OnUpdateCallback>()
        private val drawCallbacks = mutableListOf<OnDrawCallback>()
        private val xpUpdateCallbacks = mutableListOf<OnXPUpdateCallback>()
        private val killingBlowNPCCallbacks = mutableListOf<OnKillingBlowNPCCallback>()
        private val postClientTickCallbacks = mutableListOf<OnPostClientTickCallback>()

        fun registerDrawAction(action: () -> Unit) {
            synchronized(drawActions) {
                drawActions.add(action)
            }
        }
        
        fun registerUpdateCallback(callback: OnUpdateCallback) {
            updateCallbacks.add(callback)
        }
        
        fun registerDrawCallback(callback: OnDrawCallback) {
            drawCallbacks.add(callback)
        }
        
        fun registerXPUpdateCallback(callback: OnXPUpdateCallback) {
            xpUpdateCallbacks.add(callback)
        }
        
        fun registerKillingBlowNPCCallback(callback: OnKillingBlowNPCCallback) {
            killingBlowNPCCallbacks.add(callback)
        }
        
        fun registerPostClientTickCallback(callback: OnPostClientTickCallback) {
            postClientTickCallbacks.add(callback)
        }
    }

    override fun Init() {
        System.setProperty("sun.java2d.opengl", "false")
        System.setProperty("awt.useSystemAAFontSettings", "off")
        System.setProperty("swing.aatext", "false")
    }

    override fun OnLogin() {
        if (lastLogin != "" && lastLogin != Player.usernameInput.toString()) {
            XPTrackerView.xpTrackerView?.let { XPTrackerView.resetXPTracker(it) }
        }
        lastLogin = Player.usernameInput.toString()
    }

    override fun OnMiniMenuCreate(currentEntries: Array<out MiniMenuEntry>?) {
        if (currentEntries != null) {
            for ((index, entry) in currentEntries.withIndex()) {
                if (entry.type == MiniMenuType.PLAYER && index == currentEntries.size - 1) {
                    val input = entry.subject
                    val cleanedInput = input
                            .trim()
                            .replace(Regex("<col=[0-9a-fA-F]{6}>"), "")
                            .replace(Regex("<img=\\d+>"), "")
                            .replace(Regex("\\(level: \\d+\\)"), "")
                            .trim()
                    InsertMiniMenuEntry("Lookup", entry.subject, searchHiscore(cleanedInput))
                }
            }
        }
    }

    override fun OnPluginsReloaded(): Boolean {
        if (!initialized) return true
        // Ensure Swing updates happen on the EDT to avoid flicker
        SwingUtilities.invokeLater {
            updateDisplaySettings()
            rightPanelWrapper?.let { wrapper ->
                wrapper.ignoreRepaint = true
                try {
                    val parent = wrapper.parent
                    val wrapperNeedsAttach = parent != frame
                    if (wrapperNeedsAttach) {
                        parent?.remove(wrapper)
                        frame.layout = BorderLayout()
                        frame.add(wrapper, BorderLayout.EAST)
                    }
                    wrapper.revalidate()
                    wrapper.repaint()
                } finally {
                    wrapper.ignoreRepaint = false
                }
            }
            frame.revalidate()
            frame.repaint()
            
            ReflectiveEditorView.addPlugins(ReflectiveEditorView.panel)
        }
        pluginsReloaded = true
        reloadInterfaces = true
        return true
    }

    override fun OnXPUpdate(skillId: Int, xp: Int) {
        xpUpdateCallbacks.forEach { callback ->
            callback.onXPUpdate(skillId, xp)
        }
    }

    override fun Draw(timeDelta: Long) {
        if (GlRenderer.enabled && GlRenderer.canvasWidth != GameShell.canvasWidth) {
            GlRenderer.canvasWidth = GameShell.canvasWidth
            GlRenderer.setViewportBounds(0, 0, GameShell.canvasWidth, GameShell.canvasHeight)
        }

        if (pluginsReloaded) {
            SwingUtilities.invokeLater {
                ReflectiveEditorView.addPlugins(ReflectiveEditorView.panel)
            }
            pluginsReloaded = false
        }

        if (reloadInterfaces){
            InterfaceList.method3712(true) // Gets the resize working correctly
            reloadInterfaces = false
        }

        accumulatedTime += timeDelta
        if (accumulatedTime >= TICK_INTERVAL) {
            postClientTickCallbacks.forEach { callback ->
                callback.onPostClientTick()
            }
            accumulatedTime = 0L
        }

        drawCallbacks.forEach { callback ->
            callback.onDraw(timeDelta)
        }

        // Draw synced actions (that require to be done between glBegin and glEnd)
        if (drawActions.isNotEmpty()) {
            synchronized(drawActions) {
                val actionsCopy = drawActions.toList()
                drawActions.clear()
                for (action in actionsCopy) {
                    action()
                }
            }
        }

        // Init in the draw call so we know we are between glBegin and glEnd for HD
        if(!initialized && mainLoadState >= loginScreen) {
            initKondoUI()
        }
    }

    override fun LateDraw(timeDelta: Long) {
        if (!initialized) return
        if(GameShell.fullScreenFrame != null) {
            DisplayMode.setWindowMode(true, 0, FIXED_WIDTH, FIXED_HEIGHT)
            showAlert("Fullscreen is not supported by KondoKit. Disable the plugin first.",
                "Error",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }
        if(!useScaledFixed) return
        if(GetWindowMode() == WindowMode.FIXED){
            moveAltCanvasToFront()
        } else {
            moveCanvasToFront()
        }
        altCanvas?.updateGameImage()
    }

    override fun Update() {
        updateCallbacks.forEach { callback ->
            callback.onUpdate()
        }
    }

    override fun OnKillingBlowNPC(npcID: Int, x: Int, z: Int) {
        killingBlowNPCCallbacks.forEach { callback ->
            callback.onKillingBlowNPC(npcID, x, z)
        }
    }

    private fun allSpritesLoaded() : Boolean {
        try{
            for (i in 0 until 24) {
                if(!js5Archive8.isFileReady(getSpriteId(i))){
                    return false
                }
            }
            val otherIcons = arrayOf(LVL_ICON, MAG_SPRITE, LOOT_ICON, WRENCH_ICON, ViewConstants.COMBAT_LVL_SPRITE, LootTrackerView.BAG_ICON)
            for (icon in otherIcons) {
                if(!js5Archive8.isFileReady(icon)){
                    return false
                }
            }
        } catch (e : Exception){
            return false
        }
        return true
    }

    private fun updateDisplaySettings() {
        val applyDisplaySettings = {
            val mode = GetWindowMode()
            val currentScrollPaneWidth = if (mainContentPanel.isVisible) NAVBAR_WIDTH + MAIN_CONTENT_WIDTH else NAVBAR_WIDTH
            lastUIOffset = uiOffset

            // Ensure the scroll wrapper stays attached on the EAST edge even if the game resets the layout
            rightPanelWrapper?.let { wrapper ->
                val needsLayoutReset = frame.layout !is BorderLayout
                val needsAttach = wrapper.parent != frame
                if (needsLayoutReset || needsAttach) {
                    wrapper.parent?.remove(wrapper)
                    frame.layout = BorderLayout()
                    frame.add(wrapper, BorderLayout.EAST)
                    if (altCanvas != null) {
                        moveAltCanvasToFront()
                    } else {
                        moveCanvasToFront()
                    }
                }
            }

            if(mode != WindowMode.FIXED) {
                destroyAltCanvas()
            } else if (useScaledFixed && altCanvas == null) {
                initAltCanvas()
            } else if (!useScaledFixed && altCanvas != null) {
                // Was using scaled fixed but toggled the setting
                // restore the original canvas
                moveCanvasToFront()
                destroyAltCanvas()
            }

            when (mode) {
                WindowMode.FIXED -> {
                    if (frame.width < FIXED_WIDTH + currentScrollPaneWidth + uiOffset) {
                        frame.setSize(FIXED_WIDTH + currentScrollPaneWidth + uiOffset, frame.height)
                    }

                    val difference = frame.width - (uiOffset + currentScrollPaneWidth)

                    if (useScaledFixed) {
                        GameShell.leftMargin = 0
                        val canvasWidth = difference + uiOffset / 2
                        val canvasHeight = frame.height - canvas.y // Restricting height to frame height

                        altCanvas?.size = Dimension(canvasWidth, canvasHeight)
                        altCanvas?.setLocation(0, canvas.y)
                        canvas.setLocation(0, canvas.y)
                    } else {
                        val difference = frame.width - (FIXED_WIDTH + uiOffset + currentScrollPaneWidth)
                        GameShell.leftMargin = difference / 2
                    }
                }

                WindowMode.RESIZABLE -> {
                    GameShell.canvasWidth = frame.width - (currentScrollPaneWidth + uiOffset)
                }
            }

            rightPanelWrapper?.preferredSize = Dimension(currentScrollPaneWidth, frame.height)
            rightPanelWrapper?.isDoubleBuffered = true
            rightPanelWrapper?.revalidate()
            rightPanelWrapper?.repaint()
            frame.validate()
        }

        if (SwingUtilities.isEventDispatchThread()) {
            applyDisplaySettings()
        } else {
            SwingUtilities.invokeLater { applyDisplaySettings() }
        }
    }

    fun OnKondoValueUpdated(){
        StoreData("kondoUseRemoteGE", useLiveGEPrices)
        StoreData("kondoTheme", theme.toString())
        if(appliedTheme != theme) {
            showAlert(
                "KondoKit Theme changes require a relaunch.",
                "KondoKit",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
        StoreData("kondoPlayerXPMultiplier", playerXPMultiplier)
        LootTrackerView.gePriceMap = LootTrackerView.loadGEPrices()
        StoreData("kondoLaunchMinimized", launchMinimized)
        StoreData("kondoUIOffset", uiOffset)
        StoreData("kondoScaledFixed", useScaledFixed)
        if(lastUIOffset != uiOffset){
            reloadInterfaces = true
        }
        updateDisplaySettings()
    }

    private fun initAltCanvas(){
        if(GetWindowMode() != WindowMode.FIXED || altCanvas != null) return
        if (frame != null) {
            altCanvas = AltCanvas().apply {
                preferredSize = Dimension(FIXED_WIDTH, FIXED_HEIGHT)
            }
            altCanvas?.let { frame.add(it) }
            moveAltCanvasToFront()
            frame.setComponentZOrder(rightPanelWrapper, 2)
        }
    }

    private fun destroyAltCanvas(){
        if (altCanvas == null) return
        moveCanvasToFront()
        frame.remove(altCanvas)
        altCanvas = null
    }

    private fun moveAltCanvasToFront(){
        if (altCanvas == null) return
        frame.setComponentZOrder(canvas, 2)
        frame.setComponentZOrder(altCanvas, 1)
        frame.setComponentZOrder(rightPanelWrapper, 0)
    }

    private fun moveCanvasToFront(){
        if (altCanvas == null) return
        frame.setComponentZOrder(altCanvas, 2)
        frame.setComponentZOrder(canvas, 1)
        frame.setComponentZOrder(rightPanelWrapper, 0)
    }

    private fun searchHiscore(username: String): Runnable {
        return Runnable {
            setActiveView(HiscoresView.VIEW_NAME)
            HiscoresView.hiScoreView?.let { hiscoresPanel ->
                HiscoresView.searchPlayerForHiscores(username, hiscoresPanel)
            } ?: run {
                println("hiscoresPanel is null")
            }
        }
    }

    private fun restoreSettings(){
        themeName = (GetData("kondoTheme") as? String) ?: "RUNELITE"
        useLiveGEPrices = (GetData("kondoUseRemoteGE") as? Boolean) ?: true
        playerXPMultiplier = (GetData("kondoPlayerXPMultiplier") as? Int) ?: 5
        val osName = System.getProperty("os.name").toLowerCase()
        uiOffset = (GetData("kondoUIOffset") as? Int) ?: if (osName.contains("win")) 16 else 0
        launchMinimized = (GetData("kondoLaunchMinimized") as? Boolean) ?: false
        useScaledFixed = (GetData("kondoScaledFixed") as? Boolean) ?: false
    }

    private fun initKondoUI(){
        DrawText(FontType.LARGE, fromColor(Color(16777215)), TextModifier.CENTER, "KondoKit Loading Sprites...", GameShell.canvasWidth/2, GameShell.canvasHeight/2)
        if(!allSpritesLoaded()) return
        val frame: Frame? = GameShell.frame
        if (frame != null) {
            restoreSettings()
            theme = ThemeType.valueOf(themeName)
            applyTheme(getTheme(theme))
            appliedTheme = theme
            configureLookAndFeel()

            cardLayout = CardLayout()
            mainContentPanel = JPanel(cardLayout).apply {
                border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
                background = VIEW_BACKGROUND_COLOR
                preferredSize = Dimension(MAIN_CONTENT_WIDTH, frame.height)
                isOpaque = true
            }

            val xpTrackerView = XPTrackerView
            val hiscoresView = HiscoresView
            val lootTrackerView = LootTrackerView
            val reflectiveEditorView = ReflectiveEditorView
            
            xpTrackerView.createView()
            hiscoresView.createView()
            lootTrackerView.createView()
            reflectiveEditorView.createView()
            
            views.add(xpTrackerView)
            views.add(hiscoresView)
            views.add(lootTrackerView)
            views.add(reflectiveEditorView)
            
            xpTrackerView.registerFunctions()
            hiscoresView.registerFunctions()
            lootTrackerView.registerFunctions()
            reflectiveEditorView.registerFunctions()

            mainContentPanel.add(ScrollablePanel(xpTrackerView.panel), xpTrackerView.name)
            mainContentPanel.add(ScrollablePanel(hiscoresView.panel), hiscoresView.name)
            mainContentPanel.add(ScrollablePanel(lootTrackerView.panel), lootTrackerView.name)
            mainContentPanel.add(ScrollablePanel(reflectiveEditorView.panel), reflectiveEditorView.name)

            val navPanel = Panel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = WIDGET_COLOR
                preferredSize = Dimension(NAVBAR_WIDTH, frame.height)
            }

            navPanel.add(createNavButton(xpTrackerView.iconSpriteId, xpTrackerView.name))
            navPanel.add(createNavButton(hiscoresView.iconSpriteId, hiscoresView.name))
            navPanel.add(createNavButton(lootTrackerView.iconSpriteId, lootTrackerView.name))
            navPanel.add(createNavButton(reflectiveEditorView.iconSpriteId, reflectiveEditorView.name))

            val rightPanel = Panel(BorderLayout()).apply {
                add(mainContentPanel, BorderLayout.CENTER)
                add(navPanel, BorderLayout.EAST)
            }

            rightPanelWrapper = JScrollPane(rightPanel).apply {
                preferredSize = Dimension(NAVBAR_WIDTH + MAIN_CONTENT_WIDTH, frame.height)
                background = VIEW_BACKGROUND_COLOR
                border = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            }

            val desiredView = if (launchMinimized) HIDDEN_VIEW else xpTrackerView.name
            // Commit layout synchronously on the EDT to avoid initial misplacement
            val commit = Runnable {
                frame.layout = BorderLayout()
                rightPanelWrapper?.let { frame.add(it, BorderLayout.EAST) }
                setActiveView(desiredView)
                frame.validate()
                frame.repaint()
            }
            if (SwingUtilities.isEventDispatchThread()) {
                commit.run()
            } else {
                try {
                    SwingUtilities.invokeAndWait(commit)
                } catch (e: Exception) {
                    // Fallback to async if invokeAndWait fails for any reason
                    SwingUtilities.invokeLater(commit)
                }
            }
            initialized = true
            pluginsReloaded = true
            updateDisplaySettings()
        }
    }

    private fun setActiveView(viewName: String) {
        val runUpdate: () -> Unit = {
            // Track visibility change to decide if we need to resize/reload interfaces
            val wasVisible = mainContentPanel.isVisible

            // Handle the visibility of the main content panel and card switch
            if (viewName == HIDDEN_VIEW) {
                mainContentPanel.isVisible = false
            } else {
                if (!mainContentPanel.isVisible) {
                    mainContentPanel.isVisible = true
                }
                cardLayout.show(mainContentPanel, viewName)
            }

            val visibilityChanged = wasVisible != mainContentPanel.isVisible

            // Batch painting to avoid intermediate repaints
            rightPanelWrapper?.ignoreRepaint = true
            try {
                if (visibilityChanged) {
                    // Only touch layout and client interfaces if width actually changes
                    updateDisplaySettings()
                    reloadInterfaces = true
                    rightPanelWrapper?.revalidate()
                    frame?.validate()
                } else {
                    // Just a card switch; avoid full frame revalidate
                    mainContentPanel.revalidate()
                }
            } finally {
                rightPanelWrapper?.ignoreRepaint = false
            }

            // Targeted repaint for snappy feedback
            if (visibilityChanged) {
                rightPanelWrapper?.repaint()
                frame?.repaint()
            } else {
                mainContentPanel.repaint()
            }
            StateManager.focusedView = viewName
        }

        if (SwingUtilities.isEventDispatchThread()) {
            runUpdate()
        } else {
            SwingUtilities.invokeLater { runUpdate() }
        }
    }

    private fun createNavButton(spriteId: Int, viewName: String): JPanel {
        val bufferedImageSprite = getBufferedImageFromSprite(GetSprite(spriteId), NAV_TINT, NAV_GREYSCALE, BOOST)
        val buttonSize = Dimension(NAVBAR_WIDTH, 32)
        val imageSize = Dimension((bufferedImageSprite.width / 1.2f).toInt(), (bufferedImageSprite.height / 1.2f).toInt())
        val cooldownDuration = 100L

        val actionListener = ActionListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime < cooldownDuration) {
                return@ActionListener
            }
            lastClickTime = currentTime

            if (StateManager.focusedView == viewName) {
                setActiveView("HIDDEN")
            } else {
                setActiveView(viewName)
            }
        }

        val imageCanvas = ImageCanvas(bufferedImageSprite).apply {
            background = WIDGET_COLOR
            setFixedSize(imageSize)
        }

        // Wrapping the ImageCanvas in another JPanel to prevent stretching
        val imageCanvasWrapper = JPanel().apply {
            layout = GridBagLayout() // Keeps the layout of the wrapped panel minimal
            setFixedSize(imageSize)
            isOpaque = false // No background for the wrapper
            add(imageCanvas) // Adding ImageCanvas directly, layout won't stretch it
        }

        val panelButton = JPanel().apply {
            layout = GridBagLayout()
            setFixedSize(buttonSize)
            background = WIDGET_COLOR
            isOpaque = true

            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.CENTER
                fill = GridBagConstraints.NONE // Prevents stretching
            }

            add(imageCanvasWrapper, gbc)

            val hoverListener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    background = WIDGET_COLOR.darker()
                    imageCanvas.fillColor = WIDGET_COLOR.darker()
                    imageCanvas.repaint()
                    repaint()
                }

                override fun mouseExited(e: MouseEvent?) {
                    background = WIDGET_COLOR
                    imageCanvas.fillColor = WIDGET_COLOR
                    imageCanvas.repaint()
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent?) {
                    actionListener.actionPerformed(null)
                }
            }

            addMouseListener(hoverListener)
            imageCanvas.addMouseListener(hoverListener)
        }

        return panelButton
    }

    private fun configureLookAndFeel(){
        loadFont()
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel")

            // Modify the UI properties to match theme
            UIManager.put("control", VIEW_BACKGROUND_COLOR)
            UIManager.put("info", VIEW_BACKGROUND_COLOR)
            UIManager.put("nimbusBase", WIDGET_COLOR)
            UIManager.put("nimbusBlueGrey", TITLE_BAR_COLOR)

            UIManager.put("nimbusDisabledText", primaryColor)
            UIManager.put("nimbusSelectedText", secondaryColor)
            UIManager.put("text", secondaryColor)

            UIManager.put("nimbusFocus", TITLE_BAR_COLOR)
            UIManager.put("nimbusInfoBlue", POPUP_BACKGROUND)
            UIManager.put("nimbusLightBackground", WIDGET_COLOR)
            UIManager.put("nimbusSelectionBackground", PROGRESS_BAR_FILL)

            UIManager.put("Button.background", WIDGET_COLOR)
            UIManager.put("Button.foreground", secondaryColor)

            UIManager.put("CheckBox.background", VIEW_BACKGROUND_COLOR)
            UIManager.put("CheckBox.foreground", secondaryColor)
            UIManager.put("CheckBox.icon", UIManager.getIcon("CheckBox.icon"))

            UIManager.put("ComboBox.background", WIDGET_COLOR)
            UIManager.put("ComboBox.foreground", secondaryColor)
            UIManager.put("ComboBox.selectionBackground", PROGRESS_BAR_FILL)
            UIManager.put("ComboBox.selectionForeground", primaryColor)
            UIManager.put("ComboBox.buttonBackground", WIDGET_COLOR)

            UIManager.put("Spinner.background", WIDGET_COLOR)
            UIManager.put("Spinner.foreground", secondaryColor)
            UIManager.put("Spinner.border", BorderFactory.createLineBorder(TITLE_BAR_COLOR))

            UIManager.put("TextField.background", WIDGET_COLOR)
            UIManager.put("TextField.foreground", secondaryColor)
            UIManager.put("TextField.caretForeground", secondaryColor)
            UIManager.put("TextField.border", BorderFactory.createLineBorder(TITLE_BAR_COLOR))

            UIManager.put("ScrollBar.thumb", WIDGET_COLOR)
            UIManager.put("ScrollBar.track", VIEW_BACKGROUND_COLOR)
            UIManager.put("ScrollBar.thumbHighlight", TITLE_BAR_COLOR)

            UIManager.put("ProgressBar.foreground", PROGRESS_BAR_FILL)
            UIManager.put("ProgressBar.background", WIDGET_COLOR)
            UIManager.put("ProgressBar.border", BorderFactory.createLineBorder(TITLE_BAR_COLOR))

            UIManager.put("ToolTip.background", VIEW_BACKGROUND_COLOR)
            UIManager.put("ToolTip.foreground", secondaryColor)
            UIManager.put("ToolTip.border", BorderFactory.createLineBorder(TITLE_BAR_COLOR))

            // Update component tree UI to apply the new theme
            SwingUtilities.updateComponentTreeUI(frame)
            frame.background = Color.BLACK
        } catch (e : Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFont(): Font? {
        val fontStream = Helpers.openResource("res/runescape_small.ttf")
        return if (fontStream != null) {
            try {
                val font = Font.createFont(Font.TRUETYPE_FONT, fontStream)
                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                ge.registerFont(font) // Register the font in the graphics environment
                font
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            println("Font not found!")
            null
        }
    }

    object StateManager {
        var focusedView: String = ""
    }

    private fun applyTheme(theme: Theme) {
        WIDGET_COLOR = theme.widgetColor
        TITLE_BAR_COLOR = theme.titleBarColor
        VIEW_BACKGROUND_COLOR = theme.viewBackgroundColor
        primaryColor = theme.primaryColor
        secondaryColor = theme.secondaryColor
        POPUP_BACKGROUND = theme.popupBackground
        POPUP_FOREGROUND = theme.popupForeground
        TOOLTIP_BACKGROUND = theme.tooltipBackground
        SCROLL_BAR_COLOR = theme.scrollBarColor
        PROGRESS_BAR_FILL = theme.progressBarFill
        NAV_TINT = theme.navTint
        NAV_GREYSCALE = theme.navGreyScale
        BOOST = theme.boost
    }
}
