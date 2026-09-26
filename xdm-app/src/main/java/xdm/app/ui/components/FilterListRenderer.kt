package xdm.app.ui.components

import xdm.app.ui.Blazma
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.border.EmptyBorder

/**
 * Sidebar entries drawn like Blazma Boost's navigation: outlined rounded buttons, the selected
 * one filled with the Blazma orange. The hover fill is the sliding pill of [HoverPillList].
 */
class FilterListRenderer : ListCellRenderer<FilterItem> {
    companion object {
        /** Hover fill behind an entry (drawn by [HoverPillList]). */
        fun pillColor(): Color = Blazma.panelHover

        const val ROW_HEIGHT = 46
        private const val GAP = 3
        private const val ARC = 12
    }

    private val label = NavLabel().apply {
        iconTextGap = 10
        border = EmptyBorder(0, 16, 0, 16)
        font = font.deriveFont(Font.BOLD, font.size2D + 0.5f)
    }

    override fun getListCellRendererComponent(
        list: JList<out FilterItem>,
        value: FilterItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        label.apply {
            text = value.text
            selected = isSelected
            foreground = if (isSelected) Blazma.onAccent else Blazma.text
            icon = if (isSelected) value.selectedIcon else value.icon
            componentOrientation = list.componentOrientation
            horizontalAlignment = javax.swing.SwingConstants.LEADING
        }
        return label
    }

    private class NavLabel : JLabel() {
        var selected = false

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (selected) {
                g2.color = Blazma.accent
                g2.fillRoundRect(0, GAP, width - 1, height - GAP * 2, ARC, ARC)
            } else {
                g2.color = Blazma.borderStrong
                g2.drawRoundRect(0, GAP, width - 1, height - GAP * 2 - 1, ARC, ARC)
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
