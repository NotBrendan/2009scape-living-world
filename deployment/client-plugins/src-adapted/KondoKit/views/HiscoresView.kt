package KondoKit.views

import KondoKit.util.Helpers.getSpriteId
import KondoKit.util.Helpers.showToast
import KondoKit.util.ImageCanvas
import KondoKit.ui.BaseView
import KondoKit.ui.ViewConstants
import KondoKit.ui.View
import KondoKit.util.SpriteToBufferedImage.getBufferedImageFromSprite
import KondoKit.ui.components.LabelComponent
import KondoKit.ui.components.WidgetPanel
import KondoKit.ui.components.SearchField
import KondoKit.views.ViewLayoutHelpers.createSearchFieldSection
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.plugin.Companion.VIEW_BACKGROUND_COLOR
import KondoKit.plugin.Companion.POPUP_FOREGROUND
import KondoKit.plugin.Companion.secondaryColor
import KondoKit.plugin.Companion.primaryColor
import KondoKit.plugin.Companion.TOOLTIP_BACKGROUND
import KondoKit.util.JsonParser
import plugin.api.API
import rt4.Sprites
import java.awt.*
import KondoKit.util.HttpFetcher
import KondoKit.util.HttpStatusException
import java.net.SocketTimeoutException
import javax.swing.*
import javax.swing.border.MatteBorder
import kotlin.math.floor

object HiscoresView : View {

    const val VIEW_NAME = "HISCORE_SEARCH_VIEW"
    var hiScoreView: JPanel? = null
    var customSearchField: SearchField? = null
    override val name: String = VIEW_NAME
    override val iconSpriteId: Int = ViewConstants.MAG_SPRITE

    override val panel: JPanel
        get() = hiScoreView ?: JPanel()

    override fun createView() {
        createHiscoreSearchView()
    }

    override fun registerFunctions() {
    }

