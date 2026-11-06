package SlayerTrackerPlugin
    
import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.API
import plugin.api.FontColor
import plugin.api.FontType
import plugin.api.TextModifier
import rt4.Sprite
import java.awt.Color
import java.lang.Exception

class plugin : Plugin() {
    val boxColor = 6116423
    val posX = 5
    val posY = 30
    val boxWidth = 90
    val boxHeight = 30
    val boxOpacity = 160
    val textX = 65
    val textY = 50
    val spriteX = 7
    val spriteY = 30

    var slayerTaskID = -1
    var slayerTaskAmount = 0
    var curSprite: Sprite? = null

    override fun Draw(deltaTime: Long) {
        if (slayerTaskAmount == 0 || slayerTaskID == -1) return

        API.FillRect(posX, posY, boxWidth, boxHeight, boxColor, boxOpacity)
        curSprite?.render(spriteX, spriteY)
        API.DrawText(
            FontType.SMALL,
            FontColor.fromColor(Color.WHITE),
            TextModifier.LEFT,
            slayerTaskAmount.toString(),
            textX,
            textY
        )
    }

    override fun OnVarpUpdate(id: Int, value: Int) {
        if (id == 2502) {
            slayerTaskID = value and 0x7F
            slayerTaskAmount = (value shr 7) and 0xFF
            setSprite()
        }
    }

    override fun OnLogout() {
        slayerTaskID = -1
        slayerTaskAmount = 0
        curSprite = null
    }

    private fun setSprite() {
        try {
            val itemId: Int = when (slayerTaskID) {
                0  -> 4144
                1  -> 4149
                2  -> 9008
                3  -> 4135
                4  -> 4139
                5  -> 14072
                6  -> 948
                7  -> 12189
                8  -> 3098
                9  -> 1747
                10 -> 4141
                11 -> 1751
                12 -> 11047
                13 -> 2349
                14 -> 9008
                15 -> 4521
                16 -> 4134
                17 -> 8900
                18 -> 4520
                19 -> 4137
                20 -> 1739
                21 -> 7982
                22 -> 10149
                23 -> 8141
                24 -> 6637
                25 -> 6695
                26 -> 8132
                27 -> 4145
                28 -> 7500
                29 -> 1422
                30 -> 6105
                31 -> 6709
                32 -> 1387
                33 -> 28
                34 -> 4147
                35 -> 552
                36 -> 6722
                37 -> 10998
                38 -> 9016
                39 -> 2402
                40 -> 1753
                41 -> 7050
                42 -> 8137
                43 -> 12570
                44 -> 8133
                45 -> 4671
                46 -> 4671
                47 -> 1159
                48 -> 4140
                49 -> 2351
                50 -> 4142
                51 -> 7778
                52 -> 8139
                53 -> 7160
                54 -> 4146
                55 -> 2402
                56 -> 9007
                57 -> 2359
                58 -> 6661
                59 -> 10997
                60 -> 12201
                61 -> 12570
                62 -> 7420
                63 -> 4148
                64 -> 4818
                65 -> 6109
                66 -> 4138
                67 -> 8134
                68 -> 4136
                69 -> 9032
                70 -> 12055
                71 -> 7576
                72 -> 10634
                73 -> 1165
                74 -> 6811
                75 -> 553
                76 -> 8135
                77 -> 11732
                78 -> 10284
                79 -> 13923
                80 -> 2353
                81 -> 9105
                82 -> 10591
                83 -> 8136
                84 -> 4143
                85 -> 1549
                86 -> 4519
                87 -> 24
                88 -> 10535
                89 -> 571
                90 -> 2952
                91 -> 958
                92 -> 7594
                else -> -1
            }

            val sprite = API.GetObjSprite(itemId, 1, false, 1, 1)

            curSprite = sprite
        } catch (ignored: Exception){}
    }

    //Check the source of plugin.Plugin for more methods you can override! Happy hacking! <3
    //There are also many methods to aid in plugin development in plugin.api.API
}
