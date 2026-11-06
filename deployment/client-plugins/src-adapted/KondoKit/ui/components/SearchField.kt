package KondoKit.ui.components

import KondoKit.util.ImageCanvas
import KondoKit.ui.ViewConstants
import KondoKit.util.SpriteToBufferedImage
import KondoKit.util.setFixedSize
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.plugin.Companion.secondaryColor
import KondoKit.plugin.StateManager.focusedView
import plugin.api.API
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import javax.swing.*

class SearchField(
    private val parentPanel: JPanel, 
    private val onSearch: (String) -> Unit,
    private val placeholderText: String = "Search...",
    private val fieldWidth: Int = ViewConstants.SEARCH_FIELD_SIZE.width,
    private val fieldHeight: Int = ViewConstants.SEARCH_FIELD_SIZE.height,
    private val viewName: String? = ""
) : Canvas() {
    
    private var cursorVisible: Boolean = true
    private var text: String = ""
    
    private val bufferedImageSprite = SpriteToBufferedImage.getBufferedImageFromSprite(API.GetSprite(1423)) // MAG_SPRITE
    private val imageCanvas = bufferedImageSprite.let {
        ImageCanvas(it).apply {
            setFixedSize(ViewConstants.DIMENSION_SMALL_ICON)
            size = preferredSize
            fillColor = WIDGET_COLOR
        }
    }
    
    init {
        val dimension = Dimension(fieldWidth, fieldHeight)
        setFixedSize(dimension)
        background = WIDGET_COLOR
        foreground = secondaryColor
        font = ViewConstants.FONT_ARIAL_PLAIN_14

        isFocusable = true
        focusTraversalKeysEnabled = false

        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                cursorVisible = true
                repaintAsync()
            }

            override fun focusLost(e: FocusEvent) {
                cursorVisible = false
                repaintAsync()
            }
        })
        
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                // Prevent null character from being typed on Ctrl+A & Ctrl+V
                if (e.isControlDown && (e.keyChar == '\u0001' || e.keyChar == '\u0016')) {
                    e.consume()
                    return
                }
                if (e.keyChar == '\b') {
                    if (text.isNotEmpty()) {
                        text = text.dropLast(1)
                    }
                } else if (e.keyChar == '\n') {
                    // Handled in keyPressed to avoid duplicate searches
                } else {
                    text += e.keyChar
                }
                repaintAsync()
            }
            
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    triggerSearch()
                    e.consume()
                    return
                }

                if (e.isControlDown) {
                    when (e.keyCode) {
                        KeyEvent.VK_A -> {
                            // They probably want to clear the search box
                            text = ""
                            repaintAsync()
                        }
                        KeyEvent.VK_V -> {
                            try {
                                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                val pasteText = clipboard.getData(DataFlavor.stringFlavor) as String
                                text += pasteText
                                repaintAsync()
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
        })
        
        addMouseListener(object : MouseAdapter() {
            // Clicked the search field 'x' button
            override fun mouseClicked(e: MouseEvent) {
                requestFocusInWindow()
                if (e.x > width - 20 && e.y < 20) {
                    text = ""
                    triggerSearch()
                    repaintAsync()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
            }
        })
        
        Timer(500) { _ ->
            cursorVisible = !cursorVisible
            // Only repaint if the view is active or if viewName is not specified
            if (viewName == null || focusedView == viewName) {
                repaintAsync()
            }
        }.start()
    }
    
    override fun paint(g: Graphics) {
        super.paint(g)
        g.color = foreground
        g.font = font
        
        val fm = g.fontMetrics
        val cursorX = fm.stringWidth(text) + 30
        
        // Draw magnifying glass icon
        imageCanvas.let { canvas ->
            val imgG = g.create(5, 5, canvas.width, canvas.height)
            canvas.paint(imgG)
            imgG.dispose()
        }
        
        // Use a local copy of the text to avoid threading issues
        val currentText = text
        
        // Draw placeholder text if field is empty, otherwise draw actual text
        if (currentText.isEmpty()) {
            g.color = Color.GRAY // lighter color for placeholder
            g.drawString(placeholderText, 30, 20)
        } else {
            g.color = foreground // normal color for user entered text
            g.drawString(currentText, 30, 20)
        }
        
        if (cursorVisible && hasFocus()) {
            g.color = foreground
            g.drawLine(cursorX, 5, cursorX, 25)
        }
        
        // Only draw the "x" button if there's text
        if (currentText.isNotEmpty()) {
            g.color = Color.RED
            g.drawString("x", width - 20, 20)
        }
    }
    
    fun setText(newText: String) {
        text = newText
        repaintAsync()
    }

    fun getText(): String = text

    private fun triggerSearch() {
        val query = text.trim()
        if (query.isNotEmpty()) {
            text = query
            repaintAsync()
            onSearch(query)
        } else {
            onSearch(query) // Call with empty string for clearing filters
        }
    }

    private fun repaintAsync() {
        SwingUtilities.invokeLater { repaint() }
    }
}
