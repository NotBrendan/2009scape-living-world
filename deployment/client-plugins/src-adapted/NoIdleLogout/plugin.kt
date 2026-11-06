package NoIdleLogout

import plugin.Plugin
import rt4.Mouse

// The client asks the server to log you out after about 5 minutes without
// mouse or keyboard input (Protocol sends opcode 245 once Mouse and Keyboard
// idle counters pass 15000). Keeping the mouse counter at zero stops that.
class plugin : Plugin() {
    override fun Update() {
        Mouse.setIdleLoops(0)
    }
}
