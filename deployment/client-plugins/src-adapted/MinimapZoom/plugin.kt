package MinimapZoom

import plugin.Plugin
import plugin.api.API
import plugin.api.FontColor
import plugin.api.FontType
import plugin.api.MiniMenuEntry
import plugin.api.TextModifier
import plugin.api.WindowMode
import rt4.Component
import rt4.ComponentPointer
import rt4.GameShell
import rt4.InterfaceList
import rt4.MiniMap
import rt4.client
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener

// Minimap zoom: + and - buttons, the mouse wheel over the minimap, right-click entries on the
// minimap, Ctrl +/- and Ctrl 0 on the keyboard, and ::mmzoom in|out|reset.
//
// The client keeps its minimap scale in MiniMap.anInt4130: positive values show more area
// ((256 + value) / 256 times the normal view), negative values zoom in. Minimap clicks already use
// the same value, so click-to-walk follows the zoom. The server walks at most max_pathfind_dist (50)
// tiles per click and the client only knows ~52 tiles around you, so 2x out is the useful limit.
//
// Everything is drawn in Draw(), which runs inside the frame while the game view is painted, so it
// never flickers. In fixed mode the side panel is painted after Draw(), so the buttons sit in the
// game view's top-right corner; in resizable and fullscreen they sit just under the minimap.
class plugin : Plugin() {
    companion object {
        // Zoom values, from most zoomed out (2x the distance) to slightly zoomed in.
        val LEVELS = intArrayOf(256, 192, 128, 64, 0, -48)
        const val DEFAULT = 4
        const val MINIMAP_CODE = 1338
        const val BUTTON_W = 18
        const val BUTTON_H = 15
        const val LABEL_MS = 2500L
    }

    private var level = DEFAULT
    // Screen rectangle of the minimap and of the two buttons, refreshed every frame.
    private var mx = -1; private var my = -1; private var mw = 0; private var mh = 0
    private var seenLoop = -100
    private var minusX = -1; private var plusX = -1; private var buttonsY = -1
    private var labelUntil = 0L
    private var rightDown = false
    private var lastSearch = 0L
    private var minimap: Component? = null
    private var censusUntil = -1

