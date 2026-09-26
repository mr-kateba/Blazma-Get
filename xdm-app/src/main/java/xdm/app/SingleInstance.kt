package xdm.app

import xdm.core.util.Logger
import xdm.integration.BrowserIntegration
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Runs when the browser-integration port is already taken. If the owner is another Blazma Get,
 * it is asked to show its window and this launch exits; otherwise the user is told why the app
 * cannot start (typically XDM itself running on the same port).
 */
object SingleInstance {
    fun handOff() {
        if (askRunningCopyToShow()) {
            Logger.info("Blazma Get is already running; showed its window")
            exitProcess(0)
        }
        Logger.error("Port ${AppContext.INTEGRATION_PORT} is used by another program")
        SwingUtilities.invokeAndWait {
            JOptionPane.showMessageDialog(
                null,
                I8N.text("MSG_PORT_BUSY").format(AppContext.INTEGRATION_PORT),
                APP_NAME,
                JOptionPane.ERROR_MESSAGE
            )
        }
        exitProcess(1)
    }

    private fun askRunningCopyToShow(): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:${AppContext.INTEGRATION_PORT}${BrowserIntegration.SHOW_PATH}")
            .openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
            conn.responseCode == 200 &&
                conn.inputStream.use { it.readBytes().decodeToString() } == BrowserIntegration.SHOW_REPLY
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
