package KondoKit.ui

import javax.swing.JPanel

interface View {
    val name: String
    val iconSpriteId: Int
    val panel: JPanel

    fun createView()
    fun registerFunctions()
}
