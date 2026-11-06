package KondoKit.ui.components

import KondoKit.ui.ViewConstants
import KondoKit.plugin.Companion.TITLE_BAR_COLOR
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.plugin.Companion.secondaryColor
import KondoKit.ui.components.UiStyler.button
import KondoKit.ui.components.UiStyler.ButtonDefaults
import java.awt.*
import javax.swing.*

class ButtonPanel(
    private val alignment: Int = FlowLayout.CENTER,
    private val hgap: Int = 5,
    private val vgap: Int = 0
) : JPanel() {
    
    init {
        layout = FlowLayout(alignment, hgap, vgap)
        background = WIDGET_COLOR
    }
    
    fun addButton(text: String, action: () -> Unit): JButton =
        createAndAddButton(text = text, action = action)

    fun addIcon(icon: Icon, action: () -> Unit): JButton =
        createAndAddButton(icon = icon, action = action)

    private fun createAndAddButton(
        text: String? = null,
        icon: Icon? = null,
        action: () -> Unit
    ): JButton {
        val defaults = ButtonDefaults(
            background = TITLE_BAR_COLOR,
            foreground = secondaryColor,
            font = ViewConstants.FONT_RUNESCAPE_SMALL_14
        )

        return button(text = text, icon = icon, defaults = defaults, onClick = action).also {
            add(it)
        }
    }
}
