package XPGlobesPlugin

import plugin.api.API
import rt4.IntNode
import rt4.Node

object XPTable {

    const val SKILLS_XP_TABLE = 716

    private var xpTable: MutableList<Int> = mutableListOf()

    // Function to lazily load the XP table from the API if it's not already populated
    private fun loadXpTable() {
        if (xpTable.isEmpty()) {
            // Add the initial entry for key 1 = 0
            xpTable.add(0)

            // Fetch XP table from the API
            API.GetDataMap(SKILLS_XP_TABLE).table.nodes.forEach { bucket ->
                var currentNode: Node = bucket.nextNode
                while (currentNode !== bucket) {
                    if (currentNode is IntNode) {
                        xpTable.add(currentNode.value)
                    }
                    currentNode = currentNode.nextNode
                }
            }
        }
    }

    fun getXpRequiredForLevel(level: Int): Int {
        loadXpTable()
        if (level in 1..xpTable.size) {
            return xpTable[level - 1]
        }
        return 0
    }

    fun getLevelForXp(xp: Int): Pair<Int, Int> {
        loadXpTable()
        var lowIndex = 0
        var highIndex = xpTable.size - 1

        if (xp >= xpTable[highIndex]) {
            return Pair(Constants.MAX_LEVEL, xp - xpTable[highIndex]) // Level is max or above, return the highest level
        }

        while (lowIndex <= highIndex) {
            val midIndex = (lowIndex + highIndex) / 2
            when {
                xp < xpTable[midIndex] -> highIndex = midIndex - 1
                xp >= xpTable[midIndex + 1] -> lowIndex = midIndex + 1
                else -> {
                    val currentLevel = midIndex + 1
                    val xpGained = xp - xpTable[midIndex]
                    return Pair(currentLevel, xpGained)
                }
            }
        }

        return Pair(Constants.INVALID_LEVEL, 0) // If xp is below all defined levels
    }
}
