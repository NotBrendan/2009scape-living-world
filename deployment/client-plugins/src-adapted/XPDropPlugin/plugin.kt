package XPDropPlugin

import KondoKit.Exposed
import plugin.Plugin
import plugin.api.API
import plugin.api.API.*
import plugin.api.FontColor.fromColor
import plugin.api.FontType
import plugin.api.TextModifier
import plugin.api.WindowMode
import rt4.Sprite
import rt4.client
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.math.ceil


class plugin : Plugin() {

    enum class Theme {
        DEFAULT, RUNELITE
    }

    @Exposed
    private var theme = Theme.DEFAULT

    @Exposed
    private var alwaysShow = false

    private val displayTimeout = 10000L // 10 seconds
    private val drawStart = 175
    private val drawPadding = 25
    private val drawClear = 60
    private val lastXp = IntArray(24)
    private var totalXp = 0
    private val activeGains = ArrayList<XPGain>()
    private var lastGain = 0L
    private val IN_GAME = 30

    private val spriteCache = HashMap<Int, Sprite?>()

    override fun Init() {
        val themeIndex = (GetData("xp-drop-theme") as? String) ?: "DEFAULT"
        theme = Theme.valueOf(themeIndex)
        alwaysShow = (GetData("xp-drop-alwaysShow") as? Boolean) ?: false
    }

    override fun Draw(deltaTime: Long) {
        if (shouldSkipDrawing()) return

        drawTotalXPBox()
        val removeList = ArrayList<XPGain>()

        val movementSpeedFactor = deltaTime / 16.666 // 60 FPS

        for (gain in activeGains) {
            gain.currentPos -= ceil(movementSpeedFactor).toInt() // Adjust movement based on deltaTime
            if (gain.currentPos <= drawClear) {
                removeList.add(gain)
                totalXp += gain.xp
            } else if (gain.currentPos <= drawStart) {
                drawXPDrops(gain)
            }
        }

        activeGains.removeAll(removeList.toSet())
    }

    private fun shouldSkipDrawing(): Boolean {
        return client.gameState < IN_GAME || (!alwaysShow && isDisplayTimeoutExpired() && activeGains.isEmpty())
    }

    fun OnKondoValueUpdated() {
        StoreData("xp-drop-theme",theme.toString())
        StoreData("xp-drop-alwaysShow",alwaysShow)
    }

    private fun isDisplayTimeoutExpired(): Boolean {
        return System.currentTimeMillis() - lastGain >= displayTimeout
    }

    override fun OnXPUpdate(skill: Int, xp: Int) {
        if (xp == lastXp[skill]) return

        val gain = xp - lastXp[skill]
        if (gain <= 0) return

        if (lastXp[skill] == 0) {
            lastXp[skill] = xp
            totalXp += xp
            return
        }
        lastXp[skill] = xp

        val currentTail = try {
            activeGains.last().currentPos
        } catch (e: Exception) {
            drawStart - drawPadding
        }

        activeGains.add(XPGain(skill, gain, currentTail + drawPadding))
        lastGain = System.currentTimeMillis()
    }

    override fun OnLogout() {
        lastGain = 0L
        for (i in 0 until 24) lastXp[i] = 0
        totalXp = 0
        activeGains.clear()
    }

    private fun drawTotalXPBox() {
        when (theme) {
            Theme.DEFAULT -> drawDefaultXPBox()
            Theme.RUNELITE -> drawRuneliteXPBox()
        }
    }

    private fun drawXPDrops(gain : XPGain) {
        when (theme) {
            Theme.DEFAULT -> drawDefaultXPDrop(gain)
            Theme.RUNELITE -> drawRuneliteXPDrops(gain)
        }
    }

    private fun drawDefaultXPDrop(gain: XPGain) {
        var posX = API.GetWindowDimensions().width / 2
        if (API.GetWindowMode() == WindowMode.FIXED)
            posX += 60
        val sprite = spriteCache.getOrPut(gain.skill) { XPSprites.getSpriteForSkill(skillId = gain.skill) }
        sprite?.render(posX - 25, gain.currentPos - 20)
        DrawText(
                FontType.SMALL,
                fromColor(Color.WHITE),
                TextModifier.LEFT,
                addCommas(gain.xp.toString()),
                posX,
                gain.currentPos
        )
    }

