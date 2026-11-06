package KondoKit.ui

import java.awt.Dimension
import java.awt.Font

object ViewConstants {
    val FONT_RUNESCAPE_SMALL_16 = Font("RuneScape Small", Font.TRUETYPE_FONT, 16)
    val FONT_RUNESCAPE_SMALL_14 = Font("RuneScape Small", Font.PLAIN, 14)
    val FONT_RUNESCAPE_SMALL_PLAIN_16 = Font("RuneScape Small", Font.PLAIN, 16)
    val FONT_RUNESCAPE_SMALL_BOLD_16 = Font("RuneScape Small", Font.BOLD, 16)
    val FONT_ARIAL_PLAIN_14 = Font("Arial", Font.PLAIN, 14)
    val FONT_ARIAL_BOLD_12 = Font("Arial", Font.BOLD, 12)

    val DIMENSION_SMALL_ICON = Dimension(12, 12)
    val DIMENSION_LARGE_ICON = Dimension(30, 30)
    val DEFAULT_WIDGET_SIZE = Dimension(234, 50)
    val PLUGIN_LIST_ITEM_SIZE = Dimension(DEFAULT_WIDGET_SIZE.width, 36)
    val TOGGLE_PLACEHOLDER_SIZE = Dimension(60, 24)
    val TOTAL_XP_WIDGET_SIZE = Dimension(DEFAULT_WIDGET_SIZE.width, 42)
    val IMAGE_SIZE = Dimension(25, 23)
    val SEARCH_FIELD_SIZE = Dimension(DEFAULT_WIDGET_SIZE.width, 30)
    val DEFAULT_PANEL_SIZE = Dimension(DEFAULT_WIDGET_SIZE.width, 500)
    val FILTER_PANEL_SIZE = Dimension(DEFAULT_WIDGET_SIZE.width, 30)
    val SKILLS_PANEL_SIZE = Dimension(DEFAULT_WIDGET_SIZE.width, 290)
    val TOTAL_COMBAT_PANEL_SIZE = Dimension(DEFAULT_WIDGET_SIZE.width, 30)
    val SKILL_PANEL_SIZE = Dimension(DEFAULT_WIDGET_SIZE.width / 3, 35)
    val IMAGE_CANVAS_SIZE = Dimension(20, 20)
    val SKILL_SPRITE_SIZE = Dimension(14, 14)
    val NUMBER_LABEL_SIZE = Dimension(20, 20)
    val PROGRESS_BAR_SIZE = Dimension(220, 16)
    val TOGGLE_SWITCH_SIZE = Dimension(32, 20)

    val SKILL_DISPLAY_ORDER = arrayOf(0, 3, 14, 2, 16, 13, 1, 15, 10, 4, 17, 7, 5, 12, 11, 6, 9, 8, 20, 18, 19, 22, 21, 23)

    const val COMBAT_LVL_SPRITE = 168
    const val IRONMAN_SPRITE = 4
    const val MAG_SPRITE = 1423
    const val LVL_BAR_SPRITE = 898
    const val WRENCH_ICON = 907
    const val LOOT_ICON = 777
}
