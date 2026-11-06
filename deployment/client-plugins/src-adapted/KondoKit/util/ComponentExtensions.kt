package KondoKit.util

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPopupMenu
import KondoKit.util.Helpers

fun <T : Component> T.setFixedSize(width: Int, height: Int): T =
    setFixedSize(Dimension(width, height))

fun <T : Component> T.setFixedSize(size: Dimension): T {
    preferredSize = size
    minimumSize = size
    maximumSize = size
    return this
}

fun Component.attachPopupMenu(popupMenu: JPopupMenu, includeChildren: Boolean = false) {
    val listener = object : MouseAdapter() {
        private fun maybeShow(e: MouseEvent) {
            if (e.isPopupTrigger) {
                popupMenu.show(e.component, e.x, e.y)
            }
        }

        override fun mousePressed(e: MouseEvent) = maybeShow(e)

        override fun mouseReleased(e: MouseEvent) = maybeShow(e)
    }

    addMouseListener(listener)

    if (includeChildren && this is Container) {
        Helpers.addMouseListenerToAll(this, listener)
    }
}
