package KondoKit.ui.components

import KondoKit.ui.ViewConstants
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.util.setFixedSize
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JPanel

class WidgetPanel(
    private val widgetWidth: Int = ViewConstants.DEFAULT_WIDGET_SIZE.width,
    private val widgetHeight: Int = ViewConstants.DEFAULT_WIDGET_SIZE.height,
    private val addDefaultPadding: Boolean = true,
    private val paddingTop: Int? = null,
    private val paddingLeft: Int? = null,
    private val paddingBottom: Int? = null,
    private val paddingRight: Int? = null
) : JPanel() {

    init {
        layout = BorderLayout(
            if (addDefaultPadding) 5 else 0,
            if (addDefaultPadding) 5 else 0
        )
        background = WIDGET_COLOR

        val size = Dimension(widgetWidth, widgetHeight)
        setFixedSize(size)

        val top = paddingTop ?: if (addDefaultPadding) 5 else 0
        val left = paddingLeft ?: if (addDefaultPadding) 5 else 0
        val bottom = paddingBottom ?: if (addDefaultPadding) 5 else 0
        val right = paddingRight ?: if (addDefaultPadding) 5 else 0

        border = BorderFactory.createEmptyBorder(top, left, bottom, right)
    }

    fun setFixedSize(width: Int, height: Int) {
        val size = Dimension(width, height)
        this@WidgetPanel.setFixedSize(size)
    }
}
