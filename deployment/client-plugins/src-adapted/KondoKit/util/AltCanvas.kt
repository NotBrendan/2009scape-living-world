package KondoKit.util

import KondoKit.plugin.Companion.FIXED_HEIGHT
import KondoKit.plugin.Companion.FIXED_WIDTH
import plugin.api.API.IsHD
import rt4.GameShell.canvas
import rt4.GlRenderer
import rt4.SoftwareRaster
import java.awt.*
import java.awt.event.*
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.awt.image.VolatileImage

class AltCanvas : Canvas() {
    private var gameImage: VolatileImage? = null
    private var op: AffineTransformOp? = null
    private var transform: AffineTransform? = null

    private var flippedImage: BufferedImage? = null
    private var bufferImage = BufferedImage(FIXED_WIDTH, FIXED_HEIGHT, BufferedImage.TYPE_INT_BGR)

    private var lastImageWidth = -1
    private var lastImageHeight = -1

    init {
        isFocusable = true
        requestFocusInWindow()

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = relayMouseEvent(e)
            override fun mouseReleased(e: MouseEvent) = relayMouseEvent(e)
            override fun mouseClicked(e: MouseEvent) = relayMouseEvent(e)
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) = relayMouseEvent(e)
            override fun mouseDragged(e: MouseEvent) = relayMouseEvent(e)
        })

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) = relayKeyEvent { it.keyPressed(e) }
            override fun keyReleased(e: KeyEvent) = relayKeyEvent { it.keyReleased(e) }
            override fun keyTyped(e: KeyEvent) = relayKeyEvent { it.keyTyped(e) }
        })

        addMouseWheelListener(MouseWheelListener { e -> relayMouseWheelEvent(e) })
    }

    override fun update(g: Graphics) = paint(g)

    override fun addNotify() {
        super.addNotify()
        createBufferStrategy(2) // Double-buffering
        validateGameImage()
    }

    private fun validateGameImage() {
        val gc = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        gameImage?.let {
            when (it.validate(gc)) {
                VolatileImage.IMAGE_INCOMPATIBLE -> createGameImage(gc)
                VolatileImage.IMAGE_RESTORED -> renderGameImage()
            }
        } ?: createGameImage(gc)
    }

    private fun createGameImage(gc: GraphicsConfiguration) {
        gameImage = gc.createCompatibleVolatileImage(FIXED_WIDTH, FIXED_HEIGHT, Transparency.OPAQUE)
        renderGameImage()
    }

    private fun renderGameImage() {
        gameImage?.createGraphics()?.apply {
            color = Color.DARK_GRAY
            fillRect(0, 0, gameImage!!.width, gameImage!!.height)
            color = Color.BLACK
            drawString("KondoKit Scaled Fixed Canvas", 20, 20)
            dispose()
        }
    }


    override fun paint(g: Graphics) {
        bufferStrategy?.let { strategy ->
            val g2d = strategy.drawGraphics as? Graphics2D ?: return

            g2d.color = Color.BLACK
            g2d.fillRect(0, 0, width, height)

            gameImage?.let { image ->
                val scale = minOf(width.toDouble() / image.width, height.toDouble() / image.height)
                val x = ((width - image.width * scale) / 2).toInt()
                val y = ((height - image.height * scale) / 2).toInt()
                g2d.drawImage(image, x, y, (image.width * scale).toInt(), (image.height * scale).toInt(), null)
            }

            g2d.dispose() // Release the graphics context
            strategy.show() // Display the buffer
        }
    }

    private fun relayMouseEvent(e: MouseEvent) {
        requestFocusInWindow()
        val scale = minOf(width.toDouble() / gameImage!!.width, height.toDouble() / gameImage!!.height)
        val xOffset = ((width - gameImage!!.width * scale) / 2)
        val yOffset = ((height - gameImage!!.height * scale) / 2)

        val adjustedX = ((e.x - xOffset) / scale).toInt().coerceIn(0, gameImage!!.width - 1)
        val adjustedY = ((e.y - yOffset) / scale).toInt().coerceIn(0, gameImage!!.height - 1)

        canvas.dispatchEvent(MouseEvent(this, e.id, e.`when`, e.modifiersEx, adjustedX, adjustedY, e.clickCount, e.isPopupTrigger, e.button))
    }

    private fun relayKeyEvent(action: (KeyListener) -> Unit) {
        for (listener in canvas.keyListeners) action(listener)
    }

    private fun relayMouseWheelEvent(e: MouseWheelEvent) {
        val scale = minOf(width.toDouble() / gameImage!!.width, height.toDouble() / gameImage!!.height)
        val xOffset = ((width - gameImage!!.width * scale) / 2)
        val yOffset = ((height - gameImage!!.height * scale) / 2)

        val adjustedX = ((e.x - xOffset) / scale).toInt().coerceIn(0, gameImage!!.width - 1)
        val adjustedY = ((e.y - yOffset) / scale).toInt().coerceIn(0, gameImage!!.height - 1)

        canvas.dispatchEvent(MouseWheelEvent(this, e.id, e.`when`, e.modifiersEx, adjustedX, adjustedY, e.clickCount, e.isPopupTrigger, e.scrollType, e.scrollAmount, e.wheelRotation))
    }

    fun updateGameImage() {
        if (IsHD()) renderGlRaster() else renderSoftwareRaster()
        repaint()
    }

    private fun renderGlRaster() {
        val width = gameImage!!.width
        val height = gameImage!!.height

        bufferImage.setRGB(0, 0, width, height, GlRenderer.pixelData, 0, width)

        if (width != lastImageWidth || height != lastImageHeight) {
            transform = AffineTransform.getScaleInstance(1.0, -1.0).apply {
                translate(0.0, -height.toDouble())
            }
            op = AffineTransformOp(transform, AffineTransformOp.TYPE_NEAREST_NEIGHBOR)
            flippedImage = BufferedImage(width, height, bufferImage.type)
            lastImageWidth = width
            lastImageHeight = height
        }

        op!!.filter(bufferImage, flippedImage)

        gameImage?.createGraphics()?.apply {
            drawImage(flippedImage, 0, 0, null)
            dispose()
        }
    }

    private fun renderSoftwareRaster() {
        gameImage?.createGraphics()?.apply {
            SoftwareRaster.frameBuffer.draw(this)
            dispose()
        }
    }
}