    fun createHiscoreSearchView() {
        val hiscorePanel = BaseView(VIEW_NAME, addDefaultSpacing = false).apply {
            background = VIEW_BACKGROUND_COLOR
            setViewSize(ViewConstants.DEFAULT_PANEL_SIZE.height)
        }

        val searchSection = createSearchFieldSection(
            parent = hiscorePanel,
            placeholderText = "Search player...",
            viewName = VIEW_NAME
        ) { username ->
            searchPlayerForHiscores(username, hiscorePanel)
        }

        customSearchField = searchSection.searchField

        hiscorePanel.add(Box.createVerticalStrut(5))
        hiscorePanel.add(searchSection.wrapper)
        hiscorePanel.add(Box.createVerticalStrut(5))

        val playerNamePanel = WidgetPanel(
            widgetWidth = ViewConstants.FILTER_PANEL_SIZE.width,
            widgetHeight = ViewConstants.FILTER_PANEL_SIZE.height,
            addDefaultPadding = false
        ).apply {
            layout = GridBagLayout()
            background = TOOLTIP_BACKGROUND.darker()
            name = "playerNameLabel"
        }

        hiscorePanel.add(playerNamePanel)
        hiscorePanel.add(Box.createVerticalStrut(5))

        val skillsPanel = WidgetPanel(
            widgetWidth = ViewConstants.SKILLS_PANEL_SIZE.width,
            widgetHeight = ViewConstants.SKILLS_PANEL_SIZE.height,
            addDefaultPadding = false
        ).apply {
            layout = FlowLayout(FlowLayout.CENTER, 0, 0)
            background = VIEW_BACKGROUND_COLOR
        }

        for (i in ViewConstants.SKILL_DISPLAY_ORDER) {
            val skillPanel = WidgetPanel(
                widgetWidth = ViewConstants.SKILL_PANEL_SIZE.width,
                widgetHeight = ViewConstants.SKILL_PANEL_SIZE.height,
                addDefaultPadding = false
            ).apply {
                layout = BorderLayout()
                background = WIDGET_COLOR
                border = MatteBorder(5, 0, 0, 0, WIDGET_COLOR)
            }

            val bufferedImageSprite = getBufferedImageFromSprite(API.GetSprite(getSpriteId(i)))

            val imageCanvas = bufferedImageSprite.let {
                ImageCanvas(it).apply {
                    preferredSize = ViewConstants.SKILL_SPRITE_SIZE
                    size = ViewConstants.SKILL_SPRITE_SIZE
                    fillColor = WIDGET_COLOR
                }
            }

            val numberLabel = JLabel("     ", JLabel.RIGHT).apply {
                name = "skillLabel_$i"
                foreground = POPUP_FOREGROUND
                font = ViewConstants.FONT_RUNESCAPE_SMALL_16
                preferredSize = ViewConstants.NUMBER_LABEL_SIZE
                minimumSize = ViewConstants.NUMBER_LABEL_SIZE
            }

            val imageContainer = JPanel(FlowLayout(FlowLayout.CENTER, 5, 0)).apply {
                background = WIDGET_COLOR
                add(imageCanvas)
                add(numberLabel)
            }

            skillPanel.add(imageContainer, BorderLayout.CENTER)
            skillsPanel.add(skillPanel)
        }

        hiscorePanel.add(skillsPanel)

        val totalCombatPanel = WidgetPanel(
            widgetWidth = ViewConstants.TOTAL_COMBAT_PANEL_SIZE.width,
            widgetHeight = ViewConstants.TOTAL_COMBAT_PANEL_SIZE.height,
            addDefaultPadding = false
        ).apply {
            layout = FlowLayout(FlowLayout.CENTER, 0, 0)
            background = WIDGET_COLOR
        }

        val bufferedImageSprite = getBufferedImageFromSprite(API.GetSprite(ViewConstants.LVL_BAR_SPRITE))

        val totalLevelIcon = ImageCanvas(bufferedImageSprite).apply {
            fillColor = WIDGET_COLOR
            preferredSize = ViewConstants.DIMENSION_LARGE_ICON
            size = ViewConstants.DIMENSION_LARGE_ICON
        }

        val totalLevelLabel = JLabel("    ").apply {
            name = "totalLevelLabel"
            foreground = POPUP_FOREGROUND
            font = ViewConstants.FONT_ARIAL_BOLD_12
            horizontalAlignment = JLabel.LEFT
            iconTextGap = 10
        }

        val totalLevelPanel = WidgetPanel(
            widgetWidth = ViewConstants.TOTAL_COMBAT_PANEL_SIZE.width / 2,
            widgetHeight = ViewConstants.TOTAL_COMBAT_PANEL_SIZE.height,
            addDefaultPadding = false,
            paddingLeft = 45
        ).apply {
            layout = FlowLayout(FlowLayout.LEFT, 5, 5)
            background = WIDGET_COLOR
            add(totalLevelIcon)
            add(totalLevelLabel)
        }

        val bufferedImageSprite2 = getBufferedImageFromSprite(API.GetSprite(ViewConstants.COMBAT_LVL_SPRITE))

        val combatLevelIcon = ImageCanvas(bufferedImageSprite2).apply {
            fillColor = WIDGET_COLOR
            preferredSize = ViewConstants.DIMENSION_LARGE_ICON
            size = ViewConstants.DIMENSION_LARGE_ICON
        }

        val combatLevelLabel = JLabel("").apply {
            name = "combatLevelLabel"
            foreground = POPUP_FOREGROUND
            font = ViewConstants.FONT_ARIAL_BOLD_12
            horizontalAlignment = JLabel.LEFT
            iconTextGap = 10
        }

        val combatLevelPanel = WidgetPanel(
            widgetWidth = ViewConstants.TOTAL_COMBAT_PANEL_SIZE.width / 2,
            widgetHeight = ViewConstants.TOTAL_COMBAT_PANEL_SIZE.height,
            addDefaultPadding = false
        ).apply {
            layout = FlowLayout(FlowLayout.LEFT, 5, 6)
            background = WIDGET_COLOR
            add(combatLevelIcon)
            add(combatLevelLabel)
        }

        totalCombatPanel.add(totalLevelPanel)
        totalCombatPanel.add(combatLevelPanel)
        hiscorePanel.add(totalCombatPanel)
        hiscorePanel.add(Box.createVerticalStrut(10))

        hiScoreView = hiscorePanel
    }

    data class HiscoresResponse(
        val info: PlayerInfo,
        val skills: List<Skill>
    )

    data class PlayerInfo(
        val exp_multiplier: String,
        val iron_mode: String
    )

    data class Skill(
        val id: String,
        val static: String
    )


    fun searchPlayerForHiscores(username: String, hiscoresPanel: JPanel) {

        if(username.isEmpty() || username.length < 3) {
            return
        }

        val cleanUsername = username.replace(" ", "_")
        val apiUrl = System.getProperty("livingworld.pricesUrl", "https://prices.example.com") + "/api/compat/playerSkills/${cleanUsername.toLowerCase()}"

        customSearchField?.setText(username)
        updateHiscoresViewStandalone(hiscoresPanel, null, "Searching...")

        Thread {
            try {
                val response = HttpFetcher.fetchString(
                    url = apiUrl,
                    connectTimeoutMillis = 5000,
                    readTimeoutMillis = 5000
                )

                SwingUtilities.invokeLater {
                    updatePlayerDataStandalone(response, username, hiscoresPanel)
                }
            } catch (e: SocketTimeoutException) {
                SwingUtilities.invokeLater {
                    showToast(hiscoresPanel, "Request timed out", JOptionPane.ERROR_MESSAGE)
                }
            } catch (e: HttpStatusException) {
                SwingUtilities.invokeLater {
                    showToast(hiscoresPanel, "Player not found!", JOptionPane.ERROR_MESSAGE)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    showToast(hiscoresPanel, "Error fetching data!", JOptionPane.ERROR_MESSAGE)
                }
            }
        }.start()
    }

