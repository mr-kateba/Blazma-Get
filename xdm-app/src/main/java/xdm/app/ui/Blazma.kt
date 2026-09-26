package xdm.app.ui

import com.formdev.flatlaf.FlatLaf
import java.awt.Color

/**
 * The Blazma design tokens, shared with Blazma Boost, blazma.nt and the blazma.online site:
 * near-black background, raised panels, soft borders, muted secondary text and the store's
 * orange accent. Custom-painted components take their colors from here so both themes stay
 * consistent.
 */
object Blazma {
    private val dark get() = FlatLaf.isLafDark()

    /** Window background (#121216 on the site and in Blazma Boost). */
    val background: Color get() = if (dark) Color(0x121216) else Color(0xF7F7F7)

    /** Raised surfaces: cards, sidebar buttons, inputs (#1C1C22). */
    val panel: Color get() = if (dark) Color(0x1C1C22) else Color(0xFFFFFF)

    /** A panel under the mouse. */
    val panelHover: Color get() = if (dark) Color(0x24242C) else Color(0xFFF1E4)

    /** Hairlines around cards and outlined buttons. */
    val border: Color get() = if (dark) Color(0x2A2A33) else Color(0xE3E3E8)

    /** Border of an outlined button, a little stronger than a card's. */
    val borderStrong: Color get() = if (dark) Color(0x3A3A45) else Color(0xCFCFD6)

    val text: Color get() = if (dark) Color(0xF2F2F5) else Color(0x232629)

    /** Secondary text (#A0A0AB). */
    val muted: Color get() = if (dark) Color(0xA0A0AB) else Color(0x6B6B76)

    /** The Blazma orange. */
    val accent: Color get() = if (dark) Color(0xFF6D00) else Color(0xE65100)

    /** Orange used for text and titles, a touch lighter so it reads on the dark background. */
    val accentText: Color get() = if (dark) Color(0xFF8A1F) else Color(0xE65100)

    /** Text and glyphs drawn on an orange fill. */
    val onAccent: Color get() = if (dark) Color(0x121216) else Color.WHITE

    /** Orange tint behind category glyphs and selected cards. */
    fun accentTint(alpha: Int = 38): Color = accent.let { Color(it.red, it.green, it.blue, alpha) }

    val success: Color get() = if (dark) Color(0x34D399) else Color(0x059669)
    val warning: Color get() = if (dark) Color(0xFFC83D) else Color(0xB36A00)
    val danger: Color get() = if (dark) Color(0xFF5252) else Color(0xD13438)

    /** Corner radius of cards and buttons (10 px, as on the site). */
    const val RADIUS = 10
}
