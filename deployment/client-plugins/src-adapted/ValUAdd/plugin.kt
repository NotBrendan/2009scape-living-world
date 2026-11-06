package ValUAdd

import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.API.SendMessage
import rt4.Inv
import rt4.ObjTypeList
import kotlin.math.round

class plugin : Plugin() {
    override fun ProcessCommand(commandStr: String, args: Array<out String>?) {
        when(commandStr.toLowerCase()) {
            "::valuadd" -> {
                var value = 0
                val inventory = Inv.objectContainerCache.get(93) as Inv
                for(i in 0 until inventory.objectIds.size) {
                    if(inventory.objectIds[i] != -1) {
                        val obj = ObjTypeList.get(inventory.objectIds[i])
                        val stackSize = inventory.objectStackSizes[i]
                        val itemBaseCost = round(obj.cost * 0.6).toInt()
                        val itemStackCost = itemBaseCost * stackSize
                        value += itemStackCost
                    }
                }
                SendMessage("Total HA value of inventory: $value GP")
            }
        }
    }
}