    override fun Init() {
        (API.GetData("minimapzoom:level") as? Int)?.let { if (it in LEVELS.indices) level = it }
        // The client reloads plugins (at login, among other times) but keeps the API's listener lists, so an
        // earlier copy of this plugin may still be listening. Drop those first, or one wheel tick zooms twice.
        try {
            val mine = { o: Any? -> o != null && o.javaClass.name.startsWith("MinimapZoom.") }
            API.registeredWheelListeners?.removeIf { mine(it) }
            API.registeredMouseListeners?.removeIf { mine(it) }
            API.registeredKeyListeners?.removeIf { mine(it) }
        } catch (t: Throwable) { }
        API.AddMouseWheelListener(object : MouseWheelListener {
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (!API.IsLoggedIn() || !rectKnown()) return
                // AWT coordinates are in window pixels; the game works in its own (possibly scaled) pixels.
                val scale = if (GameShell.canvasScale > 0) GameShell.canvasScale else 1.0
                val gx = (e.x / scale).toInt()
                val gy = (e.y / scale).toInt()
                if (gx in mx until mx + mw && gy in my until my + mh) {
                    if (e.wheelRotation > 0) step(-1) else if (e.wheelRotation < 0) step(1)
                }
            }
        })
        API.AddMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.button == MouseEvent.BUTTON3) rightDown = true }
            override fun mouseReleased(e: MouseEvent) { if (e.button == MouseEvent.BUTTON3) rightDown = false }
        })
        API.AddKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!e.isControlDown || !API.IsLoggedIn()) return
                when (e.keyCode) {
                    KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> step(1)
                    KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> step(-1)
                    KeyEvent.VK_0, KeyEvent.VK_NUMPAD0 -> setLevel(DEFAULT)
                }
            }
        })
    }

    private fun rectKnown() = mx >= 0 && client.loop - seenLoop <= 5

    private fun step(dir: Int) = setLevel((level + dir).coerceIn(0, LEVELS.size - 1))

    private fun setLevel(next: Int) {
        labelUntil = System.currentTimeMillis() + LABEL_MS
        if (next == level) return
        level = next
        API.StoreData("minimapzoom:level", level) // a plain Int: safe to save
        apply()
    }

    private fun apply() {
        // Overrides the client's small anti-cheat wobble on the same value.
        MiniMap.anInt4130 = LEVELS[level]
    }

    override fun Update() {
        if (API.IsLoggedIn()) apply()
    }

    /** Diagnostics only (::mmzoom census): which components the client reports through this hook. */
    override fun ComponentDraw(a: Int, c: Component?, x: Int, y: Int) {
        if (c == null || client.loop > censusUntil) return
        println("mmzoom census loop=${client.loop} a=$a id=${c.id ushr 16}:${c.id and 0xffff} type=${c.type} code=${c.clientCode} at=$x,$y ${c.width}x${c.height}")
    }

    /**
     * Finds the minimap component (client code 1338) drawn most recently (fixed and resizable layouts
     * each have one) and works out its absolute screen position by walking its parents and the
     * interface it is opened inside. Returns a description for ::mmzoom dump.
     */
    private fun locate(): String {
        val now = System.currentTimeMillis()
        var c = minimap
        if (c == null || now - lastSearch > 2000 || client.loop - c.rectangleLoop > 5) {
            lastSearch = now
            c = null
            val all = InterfaceList.components ?: run { mx = -1; return "no components" }
            for (group in all) {
                if (group == null) continue
                for (comp in group) {
                    if (comp == null || comp.clientCode != MINIMAP_CODE || comp.width <= 0) continue
                    if (c == null || comp.rectangleLoop > c.rectangleLoop) c = comp
                }
            }
            minimap = c
        }
        if (c == null) { mx = -1; return "no 1338 component" }
        if (client.loop - c.rectangleLoop > 5) { mx = -1; return "1338 component ${c.id ushr 16}:${c.id and 0xffff} not drawn for ${client.loop - c.rectangleLoop} frames" }
        var x = c.x
        var y = c.y
        var cur: Component = c
        var guard = 0
        val trail = StringBuilder("${c.id ushr 16}:${c.id and 0xffff}@${c.x},${c.y}")
        while (guard++ < 30) {
            if (cur.overlayer != -1) {
                val parent = InterfaceList.getComponent(cur.overlayer) ?: break
                x += parent.x - parent.scrollX
                y += parent.y - parent.scrollY
                trail.append(" <").append(parent.id ushr 16).append(':').append(parent.id and 0xffff)
                cur = parent
                continue
            }
            val group = cur.id ushr 16
            if (group == InterfaceList.topLevelInterface) break
            val host = hostOf(group) ?: break
            x += host.x - host.scrollX
            y += host.y - host.scrollY
            trail.append(" <<").append(host.id ushr 16).append(':').append(host.id and 0xffff)
            cur = host
        }
        mx = x; my = y; mw = c.width; mh = c.height
        seenLoop = client.loop
        return "$trail -> $x,$y ${c.width}x${c.height} top=${InterfaceList.topLevelInterface}"
    }

    private fun hostOf(group: Int): Component? {
        val table = InterfaceList.openInterfaces ?: return null
        var node = table.head()
        while (node != null) {
            if (node is ComponentPointer && node.interfaceId == group) return InterfaceList.getComponent(node.key.toInt())
            node = table.next()
        }
        return null
    }

    private fun factorText(): String {
        val v = LEVELS[level]
        if (v == 0) return "1x"
        val f = (256 + v) / 256.0
        return if (f == Math.floor(f)) "${f.toInt()}x" else String.format("%.2fx", f).replace("0x", "x")
    }

    override fun Draw(timeDelta: Long) {
        if (!API.IsLoggedIn()) return
        try {
            apply()
            locate()
            if (mx < 0) { buttonsY = -1; return }
            val fixed = API.GetWindowMode() == WindowMode.FIXED
            val cx: Int
            if (fixed) {
                // Top-right of the 512x334 game view at (4,4): drawn in-frame, nothing paints over it.
                cx = 516 - 6 - BUTTON_W - 14
                buttonsY = 8
            } else {
                // Just under the minimap, over the game view. Never on the minimap itself: there its own
                // "Walk here" wins the click.
                cx = mx + mw / 2
                buttonsY = my + mh + 16 // the minimap's decorative frame overhangs its rectangle by about a dozen pixels
            }
            minusX = cx - BUTTON_W - 14
            plusX = cx + 14
            drawButton(minusX, "-", level > 0)
            drawButton(plusX, "+", level < LEVELS.size - 1)
            val showLabel = System.currentTimeMillis() < labelUntil || LEVELS[level] != 0
            if (showLabel) API.DrawText(FontType.SMALL, FontColor.fromColor(java.awt.Color(255, 230, 140)), TextModifier.CENTER, factorText(), cx, buttonsY + 11)
        } catch (t: Throwable) {
            mx = -1; buttonsY = -1 // never break the frame
        }
    }

    private fun drawButton(x: Int, label: String, enabled: Boolean) {
        val hover = API.GetMouseX() in x until x + BUTTON_W && API.GetMouseY() in buttonsY until buttonsY + BUTTON_H
        API.FillRect(x, buttonsY, BUTTON_W, BUTTON_H, if (hover && enabled) 0x5a4a32 else 0x3e3529, 0)
        API.DrawRect(x, buttonsY, BUTTON_W, BUTTON_H, 0x1a1510)
        API.DrawText(FontType.SMALL, FontColor.fromColor(if (enabled) java.awt.Color(255, 152, 31) else java.awt.Color(110, 100, 90)),
            TextModifier.CENTER, label, x + BUTTON_W / 2, buttonsY + 12)
    }

    private fun overButton(x: Int, bx: Int, y: Int) = buttonsY >= 0 && y in buttonsY until buttonsY + BUTTON_H && x in bx until bx + BUTTON_W

    // Entries added last become the left-click action, so a click on a button zooms instead of walking.
    // On the minimap itself entries are added only while the right button is held, so left-click walks.
    override fun OnMiniMenuCreate(currentEntries: Array<out MiniMenuEntry>?) {
        if (!API.IsLoggedIn()) return
        val x = API.GetMouseX()
        val y = API.GetMouseY()
        if (overButton(x, minusX, y)) { if (level > 0) API.InsertMiniMenuEntry("Zoom out", "Minimap") { step(-1) }; return }
        if (overButton(x, plusX, y)) { if (level < LEVELS.size - 1) API.InsertMiniMenuEntry("Zoom in", "Minimap") { step(1) }; return }
        if (!rightDown || !rectKnown()) return
        if (x !in mx until mx + mw || y !in my until my + mh) return
        if (level < LEVELS.size - 1) API.InsertMiniMenuEntry("Zoom in", "Minimap") { step(1) }
        if (level > 0) API.InsertMiniMenuEntry("Zoom out", "Minimap") { step(-1) }
        if (level != DEFAULT) API.InsertMiniMenuEntry("Reset zoom", "Minimap") { setLevel(DEFAULT) }
    }

    override fun ProcessCommand(commandStr: String?, args: Array<out String>?) {
        if (!commandStr.equals("::mmzoom", true)) return
        val arg = args?.firstOrNull()?.toLowerCase()
        when {
            arg == "in" -> step(1)
            arg == "out" -> step(-1)
            arg == "reset" -> setLevel(DEFAULT)
            arg == "census" -> { censusUntil = client.loop + 2; println("mmzoom census start loop=${client.loop} mode=${API.GetWindowMode()}") }
            arg == "dump" -> { val r = locate(); val msg = "mmzoom dump: rect=$mx,$my ${mw}x$mh buttons=$minusX/$plusX@$buttonsY mode=${API.GetWindowMode()} $r"; println(msg); API.SendMessage(msg) }
            arg?.toIntOrNull() != null -> setLevel(arg.toInt().coerceIn(0, LEVELS.size - 1))
            else -> API.SendMessage("Minimap zoom ${factorText()} (level $level). Use the + and - buttons, the wheel over the minimap, right-click it, or Ctrl +/-/0.")
        }
    }
}
