package KondoKit.ui.components

import KondoKit.ui.ViewConstants
import KondoKit.plugin.Companion.PROGRESS_BAR_FILL
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.plugin.Companion.primaryColor
import KondoKit.plugin.Companion.secondaryColor
import KondoKit.util.setFixedSize
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel

class ToggleSwitch : JPanel() {
    private var activated = false
    private var switchColor = primaryColor
    private var buttonColor = secondaryColor
    private var borderColor = WIDGET_COLOR
    private var activeSwitch = PROGRESS_BAR_FILL
    private var puffer: BufferedImage? = null
    private var g: Graphics2D? = null

    var onToggleListener: ((Boolean) -> Unit)? = null

    init {
        isVisible = true
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(arg0: MouseEvent) {
                activated = !activated
                onToggleListener?.invoke(activated)
                repaint()
            }
        })
        cursor = Cursor(Cursor.HAND_CURSOR)
        setFixedSize(ViewConstants.TOGGLE_SWITCH_SIZE)
        isOpaque = false
    }

    override fun paint(gr: Graphics) {
        if (g == null || puffer?.width != width || puffer?.height != height) {
            puffer = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            g = puffer?.createGraphics()
            val rh = RenderingHints(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            )
            g?.setRenderingHints(rh)
        }

        g?.color = Color(0, 0, 0, 0)
        g?.fillRect(0, 0, width, height)

        val trackHeight = height - 2
        val trackWidth = width - 2
        val trackArc = trackHeight
        
        g?.color = if (activated) activeSwitch else switchColor
        g?.fillRoundRect(1, 1, trackWidth, trackHeight, trackArc, trackArc)
        g?.color = borderColor
        g?.drawRoundRect(1, 1, trackWidth, trackHeight, trackArc, trackArc)

        val thumbDiameter = trackHeight - 4
        val thumbX = if (activated) {
            width - thumbDiameter - 3 // Right side when activated
        } else {
            3 // Left side when not activated
        }
        val thumbY = (height - thumbDiameter) / 2

        g?.color = buttonColor
        g?.fillOval(thumbX, thumbY, thumbDiameter, thumbDiameter)
        g?.color = borderColor
        g?.drawOval(thumbX, thumbY, thumbDiameter, thumbDiameter)

        gr.drawImage(puffer, 0, 0, null)
    }

    fun isActivated(): Boolean {
        return activated
    }

    fun setActivated(activated: Boolean) {
        this.activated = activated
        repaint()
    }

    fun getSwitchColor(): Color {
        return switchColor
    }

    /**
     * Unactivated Background Color of switch
     */
    fun setSwitchColor(switchColor: Color) {
        this.switchColor = switchColor
    }

    fun getButtonColor(): Color {
        return buttonColor
    }

    /**
     * Switch-Button color
     */
    fun setButtonColor(buttonColor: Color) {
        this.buttonColor = buttonColor
    }

    fun getBorderColor(): Color {
        return borderColor
    }

    /**
     * Border-color of whole switch and switch-button
     */
    fun setBorderColor(borderColor: Color) {
        this.borderColor = borderColor
    }

    fun getActiveSwitch(): Color {
        return activeSwitch
    }

    fun setActiveSwitch(activeSwitch: Color) {
        this.activeSwitch = activeSwitch
    }
}
