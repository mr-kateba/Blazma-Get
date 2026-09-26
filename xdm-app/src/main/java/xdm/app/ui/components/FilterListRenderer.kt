package xdm.app.ui.components


import xdm.app.UiLocale
import com.formdev.flatlaf.FlatLaf
import xdm.app.ui.screens.settings.settingsAccentColor
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

class FilterListRenderer : ListCellRenderer<FilterItem> {
    companion object {
        /**
         * Emphasized variant of a normal foreground color, used for the selected
         * row's icon and text: lighter in the dark theme, darker in the light one,
         * so the selected row stands out against its neighbours.
         */
        fun emphasize(c: Color): Color =
            if (FlatLaf.isLafDark()) blend(c, Color.WHITE, 0.95f)
            else blend(c, Color.BLACK, 0.45f)

        /**
         * The hover pill. The sidebar is `Table.background`, so in the dark theme it is a
         * translucent button fill; in the light theme a neutral shift is too faint against the
         * pale sidebar, so it is the accent tint the settings nav rail uses.
         */
        fun pillColor(): Color =
            if (FlatLaf.isLafDark()) {
                val c1 = UIManager.getColor("Button.background")
                Color(c1.red, c1.green, c1.blue, 75)
            } else {
                val accent = settingsAccentColor()
                Color(accent.red, accent.green, accent.blue, 46)
            }

        private fun blend(c: Color, towards: Color, amount: Float): Color = Color(
            (c.red + (towards.red - c.red) * amount).toInt(),
            (c.green + (towards.green - c.green) * amount).toInt(),
            (c.blue + (towards.blue - c.blue) * amount).toInt()
        )
    }

    private val label = PillLabel().apply {
        icon = createIcon(RemixIcon.ARROW_UP_DOWN_FILL, 20, Color.GRAY)
        iconTextGap = 10
        // The same border in both states, so selecting a row never shifts its text.
        border = EmptyBorder(UiLocale.mirrored(8, 10, 8, 15))
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
            // The selection pill is a soft accent tint, not a solid fill, so the text
            // brightens/darkens from the normal foreground rather than switching to
            // selectionForeground (unreadable on the tint in the light theme).
            foreground = if (isSelected) emphasize(list.foreground) else list.foreground
            icon = if (isSelected) value.selectedIcon else value.icon
        }
        return label
    }

    /**
     * Marks the selected row with an accent bar on its leading edge. The translucent pill is
     * drawn by [HoverPillList] under the mouse only, so the sidebar never shows two rows
     * highlighted at once (one per list) and the highlight follows the pointer smoothly.
     */
    private class PillLabel : JLabel() {
        var selected = false

        override fun paintComponent(g: Graphics) {
            if (selected) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = settingsAccentColor()
                val barH = height - 18
                val x = if (componentOrientation.isLeftToRight) 1 else width - 4
                g2.fillRoundRect(x, (height - barH) / 2, 3, barH, 3, 3)
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
