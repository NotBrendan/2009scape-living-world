package EscClose

import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.API
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

class plugin : Plugin() {
    override fun Init() {
        API.AddKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE)
                    API.DispatchCommand("::xface")
            }
        })
    }
}