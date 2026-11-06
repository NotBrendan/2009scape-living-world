package KondoKit.ui.theme

import java.awt.Color

object Themes {
    enum class ThemeType {
        RUNELITE,
        DARKER_RUNELITE,
        SARADOMIN,
        ORION,
    }

    fun getTheme(themeType: ThemeType): Theme {
        return when (themeType) {
            ThemeType.RUNELITE -> Theme(
                widgetColor = Color(30, 30, 30),
                titleBarColor = Color(21, 21, 21),
                viewBackgroundColor = Color(40, 40, 40),
                primaryColor = Color(165, 165, 165),
                secondaryColor = Color(255, 255, 255),
                popupBackground = Color(45, 45, 45),
                popupForeground = Color(220, 220, 220),
                tooltipBackground = Color(50, 50, 50),
                scrollBarColor = Color(64, 64, 64),
                progressBarFill = Color(61, 56, 49),
                navTint = null,
                navGreyScale = false,
                boost = 1f
            )
            ThemeType.DARKER_RUNELITE -> Theme(
                widgetColor = Color(15, 15, 15),
                titleBarColor = Color(10, 10, 10),
                viewBackgroundColor = Color(20, 20, 20),
                primaryColor = Color(140, 140, 140),
                secondaryColor = Color(210, 210, 210),
                popupBackground = Color(25, 25, 25),
                popupForeground = Color(200, 200, 200),
                tooltipBackground = Color(30, 30, 30),
                scrollBarColor = Color(40, 40, 40),
                progressBarFill = Color(45, 40, 35),
                navTint = null,
                navGreyScale = false,
                boost = 0.8f
            )
            ThemeType.ORION -> Theme(
                widgetColor = Color(50, 50, 50),
                titleBarColor = Color(35, 35, 35),
                viewBackgroundColor = Color(60, 60, 60),
                primaryColor = Color(180, 180, 180),
                secondaryColor = Color(210, 210, 210),
                popupBackground = Color(45, 45, 45),
                popupForeground = Color(230, 230, 230),
                tooltipBackground = Color(55, 55, 55),
                scrollBarColor = Color(75, 75, 75),
                progressBarFill = Color(100, 100, 100),
                navTint = null,
                navGreyScale = true,
                boost = 1.3f
            )
            ThemeType.SARADOMIN -> Theme(
                widgetColor = Color(62, 53, 41),
                titleBarColor = Color(111, 93, 69).darker(),
                viewBackgroundColor = Color(101, 85, 63),
                primaryColor = Color(180, 150, 120),
                secondaryColor = Color(230, 210, 190),
                popupBackground = Color(70, 56, 42),
                popupForeground = Color(220, 200, 180),
                tooltipBackground = Color(80, 65, 50),
                scrollBarColor = Color(100, 85, 70),
                progressBarFill = Color(130, 110, 90),
                navTint = null,
                navGreyScale = false,
                boost = 1f
            )
        }
    }

    data class Theme(
        val widgetColor: Color,
        val titleBarColor: Color,
        val viewBackgroundColor: Color,
        val primaryColor: Color,
        val secondaryColor: Color,
        val popupBackground: Color,
        val popupForeground: Color,
        val tooltipBackground: Color,
        val scrollBarColor: Color,
        val progressBarFill: Color,
        val navTint: Color?,
        val navGreyScale: Boolean,
        val boost: Float
    )
}
