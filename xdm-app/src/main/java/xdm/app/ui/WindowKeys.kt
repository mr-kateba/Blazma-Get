package xdm.app.ui

import xdm.app.I8N
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.KeyStroke
import javax.swing.UIManager
import java.awt.event.ActionEvent

/**
 * Behaviour shared by every dialog: Esc closes it, and the standard Swing buttons
 * (JOptionPane's Yes/No/OK/Cancel) use the app's language. Swing ships no Arabic strings, so
 * without this confirmations would show English buttons in the Arabic UI.
 */
object WindowKeys {
    private const val CLOSE_ON_ESC = "blazma.closeOnEsc"

    fun install() {
        UIManager.put("OptionPane.yesButtonText", I8N.text("MB_YES"))
        UIManager.put("OptionPane.noButtonText", I8N.text("MB_NO"))
        UIManager.put("OptionPane.okButtonText", I8N.text("MB_OK"))
        UIManager.put("OptionPane.cancelButtonText", I8N.text("ND_CANCEL"))

        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            val dialog = (e as? WindowEvent)?.window as? JDialog ?: return@addAWTEventListener
            if (e.id == WindowEvent.WINDOW_OPENED) closeOnEsc(dialog)
        }, AWTEvent.WINDOW_EVENT_MASK)
    }

    /** Esc acts like the window's close button, so each dialog's own close handling still runs. */
    private fun closeOnEsc(dialog: JDialog) {
        val root = dialog.rootPane
        if (root.getClientProperty(CLOSE_ON_ESC) == true) return
        root.putClientProperty(CLOSE_ON_ESC, true)
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE_ON_ESC)
        root.actionMap.put(CLOSE_ON_ESC, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                dialog.dispatchEvent(WindowEvent(dialog, WindowEvent.WINDOW_CLOSING))
            }
        })
    }
}