    private fun updatePlayerDataStandalone(jsonResponse: String, username: String, hiscoresPanel: JPanel) {
        val hiscoresResponse = JsonParser.fromJson<HiscoresResponse>(jsonResponse)
        updateHiscoresViewStandalone(hiscoresPanel, hiscoresResponse, username)
    }

    private fun updateHiscoresViewStandalone(hiscoresPanel: JPanel, data: HiscoresResponse?, username: String) {
        val playerNameLabel = findComponentByNameStandalone(hiscoresPanel, "playerNameLabel") as? JPanel
        playerNameLabel?.removeAll()
        var nameLabel = LabelComponent().apply {
            updateHtmlText(username, secondaryColor, "", primaryColor)
            font = ViewConstants.FONT_ARIAL_BOLD_12
            foreground = POPUP_FOREGROUND
            border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
            horizontalAlignment = JLabel.CENTER
        }
        playerNameLabel?.add(nameLabel)
        playerNameLabel?.revalidate()
        playerNameLabel?.repaint()

        if (data == null) return

        playerNameLabel?.removeAll()

        val ironMode = data.info.iron_mode

        if (ironMode != "0") {
            val ironmanBufferedImage =
                getBufferedImageFromSprite(Sprites.nameIcons[ViewConstants.IRONMAN_SPRITE + ironMode.toInt() - 1])
            val imageCanvas = ironmanBufferedImage.let {
                ImageCanvas(it).apply {
                    preferredSize = ViewConstants.IMAGE_CANVAS_SIZE
                    size = ViewConstants.IMAGE_CANVAS_SIZE
                }
            }

            playerNameLabel?.add(imageCanvas)
        }

        val exp_multiplier = data.info.exp_multiplier
        nameLabel = LabelComponent().apply {
            updateHtmlText(username, secondaryColor, " (${exp_multiplier}x)", primaryColor)
            font = ViewConstants.FONT_ARIAL_BOLD_12
            foreground = POPUP_FOREGROUND
            border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
            horizontalAlignment = JLabel.CENTER
        }


        playerNameLabel?.add(nameLabel)

        playerNameLabel?.revalidate()
        playerNameLabel?.repaint()

        data.skills.forEachIndexed { index, skill ->
            val labelName = "skillLabel_$index"
            val numberLabel = findComponentByNameStandalone(hiscoresPanel, labelName) as? JLabel
            numberLabel?.text = skill.static.toInt().toString()
        }

        updateTotalAndCombatLevelStandalone(data.skills, hiscoresPanel, true)

        hiscoresPanel.revalidate()
        hiscoresPanel.repaint()
    }

    private fun updateTotalAndCombatLevelStandalone(
        skills: List<Skill>,
        hiscoresPanel: JPanel,
        isMemberWorld: Boolean
    ) {
        val totalLevel = skills.sumBy { it.static.toInt() }
        val totalLevelLabel = findComponentByNameStandalone(hiscoresPanel, "totalLevelLabel") as? JLabel
        totalLevelLabel?.text = totalLevel.toString()

        val attack = skills.find { it.id == "0" }?.static?.toInt() ?: 1
        val defence = skills.find { it.id == "1" }?.static?.toInt() ?: 1
        val strength = skills.find { it.id == "2" }?.static?.toInt() ?: 1
        val hitpoints = skills.find { it.id == "3" }?.static?.toInt() ?: 1
        val ranged = skills.find { it.id == "4" }?.static?.toInt() ?: 1
        val prayer = skills.find { it.id == "5" }?.static?.toInt() ?: 1
        val magic = skills.find { it.id == "6" }?.static?.toInt() ?: 1
        val summoning = skills.find { it.id == "23" }?.static?.toInt() ?: 1

        val combatLevel =
            calculateCombatLevel(attack, defence, strength, hitpoints, prayer, ranged, magic, summoning, true)
        val combatLevelLabel = findComponentByNameStandalone(hiscoresPanel, "combatLevelLabel") as? JLabel
        combatLevelLabel?.text = combatLevel.toString()
    }

    private fun findComponentByNameStandalone(container: Container, name: String): Component? {
        for (component in container.components) {
            if (name == component.name) {
                return component
            }
            if (component is Container) {
                val child = findComponentByNameStandalone(component, name)
                if (child != null) {
                    return child
                }
            }
        }
        return null
    }

    private fun calculateCombatLevel(
        attack: Int,
        defence: Int,
        strength: Int,
        hitpoints: Int,
        prayer: Int,
        ranged: Int,
        magic: Int,
        summoning: Int,
        isMemberWorld: Boolean
    ): Double {
        val base = (defence + hitpoints + floor(prayer.toDouble() / 2)) * 0.25
        val melee = (attack + strength) * 0.325
        val range = floor(ranged * 1.5) * 0.325
        val mage = floor(magic * 1.5) * 0.325
        val maxCombatType = maxOf(melee, range, mage)

        val summoningFactor = if (isMemberWorld) floor(summoning.toDouble() / 8) else 0.0
        return Math.round((base + maxCombatType + summoningFactor) * 1000.0) / 1000.0
    }
}
