package xdm.app

import xdm.core.util.Logger
import java.awt.AWTEvent
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ContainerEvent
import java.awt.event.WindowEvent
import java.util.Locale

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
     * Keeps number and date formatting on Western digits (1 2 3) even on an Arabic or Persian
     * system, so sizes, speeds and spinners never mix two digit styles on one screen.
     */
    fun useLatinDigits() {
        // Swing components take Locale.getDefault(), formatters the FORMAT category; fix both.
        val current = Locale.getDefault()
        if (current.language in rtlLanguages) {
            Locale.setDefault(Locale.Builder().setLocale(current).setUnicodeLocaleKeyword("nu", "latn").build())
        }
    }

    /** Turns on right-to-left layout for every window when [lang] is written right to left. */
    fun applyDirection(lang: String) {
        useLatinDigits()
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

    /** Applies RTL to [c] and its children, and relayouts it if it was already on screen. */
    fun mirror(c: Component?) {
        if (c == null || !isRtl || !c.componentOrientation.isLeftToRight && c !is Window) return
        c.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
        if (c.isShowing) {
            c.revalidate()
            c.repaint()
        }
    }
}
