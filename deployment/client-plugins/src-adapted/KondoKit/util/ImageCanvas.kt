package KondoKit.util

import KondoKit.plugin.Companion.WIDGET_COLOR
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage

class ImageCanvas(private val image: BufferedImage) : Canvas() {

    var fillColor: Color = WIDGET_COLOR

    init {
        val width = image.width
        val height = image.height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = image.getRGB(x, y)
                if (color != 0) {
                    val newColor = (color and 0x00FFFFFF) or (0xFF shl 24)
                    image.setRGB(x, y, newColor)
                }
            }
        }
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        g.color = fillColor
        g.fillRect(0, 0, width, height)
        g.drawImage(image, 0, 0, width, height, this)
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(image.width, image.height)
    }
}
