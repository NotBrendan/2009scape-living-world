package KondoKit.ui.components

import KondoKit.util.Helpers.formatHtmlLabelText
import KondoKit.ui.ViewConstants
import KondoKit.plugin.Companion.primaryColor
import KondoKit.plugin.Companion.secondaryColor
import java.awt.Font
import java.awt.Color
import javax.swing.JLabel
import javax.swing.SwingConstants

class LabelComponent(
    text: String = "",
    private val isHtml: Boolean = false,
    private val alignment: Int = SwingConstants.LEFT
) : JLabel() {
    
    init {
        this.text = text
        this.horizontalAlignment = alignment
        this.font = ViewConstants.FONT_RUNESCAPE_SMALL_16
    }
    
    fun updateText(plainText: String, color: Color = secondaryColor) {
        this.text = plainText
        this.foreground = color
    }
    
    fun updateHtmlText(text1: String, color1: Color = primaryColor, text2: String = "", color2: Color = secondaryColor) {
        this.text = formatHtmlLabelText(text1, color1, text2, color2)
    }
    
    fun setAsHeader() {
        font = ViewConstants.FONT_RUNESCAPE_SMALL_16.deriveFont(Font.BOLD)
    }
}
