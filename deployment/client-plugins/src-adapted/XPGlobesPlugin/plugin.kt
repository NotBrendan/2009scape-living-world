package XPGlobesPlugin

import rt4.Sprite
import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.*
import java.awt.Color
import java.awt.geom.Arc2D
import java.awt.image.BufferedImage
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.Graphics2D
import java.awt.BasicStroke
import kotlin.math.cos
import kotlin.math.PI

class plugin : Plugin() {
    private var xpGlobes = Array(Constants.SKILL_COUNT) { skillId -> XPGlobe(skillId, Constants.INVALID_XP, 0L, null, null ) }
    private var hasActiveGlobes = false
    private var backgroundSprite: Sprite? = null
    private var borderSprite: Sprite? = null


    override fun Draw(deltaTime: Long) {
        if (!hasActiveGlobes)
            return

        var posX = API.GetWindowDimensions().width / 2
        val posY = API.GetWindowDimensions().height / 4

        if (API.GetWindowMode() == WindowMode.FIXED) {
            posX += 60
        }

        API.ClipRect(0, 0, posX * 2, posY * 4)

        // update globes
        val activeGlobes = ArrayList<XPGlobe>()
        for (xpGlobe in xpGlobes) {
            val globeDelta = System.currentTimeMillis() - xpGlobe.timestamp
            if (globeDelta >= Constants.GLOBE_LIFETIME) {
                xpGlobe.timestamp  = 0L // dead
                xpGlobe.textSprite = null
            }

            if (xpGlobe.timestamp != 0L) {
                activeGlobes.add(xpGlobe) // alive
            }
        }

        if (activeGlobes.isEmpty()) {
            hasActiveGlobes = false
            return
        }

        val maxGlobes = if (API.GetWindowMode() == WindowMode.FIXED) Constants.MAX_GLOBES_SD else Constants.MAX_GLOBES
        val globeCount = if (activeGlobes.size > maxGlobes) maxGlobes else activeGlobes.size
        val (backgroundSize, globeBorder, xpBorder) = getGlobeDimensions()
        val globeSize = backgroundSize + globeBorder * 2 + xpBorder * 2
        val allGlobesWidth = globeCount * (globeSize + Constants.GLOBE_PADDING) - Constants.GLOBE_PADDING
        var globePosX = posX - (allGlobesWidth / 2)

        // render globes
        activeGlobes.take(globeCount).forEach { xpGlobe ->
            drawXpGlobe(xpGlobe, globePosX)
            globePosX += globeSize + Constants.GLOBE_PADDING // Update globePosX for the next globe
        }
    }


    override fun OnXPUpdate(skillId: Int, xp: Int) {
        val xpGlobe = xpGlobes[skillId]
        if (xpGlobe.xp == Constants.INVALID_XP) {
            xpGlobe.xp = xp
            return
        }

        if (xp == xpGlobe.xp) {
            return
        }

        val prevXp = xpGlobe.xp
        xpGlobe.xp = xp
        val (prevLevel, _) = XPTable.getLevelForXp(prevXp)
        val (level, gainedXp) = XPTable.getLevelForXp(xp)

        // we do not draw XP globes for level >= MAX_LEVEL
        if (level != Constants.INVALID_LEVEL && level < Constants.MAX_LEVEL) {
            var arcWeight = 1.0
            var arcColor = Constants.GLOBE_XP_ARC_LEVEL_UP_COLOR
            val hasLeveledUp = level != prevLevel

            if (!hasLeveledUp) {
                val levelXpDiff = XPTable.getXpRequiredForLevel(level + 1) - XPTable.getXpRequiredForLevel(level)
                arcWeight = gainedXp.toDouble() / levelXpDiff.toDouble()
                arcColor = Constants.GLOBE_XP_ARC_COLOR
            }

            val (backgroundSize, globeBorder, xpBorder) = getGlobeDimensions()
            val globeSize = backgroundSize + globeBorder * 2 + xpBorder * 2
            if (xpGlobe.textSprite == null) {
                // update arcSprite and timestamp only if the textSprite has finished the animation
                xpGlobe.arcSprite = createArcSprite(backgroundSize + xpBorder * 2, arcColor, arcWeight)
                xpGlobe.timestamp = System.currentTimeMillis()
            }

            if (borderSprite == null) {
                borderSprite = createArcSprite(globeSize, Constants.GLOBE_BORDER_COLOR, 1.0)
            }

            if (backgroundSprite == null) {
                backgroundSprite = createArcSprite(backgroundSize, Constants.GLOBE_BKG_COLOR, 1.0)
            }

            if (hasLeveledUp) {
                val fontSize = Constants.GLOBE_TEXT_SIZE
                val fgColor = Constants.GLOBE_TEXT_FG_COLOR
                val bgColor = Constants.GLOBE_TEXT_BG_COLOR
                val outlineSize = Constants.GLOBE_TEXT_OUTLINE_WIDTH
                xpGlobes[skillId].textSprite = createTextSprite(fontSize, fgColor, bgColor, outlineSize, level.toString())
                xpGlobe.timestamp = System.currentTimeMillis()
            }

            hasActiveGlobes = true
        }
    }


    override fun OnLogout() {
        hasActiveGlobes = false
        xpGlobes = Array(Constants.SKILL_COUNT)  { skillId -> XPGlobe(skillId, Constants.INVALID_XP, 0L, null, null) }
    }


    data class XPGlobe(val skillId: Int, var xp: Int, var timestamp: Long, var arcSprite: Sprite?, var textSprite: Sprite?)


    private fun getGlobeDimensions() : Triple<Int, Int, Int> {
        val backgroundSize = Constants.GLOBE_BKG_SIZE
        val globeBorder = Constants.GLOBE_BORDER_WIDTH
        val xpBorder: Int = Constants.GLOBE_XP_ARC_WIDTH
        return Triple(backgroundSize, globeBorder, xpBorder)
    }


