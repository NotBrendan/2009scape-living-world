package SmartRoofs

import plugin.Plugin
import plugin.api.API
import plugin.api.MiniMenuEntry

class plugin : Plugin() {
    override fun Init() {
        API.SetRoofVisibilityHandler(RoofHider.HANDLER)
    }

    override fun Draw(timeDelta: Long) {
        if (!API.IsLoggedIn()) {
            clearTargets()
            return
        }
        API.SetRoofVisibilityHandler(RoofHider.HANDLER)
        RoofHider.setSmartRoofsEnabled(true)
        RoofHider.updateSmartRoofs()

        val mapFlag = API.GetMapFlagTile()
        if (mapFlag == null) {
            RoofHider.clearMapFlagRoof()
        } else {
            RoofHider.setMapFlagRoof(mapFlag[0], mapFlag[1], MAP_FLAG_RADIUS)
        }
    }

    override fun OnMiniMenuCreate(currentEntries: Array<out MiniMenuEntry>?) {
        RoofHider.updateHoverPick(currentEntries)
    }

    override fun OnLogout() {
        clearTargets()
    }

    override fun OnPluginsReloaded(): Boolean {
        clearTargets()
        API.ClearRoofVisibilityHandler(RoofHider.HANDLER)
        return false
    }

    private fun clearTargets() {
        RoofHider.clearMapFlagRoof()
        RoofHider.setSmartRoofsEnabled(false)
    }

    companion object {
        private const val MAP_FLAG_RADIUS = 0
    }
}
