package xdm.app.ui.components

import xdm.app.ui.Blazma
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import kotlin.math.abs

private fun Graphics.smooth(): Graphics2D = (create() as Graphics2D).apply {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
}

/**
 * The main call to action ("New"), filled with the Blazma orange like the selected button of
 * Blazma Boost. Painted by hand because FlatLaf draws buttons inside a toolbar flat.
 */
class PrimaryButton(text: String, icon: Icon? = null) : JButton(text, icon) {
    private var hovered = false

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        foreground = Blazma.onAccent
        font = font.deriveFont(Font.BOLD)
        iconTextGap = 8
        border = EmptyBorder(7, 14, 7, 14)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.smooth()
        val base = Blazma.accent
        g2.color = when {
            model.isPressed -> base.darker()
            hovered -> base.brighter()
            else -> base
        }
        g2.fillRoundRect(0, 0, width, height, Blazma.RADIUS, Blazma.RADIUS)
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * A segmented control (All | Incomplete | Completed) with an orange thumb that slides to the
 * chosen segment. Each segment can show a count next to its label.
 */
class SegmentedTabs(
    private val labels: List<String>,
    private val onSelect: (Int) -> Unit,
) : JComponent() {
    var selectedIndex = 0
        private set
    private var hoverIndex = -1
    private var counts: List<Int?> = labels.map { null }
    private var thumbX = -1f
    private val timer = Timer(15) { animate() }

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = javax.swing.UIManager.getFont("Label.font").deriveFont(Font.BOLD)
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = setHover(indexAt(e.x))
            override fun mouseExited(e: MouseEvent) = setHover(-1)
            override fun mousePressed(e: MouseEvent) {
                val i = indexAt(e.x)
                if (i >= 0 && i != selectedIndex) select(i, notify = true)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun setCounts(values: List<Int?>) {
        if (values != counts) {
            counts = values
            revalidate()
            repaint()
        }
    }

    fun select(index: Int, notify: Boolean = false) {
        selectedIndex = index
        timer.start()
        if (notify) onSelect(index)
        repaint()
    }

    private fun setHover(i: Int) {
        if (i != hoverIndex) {
            hoverIndex = i
            repaint()
        }
    }

    private fun segmentText(i: Int) = counts[i]?.let { "${labels[i]}  $it" } ?: labels[i]

    private fun segmentWidths(): List<Int> {
        val fm = getFontMetrics(font)
        return labels.indices.map { fm.stringWidth(segmentText(it)) + PAD_X * 2 }
    }

    /** Segment x positions, laid out right to left in an RTL window. */
    private fun segmentXs(): List<Int> {
        val widths = segmentWidths()
        val xs = mutableListOf<Int>()
        var x = INSET
        widths.forEach { w -> xs.add(x); x += w }
        return if (componentOrientation.isLeftToRight) xs
        else xs.mapIndexed { i, sx -> width - sx - widths[i] }
    }

    private fun indexAt(x: Int): Int {
        val xs = segmentXs()
        val ws = segmentWidths()
        return labels.indices.firstOrNull { x >= xs[it] && x < xs[it] + ws[it] } ?: -1
    }

    private fun animate() {
        val target = segmentXs()[selectedIndex].toFloat()
        thumbX = if (thumbX < 0) target else thumbX + (target - thumbX) * 0.3f
        if (abs(target - thumbX) < 0.5f) {
            thumbX = target
            timer.stop()
        }
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(segmentWidths().sum() + INSET * 2, fm.height + PAD_Y * 2 + INSET * 2)
    }

    override fun getMaximumSize(): Dimension = preferredSize
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.smooth()
        val arc = Blazma.RADIUS + 2
        g2.color = Blazma.panel
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = Blazma.border
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

        val xs = segmentXs()
        val ws = segmentWidths()
        if (thumbX < 0 || !timer.isRunning && thumbX != xs[selectedIndex].toFloat()) thumbX = xs[selectedIndex].toFloat()
        g2.color = Blazma.accent
        g2.fillRoundRect(thumbX.toInt(), INSET, ws[selectedIndex], height - INSET * 2, Blazma.RADIUS, Blazma.RADIUS)

        val fm = g2.getFontMetrics(font)
        g2.font = font
        val baseline = (height - fm.height) / 2 + fm.ascent
        labels.indices.forEach { i ->
            g2.color = when {
                i == selectedIndex -> Blazma.onAccent
                i == hoverIndex -> Blazma.text
                else -> Blazma.muted
            }
            val text = segmentText(i)
            g2.drawString(text, xs[i] + (ws[i] - fm.stringWidth(text)) / 2, baseline)
        }
        g2.dispose()
    }

    private companion object {
        const val PAD_X = 14
        const val PAD_Y = 6
        const val INSET = 3
    }
}

/** Space around a component, mirrored in RTL. */
fun padding(top: Int, start: Int, bottom: Int, end: Int): Insets =
    xdm.app.UiLocale.mirrored(top, start, bottom, end)

/**
 * Bottom bar of the main window: total download speed and how many downloads are running,
 * queued and finished, like the stat strip of blazma.nt.
 */
class StatusBar : javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 18, 0)) {
    private val speed = javax.swing.JLabel()
    private val active = javax.swing.JLabel()
    private val queued = javax.swing.JLabel()
    private val done = javax.swing.JLabel()

    init {
        background = Blazma.background
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.border.MatteBorder(1, 0, 0, 0, Blazma.border),
            EmptyBorder(7, 4, 7, 4)
        )
        speed.font = speed.font.deriveFont(Font.BOLD)
        speed.foreground = Blazma.accentText
        speed.icon = xdm.app.utils.createIcon(xdm.app.utils.RemixIcon.SPEED_LINE, 16, Blazma.accentText)
        speed.iconTextGap = 6
        listOf(active, queued, done).forEach { it.foreground = Blazma.muted }
        add(speed)
        add(active)
        add(queued)
        add(done)
    }

    fun update(records: List<xdm.app.DbRecord>) {
        val running = records.filter {
            it.status == xdm.app.RecordStatus.DOWNLOADING || it.status == xdm.app.RecordStatus.ASSEMBLING ||
                it.status == xdm.app.RecordStatus.PUBLISHING
        }
        val total = running.filter { it.status == xdm.app.RecordStatus.DOWNLOADING }.sumOf { it.speed.toDouble() }
        speed.text = if (running.isEmpty()) xdm.app.I8N.text("SB_IDLE")
        else xdm.app.UiLocale.ltr(xdm.core.util.FormatHelper.formatSize(total) + "/s")
        active.text = "● " + xdm.app.I8N.text("SB_ACTIVE").format(running.size)
        queued.text = "● " + xdm.app.I8N.text("SB_QUEUED").format(records.count { it.status == xdm.app.RecordStatus.READY })
        done.text = "● " + xdm.app.I8N.text("SB_DONE").format(records.count { it.status == xdm.app.RecordStatus.FINISHED })
        active.foreground = if (running.isEmpty()) Blazma.muted else Blazma.success
    }
}
