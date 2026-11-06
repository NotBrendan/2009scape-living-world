package KondoKit.ui

import KondoKit.ui.ViewConstants
import KondoKit.plugin.Companion.VIEW_BACKGROUND_COLOR
import KondoKit.util.setFixedSize
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

open class BaseView(
    private val viewName: String,
    private val preferredWidth: Int = ViewConstants.DEFAULT_PANEL_SIZE.width,
    private val addDefaultSpacing: Boolean = true
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        name = viewName
        background = VIEW_BACKGROUND_COLOR
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        
        if (addDefaultSpacing) {
            add(Box.createVerticalStrut(5))
        }
    }

    fun setViewSize(height: Int) {
        setFixedSize(preferredWidth, height)
    }
}
