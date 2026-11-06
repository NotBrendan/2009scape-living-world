package SDChatForHD

import plugin.Plugin
import plugin.api.API
import rt4.Component
import rt4.GlRenderer
import rt4.InterfaceList
import rt4.JagString

class plugin : Plugin() {

    companion object {
        private const val CHAT_BACKDROP_HASH = 49283073
        private const val PAPER_TEXTURE_SPRITE = 1017
        private const val CHAT_BORDER_HASH = 49283074
        private const val CHAT_SCROLLBAR_HASH = 8978489
        private const val CHAT_BUTTONS_UNDERLAY_HASH = 48889879
        private const val CHAT_BUTTONS_UNDERLAY_SPRITE = 1018
        private const val CHAT_HD_BOTTOM_LINE_HASH = 8978486
        private const val CHAT_HD_TOP_LINE_HASH = 8978487
        private const val CHAT_SD_LINE_HASH = 8978485
        private const val CHAT_FIRST_ROW_HASH = 8978483
        private const val CHAT_FIRST_MESSAGE_HASH = 8978490
        private const val CHAT_LAST_MESSAGE_HASH = 8978589

        private val HD_PUBLIC = JagString.parse("<col=7fa9ff>")
        private val SD_PUBLIC = JagString.parse("<col=0000ff>")
        private val HD_TRADE = JagString.parse("<col=ff78d9>")
        private val SD_TRADE = JagString.parse("<col=800080>")
        private val HD_CLAN = JagString.parse("<col=ff5256>")
        private val SD_CLAN = JagString.parse("<col=800000>")
        private val HD_GOLD = JagString.parse("<col=f1b04c>")
        private val SD_CLAN_GOLD = JagString.parse("<col=800000>")
        private val HD_GLOBAL = JagString.parse("<col=f1b04c>")
        private val SD_GLOBAL = JagString.parse("<col=7512ff>")
        private val HD_BRACKETS = JagString.parse("<col=ffffff>")
        private val SD_BRACKETS = JagString.parse("<col=000000>")
        private val GLOBAL_SYMBOL = JagString.parse("<col=ffffff>[<col=f1b04c>G")
    }

    override fun Draw(timeDelta: Long) {
        if (!API.IsLoggedIn()) {
            return
        }

        if (!GlRenderer.enabled) {
            return
        }

        val chatBackdrop = InterfaceList.getComponent(CHAT_BACKDROP_HASH)
        chatBackdrop.spriteId = PAPER_TEXTURE_SPRITE

        val chatBorder = InterfaceList.getComponent(CHAT_BORDER_HASH)
        chatBorder?.hidden = false

        chatBorder?.createdComponents?.forEach { child ->
            child.hidden = false
        }

        val chatScrollbar = InterfaceList.getComponent(CHAT_SCROLLBAR_HASH)

        chatScrollbar?.createdComponents?.forEach { child ->
            when (child.createdComponentId) {
                0 -> child.spriteId = 792
                1 -> child.spriteId = 790
                2 -> child.spriteId = 789
                3 -> child.spriteId = 791
                4 -> child.spriteId = 773
                5 -> child.spriteId = 788
            }
        }

        val chatButtonsUnderlay = InterfaceList.getComponent(CHAT_BUTTONS_UNDERLAY_HASH)

        if (chatButtonsUnderlay != null) {
            chatButtonsUnderlay.spriteId = CHAT_BUTTONS_UNDERLAY_SPRITE
            chatButtonsUnderlay.getSprite(false)?.render(
                chatButtonsUnderlay.x,
                chatButtonsUnderlay.y,
            )
        }

        InterfaceList.getComponent(CHAT_SD_LINE_HASH)?.hidden = false
        InterfaceList.getComponent(CHAT_HD_BOTTOM_LINE_HASH)?.hidden = true
        InterfaceList.getComponent(CHAT_HD_TOP_LINE_HASH)?.hidden = true

        useSdChatColors(InterfaceList.getComponent(CHAT_FIRST_ROW_HASH))

        for (id in CHAT_FIRST_MESSAGE_HASH..CHAT_LAST_MESSAGE_HASH) {
            useSdChatColors(InterfaceList.getComponent(id))
        }
    }

    private fun useSdChatColors(component: Component?) {
        if (component == null) return

        var text = component.text ?: return

        if (text.indexOf(GLOBAL_SYMBOL) >= 0) {
            text = text.replaceAll(SD_GLOBAL, HD_GLOBAL)
            text = text.replaceAll(SD_BRACKETS, HD_BRACKETS)
        } else {
            text = text.replaceAll(SD_PUBLIC, HD_PUBLIC)
            text = text.replaceAll(SD_TRADE, HD_TRADE)
            text = text.replaceAll(SD_CLAN, HD_CLAN)
            text = text.replaceAll(SD_CLAN_GOLD, HD_GOLD)
        }

        component.text = text
        component.color = 0
        component.shadowed = false
    }
}