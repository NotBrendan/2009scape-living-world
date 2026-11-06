package KondoKit.ui.components

import KondoKit.util.ImageCanvas
import KondoKit.util.SpriteToBufferedImage
import KondoKit.util.setFixedSize
import KondoKit.plugin.Companion.WIDGET_COLOR
import plugin.api.API
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JPanel

class IconComponent(
    spriteId: Int,
    private val iconWidth: Int = 25,
    private val iconHeight: Int = 23,
    private val useThemeColors: Boolean = true,
    private val tint: Color? = null,
    private val grayscale: Boolean = false,
    private val brightnessBoost: Float = 1.0f
) : JPanel() {

    private val imageCanvas: ImageCanvas

    init {
        layout = BorderLayout()
        background = if (useThemeColors) WIDGET_COLOR else Color.WHITE
        
        val bufferedImageSprite = SpriteToBufferedImage.getBufferedImageFromSprite(
            API.GetSprite(spriteId), 
            tint, 
            grayscale, 
            brightnessBoost
        )
        
        imageCanvas = ImageCanvas(bufferedImageSprite).apply {
            setFixedSize(iconWidth, iconHeight)
            background = if (useThemeColors) WIDGET_COLOR else Color.WHITE
        }
        
        add(imageCanvas, BorderLayout.CENTER)
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
    }

    fun updateFillColor(color: Color) {
        imageCanvas.fillColor = color
        background = color
        imageCanvas.repaint()
        repaint()
    }
    
    fun applyThemeColors() {
        updateFillColor(WIDGET_COLOR)
    }
}
