package KondoKit.ui.components

import KondoKit.ui.ViewConstants
import KondoKit.util.setFixedSize
import KondoKit.plugin.Companion.TITLE_BAR_COLOR
import KondoKit.plugin.Companion.secondaryColor
import java.awt.*
import javax.swing.*

class ViewHeader(
    title: String,
    private val headerHeight: Int = 40
) : JPanel() {
    
    private val titleLabel = JLabel(title)
    
    init {
        background = TITLE_BAR_COLOR
        setFixedSize(Int.MAX_VALUE, headerHeight)
        border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
        layout = BorderLayout()
        
        titleLabel.foreground = secondaryColor
        titleLabel.font = ViewConstants.FONT_RUNESCAPE_SMALL_PLAIN_16
        titleLabel.horizontalAlignment = SwingConstants.CENTER
        
        add(titleLabel, BorderLayout.CENTER)
    }
    
    fun setTitle(title: String) {
        titleLabel.text = title
    }
}
