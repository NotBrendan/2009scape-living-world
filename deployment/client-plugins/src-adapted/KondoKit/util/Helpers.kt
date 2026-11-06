package KondoKit.util

import KondoKit.plugin
import rt4.GameShell
import java.awt.*
import java.awt.image.BufferedImage
import java.awt.event.MouseListener
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.*
import java.util.Timer
import javax.swing.*

object Helpers {
    fun openResource(path: String) = plugin::class.java.getResourceAsStream(path)

    // Read a bundled resource as text using the given charset (UTF-8 by default)
    fun readResourceText(path: String, charset: Charset = StandardCharsets.UTF_8): String? {
        val stream = openResource(path) ?: return null
        stream.use { s ->
            return s.reader(charset).use { it.readText() }
        }
    }

    data class ImageCanvasComponents(val canvas: ImageCanvas, val container: JPanel)

    fun createImageCanvasComponents(
        bufferedImage: BufferedImage,
        background: Color = plugin.WIDGET_COLOR,
        size: Dimension = Dimension(bufferedImage.width, bufferedImage.height),
        borderInsets: Insets = Insets(-2, 0, 0, 2),
        componentPosition: String = BorderLayout.NORTH
    ): ImageCanvasComponents {
        val imageCanvas = ImageCanvas(bufferedImage).apply {
            setFixedSize(size)
            this.size = size
            this.background = background
        }

        val container = JPanel(BorderLayout()).apply {
            this.background = background
            add(imageCanvas, componentPosition)
            border = BorderFactory.createEmptyBorder(
                borderInsets.top,
                borderInsets.left,
                borderInsets.bottom,
                borderInsets.right
            )
        }

        return ImageCanvasComponents(imageCanvas, container)
    }

    fun convertValue(type: Class<*>, genericType: Type?, value: String): Any {
        return when {
            type == Int::class.java -> value.toInt()
            type == Double::class.java -> value.toDouble()
            type == Boolean::class.java -> value.toBoolean()
            type == Color::class.java -> convertToColor(value)
            type == List::class.java && genericType is ParameterizedType -> {
                val actualTypeArgument = genericType.actualTypeArguments.firstOrNull()
                when {
                    value.isBlank() -> emptyList<Any>() // Handle empty string by returning an empty list
                    actualTypeArgument == Int::class.javaObjectType -> value.trim('[', ']').split(",").filter { it.isNotBlank() }.map { it.trim().toInt() }
                    actualTypeArgument == String::class.java -> value.trim('[', ']').split(",").filter { it.isNotBlank() }.map { it.trim() }
                    else -> throw IllegalArgumentException("Unsupported List type: $actualTypeArgument")
                }
            }
            else -> value // Default to String
        }
    }

    fun showToast(
        parentComponent: Component?,
        message: String,
        messageType: Int = JOptionPane.INFORMATION_MESSAGE
    ) {
        SwingUtilities.invokeLater {
            val toast = JWindow()
            toast.type = Window.Type.POPUP
            toast.background = Color(0, 0, 0, 0)

            val panel = JPanel()
            panel.isOpaque = false
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

            val label = JLabel(message)
            label.foreground = Color.WHITE

            label.background = when (messageType) {
                JOptionPane.ERROR_MESSAGE -> Color(220, 20, 60, 230) // Crimson for errors
                JOptionPane.INFORMATION_MESSAGE -> Color(0, 128, 0, 230) // Green for success
                JOptionPane.WARNING_MESSAGE -> Color(255, 165, 0, 230) // Orange for warnings
                else -> Color(0, 0, 0, 170) // Default semi-transparent black
            }

            label.isOpaque = true
            label.border = BorderFactory.createEmptyBorder(10, 20, 10, 20)
            label.maximumSize = Dimension(242, 50)
            label.preferredSize = Dimension(242, 50)
            panel.add(label)



            toast.contentPane.add(panel)
            toast.pack()


            // Adjust for parent component location if it exists
            if (parentComponent != null && GameShell.canvas.isShowing) {
                val parentLocation = parentComponent.locationOnScreen
                val x = parentLocation.x
                val y = GameShell.canvas.locationOnScreen.y
                toast.setLocation(x, y)
            } else {
                // Fallback to screen center if no parent is provided
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                val x = (screenSize.width - toast.width) / 2
                val y = screenSize.height - toast.height - 50
                toast.setLocation(x, y)
            }

            toast.isVisible = true

            Timer().schedule(object : TimerTask() {
                override fun run() {
                    SwingUtilities.invokeLater {
                        toast.isVisible = false
                        toast.dispose()
                    }
                }
            }, 2000)
        }
    }


