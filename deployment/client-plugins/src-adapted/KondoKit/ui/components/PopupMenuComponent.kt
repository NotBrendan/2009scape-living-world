package KondoKit.ui.components

import KondoKit.ui.components.UiStyler.menuItem
import KondoKit.plugin.Companion.POPUP_BACKGROUND
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

class PopupMenuComponent : JPopupMenu() {
    
    init {
        background = POPUP_BACKGROUND
    }
    
    fun addMenuItem(text: String, action: () -> Unit): JMenuItem {
        val item = menuItem(text = text, onClick = action)
        add(item)
        return item
    }
}
