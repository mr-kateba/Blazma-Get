package xdm.app

import xdm.core.util.Logger
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import javax.swing.Timer

/**
 * Watches the clipboard and offers to download a copied link that points at a file (an address
 * ending in one of the "File types" from Settings > Browser), like IDM does. Runs on the EDT
 * with a one-second timer; text the app copies itself ("Copy URL") is ignored.
 */
object ClipboardMonitor {
    private var last: String? = null
    private var timer: Timer? = null

    fun start() {
        if (timer != null) return
        last = read() // whatever is already on the clipboard is not new
        timer = Timer(1000) { check() }.apply { isRepeats = true; start() }
    }

    /** Called before the app puts [text] on the clipboard, so the monitor does not react to it. */
    fun ignore(text: String) {
        last = text
    }

    private fun check() {
        val text = read() ?: return
        if (text == last) return
        last = text
        if (!AppContext.config.monitorClipboard) return
        val urls = DownloadLinks.extract(text)
        val url = urls.singleOrNull() ?: return
        if (DownloadLinks.looksLikeDownload(url, AppContext.config.fileExtensions)) {
            Logger.info("Clipboard", "Download link copied: $url")
            AppContext.app.addDownloadFromUrl(url)
        }
    }

    private fun read(): String? = try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            (clipboard.getData(DataFlavor.stringFlavor) as? String)?.trim()?.take(4096)
        } else {
            null
        }
    } catch (_: Exception) {
        null // busy (another app is writing it) or no text: try again next tick
    }
}