    private fun drawXpGlobe(globe: XPGlobe, posX: Int, posY: Int = Constants.GLOBES_Y_OFFSET) {

        val (backgroundSize, globeBorder, xpBorder) = getGlobeDimensions()
        val totalBorder = globeBorder + xpBorder

        // rendering background
        borderSprite?.render(posX, posY)
        globe.arcSprite?.render(posX + globeBorder, posY + globeBorder)
        backgroundSprite?.render(posX + totalBorder, posY + totalBorder)

        // rendering skill sprite
        val skillSprite = XPSprites.getSpriteForSkill(globe.skillId)

        val spriteWidth = skillSprite?.anInt1860 ?: 0 // sprite width without trimmed pixels
        val spriteHeight = skillSprite?.anInt1866 ?: 0 // sprite height without trimmed pixels
        val xOffset = (backgroundSize - spriteWidth) / 2
        val yOffset = (backgroundSize - spriteHeight) / 2

        val drawX = posX + totalBorder + xOffset
        val drawY = posY + totalBorder + yOffset

        // even if the centering logic is correct, the sprite seems not to be well-centered inside the
        // graphic resource. Manually adjust...
        val (spriteXOffset, spriteYOffset) = XPSprites.getSpriteOffsetForSkill(globe.skillId)

        skillSprite?.render(drawX + spriteXOffset, drawY + spriteYOffset)

        // rendering level-up text animation
        if (globe.textSprite != null) {
            val clamp: (Float, Float, Float) -> Float = { value, min, max ->
                when {
                    value < min -> min
                    value > max -> max
                    else -> value
                }
            }

            val lerp: (Float, Float, Float) -> Float = { valA, valB, factor ->
                valA * (1.0F - factor) + (valB * factor)
            }

            val currentTime = System.currentTimeMillis()
            var animWeight = (currentTime - globe.timestamp).toFloat() / Constants.GLOBE_LIFETIME
            animWeight = clamp(animWeight, 0.0F, 1.0F)
            // sample cosine for the text pulse animation effect (cosine phase-shift is to respect number of pulses)
            var pulseWeight = cos(PI + animWeight * (PI * 2 * Constants.GLOBE_TEXT_PULSES).toFloat()).toFloat()
            pulseWeight = (pulseWeight + 1.0F) / 2.0F // map pulseWeight from [-1,1] to [0,1]
            val pulseIntensity = lerp(Constants.GLOBE_TEXT_PULSE_LOW, Constants.GLOBE_TEXT_PULSE_HIGH, pulseWeight)

            val globeSize = backgroundSize + globeBorder * 2 + xpBorder * 2

            val textWidth = globe.textSprite?.anInt1860 ?: 0
            val textHeight = globe.textSprite?.anInt1866 ?: 0
            val textXOffset = (globeSize - textWidth) / 2
            val textYOffset = (globeSize - textHeight) / 2

            val textX = posX + textXOffset + (Constants.GLOBE_TEXT_OUTLINE_WIDTH / 2)
            val textY = posY + textYOffset + (Constants.GLOBE_TEXT_OUTLINE_WIDTH / 2)

            val alpha = lerp(0f, 255f, pulseIntensity).toInt()
            globe.textSprite?.renderAlpha(textX, textY, alpha)
        }
    }

    private fun createTextSprite(size: Int, fgColor: Color, bgColor: Color, outlineSize: Int, text: String) : Sprite {
        return SpritePNGLoader.getImageIndexedSprite(createTextImage(size, fgColor, bgColor, outlineSize, text))
    }

    private fun createTextImage(fontSize: Int, fgColor: Color, bgColor: Color, outlineSize: Int, text: String) : BufferedImage {
        // Create a dummy font to calculate the size of the BufferedImage
        val font = Font("Arial", Font.PLAIN, fontSize)
        val affineTransform = AffineTransform()
        val frc = FontRenderContext(affineTransform, true, true)
        val textWidth = font.getStringBounds(text, frc).width.toInt() + outlineSize
        val textHeight = font.getStringBounds(text, frc).height.toInt() + outlineSize

        // Create an image that can contain the text
        val image = BufferedImage(textWidth, textHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics() as Graphics2D

        // Enable antialiasing for smoother text
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

        // Set the font
        graphics.font = font

        // Calculate position for the text
        val metrics = graphics.getFontMetrics(font)
        val x = 0
        val y = metrics.ascent

        // Create a glyph vector for the outline
        val glyphVector = font.createGlyphVector(frc, text)
        val shape = glyphVector.getOutline(x.toFloat(), y.toFloat())

        // Draw the outline (background color)
        graphics.color = bgColor
        graphics.stroke = BasicStroke(outlineSize.toFloat()) // Set the outline width
        graphics.draw(shape)

        // Fill the text (foreground color)
        graphics.color = fgColor
        graphics.fill(shape)

        // Dispose graphics to release resources
        graphics.dispose()

        return image
    }


    private fun createArcSprite(size: Int, arcColor: Color, weight: Double): Sprite {
        return SpritePNGLoader.getImageIndexedSprite(createArcImage(size, arcColor, weight))
    }


    private fun createArcImage(size: Int, arcColor: Color, weight: Double): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()

        // Enable antialiasing for smoother circle edges
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

        // Set the color for the circle
        graphics.color = arcColor

        // Calculate the angle and starting angle based on the weight
        val angle = weight * 360
        val startAngle = 360 * (0.75 - weight)

        // Draw the portion of the circle
        graphics.fill(Arc2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), startAngle, angle, Arc2D.PIE))

        // Dispose graphics to release resources
        graphics.dispose()

        return image
    }
}
