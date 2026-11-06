package HolidayLoginMusic

import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.API
import plugin.api.FontColor
import plugin.api.FontType
import plugin.api.TextModifier
import java.awt.Color
import java.time.LocalDate
import java.time.Month


open class plugin : Plugin() {
    val halloweenStartDate = LocalDate.of(LocalDate.now().year, Month.OCTOBER, 17)
    val halloweenEndDate = LocalDate.of(LocalDate.now().year, Month.NOVEMBER, 7)
    val christmasStartDate = LocalDate.of(LocalDate.now().year, Month.DECEMBER, 1)
    val christmasEndDate = LocalDate.of(LocalDate.now().year, Month.DECEMBER, 31)
    val rs2ReleaseDate = LocalDate.of(LocalDate.now().year, Month.MARCH, 29)
    val farmingReleaseDate = LocalDate.of(LocalDate.now().year, Month.JULY, 11)
    val constructionReleaseDate = LocalDate.of(LocalDate.now().year, Month.MAY, 31)
    val hunterReleaseDate = LocalDate.of(LocalDate.now().year, Month.NOVEMBER, 21)
    val summonReleaseDate = LocalDate.of(LocalDate.now().year, Month.JANUARY, 15)

    val christmasMusicList = listOf("scape santa", "land of snow", "jungle bells")
    var currentHoliday: String = "none"


    fun checkIfHoliday(): String {
        val currentDate = LocalDate.now()
        if (!currentDate.isBefore(halloweenStartDate) && !currentDate.isAfter(halloweenEndDate))
            return "halloween"
        if (!currentDate.isBefore(christmasStartDate) && !currentDate.isAfter(christmasEndDate))
            return "christmas"
        if (currentDate.isEqual(rs2ReleaseDate))
            return "rs2release"
        if (currentDate.isEqual(farmingReleaseDate))
            return "farmingrelease"
        if (currentDate.isEqual(constructionReleaseDate))
            return "constructionrelease"
        if (currentDate.isEqual(hunterReleaseDate))
            return "hunterrelease"
        if (currentDate.isEqual(summonReleaseDate))
            return "summoningrelease"

        return "none"
    }

    override fun Init() {
        currentHoliday = checkIfHoliday()
        when (currentHoliday) {
            "halloween" -> API.SetLoginScreenMusicOnLoad("scape scared")
            "christmas" -> API.SetLoginScreenMusicOnLoad(christmasMusicList.random())
            "rs2release" -> API.SetLoginScreenMusicOnLoad("scape original")
            "farmingrelease" -> API.SetLoginScreenMusicOnLoad("ground scape")
            "constructionrelease" -> API.SetLoginScreenMusicOnLoad("homescape")
            "hunterrelease" -> API.SetLoginScreenMusicOnLoad("scape hunter")
            "summoningrelease" -> API.SetLoginScreenMusicOnLoad("scape summon")
        }
    }

    override fun Draw(deltaTime: Long) {
        if (!API.IsLoggedIn()) {
            when(currentHoliday) {
                "christmas" -> API.DrawText(FontType.LARGE, FontColor.fromColor(Color.GREEN), TextModifier.LEFT, "Happy Holidays.", 0, API.GetWindowDimensions().height - 5)
                "halloween" -> API.DrawText(FontType.LARGE, FontColor.fromColor(Color.ORANGE), TextModifier.LEFT, "Happy Halloween.", 0, API.GetWindowDimensions().height - 5)
                "rs2release" -> API.DrawText(FontType.LARGE, FontColor.fromColor(Color.YELLOW), TextModifier.LEFT, "On this day in 2004, RS2 was released.", 0, API.GetWindowDimensions().height - 5)
                "farmingrelease" -> API.DrawText(FontType.LARGE, FontColor.fromColor(Color.YELLOW), TextModifier.LEFT, "On this day in 2005, the farming skill was released.", 0, API.GetWindowDimensions().height - 5)
                "constructionrelease" -> API.DrawText(FontType.LARGE, FontColor.fromColor(Color.YELLOW), TextModifier.LEFT, "On this day in 2006, the construction skill was released.", 0, API.GetWindowDimensions().height - 5)
                "hunterrelease" -> API.DrawText(FontType.LARGE, FontColor.fromColor(Color.YELLOW), TextModifier.LEFT, "On this day in 2006, the hunter skill was released.", 0, API.GetWindowDimensions().height - 5)
                "summoningrelease" -> API.DrawText(FontType.LARGE, FontColor.fromColor(Color.YELLOW), TextModifier.LEFT, "On this day in 2008, the summoning skill was released.", 0, API.GetWindowDimensions().height - 5)
            }
        }
    }
}