package XPGlobesPlugin

import java.awt.Color


object Constants {
    const val SKILL_COUNT = 24
    const val MAX_LEVEL = 99
    const val INVALID_LEVEL = -1
    const val INVALID_XP = -1
    const val GLOBE_LIFETIME = 7000L // 7 seconds
    const val GLOBES_Y_OFFSET = 48 // y-offset in screen space where globes are drawn
    val GLOBE_BKG_COLOR: Color = Color.GRAY
    const val GLOBE_BKG_SIZE = 33 // Size of the globe background
    val GLOBE_BORDER_COLOR: Color = Color.BLACK
    const val GLOBE_BORDER_WIDTH = 1 // Width of the globe border
    val GLOBE_XP_ARC_COLOR: Color = Color.YELLOW
    val GLOBE_XP_ARC_LEVEL_UP_COLOR: Color = Color.BLUE
    const val GLOBE_XP_ARC_WIDTH = 3 // Width of the Xp arc
    const val MAX_GLOBES = 6 // maximum number of globes we will draw on resizable clients
    const val MAX_GLOBES_SD = 3 // maximum number of globes we will draw on fixed clients
    const val GLOBE_PADDING = 3 // horizontal padding between globes
    const val GLOBE_TEXT_SIZE = 20 // font size of level text
    const val GLOBE_TEXT_PULSES = 7 // 7 text pulses per globe lifetime, on level-up
    const val GLOBE_TEXT_PULSE_LOW = 0.2F // alpha during text fade-out
    const val GLOBE_TEXT_PULSE_HIGH = 0.8F // alpha during text fade-in
    val GLOBE_TEXT_FG_COLOR: Color = Color.BLUE // foreground color of level text during level-up
    val GLOBE_TEXT_BG_COLOR: Color = Color.BLACK // background color (outline) of text number during level-up
    const val GLOBE_TEXT_OUTLINE_WIDTH = 2 // outline width
}
