package BetterShiftDrop

import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.API
import plugin.api.MiniMenuEntry
import rt4.Cheat
import rt4.Keyboard

class plugin : Plugin() {
    override fun Init() {
        if(API.GetData("bsd-toggle") == null) {
            API.StoreData("bsd-toggle", true)
            Cheat.shiftClick = false
        } else if(API.GetData("bsd-toggle") == true) {
            Cheat.shiftClick = false
        }
    }
    override fun OnMiniMenuCreate(currentEntries: Array<out MiniMenuEntry>?) {
        if(currentEntries == null) return
        if(API.GetData("bsd-toggle") == true) {
            if(API.IsKeyPressed(Keyboard.KEY_SHIFT)) {
                for(entry in currentEntries) {
                    if(entry.verb.toLowerCase() == "drop" || entry.verb.toLowerCase() == "destroy") continue
                    if(!entry.isStrictlySecondary) entry.toggleStrictlySecondary()
                }
            }
        }
    }


    override fun ProcessCommand(commandStr: String?, args: Array<out String>?) {
        super.ProcessCommand(commandStr, args)
        when(commandStr) {
            "::bsd" -> {
                if(API.GetData("bsd-toggle") == true) {
                    API.StoreData("bsd-toggle", false)
                } else if(API.GetData("bsd-toggle") == false) {
                    API.StoreData("bsd-toggle", true)
                } else {
                    API.StoreData("bsd-toggle", true)
                }
            }
        }
    }
}