    private fun convertToColor(value: String): Color {
        return Color.decode(value)
    }

    fun colorToHex(color: Color): String {
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    fun colorToIntArray(color: Color): IntArray {
        return intArrayOf(color.red, color.green, color.blue)
    }

    interface FieldObserver {
        fun onFieldChange(field: Field, newValue: Any?)
    }

    fun addMouseListenerToAll(container: Container, listener: MouseListener) {
        // Recursively go through all components within the container
        for (component in container.components) {
            // Add the passed MouseListener to the component
            if (component is JComponent || component is Canvas) {
                component.addMouseListener(listener)
            }

            // If the component is a container, recursively call this function
            if (component is Container) {
                addMouseListenerToAll(component, listener)
            }
        }
    }




    class FieldNotifier(private val plugin: Any) {
        private val observers = mutableListOf<FieldObserver>()

        private fun notifyFieldChange(field: Field, newValue: Any?) {
            for (observer in observers) {
                observer.onFieldChange(field, newValue)
            }
        }

        fun setFieldValue(field: Field, value: Any?) {
            field.isAccessible = true
            field.set(plugin, value)
            notifyFieldChange(field, value)

            try {
                val onUpdateMethod = plugin::class.java.getMethod("OnKondoValueUpdated")
                onUpdateMethod.invoke(plugin)
            } catch (e: NoSuchMethodException) {
                // The method doesn't exist
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getSpriteId(skillId: Int) : Int {
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

    fun showAlert(message: String, title: String, type: Int){
        JOptionPane.showMessageDialog(null, message, title, type)
    }

    fun formatHtmlLabelText(text1: String, color1: Color, text2: String, color2: Color): String {
        return "<html><div style='white-space:nowrap;'>" +
                "<span style='color:rgb(${color1.red},${color1.green},${color1.blue});'>$text1</span>" +
                "<span style='color:rgb(${color2.red},${color2.green},${color2.blue});'>$text2</span>" +
                "</div></html>"
    }

    fun formatNumber(value: Int): String {
        return when {
            value >= 1_000_000 -> String.format("%.2fM", value / 1_000_000.0)
            value >= 1_000 -> String.format("%.2fK", value / 1_000.0)
            else -> value.toString()
        }
    }

    fun getProgressBarColor(skillId: Int): Color {
        // Straight from runelite
        return when (skillId) {
            0 -> Color(155, 32, 7)    // ATTACK
            1 -> Color(98, 119, 190)  // DEFENCE
            2 -> Color(4, 149, 90)    // STRENGTH
            3 -> Color(131, 126, 126) // HITPOINTS
            4 -> Color(109, 144, 23)  // RANGED
            5 -> Color(159, 147, 35)  // PRAYER
            6 -> Color(50, 80, 193)   // MAGIC
            7 -> Color(112, 35, 134)  // COOKING
            8 -> Color(52, 140, 37)   // WOODCUTTING
            9 -> Color(3, 141, 125)   // FLETCHING
            10 -> Color(106, 132, 164) // FISHING
            11 -> Color(189, 120, 25)  // FIREMAKING
            12 -> Color(151, 110, 77)  // CRAFTING
            13 -> Color(108, 107, 82)  // SMITHING
            14 -> Color(93, 143, 167)  // MINING
            15 -> Color(7, 133, 9)    // HERBLORE
            16 -> Color(58, 60, 137)  // AGILITY
            17 -> Color(108, 52, 87)  // THIEVING
            18 -> Color(100, 100, 100) // SLAYER
            19 -> Color(101, 152, 63)  // FARMING
            20 -> Color(170, 141, 26)  // RUNECRAFT
            21 -> Color(92, 89, 65)   // HUNTER
            22 -> Color(130, 116, 95)  // CONSTRUCTION
            23 -> Color(150, 50, 50)  // Placeholder for any additional skill
            else -> Color(128, 128, 128) // Default grey for unhandled skill IDs
        }
    }
}
