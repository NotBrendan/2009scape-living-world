package KondoKit.views

import KondoKit.ui.ViewConstants
import KondoKit.ui.components.SearchField
import KondoKit.ui.components.WidgetPanel
import KondoKit.plugin.Companion.VIEW_BACKGROUND_COLOR
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

object ViewLayoutHelpers {

    data class SearchFieldSection(
        val wrapper: JPanel,
        val searchField: SearchField
    )

    /**
     * Creates a standard search field row with consistent sizing and styling.
     */
    fun createSearchFieldSection(
        parent: JPanel,
        placeholderText: String,
        viewName: String,
        initialText: String = "",
        fieldWidth: Int = ViewConstants.SEARCH_FIELD_SIZE.width,
        fieldHeight: Int = ViewConstants.SEARCH_FIELD_SIZE.height,
        onSearch: (String) -> Unit
    ): SearchFieldSection {
        val searchField = SearchField(
            parentPanel = parent,
            onSearch = onSearch,
            placeholderText = placeholderText,
            fieldWidth = fieldWidth,
            fieldHeight = fieldHeight,
            viewName = viewName
        )

        if (initialText.isNotBlank()) {
            searchField.setText(initialText)
        }

        val preferredSize = Dimension(fieldWidth, fieldHeight)
        val wrapper = WidgetPanel(
            widgetWidth = preferredSize.width,
            widgetHeight = preferredSize.height,
            addDefaultPadding = false
        ).apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = VIEW_BACKGROUND_COLOR
            alignmentX = Component.CENTER_ALIGNMENT
            add(searchField)
        }

        return SearchFieldSection(wrapper = wrapper, searchField = searchField)
    }

    fun createVerticalPanel(background: Color = VIEW_BACKGROUND_COLOR): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            this.background = background
        }
    }
}

fun Container.addVerticalSpacing(pixels: Int) {
    add(Box.createVerticalStrut(pixels))
}
