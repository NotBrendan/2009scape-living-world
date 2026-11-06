package KondoKit.ui.components

import KondoKit.ui.ViewConstants
import KondoKit.plugin.Companion.POPUP_BACKGROUND
import KondoKit.plugin.Companion.POPUP_FOREGROUND
import KondoKit.plugin.Companion.TITLE_BAR_COLOR
import KondoKit.plugin.Companion.secondaryColor
import java.awt.Color
import java.awt.Font
import java.awt.Insets
import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JMenuItem

/**
 * Centralises common Swing styling so buttons and menu items share a
 * consistent look and the setup code remains in one place.
 */
object UiStyler {

    data class ButtonDefaults(
        val background: Color = TITLE_BAR_COLOR,
        val foreground: Color = secondaryColor,
        val font: Font = ViewConstants.FONT_RUNESCAPE_SMALL_14,
        val margin: Insets? = null
    )

    data class MenuItemDefaults(
        val background: Color = POPUP_BACKGROUND,
        val foreground: Color = POPUP_FOREGROUND,
        val font: Font = ViewConstants.FONT_RUNESCAPE_SMALL_16
    )

    fun <T : AbstractButton> T.applyDefaults(
        defaults: ButtonDefaults = ButtonDefaults(),
        onClick: (() -> Unit)? = null
    ): T {
        background = defaults.background
        foreground = defaults.foreground
        font = defaults.font
        defaults.margin?.let { margin = it }
        onClick?.let { addActionListener { _ -> it() } }
        return this
    }

    fun button(
        text: String? = null,
        icon: Icon? = null,
        defaults: ButtonDefaults = ButtonDefaults(),
        onClick: (() -> Unit)? = null
    ): JButton {
        return JButton().apply {
            text?.let { this.text = it }
            icon?.let { this.icon = it }
            isOpaque = false
            applyDefaults(defaults, onClick)
        }
    }

    fun menuItem(
        text: String,
        defaults: MenuItemDefaults = MenuItemDefaults(),
        onClick: (() -> Unit)? = null
    ): JMenuItem {
        return JMenuItem(text).apply {
            background = defaults.background
            foreground = defaults.foreground
            font = defaults.font
            onClick?.let { addActionListener { _ -> it() } }
        }
    }

}
