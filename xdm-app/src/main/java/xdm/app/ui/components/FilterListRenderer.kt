package xdm.app.ui.components

import xdm.app.ui.Blazma
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Sidebar entries drawn like Blazma Boost's navigation: dark keys with a light outline and the
 * label centered, the selected one solid orange with light text. The resting fill and the hover
 * fill are painted by [HoverPillList] underneath, so the hover can slide between rows.
 */
class FilterListRenderer : ListCellRenderer<FilterItem> {
    companion object {
        /** Hover fill behind an entry (drawn by [HoverPillList]). */
        fun pillColor(): Color = Blazma.buttonHover

        /** A 40 px key plus Blazma Boost's 6 px gap below it. */
        const val ROW_HEIGHT = 46
        const val GAP = 3

        /** The resting face of an unselected entry, painted under the hover pill. */
        fun paintRest(g: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
            g.color = Blazma.panel
            g.fillRoundRect(x, y + GAP, w, h - GAP * 2, Blazma.BUTTON_ARC, Blazma.BUTTON_ARC)
        }
    }

    private val label = NavLabel().apply {
        iconTextGap = 10
        border = EmptyBorder(0, 12, 0, 12)
        font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        horizontalAlignment = SwingConstants.CENTER
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
        }
        return label
    }

    private class NavLabel : JLabel() {
        var selected = false

        override fun paintComponent(g: Graphics) {
            if (selected) {
                Blazma.paintButton(g, 0, GAP, width, height - GAP * 2, selected = true)
            } else {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Blazma.outline
                g2.stroke = BasicStroke(1.2f)
                g2.drawRoundRect(0, GAP, width - 1, height - GAP * 2 - 1, Blazma.BUTTON_ARC, Blazma.BUTTON_ARC)
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
