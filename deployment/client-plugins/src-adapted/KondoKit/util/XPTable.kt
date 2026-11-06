package KondoKit.util

object XPTable {
    private val xpForLevels: IntArray = IntArray(100)

    init {
        var points = 0.0
        for (level in 1..99) {
            points += Math.floor(level + 300.0 * Math.pow(2.0, level / 7.0))
            xpForLevels[level] = Math.floor(points / 4).toInt()
        }
    }

    fun getLevelForXp(xp: Int): Pair<Int, Int> {
        for (i in 1..99) {
            if (xp < xpForLevels[i]) {
                return Pair(i, xp - xpForLevels[i - 1])
            }
        }
        return Pair(99, 0)
    }

    fun getXpRequiredForLevel(level: Int): Int {
        if (level <= 0) return 0
        if (level >= 99) return xpForLevels[99]
        return xpForLevels[level - 1]
    }
}
