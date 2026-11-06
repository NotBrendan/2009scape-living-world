package Craftify

import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.API
import plugin.api.FontColor
import plugin.api.FontType
import plugin.api.TextModifier
import rt4.Fonts
import rt4.Player
import java.awt.Color

class plugin : Plugin() {
    override fun PlayerOverheadDraw(player: Player?, screenX: Int, screenY: Int) {
        if (player == null) return
        val width = Fonts.p11Full.getStringWidth(player.username)
        API.FillRect((screenX - width / 2) - 2, screenY - 20, width + 4, 14, FontColor.fromColor(Color.BLACK).colorCode, 125)
        API.DrawText(FontType.SMALL, FontColor.fromColor(Color.WHITE), TextModifier.CENTER, player.username.toString(), screenX, screenY - 8)
    }
}