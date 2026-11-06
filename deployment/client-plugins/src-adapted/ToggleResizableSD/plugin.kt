package ToggleResizableSD

import KondoKit.Exposed
import plugin.Plugin
import plugin.api.API
import plugin.api.API.StoreData
import rt4.DisplayMode
import rt4.GameShell
import rt4.InterfaceList
import rt4.client
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

class plugin : Plugin() {

    @Exposed("Use Resizable SD")
    var useResizable = false

    @Exposed("Setting wantHd to true hides the black screen on logout (when resize SD is enabled), by enabling HD on logout ")
    var wantHd = false

    override fun Init() {
        API.AddKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_F12) {
                    toggleResizableSd()
                }
            }
        })

        useResizable = DisplayMode.resizableSD
        if (API.GetData("use-resizable-sd") == true) {
            useResizable = true
        }

        var osNameLowerCase: String = System.getProperty("os.name").toLowerCase()
        if (!osNameLowerCase.startsWith("mac")) {
            wantHd = true
        }

        if (API.GetData("want-hd") == false) {
            wantHd = false
        }
    }

    override fun ProcessCommand(commandStr: String, args: Array<out String>?) {
        when (commandStr.toLowerCase()) {
            "::toggleresizablesd", "::resizablesd", "::togglersd", "::rsd" -> {
                toggleResizableSd()
            }
            "::toggleresizablesdhd", "::resizablesdhd", "::togglersdhd", "::rsdhd" -> {
                wantHd = !wantHd
                StoreData("want-hd", wantHd)
                API.SendMessage("You have turned login screen HD " + (if (wantHd) "on" else "off"))
            }
        }
    }

    fun toggleResizableSd() {
        //We only want to toggle resizable SD when we are logged in and the lobby/welcome interface is not open.
        if (InterfaceList.aClass13_26 == null || client.gameState != 30) {
            return
        }

        DisplayMode.resizableSD = !DisplayMode.resizableSD
        useResizable = DisplayMode.resizableSD
        StoreData("use-resizable-sd", useResizable)

        if (!DisplayMode.resizableSD) {
            DisplayMode.setWindowMode(true, 0, -1, -1)
        } else {
            DisplayMode.setWindowMode(true, 0, GameShell.frameWidth, GameShell.frameHeight)
        }
    }

    override fun Draw(timeDelta: Long) {
        if (useResizable != DisplayMode.resizableSD) {
            toggleResizableSd()
        }
    }

    fun OnKondoValueUpdated() {
        StoreData("want-hd", wantHd)
        StoreData("use-resizable-sd", useResizable)
    }

    override fun OnLogout() {
        if (DisplayMode.resizableSD && wantHd) {
            //Because resizable SD always uses the "HD" size canvas/window mode (check the in-game Graphics Options with resizeable SD enabled if you don't believe me!), useHD becomes true when logging out, so logging out with resizeSD enabled means "HD" will always be enabled on the login screen after logging out, so we might as well fix the HD flyover by setting resizableSD to false first, and then calling setWindowMode to replace the canvas and set newMode to 2.
            DisplayMode.resizableSD = false
            DisplayMode.setWindowMode(true, 2, GameShell.frameWidth, GameShell.frameHeight)
        }
    }
}