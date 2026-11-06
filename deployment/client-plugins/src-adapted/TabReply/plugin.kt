package TabReply

import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.API
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

class plugin : Plugin() {
    override fun Init() {
        API.AddKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_TAB)
                    API.DispatchCommand("::reply")
            }
        })
    }
}