    private fun drawRuneliteXPDrops(gain: XPGain) {
        val w = API.GetWindowDimensions().width
        val offset = if(API.GetWindowMode() == WindowMode.FIXED) 251 else 225
        val extra = 2;
        val posX = w - (offset + extra)

        val str = addCommas(gain.xp.toString())
        val fontCharWidth = 4
        val displace = str.length*fontCharWidth + 30

        // should be scaled https://github.com/runelite/runelite/blob/0500906f8de9cd20875c168a7a59e5e066ed5058/runelite-client/src/main/java/net/runelite/client/game/SkillIconManager.java#L50
        // but for now this is good enough

        val sprite = spriteCache.getOrPut(gain.skill) { XPSprites.getSpriteForSkill(skillId = gain.skill) }
        sprite?.render(posX - displace, gain.currentPos - 20)
        drawTextWithDropShadow(posX, gain.currentPos, Color.WHITE, addCommas(gain.xp.toString()))
    }

    private fun drawDefaultXPBox() {
        var posX = API.GetWindowDimensions().width / 2
        val posY = API.GetWindowDimensions().height / 4

        if (API.GetWindowMode() == WindowMode.FIXED)
            posX += 60

        API.ClipRect(0, 0, posX * 2, posY * 4)

        val horizontal = spriteCache.getOrPut(822) { API.GetSprite(822) }
        val horizontalTop = spriteCache.getOrPut(820) { API.GetSprite(820) }
        val tlCorner = spriteCache.getOrPut(824) { API.GetSprite(824) }
        val blCorner = spriteCache.getOrPut(826) { API.GetSprite(826) }
        val trCorner = spriteCache.getOrPut(825) { API.GetSprite(825) }
        val brCorner = spriteCache.getOrPut(827) { API.GetSprite(827) }
        val bg = spriteCache.getOrPut(657) { API.GetSprite(657) }

        bg?.render(posX - 77, 10)
        API.FillRect(posX - 75, 5, 140, 30, 0, 64)

        blCorner?.render(posX - 77, 10)
        tlCorner?.render(posX - 77, 5)
        trCorner?.render(posX + 41, 5)
        brCorner?.render(posX + 41, 10)

        horizontalTop?.render(posX - 45, -8)
        horizontal?.render(posX - 45, 22)
        horizontalTop?.render(posX - 15, -8)
        horizontal?.render(posX - 15, 22)
        horizontalTop?.render(posX + 9, -8)
        horizontal?.render(posX + 9, 22)

        DrawText(
                FontType.SMALL,
                fromColor(Color.WHITE),
                TextModifier.LEFT,
                "Total Xp: ${addCommas(totalXp.toString())}",
                posX - 65,
                28
        )
    }

    private fun drawRuneliteXPBox() {
        val boxHeight = 29
        val boxWidth = 119
        val posX = API.GetWindowDimensions().width

        val innerBorderColor = Color(90, 82, 69).rgb
        val outerBorderColor =  Color(56,48,35).rgb

        val offset = if(API.GetWindowMode() == WindowMode.FIXED) 251 else 225
        val boxStart = posX - (offset + boxWidth)
        val yOffset = if(API.GetWindowMode() == WindowMode.FIXED) 4 else 0

        val lvlIcon = 898;
        val sprite = spriteCache.getOrPut(lvlIcon){
            val imageStream: InputStream = plugin::class.java.getResourceAsStream("res/rl-lvls.png")
            imageStream.use { imageStream ->
                val image: BufferedImage = ImageIO.read(imageStream)
                API.GetSpriteFromPNG(image)
            }
        }

        // Draw a simple rectangle instead of the default box design
        API.FillRect(boxStart, yOffset, boxWidth, boxHeight, innerBorderColor, 150)
        drawTextWithDropShadow(boxStart+boxWidth-4, 18+yOffset, Color.WHITE, addCommas(totalXp.toString()))

        // Inner Border
        API.DrawRect(boxStart+1, 1+yOffset, boxWidth-2, boxHeight-2, innerBorderColor)
        // redraw around the border
        API.DrawRect(boxStart, yOffset, boxWidth, boxHeight, outerBorderColor)
        sprite?.render(boxStart + 3, 3+yOffset)
    }

    data class XPGain(val skill: Int, val xp: Int, var currentPos: Int)

    fun addCommas(num: String): String {
        var newString = ""
        if (num.length > 9) {
            return "Lots!"
        }
        var counter = 1
        num.reversed().forEach {
            if (counter % 3 == 0 && counter != num.length) {
                newString += "$it,"
            } else {
                newString += it
            }
            counter++
        }
        return newString.reversed()
    }

    private fun drawTextWithDropShadow(x: Int, y: Int, color: Color, text: String, mod : TextModifier = TextModifier.RIGHT) {
        DrawText(FontType.SMALL, fromColor(Color(0)), mod, text, x + 1, y + 1)
        DrawText(FontType.SMALL, fromColor(color), mod, text, x, y)
    }
}

