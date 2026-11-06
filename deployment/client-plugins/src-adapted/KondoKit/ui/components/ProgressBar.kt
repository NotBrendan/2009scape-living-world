package KondoKit.ui.components

import KondoKit.ui.ViewConstants
import KondoKit.plugin.Companion.PROGRESS_BAR_FILL
import KondoKit.plugin.Companion.secondaryColor
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics

class ProgressBar(
        private var progress: Double,
        private val barColor: Color,
        private var currentLevel: Int = 0,
        private var nextLevel: Int = 1
) : Canvas() {

    init {
        font = ViewConstants.FONT_RUNESCAPE_SMALL_16
    }

    override fun paint(g: Graphics) {
        super.paint(g)

        g.color = barColor
        val width = (progress * this.width / 100).toInt()
        g.fillRect(0, 0, width, this.height)

        g.color = PROGRESS_BAR_FILL
        g.fillRect(width, 0, this.width - width, this.height)

        val textY = this.height / 2 + 6

        drawTextWithShadow(g, "Lvl. $currentLevel", 5, textY, secondaryColor)

        val percentageText = String.format("%.2f%%", progress)
        val percentageWidth = g.fontMetrics.stringWidth(percentageText)
        drawTextWithShadow(g, percentageText, (this.width - percentageWidth) / 2, textY, secondaryColor)

        val nextLevelText = "Lvl. $nextLevel"
        val nextLevelWidth = g.fontMetrics.stringWidth(nextLevelText)
        drawTextWithShadow(g, nextLevelText, this.width - nextLevelWidth - 5, textY, secondaryColor)
    }

    override fun getPreferredSize(): Dimension {
        return ViewConstants.PROGRESS_BAR_SIZE
    }

    override fun getMinimumSize(): Dimension {
        return ViewConstants.PROGRESS_BAR_SIZE
    }

    fun updateProgress(newProgress: Double, currentLevel: Int, nextLevel: Int, isVisible : Boolean) {
        this.progress = newProgress
        this.currentLevel = currentLevel
        this.nextLevel = nextLevel
        if(isVisible)
            repaint()
    }

    private fun drawTextWithShadow(g: Graphics, text: String, x: Int, y: Int, textColor: Color) {
        g.color = Color(0, 0, 0)
        g.drawString(text, x + 1, y + 1)

        g.color = textColor
        g.drawString(text, x, y)
    }
}
