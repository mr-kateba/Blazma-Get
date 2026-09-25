package xdm.app

import xdm.core.util.Logger
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.ComponentOrientation
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ContainerEvent
import java.awt.event.WindowEvent
import java.util.Locale
import javax.swing.JComponent

/**
 * Language-dependent look of the UI: picks the first-run language, the text direction and the
 * bundled UI font.
 *
 * Right-to-left is applied globally instead of per screen: every window gets the RTL orientation
 * when it opens, and every component added later to an RTL container inherits it. Layouts that
 * use relative constraints (LINE_START, LEADING, GridBag, FlowLayout.LEADING) then mirror on
 * their own.
 */
object UiLocale {
    /** Family name of the bundled font (IBM Plex Sans Arabic, SIL OFL), which also has Latin glyphs. */
    const val FONT_FAMILY = "IBM Plex Sans Arabic"

    private val rtlLanguages = setOf("ar", "fa", "he", "ur")

    /** Languages the bundled font covers; others keep FlatLaf's system font so no glyph is missing. */
    private val bundledFontLanguages = setOf("ar", "en", "fa")

    private val arabicRegions = setOf(
        "SA", "AE", "KW", "QA", "BH", "OM", "YE", "IQ", "JO", "SY", "LB", "PS",
        "EG", "SD", "LY", "TN", "DZ", "MA", "MR"
    )

    var isRtl = false
        private set

    val orientation: ComponentOrientation
        get() = if (isRtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT

    /** Arabic for an Arabic system language or an Arab region setting, English otherwise. */
    fun defaultLanguage(locale: Locale = Locale.getDefault()): String =
        if (locale.language == "ar" || locale.country in arabicRegions) "ar" else "en"

    private fun baseLanguage(lang: String) = lang.lowercase().substringBefore('-').substringBefore('_')

    fun isRtlLanguage(lang: String) = baseLanguage(lang) in rtlLanguages

    /** Registers the bundled font; returns its family name when [lang] should use it. */
    fun registerFont(lang: String): String? {
        if (baseLanguage(lang) !in bundledFontLanguages) return null
        return try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            for (weight in listOf("Regular", "Medium", "Bold")) {
                UiLocale::class.java.getResourceAsStream("/fonts/IBMPlexSansArabic-$weight.ttf")?.use {
                    ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, it))
                }
            }
            FONT_FAMILY
        } catch (e: Exception) {
            Logger.error(e)
            null
        }
    }

    /**
     * Makes dates and numbers follow the UI language rather than the system's (English UI on an
     * Arabic Windows no longer shows Arabic month names), keeps the system's region, and always
     * uses Western digits (1 2 3) so sizes, speeds and spinners never mix two digit styles.
     */
    fun useUiLocale(lang: String) {
        val system = Locale.getDefault()
        val language = when (val base = baseLanguage(lang)) {
            "tw" -> "zh"
            "np" -> "ne"
            else -> base
        }
        val locale = runCatching {
            Locale.Builder().setLanguage(language).setRegion(system.country)
                .setUnicodeLocaleKeyword("nu", "latn").build()
        }.recoverCatching {
            Locale.Builder().setLanguage(language).setUnicodeLocaleKeyword("nu", "latn").build()
        }.getOrNull() ?: return
        Locale.setDefault(locale)
    }

    /** Turns on right-to-left layout for every window when [lang] is written right to left. */
    fun applyDirection(lang: String) {
        useUiLocale(lang)
        isRtl = isRtlLanguage(lang)
        if (!isRtl) return
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            when (e) {
                is WindowEvent -> if (e.id == WindowEvent.WINDOW_OPENED) mirror(e.window)
                is ContainerEvent -> if (e.id == ContainerEvent.COMPONENT_ADDED) {
                    val parent = e.container
                    if (!parent.componentOrientation.isLeftToRight || parent is Window) mirror(e.child)
                }
            }
        }, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK)
    }

    /**
     * Insets given in left-to-right terms, with left and right swapped when the UI runs
     * right to left. For borders, which Swing never mirrors on its own.
     */
    fun mirrored(top: Int, left: Int, bottom: Int, right: Int): java.awt.Insets =
        if (isRtl) java.awt.Insets(top, right, bottom, left) else java.awt.Insets(top, left, bottom, right)

    /**
     * Keeps a Latin run (a size, a speed, a path) in its own left-to-right order inside
     * right-to-left text, so "1.5 MB/s" never shows up as "MB/s 1.5". Labels only: the
     * embedding marks would end up in the value of an editable field.
     */
    fun ltr(s: String): String = if (isRtl) "\u202A$s\u202C" else s

    private const val KEEP_LTR = "blazma.keepLtr"

    /** Keeps [c] left-to-right in an RTL window: for paths and addresses, which read LTR. */
    fun <T : JComponent> keepLtr(c: T): T = c.apply {
        putClientProperty(KEEP_LTR, true)
        componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
    }

    private fun restoreLtr(c: Component) {
        if (c is JComponent && c.getClientProperty(KEEP_LTR) == true) {
            c.componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
        }
        if (c is Container) c.components.forEach { restoreLtr(it) }
    }

    /** Applies RTL to [c] and its children, and relayouts it if it was already on screen. */
    fun mirror(c: Component?) {
        if (c == null || !isRtl || !c.componentOrientation.isLeftToRight && c !is Window) return
        c.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
        restoreLtr(c)
        if (c.isShowing) {
            c.revalidate()
            c.repaint()
        }
    }
}
