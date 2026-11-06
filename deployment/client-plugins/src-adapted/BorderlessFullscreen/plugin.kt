package BorderlessFullscreen

import plugin.Plugin
import plugin.api.API
import plugin.api.WindowMode
import rt4.GameShell
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

// Borderless fullscreen (what Fullscreenizer does, inside the client): the game window loses its border
// and is stretched over the monitor. Windows never revokes a plain window, so alt-tab, notifications and
// overlays leave it alone, unlike the client's own exclusive fullscreen.
//
// This touches ONLY the AWT frame. It never calls DisplayMode.setWindowMode: the client reloads every
// plugin when the window mode changes, and doing that from inside a plugin hook crashes the client.
// In the resizable layout the client fills the frame; in the fixed layout the 765x503 game sits in the
// middle of a black window, which is exactly what Fullscreenizer gives a fixed-size game.
//
// F11 or ::borderless toggles it; ::borderless 2 picks monitor 2; ::borderless off leaves it.
// The setting is remembered and re-applied on the next launch.
class plugin : Plugin() {
    companion object {
        const val KEY = "borderless:on"
        const val MONITOR = "borderless:monitor"
    }

    private var on = false
    private var monitor = -1
    private var savedBounds: Rectangle? = null
    private var lastCheck = 0L
    private var busyUntil = 0L

    override fun Init() {
        on = (API.GetData(KEY) as? Boolean) ?: false
        monitor = (API.GetData(MONITOR) as? Int) ?: -1
        (API.GetData("borderless:saved") as? String)?.let { s ->
            val p = s.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (p.size == 4) savedBounds = Rectangle(p[0], p[1], p[2], p[3])
        }
        API.AddKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_F11) toggle() }
        })
    }

    private fun frame() = GameShell.frame

    private fun monitorBounds(): Rectangle {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        if (monitor in 1..devices.size) return devices[monitor - 1].defaultConfiguration.bounds
        val f = frame()
        if (f != null) {
            val c = f.bounds; val cx = c.x + c.width / 2; val cy = c.y + c.height / 2
            for (d in devices) { val b = d.defaultConfiguration.bounds; if (b.contains(cx, cy)) return b }
        }
        return devices[0].defaultConfiguration.bounds
    }

    private fun applied(): Boolean {
        val f = frame() ?: return false
        return f.isUndecorated && f.bounds == monitorBounds()
    }

    private fun toggle() { if (on) leave() else enter() }

    /** Strip the border and cover the monitor, on the AWT thread; the client follows the frame size on its own. */
    private fun enter() {
        val f = frame() ?: return
        if (System.currentTimeMillis() < busyUntil) return
        busyUntil = System.currentTimeMillis() + 3000
        if (savedBounds == null && !f.isUndecorated) {
            savedBounds = Rectangle(f.bounds)
            API.StoreData("borderless:saved", "${f.x},${f.y},${f.width},${f.height}")
        }
        val b = monitorBounds()
        EventQueue.invokeLater {
            try {
                f.isVisible = false
                f.dispose()
                f.isUndecorated = true
                f.bounds = b
                f.isVisible = true
                // Fixed layout: centre the 765x503 game in the black frame, like Fullscreenizer does for a fixed-size game.
                if (API.GetWindowMode() == WindowMode.FIXED) { GameShell.leftMargin = Math.max(0, (b.width - 765) / 2); GameShell.topMargin = Math.max(0, (b.height - 503) / 2) }
                GameShell.leftMargin = 0; GameShell.topMargin = 0
                GameShell.replaceCanvas = true // the client rebuilds its canvas inside the recreated frame
                GameShell.fullRedraw = true
                f.toFront()
                f.requestFocus()
            } catch (t: Throwable) { API.SendMessage("Borderless: couldn't change the window: $t") }
        }
        on = true
        API.StoreData(KEY, true)
        API.SendMessage("Borderless fullscreen on. F11 or ::borderless off to leave" + (if (API.GetWindowMode() == WindowMode.FIXED) "; use the resizable layout (::rsd) to fill the screen." else "."))
    }

    private fun leave() {
        val f = frame() ?: return
        if (System.currentTimeMillis() < busyUntil) return
        busyUntil = System.currentTimeMillis() + 3000
        val b = savedBounds ?: Rectangle(100, 100, 765 + 16, 503 + 39)
        EventQueue.invokeLater {
            try {
                f.isVisible = false
                f.dispose()
                f.isUndecorated = false
                f.bounds = b
                f.isVisible = true
                GameShell.replaceCanvas = true // the client rebuilds its canvas inside the recreated frame
                GameShell.fullRedraw = true
            } catch (t: Throwable) { API.SendMessage("Borderless: couldn't restore the window: $t") }
        }
        on = false
        savedBounds = null
        API.StoreData(KEY, false)
        API.SendMessage("Borderless fullscreen off.")
    }

    override fun Update() {
        if (!on || frame() == null) return
        // Re-apply after a plugin reload, a display or DPI change; checked a few times a second.
        val now = System.currentTimeMillis()
        if (now - lastCheck < 500 || now < busyUntil) return
        lastCheck = now
        try { if (!applied()) { on = false; enter() } } catch (t: Throwable) { }
    }

    override fun ProcessCommand(commandStr: String?, args: Array<out String>?) {
        if (!commandStr.equals("::borderless", true)) return
        val a = args?.firstOrNull()?.toLowerCase()
        when {
            a == "off" -> if (on) leave()
            a == "on" -> if (!on) enter()
            a == "status" -> { val f = frame(); API.SendMessage("Borderless ${if (on) "on" else "off"}; window ${f?.bounds} undecorated=${f?.isUndecorated} canvas=${GameShell.canvasWidth}x${GameShell.canvasHeight} mode=${API.GetWindowMode()} fullScreenFrame=${GameShell.fullScreenFrame != null}") }
            a?.toIntOrNull() != null -> { monitor = a.toInt(); API.StoreData(MONITOR, monitor); on = false; enter() }
            else -> toggle()
        }
    }
}
