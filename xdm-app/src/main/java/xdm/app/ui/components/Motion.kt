package xdm.app.ui.components

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListModel
import javax.swing.Timer
import kotlin.math.abs

/** Frame interval of the small UI animations (about 60 frames per second). */
private const val FRAME_MS = 15

/**
 * A list whose hover highlight is a rounded pill that slides to the row under the mouse and
 * fades in and out, instead of each row lighting up on its own. It is the only background
 * highlight, so two lists side by side never show two highlighted rows at once; the selected
 * row is marked by its renderer (accent bar and glyph) instead.
 */
open class HoverPillList<T>(model: ListModel<T>, private val pillColor: () -> Color) : JList<T>(model) {
    private var hoverIndex = -1
    private var pillY = 0f
    private var pillHeight = 0f
    private var alpha = 0f
    private var targetAlpha = 0f
    private val timer = Timer(FRAME_MS) { step() }

    init {
        isOpaque = false
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = hover(rowAt(e))
            override fun mouseDragged(e: MouseEvent) = hover(rowAt(e))
            override fun mouseExited(e: MouseEvent) = hover(-1)
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun rowAt(e: MouseEvent): Int {
        val index = locationToIndex(e.point)
        return if (index >= 0 && getCellBounds(index, index)?.contains(e.point) == true) index else -1
    }

    private fun hover(index: Int) {
        if (index == hoverIndex && (index >= 0) == (targetAlpha > 0f)) return
        if (index >= 0) {
            val bounds = getCellBounds(index, index) ?: return
            // Coming from nowhere, appear in place instead of sliding in from an old row.
            if (alpha < 0.05f) {
                pillY = bounds.y.toFloat()
                pillHeight = bounds.height.toFloat()
            }
            targetAlpha = 1f
        } else {
            targetAlpha = 0f
        }
        hoverIndex = index
        timer.start()
    }

    private fun step() {
        val bounds = if (hoverIndex >= 0) getCellBounds(hoverIndex, hoverIndex) else null
        if (bounds != null) {
            pillY += (bounds.y - pillY) * 0.35f
            pillHeight += (bounds.height - pillHeight) * 0.35f
        }
        alpha = if (alpha < targetAlpha) (alpha + 0.14f).coerceAtMost(targetAlpha)
        else (alpha - 0.10f).coerceAtLeast(targetAlpha)
        val settled = alpha == targetAlpha && (bounds == null || abs(bounds.y - pillY) < 0.5f)
        if (settled) {
            bounds?.let { pillY = it.y.toFloat(); pillHeight = it.height.toFloat() }
            timer.stop()
        }
        repaint()
    }

    /** Space between a row's edge and its pill, top and bottom. */
    protected open val pillInset = 2

    /** Paints a row's resting face under the pill (nothing by default). */
    protected open fun paintRowBase(g: Graphics2D, index: Int, bounds: java.awt.Rectangle) {}

    override fun paintComponent(g: Graphics) {
        run {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (i in 0 until model.size) getCellBounds(i, i)?.let { paintRowBase(g2, i, it) }
            g2.dispose()
        }
        if (alpha > 0.01f) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val c = pillColor()
            g2.color = Color(c.red, c.green, c.blue, (c.alpha * alpha).toInt().coerceIn(0, 255))
            val x = insets.left
            val w = width - insets.left - insets.right
            g2.fillRoundRect(x, pillY.toInt() + pillInset, w, pillHeight.toInt() - pillInset * 2, 12, 12)
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * A panel whose content fades in and rises a few pixels when [play] is called, so a change of
 * content (another filter, another settings page) is felt instead of snapping silently.
 */
class FadeInPanel(layout: LayoutManager) : JPanel(layout) {
    private var progress = 1f
    private var startedAt = 0L
    private val timer = Timer(FRAME_MS) {
        val t = ((System.nanoTime() - startedAt) / 1_000_000f / DURATION_MS).coerceAtMost(1f)
        progress = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
        if (t >= 1f) (it.source as Timer).stop()
        repaint()
    }

    fun play() {
        startedAt = System.nanoTime()
        progress = 0f
        timer.restart()
        repaint()
    }

    override fun paint(g: Graphics) {
        if (progress >= 1f) {
            super.paint(g)
            return
        }
        // Clear with the background first: the content is drawn shifted and translucent.
        g.color = background
        g.fillRect(0, 0, width, height)
        val g2 = g.create() as Graphics2D
        g2.composite = AlphaComposite.SrcOver.derive(progress)
        g2.translate(0, ((1f - progress) * RISE_PX).toInt())
        super.paint(g2)
        g2.dispose()
    }

    private companion object {
        const val DURATION_MS = 260f
        const val RISE_PX = 16f
    }
}

/**
 * A picture of a window's content laid over it, fading out: the window is rebuilt underneath
 * (a theme change) and the old look dissolves into the new one instead of flashing.
 */
class SnapshotFade private constructor(private val image: java.awt.image.BufferedImage) : javax.swing.JComponent() {
    private var alpha = 1f

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.composite = AlphaComposite.SrcOver.derive(alpha.coerceIn(0f, 1f))
        g2.drawImage(image, 0, 0, null)
        g2.dispose()
    }

    /** Starts the fade; the snapshot removes itself when it is gone. */
    fun play() {
        val started = System.nanoTime()
        Timer(FRAME_MS) { e ->
            val t = ((System.nanoTime() - started) / 1_000_000f / DURATION_MS).coerceAtMost(1f)
            alpha = 1f - t * t * (3 - 2 * t) // smoothstep
            repaint()
            if (t >= 1f) {
                (e.source as Timer).stop()
                parent?.let { p -> p.remove(this); p.repaint() }
            }
        }.start()
    }

    companion object {
        private const val DURATION_MS = 320f

        /** Covers [pane] with a snapshot of how it looks now. */
        fun cover(pane: javax.swing.JLayeredPane): SnapshotFade {
            val image = java.awt.image.BufferedImage(
                pane.width.coerceAtLeast(1), pane.height.coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB
            )
            image.createGraphics().also { pane.paint(it); it.dispose() }
            return SnapshotFade(image).also {
                it.setBounds(0, 0, pane.width, pane.height)
                pane.add(it, javax.swing.JLayeredPane.DRAG_LAYER as Any)
            }
        }
    }
}
