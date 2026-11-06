package XPGlobesPlugin

import plugin.api.API
import rt4.Sprite


object XPSprites {

    private val spriteOffsets: Array<Pair<Int, Int>> = arrayOf(
                Pair(1,-1),  // attack
                Pair(0,0),   // defense
                Pair(0,0),   // strength
                Pair(0,1),   // health
                Pair(0,0),   // ranged
                Pair(0,0),   // prayer
                Pair(0,-1),  // magic
                Pair(0,-1),  // cooking
                Pair(0,0),   // woodcutting
                Pair(1,0),   // fletching
                Pair(3,0),   // fishing
                Pair(0,-1),  // fire-making
                Pair(1,0),   // crafting
                Pair(1,0),   // smithing
                Pair(2,-1),  // mining
                Pair(0,1),   // herblore
                Pair(2,0),   // agility
                Pair(0,0),   // thieving
                Pair(-1,0),  // slayer
                Pair(0,0),   // farming
                Pair(0,0),   // runecrafting
                Pair(0,0),   // hunter
                Pair(1,-1),  // construction
                Pair(2,-1),  // summoning
            )


    fun getSpriteForSkill(skillId: Int) : Sprite? {
        return API.GetSprite(getSpriteId(skillId))
    }


    fun getSpriteOffsetForSkill(skillId: Int) : Pair<Int, Int> {
        if (skillId < 0 || skillId >= Constants.SKILL_COUNT) {
            return Pair(0,0)
        }

        return spriteOffsets[skillId];
    }


    private fun getSpriteId(skillId: Int) : Int {
        return when (skillId) {
            0 -> 197
            1 -> 199
            2 -> 198
            3 -> 203
            4 -> 200
            5 -> 201
            6 -> 202
            7 -> 212
            8 -> 214
            9 -> 208
            10 -> 211
            11 -> 213
            12 -> 207
            13 -> 210
            14 -> 209
            15 -> 205
            16 -> 204
            17 -> 206
            18 -> 216
            19 -> 217
            20 -> 215
            21 -> 220
            22 -> 221
            23 -> 222
            else -> 222
        }
    }
}
