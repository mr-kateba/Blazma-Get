package xdm.app.ui.components


import xdm.app.APP_NAME
import xdm.app.UiLocale
import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.ui.Blazma
import xdm.app.utils.logoIcon
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

class FilterListPanel(
    val categoryChanged: (DownloadCategory?) -> Unit
) :
    JPanel() {
    private val jsp: JScrollPane
    private val catFilterModel = DefaultListModel<FilterItem>()
    private val catFilterList: JList<FilterItem>

    init {
        fillCategories()

        catFilterList = stretchingList(catFilterModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            border = EmptyBorder(4, 14, 0, 14)
            isOpaque = false
            fixedCellHeight = FilterListRenderer.ROW_HEIGHT
            cellRenderer = FilterListRenderer()
            alignmentX = 0f
        }

        val sidebarBackground = Blazma.background

        val box = FilterBox().apply {
            add(brandHeader())
            add(catFilterList)
            border = EmptyBorder(0, 0, 10, 0)
            isOpaque = true
            background = sidebarBackground
        }

        jsp = JScrollPane(box).apply {
            border = MatteBorder(UiLocale.mirrored(0, 0, 0, 1), Blazma.border)
            // The rows never fill the sidebar's height, so the viewport has to carry the
            // same colour as the panel -- otherwise the strip below the last row shows the
            // scroll pane's own background instead.
            isOpaque = true
            background = sidebarBackground
            viewport.isOpaque = true
            viewport.background = sidebarBackground
        }

        catFilterList.selectedIndex = 0

        catFilterList.addListSelectionListener {
            val index = catFilterList.selectedIndex
            if (index != -1 && !it.valueIsAdjusting) {
                val category = catFilterModel[index] as FilterItem.Category
                categoryChanged(category.category)
            }
        }
    }

    /** Name of the selected category, shown as the page title. */
    val selectedTitle: String
        get() = (catFilterList.selectedValue as? FilterItem)?.text ?: ""

    /** Logo and app name at the top of the sidebar, as in Blazma Boost. */
    private fun brandHeader(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
        isOpaque = false
        border = EmptyBorder(18, 0, 16, 0)
        alignmentX = 0f
        add(JLabel(logoIcon(48)).apply { alignmentX = CENTER_ALIGNMENT })
        add(Box.createRigidArea(Dimension(0, 8)))
        add(JLabel(APP_NAME).apply {
            alignmentX = CENTER_ALIGNMENT
            foreground = Blazma.accentText
            font = font.deriveFont(Font.BOLD, font.size2D + 4f)
        })
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun fillCategories() {
        catFilterModel.clear()
        catFilterModel.addElement(
            FilterItem.Category(
                null, text("CAT_ALL_TYPES"),
                makeIcon(CategoryStyle.lineVariant(CategoryStyle.ALL_ICON), Blazma.text),
                makeIcon(CategoryStyle.lineVariant(CategoryStyle.ALL_ICON), selectedIconColor())
            )
        )
        for (cat in AppContext.config.categories) {
            // The sidebar draws line style; the download list keeps the stored filled glyph.
            val glyph = CategoryStyle.lineVariant(CategoryStyle.iconName(cat))
            catFilterModel.addElement(
                FilterItem.Category(
                    cat, cat.displayName,
                    makeIcon(glyph, Blazma.text),
                    makeIcon(glyph, selectedIconColor())
                )
            )
        }
    }

    /**
     * Rebuilds the category rows from the config after the settings window edits them.
     * Keeps the current selection when that category still exists, otherwise falls back
     * to "All types" so the list never shows a filter the user can no longer see.
     */
    fun reloadCategories() {
        val selectedId = (catFilterList.selectedValue as? FilterItem.Category)?.category?.id
        fillCategories()
        val index = (0 until catFilterModel.size())
            .firstOrNull { (catFilterModel[it] as FilterItem.Category).category?.id == selectedId }
            ?: 0
        catFilterList.selectedIndex = index
        // Notify unconditionally: when the index is unchanged the selection listener does
        // not fire, but the category behind it may have been edited, so the list still
        // needs to re-filter and repaint its icons.
        categoryChanged((catFilterModel[index] as FilterItem.Category).category)
    }

    /**
     * A list that reports an unbounded maximum width but its natural height. Inside the
     * vertical [FilterBox] a plain JList would be capped at its preferred width, leaving the
     * selection pill stopping short of the sidebar's edge; the height stays preferred so the
     * two stacked lists do not share out the leftover vertical space between them.
     */
    private fun stretchingList(model: DefaultListModel<FilterItem>): JList<FilterItem> =
        object : HoverPillList<FilterItem>(model, FilterListRenderer::pillColor) {
            override val pillInset = FilterListRenderer.GAP

            override fun paintRowBase(g: java.awt.Graphics2D, index: Int, bounds: Rectangle) {
                if (index != selectedIndex) {
                    FilterListRenderer.paintRest(g, bounds.x, bounds.y, bounds.width, bounds.height)
                }
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply { cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) }

    /**
     * Color of a selected row's glyph: the accent, matching the accent bar the renderer draws
     * and the settings window's selected nav entry.
     */
    private fun selectedIconColor(): Color = Blazma.onAccent

    private fun makeIcon(icon: RemixIcon, color: Color): Icon {
        return createIcon(icon, 18, color)
    }

    /**
     * Stretches to the scroll pane's width instead of to the lists' preferred width, so a
     * selected row's pill spans the whole sidebar (less the lists' own side padding) rather
     * than stopping at the end of the longest label.
     */
    private class FilterBox : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 24
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            visibleRect.height

        override fun getScrollableTracksViewportWidth(): Boolean = true

        /** Fill the viewport when the rows are shorter than it, but scroll when they are not. */
        override fun getScrollableTracksViewportHeight(): Boolean =
            (parent as? JViewport)?.let { it.height > preferredSize.height } ?: false
    }

    val component: Component
        get() = this.jsp
}
