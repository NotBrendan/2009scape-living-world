package KondoKit.ui.components

import KondoKit.util.Helpers
import KondoKit.util.Helpers.FieldNotifier
import KondoKit.ui.ViewConstants
import KondoKit.ui.components.UiStyler
import KondoKit.util.setFixedSize
import KondoKit.plugin.Companion.WIDGET_COLOR
import KondoKit.plugin.Companion.primaryColor
import KondoKit.plugin.Companion.secondaryColor
import plugin.Plugin
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.lang.reflect.Field
import java.util.*
import java.util.Timer
import javax.swing.*
import javax.swing.table.DefaultTableModel

class SettingsPanel(private val plugin: Plugin) : JPanel() {
    
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = WIDGET_COLOR
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
    }
    
    fun addSettingsFromPlugin() {
        val fieldNotifier = FieldNotifier(plugin)
        val exposedFields = plugin.javaClass.declaredFields.filter { field ->
            field.annotations.any { annotation ->
                annotation.annotationClass.simpleName == "Exposed"
            }
        }

        if (exposedFields.isNotEmpty()) {
            for (field in exposedFields) {
                field.isAccessible = true
                
                // Get the "Exposed" annotation specifically and retrieve its description, if available
                val description = field.exposedDescription()
                
                val fieldPanel = JPanel().apply {
                    layout = GridBagLayout()
                    background = WIDGET_COLOR
                    foreground = secondaryColor
                    border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
                    maximumSize = Dimension(Int.MAX_VALUE, 50)
                }
                
                val gbc = GridBagConstraints().apply {
                    insets = Insets(0, 5, 0, 5)
                }
                
                val label = JLabel(field.name.capitalize()).apply {
                    foreground = secondaryColor
                    font = ViewConstants.FONT_RUNESCAPE_SMALL_16
                }
                gbc.gridx = 0
                gbc.gridy = 0
                gbc.weightx = 0.0
                gbc.anchor = GridBagConstraints.WEST
                fieldPanel.add(label, gbc)
                
                // Create appropriate input component based on field type
                val inputComponent: JComponent
                val isHashMapEditor: Boolean
                when {
                    field.type == Boolean::class.javaPrimitiveType || field.type == java.lang.Boolean::class.java -> {
                        inputComponent = JCheckBox().apply {
                            isSelected = field.get(plugin) as Boolean
                        }
                        isHashMapEditor = false
                    }
                    
                    field.type.isEnum -> {
                        val enumConstants = field.type.enumConstants
                        inputComponent = JComboBox(enumConstants as Array<out Enum<*>>).apply {
                            selectedItem = field.get(plugin)
                        }
                        isHashMapEditor = false
                    }
                    
                    field.type == Int::class.javaPrimitiveType || field.type == Integer::class.java -> {
                        inputComponent = JSpinner(SpinnerNumberModel(field.get(plugin) as Int, Int.MIN_VALUE, Int.MAX_VALUE, 1))
                        isHashMapEditor = false
                    }
                    
                    field.type == Float::class.javaPrimitiveType || 
                    field.type == Double::class.javaPrimitiveType || 
                    field.type == java.lang.Float::class.java || 
                    field.type == java.lang.Double::class.java -> {
                        inputComponent = JSpinner(SpinnerNumberModel((field.get(plugin) as Number).toDouble(), -Double.MAX_VALUE, Double.MAX_VALUE, 0.1))
                        isHashMapEditor = false
                    }
                    
                    // Check if the field is a HashMap
                    field.isHashMapType() -> {
                        inputComponent = createHashMapEditor(field, plugin, fieldNotifier)
                        isHashMapEditor = true
                    }
                    
                    else -> {
                        inputComponent = JTextField(field.get(plugin)?.toString() ?: "")
                        isHashMapEditor = false
                    }
                }
                
                // Handle HashMap editors differently - they don't use the fieldPanel layout
                if (isHashMapEditor) {
                    // Add the HashMap editor directly to the settings panel
                    add(inputComponent)
                } else {
                    // Add mouse listener to the label only if a description is available
                    if (description.isNotBlank()) {
                        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        label.addMouseListener(object : MouseAdapter() {
                            override fun mouseEntered(e: MouseEvent) {
                                showCustomToolTip(description, label)
                            }
                            
                            override fun mouseExited(e: MouseEvent) {
                                customToolTipWindow?.isVisible = false
                            }
                        })
                    }
                    
                    gbc.gridx = 1
                    gbc.gridy = 0
                    gbc.weightx = 1.0
                    gbc.fill = GridBagConstraints.HORIZONTAL
                    fieldPanel.add(inputComponent, gbc)
                    
                    // Add apply button for non-HashMap editors
                    val applyButton = UiStyler.button(
                        text = "=>",
                        onClick = {
                            try {
                                val newValue = when (inputComponent) {
                                    is JCheckBox -> inputComponent.isSelected
                                    is JComboBox<*> -> inputComponent.selectedItem
                                    is JSpinner -> inputComponent.value
                                    is JTextField -> Helpers.convertValue(field.type, field.genericType, inputComponent.text)
                                    else -> throw IllegalArgumentException("Unsupported input component type")
                                }
                                fieldNotifier.setFieldValue(field, newValue)
                                Helpers.showToast(
                                    this@SettingsPanel,
                                    "${field.name} updated successfully!"
                                )
                            } catch (e: Exception) {
                                Helpers.showToast(
                                    this@SettingsPanel,
                                    "Failed to update ${field.name}: ${e.message}",
                                    JOptionPane.ERROR_MESSAGE
                                )
                            }
                        }
                    ).also {
                        it.maximumSize = Dimension(Int.MAX_VALUE, 8)
                    }

                    gbc.gridx = 2
                    gbc.gridy = 0
                    gbc.weightx = 0.0
                    gbc.fill = GridBagConstraints.NONE

                    fieldPanel.add(applyButton, gbc)
                    add(fieldPanel)
                }
                
                // Track field changes in real-time and update UI (skip HashMap fields)
                if (!field.isHashMapType()) {
                    var previousValue = field.get(plugin)?.toString()
                    val timer = Timer()
                    timer.schedule(object : TimerTask() {
                        override fun run() {
                            val currentValue = field.get(plugin)?.toString()
                            if (currentValue != previousValue) {
                                previousValue = currentValue
                                SwingUtilities.invokeLater {
                                    // Update the inputComponent based on the new value
                                    when (inputComponent) {
                                        is JCheckBox -> inputComponent.isSelected = field.get(plugin) as Boolean
                                        is JComboBox<*> -> inputComponent.selectedItem = field.get(plugin)
                                        is JSpinner -> inputComponent.value = field.get(plugin)
                                        is JTextField -> inputComponent.text = field.get(plugin)?.toString() ?: ""
                                    }
                                }
                            }
                        }
                    }, 0, 1000) // Poll every 1000 milliseconds (1 second)
                }
            }
            
            if (exposedFields.isNotEmpty()) {
                add(Box.createVerticalStrut(5))
            }
        }
    }
    
    private fun createHashMapEditor(field: Field, plugin: Plugin, fieldNotifier: FieldNotifier): JComponent {
        // Create a panel to hold the table and buttons
        val editorPanel = JPanel()
        editorPanel.layout = BoxLayout(editorPanel, BoxLayout.Y_AXIS)
        editorPanel.background = WIDGET_COLOR
        editorPanel.border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
        editorPanel.maximumSize = Dimension(Int.MAX_VALUE, 250)
        
        val description = field.exposedDescription()
        
        // Create title label
        val titleLabel = JLabel("${field.name} (Key-Value Pairs)").apply {
            foreground = secondaryColor
            font = ViewConstants.FONT_RUNESCAPE_SMALL_16
            alignmentX = Component.LEFT_ALIGNMENT
            
            // Add mouse listener to the label only if a description is available
            if (description.isNotBlank()) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        showCustomToolTip(description, this@apply)
                    }
                    
                    override fun mouseExited(e: MouseEvent) {
                        SettingsPanel.customToolTipWindow?.isVisible = false
                    }
                })
            }
        }
        
        // Get the current HashMap value
        val hashMap = field.get(plugin) as? HashMap<*, *> ?: HashMap<Any, Any>()
        
        // Create a table model for the HashMap
        val tableModel = object : DefaultTableModel() {
            init {
                val columnVector = java.util.Vector<String>()
                columnVector.add("Key")
                columnVector.add("Value")
                columnIdentifiers = columnVector
                hashMap.forEach { (key, value) ->
                    val vector = java.util.Vector<Any>()
                    vector.add(key.toString())
                    vector.add(value.toString())
                    addRow(vector)
                }
            }
            
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return true
            }
            
            override fun getColumnClass(columnIndex: Int): Class<*> {
                return String::class.java
            }
        }
        
        // Create the table
        val table = JTable(tableModel).apply {
            background = WIDGET_COLOR
            foreground = secondaryColor
            gridColor = primaryColor
            tableHeader.background = WIDGET_COLOR
            tableHeader.foreground = secondaryColor
            preferredScrollableViewportSize = Dimension(Int.MAX_VALUE, 150)
            setFillsViewportHeight(true)
        }
        
        // Create a scroll pane for the table with fixed size
        val scrollPane = JScrollPane(table).apply {
            setFixedSize(Dimension(Int.MAX_VALUE, 150))
            background = WIDGET_COLOR
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        // Create buttons panel
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            background = WIDGET_COLOR
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        // Add button
        val addButton = UiStyler.button(
            text = "+",
            onClick = {
                val vector = java.util.Vector<Any>()
                vector.add("")
                vector.add("")
                tableModel.addRow(vector)
                // Select the new row for editing
                val newRow = tableModel.rowCount - 1
                table.setRowSelectionInterval(newRow, newRow)
                table.editCellAt(newRow, 0)
                table.editorComponent?.requestFocus()
            }
        ).apply {
            setFixedSize(40, 30)
        }

        // Remove button
        val removeButton = UiStyler.button(
            text = "-",
            onClick = {
                val selectedRow = table.selectedRow
                if (selectedRow >= 0) {
                    tableModel.removeRow(selectedRow)
                } else {
                    Helpers.showToast(
                        this@SettingsPanel,
                        "Please select a row to remove",
                        JOptionPane.WARNING_MESSAGE
                    )
                }
            }
        ).apply {
            setFixedSize(40, 30)
        }

        // Apply button
        val applyButton = UiStyler.button(
            text = "Apply Changes",
            onClick = {
                try {
                    // Commit any active cell editing before reading values
                    if (table.isEditing) {
                        table.cellEditor.stopCellEditing()
                    }

                    // Get the current HashMap from the field and modify it in place
                    val currentHashMap = field.get(plugin) as? HashMap<Any, Any> ?: HashMap<Any, Any>()

                    // Clear the current HashMap
                    currentHashMap.clear()

                    // Add the new entries from the table to the existing HashMap
                    for (i in 0 until tableModel.rowCount) {
                        val key = tableModel.getValueAt(i, 0).toString()
                        val value = tableModel.getValueAt(i, 1).toString()

                        if (key.isBlank()) {
                            continue
                        }

                        if (value.isBlank()) {
                            Helpers.showToast(
                                this@SettingsPanel,
                                "Skipping entry with empty value for key '$key'",
                                JOptionPane.WARNING_MESSAGE
                            )
                            continue
                        }

                        val convertedValue = try {
                            val fieldTypeName = field.genericType.toString()
                            when {
                                fieldTypeName.contains("java.util.HashMap<java.lang.String, java.lang.Integer>") ||
                                        fieldTypeName.contains("HashMap<String, Int>") ||
                                        fieldTypeName.contains("HashMap<String, Integer>") -> {
                                    try {
                                        value.toInt()
                                    } catch (numberFormat: NumberFormatException) {
                                        Helpers.showToast(
                                            this@SettingsPanel,
                                            "Invalid number format for key '$key': '$value'. Using 0 as default.",
                                            JOptionPane.WARNING_MESSAGE
                                        )
                                        0
                                    }
                                }
                                fieldTypeName.contains("java.lang.Integer") || fieldTypeName.contains("int") -> value.toInt()
                                fieldTypeName.contains("java.lang.Double") || fieldTypeName.contains("double") -> value.toDouble()
                                fieldTypeName.contains("java.lang.Float") || fieldTypeName.contains("float") -> value.toFloat()
                                fieldTypeName.contains("java.lang.Boolean") || fieldTypeName.contains("boolean") -> value.toBoolean()
                                else -> value
                            }
                        } catch (e: Exception) {
                            value
                        }

                        currentHashMap[key] = convertedValue
                    }

                    fieldNotifier.setFieldValue(field, currentHashMap)
                    Helpers.showToast(
                        this@SettingsPanel,
                        "${field.name} updated successfully!"
                    )
                } catch (e: Exception) {
                    Helpers.showToast(
                        this@SettingsPanel,
                        "Failed to update ${field.name}: ${e.message}",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        )
        
        buttonsPanel.add(addButton)
        buttonsPanel.add(removeButton)
        buttonsPanel.add(Box.createHorizontalStrut(20))
        buttonsPanel.add(applyButton)
        
        // Add components to the editor panel in vertical stack
        editorPanel.add(titleLabel)
        editorPanel.add(Box.createVerticalStrut(5))
        editorPanel.add(scrollPane)
        editorPanel.add(Box.createVerticalStrut(5))
        editorPanel.add(buttonsPanel)
        
        return editorPanel
    }

    private fun Field.exposedDescription(): String {
        val exposedAnnotation = annotations.firstOrNull {
            it.annotationClass.simpleName == "Exposed"
        } ?: return ""

        return try {
            val descriptionMethod = exposedAnnotation.annotationClass.java.getMethod("description")
            descriptionMethod.invoke(exposedAnnotation) as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun Field.isHashMapType(): Boolean {
        return type == HashMap::class.java || type.simpleName == "HashMap"
    }

    companion object {
        var customToolTipWindow: JWindow? = null
        
        private var customToolTipLabel: JLabel? = null

        fun showCustomToolTip(text: String, component: JComponent) {
            val tooltipFont = ViewConstants.FONT_RUNESCAPE_SMALL_PLAIN_16
            val maxWidth = 150

            val bgColor = Helpers.colorToHex(KondoKit.plugin.Companion.TOOLTIP_BACKGROUND)
            val textColor = Helpers.colorToHex(KondoKit.plugin.Companion.secondaryColor)
            // Constrain width via HTML so Swing's renderer wraps the text naturally.
            val safeText = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val html = """
                <html>
                  <body style="margin:0; padding:6px; width:${maxWidth}px; color:$textColor; background-color:$bgColor; word-wrap:break-word;">
                    $safeText
                  </body>
                </html>
            """.trimIndent()

            val (window, label) = if (customToolTipWindow == null || customToolTipLabel == null) {
                val lbl = JLabel().apply {
                    border = BorderFactory.createLineBorder(Color.BLACK)
                    isOpaque = true
                    background = KondoKit.plugin.Companion.TOOLTIP_BACKGROUND
                    foreground = Color.WHITE
                    font = tooltipFont
                    horizontalAlignment = SwingConstants.LEFT
                    verticalAlignment = SwingConstants.TOP
                }
                val win = JWindow().apply { contentPane.add(lbl) }
                customToolTipWindow = win
                customToolTipLabel = lbl
                win to lbl
            } else {
                customToolTipWindow!! to customToolTipLabel!!
            }

            label.text = html
            label.font = tooltipFont
            label.preferredSize = null // let HTML + pack decide size from width constraint
            window.pack()

            // Position the tooltip near the component
            val locationOnScreen = component.locationOnScreen
            customToolTipWindow!!.setLocation(locationOnScreen.x, locationOnScreen.y + 15)
            customToolTipWindow!!.isVisible = true
        }
    }
}
