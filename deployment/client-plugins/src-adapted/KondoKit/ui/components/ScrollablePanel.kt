package KondoKit.ui.components

import KondoKit.plugin.Companion.SCROLL_BAR_COLOR
import KondoKit.plugin.Companion.VIEW_BACKGROUND_COLOR
import rt4.GameShell.frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.*
import java.util.*
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ScrollablePanel(private val content: JPanel) : JPanel() {
    private var lastMouseY = 0
    private var currentOffsetY = 0
    private var scrollbarHeight = 0
    private var scrollbarY = 0
    private var showScrollbar = false
    private var draggingScrollPill = false
    private var lastSize = 0

    // Define a buffer for the view height (extra space for smoother scrolling)
    private val viewBuffer = -30

    init {
        layout = null
        background = VIEW_BACKGROUND_COLOR // Color.red color can be set to debug

        // Initial content bounds
        content.bounds = Rectangle(0, 0, 242, content.preferredSize.height.coerceAtLeast(frame.height + viewBuffer))
        add(content)

        // Add listeners for scrolling interactions
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                lastMouseY = e.y
                if (showScrollbar && e.x in (242 - 10)..242 && e.y in scrollbarY..(scrollbarY + scrollbarHeight)) {
                    draggingScrollPill = true
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                draggingScrollPill = false
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val deltaY = e.y - lastMouseY
                if (draggingScrollPill && showScrollbar) {
                    val viewHeight = frame.height
                    val contentHeight = content.height
                    val scrollRatio = contentHeight.toDouble() / viewHeight
                    scrollContent((deltaY * scrollRatio).toInt())
                } else if (showScrollbar) {
                    scrollContent(deltaY)
                }
                lastMouseY = e.y
            }
        })

        addMouseWheelListener { e ->
            if (showScrollbar) {
                scrollContent(-e.wheelRotation * 20)
            }
        }

        // Timer to periodically check and update scrollbar status
        Timer().schedule(object : TimerTask() {
            override fun run() {
                updateScrollbar()
                if(lastSize != content.preferredSize.height.coerceAtLeast(frame.height + viewBuffer))
                    handleResize()
            }
        }, 0, 1000)

        // Component listener for resizing the frame
        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                handleResize()
            }
        })
    }

    private fun handleResize() {
        SwingUtilities.invokeLater{
            // Ensure the ScrollablePanel resizes with the frame
            bounds = Rectangle(0, 0, 242, frame.height)

        // Dynamically update content bounds and scrollbar on frame resize with buffer
        lastSize = content.preferredSize.height.coerceAtLeast(frame.height + viewBuffer)
        content.bounds = Rectangle(0, 0, 242, lastSize)
        showScrollbar = content.height > frame.height

            currentOffsetY = 0

            content.setLocation(0, currentOffsetY)
            updateScrollbar()

            revalidate()
            repaint()
        }
    }

    private fun scrollContent(deltaY: Int) {
        if (!showScrollbar) {
            currentOffsetY = 0
            content.setLocation(0, currentOffsetY)
            return
        }
        SwingUtilities.invokeLater {
            currentOffsetY += deltaY

            // Apply buffer to maxOffset
            val maxOffset = (frame.height - content.height + viewBuffer).coerceAtMost(0)
            currentOffsetY = currentOffsetY.coerceAtMost(0).coerceAtLeast(maxOffset)

            content.setLocation(0, currentOffsetY)

            val contentHeight = content.height
            val viewHeight = frame.height + viewBuffer
            val scrollableRatio = viewHeight.toDouble() / contentHeight
            scrollbarY = ((-currentOffsetY / contentHeight.toDouble()) * viewHeight).toInt()
            scrollbarHeight = (viewHeight * scrollableRatio).toInt().coerceAtLeast(20)
            repaint()
        }
    }

    private fun updateScrollbar() {
        SwingUtilities.invokeLater {
            showScrollbar = content.height > frame.height

            val contentHeight = content.height
            val viewHeight = frame.height + viewBuffer

            if (showScrollbar) {
                val scrollableRatio = viewHeight.toDouble() / contentHeight
                scrollbarY = ((-currentOffsetY / contentHeight.toDouble()) * viewHeight).toInt()
                scrollbarHeight = (viewHeight * scrollableRatio).toInt().coerceAtLeast(20)
            } else {
                scrollbarY = 0
                scrollbarHeight = 0
            }
            repaint()
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
    }

    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        if (showScrollbar) {
            val g2 = g as Graphics2D
            val scrollbarX = 238
            g2.color = SCROLL_BAR_COLOR
            g2.fillRect(scrollbarX, scrollbarY, 2, scrollbarHeight)
        }
    }
}
