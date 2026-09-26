package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.openFolderExternal
import xdm.app.utils.setClipBoardText
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel

/** Which build of the bundled extension a browser needs. */
enum class ExtensionBrowser(val folder: String, val extensionsPage: String) {
    CHROME("chrome-extension", "chrome://extensions"),
    EDGE("chrome-extension", "edge://extensions"),
    FIREFOX("firefox-extension", "about:debugging#/runtime/this-firefox"),
    OTHER("chrome-extension", "chrome://extensions"),
}

/**
 * Walks the user through loading the browser extension that ships inside the app. The extension
 * is not in the browser stores yet, so it is copied to the config folder and loaded from there
 * as an unpacked extension (Chrome, Edge, Brave, Opera...) or a temporary add-on (Firefox).
 */
class ExtensionSetupDialog(owner: Window?, private val browser: ExtensionBrowser) : JDialog(owner) {

    init {
        title = I8N.text("EXT_TITLE")
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val steps = if (browser == ExtensionBrowser.FIREFOX) {
            listOf("EXT_FF_STEP1", "EXT_FF_STEP2", "EXT_FF_STEP3", "EXT_FF_NOTE")
        } else {
            listOf("EXT_STEP1", "EXT_STEP2", "EXT_STEP3", "EXT_STEP4")
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            border = BorderFactory.createEmptyBorder(20, 24, 12, 24)
            add(JLabel(I8N.text("EXT_INTRO")).apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createRigidArea(Dimension(0, 14)))
            steps.forEachIndexed { i, key ->
                val text = I8N.text(key).format(browser.extensionsPage)
                val numbered = if (key.endsWith("NOTE")) text else "${i + 1}. $text"
                add(JLabel("<html><body style='width:380px'>$numbered</body></html>").apply {
                    alignmentX = LEFT_ALIGNMENT
                    border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
                })
            }
        }

        val btnFolder = JButton(I8N.text("EXT_OPEN_FOLDER")).apply {
            putClientProperty("JButton.buttonType", "default")
            addActionListener { openExtensionFolder() }
        }
        val btnCopy = JButton(I8N.text("EXT_COPY_PAGE")).apply {
            addActionListener {
                setClipBoardText(browser.extensionsPage)
                text = I8N.text("EXT_COPIED")
            }
        }
        val btnClose = JButton(I8N.text("LBL_CLOSE")).apply { addActionListener { dispose() } }
        val buttons = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 12)).apply {
            add(btnClose)
            add(btnCopy)
            add(btnFolder)
        }
        rootPane.defaultButton = btnFolder

        contentPane.layout = BorderLayout()
        contentPane.add(body, BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.PAGE_END)
        pack()
        setLocationRelativeTo(owner)
    }

    private fun openExtensionFolder() {
        val dir = extract(browser.folder)
        if (dir == null) {
            JOptionPane.showMessageDialog(this, I8N.text("EXT_EXTRACT_FAILED"), title, JOptionPane.ERROR_MESSAGE)
            return
        }
        runCatching { openFolderExternal(null, dir.absolutePath) }
            .onFailure { Logger.error("Unable to open ${dir.absolutePath}", it) }
    }

    companion object {
        /**
         * Copies the bundled extension [folder] to `<config>/browser-extension/<folder>` (replacing
         * an older copy, so an app update also updates the extension files) and returns it.
         */
        fun extract(folder: String): File? = runCatching {
            val target = File(AppContext.configDir, "browser-extension/$folder")
            val url = ExtensionSetupDialog::class.java.getResource("/extension/$folder")
                ?: error("extension $folder is not bundled")
            target.deleteRecursively()
            if (url.protocol == "jar") {
                // Read the app jar with JarFile (java.base). Not FileSystems.newFileSystem: the
                // installers' trimmed Java runtime has no zip file system (jdk.zipfs), so that
                // failed with "Provider jar not found" on every Windows and Linux install.
                val jarFile = File((url.openConnection() as JarURLConnection).jarFileURL.toURI())
                JarFile(jarFile).use { copyEntries(it, "extension/$folder/", target) }
            } else {
                copyTree(Paths.get(url.toURI()), target)
            }
            target
        }.onFailure { Logger.error("Unable to extract browser extension $folder", it) }.getOrNull()

        /** Copies the files of [jar] under [prefix] into [target], keeping their relative paths. */
        internal fun copyEntries(jar: JarFile, prefix: String, target: File) {
            val root = target.canonicalFile
            var copied = 0
            for (entry in jar.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val dest = File(root, entry.name.removePrefix(prefix)).canonicalFile
                require(dest.path.startsWith(root.path + File.separator)) { "entry outside the folder: ${entry.name}" }
                dest.parentFile.mkdirs()
                jar.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                copied++
            }
            check(copied > 0) { "no files under $prefix" }
        }

        private fun copyTree(source: Path, target: File) {
            Files.walk(source).use { paths ->
                paths.forEach { p ->
                    val dest = target.toPath().resolve(source.relativize(p).toString())
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dest)
                    } else {
                        Files.createDirectories(dest.parent)
                        Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }
}
