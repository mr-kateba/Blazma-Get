package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.UiLocale
import xdm.app.ui.Blazma
import xdm.app.utils.RemixIcon
import xdm.app.utils.chooseFile
import xdm.app.utils.createIcon
import xdm.app.utils.isAutoCategorySelected
import xdm.app.utils.persistFolderChoiceOnChange
import xdm.app.utils.populateSaveInFolders
import xdm.app.utils.rememberFolderChoice
import xdm.app.utils.selectedBaseFolder
import xdm.app.ytdlp.DownloadOption
import xdm.app.ytdlp.VideoInfo
import xdm.app.ytdlp.YtDlpTool
import xdm.core.downloaders.YtDlpDownloadTaskInfo
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import xdm.core.util.FormatHelper
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.io.File
import java.net.URL
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.border.EmptyBorder

/**
 * "Download video" for a page yt-dlp understands (YouTube and 1000+ other sites): looks up the
 * qualities in the background, then lets the user pick one, name the file and choose the folder.
 */
class YtDlpWindow(private val pageUrl: String, pageTitle: String?) : JDialog() {
    private val pageTitleHint = pageTitle?.takeIf { it.isNotBlank() }
    private val cards = CardLayout()
    private val body = JPanel(cards)
    private val status = JLabel(text("YT_LOADING"))
    private val errorText = JLabel()
    private val thumb = JLabel()
    private val titleLabel = JLabel()
    private val metaLabel = JLabel()
    private val options = DefaultListModel<DownloadOption>()
    private val list = JList(options)
    private val txtName = JTextField(28)
    private val modelSaveIn = DefaultComboBoxModel<String>()
    private val cmbSaveIn = UiLocale.keepLtr(JComboBox(modelSaveIn))
    private val btnDownload = JButton(text("ND_DOWNLOAD"))
    private var info: VideoInfo? = null
    private var baseName = FileUtils.sanitizeFileName(pageTitle?.takeIf { it.isNotBlank() } ?: "video") ?: "video"

    init {
        title = text("YT_TITLE")
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isAlwaysOnTop = true
        contentPane = JPanel(BorderLayout(0, 14)).apply {
            border = EmptyBorder(18, 20, 16, 20)
            add(header(), BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
            add(buttons(), BorderLayout.SOUTH)
        }
        titleLabel.text = html(baseName)
        body.add(loadingCard(), "loading")
        body.add(errorCard(), "error")
        body.add(choiceCard(), "choice")
        btnDownload.isEnabled = false
        rootPane.defaultButton = btnDownload
        size = Dimension(560, 520)
        minimumSize = Dimension(480, 440)
        setLocationRelativeTo(null)
        load()
    }

    private fun html(s: String) = "<html><body style='width:330px'>" +
        s.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>"

    private fun header(): JPanel = JPanel(BorderLayout(14, 0)).apply {
        isOpaque = false
        thumb.preferredSize = Dimension(128, 72)
        thumb.icon = createIcon(RemixIcon.PLAY_CIRCLE_LINE, 36, Blazma.accentText)
        thumb.horizontalAlignment = JLabel.CENTER
        thumb.border = BorderFactory.createLineBorder(Blazma.border)
        add(thumb, BorderLayout.LINE_START)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, titleLabel.font.size2D + 2f)
        metaLabel.foreground = Blazma.muted
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            add(titleLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(6))
            add(metaLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }, BorderLayout.CENTER)
    }

    private fun loadingCard() = JPanel(BorderLayout(0, 10)).apply {
        isOpaque = false
        add(JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(status, BorderLayout.NORTH)
            add(JProgressBar().apply { isIndeterminate = true; preferredSize = Dimension(10, 8) }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
    }

    private fun errorCard() = JPanel(BorderLayout(0, 12)).apply {
        isOpaque = false
        val retry = JButton(text("YT_RETRY")).apply { addActionListener { load() } }
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            add(JLabel(text("YT_ERROR")).apply {
                font = font.deriveFont(Font.BOLD)
                foreground = Blazma.danger
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(6))
            add(errorText.apply { foreground = Blazma.muted; alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(12))
            add(retry.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }, BorderLayout.NORTH)
    }

    private fun choiceCard() = JPanel(BorderLayout(0, 10)).apply {
        isOpaque = false
        add(JLabel(text("YT_QUALITY")).apply {
            font = font.deriveFont(Font.BOLD)
            foreground = Blazma.accentText
        }, BorderLayout.NORTH)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = OptionRenderer()
        list.fixedCellHeight = 60
        list.addListSelectionListener { if (!it.valueIsAdjusting) updateName() }
        add(JScrollPane(list).apply { border = BorderFactory.createLineBorder(Blazma.border) }, BorderLayout.CENTER)

        populateSaveInFolders(modelSaveIn, cmbSaveIn)
        persistFolderChoiceOnChange(cmbSaveIn)
        val browse = JButton(createIcon(RemixIcon.FOLDER_FILL, 16, Blazma.muted)).apply {
            addActionListener {
                chooseFile(this@YtDlpWindow, true, File(selectedBaseFolder(cmbSaveIn)))?.let { dir ->
                    val idx = modelSaveIn.getIndexOf(dir.absolutePath).takeIf { it >= 0 }
                        ?: modelSaveIn.size.also { modelSaveIn.addElement(dir.absolutePath) }
                    cmbSaveIn.selectedIndex = idx
                }
            }
        }
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            add(row(text("ND_FILE"), txtName))
            add(Box.createVerticalStrut(8))
            add(row(text("LBL_SAVE_IN"), JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(cmbSaveIn, BorderLayout.CENTER)
                add(browse, BorderLayout.LINE_END)
            }))
        }, BorderLayout.SOUTH)
    }

    private fun row(label: String, field: Component) = JPanel(BorderLayout(10, 0)).apply {
        isOpaque = false
        add(JLabel(label).apply { preferredSize = Dimension(70, preferredSize.height) }, BorderLayout.LINE_START)
        add(field, BorderLayout.CENTER)
    }

    private fun buttons() = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
        isOpaque = false
        val cancel = JButton(text("ND_CANCEL")).apply { addActionListener { dispose() } }
        btnDownload.addActionListener { download() }
        add(cancel)
        add(btnDownload)
    }

    /** Looks the page up with yt-dlp off the UI thread, installing the tool first if needed. */
    private fun load() {
        cards.show(body, "loading")
        btnDownload.isEnabled = false
        status.text = text("YT_LOADING")
        object : SwingWorker<VideoInfo, String>() {
            override fun doInBackground(): VideoInfo {
                if (!YtDlpTool.isInstalled) {
                    YtDlpTool.ensure { done, total ->
                        val amount = if (total > 0) "${done * 100 / total}%" else FormatHelper.formatSize(done.toDouble())
                        publish(text("YT_INSTALLING").format(UiLocale.ltr(amount)))
                    }
                    publish(text("YT_LOADING"))
                }
                return YtDlpTool.fetchInfo(pageUrl)
            }

            override fun process(chunks: List<String>) {
                status.text = chunks.last()
            }

            override fun done() {
                try {
                    show(get())
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    Logger.error("XDM", "Video lookup failed for $pageUrl", cause)
                    val details = (cause.message ?: "").substringBefore("; please report").trim()
                    errorText.text = html(friendlyError(cause) + "\n\n" + text("YT_DETAILS") + "\n" + details)
                        .replace("\n", "<br>")
                    cards.show(body, "error")
                }
            }
        }.execute()
    }

    /** What went wrong, in the user's words; yt-dlp's own text is shown under it. */
    private fun friendlyError(e: Throwable): String {
        val m = e.message ?: ""
        fun has(vararg s: String) = s.any { m.contains(it, ignoreCase = true) }
        return text(
            when {
                e !is xdm.app.ytdlp.YtDlpException -> "YT_ERR_TOOL"
                has("Sign in", "login", "cookies") -> "YT_ERR_LOGIN"
                has("Private video", "unavailable", "removed", "not available", "blocked it") -> "YT_ERR_UNAVAILABLE"
                has("Unsupported URL") -> "YT_ERR_UNSUPPORTED"
                else -> "YT_ERR_NETWORK"
            }
        )
    }

    private fun show(video: VideoInfo) {
        info = video
        // Generic pages only give yt-dlp a file name as the title; the browser tab's title is better.
        val title = if (video.site.equals("generic", true) && pageTitleHint != null) pageTitleHint else video.title
        baseName = FileUtils.sanitizeFileName(title) ?: baseName
        titleLabel.text = html(title)
        metaLabel.text = listOfNotNull(
            video.site,
            video.durationSeconds?.takeIf { it > 0 }?.let { UiLocale.ltr(FormatHelper.hms(it.toInt())) }
        ).joinToString("  ·  ")
        options.clear()
        video.options.forEach { options.addElement(it) }
        list.selectedIndex = 0
        updateName()
        cards.show(body, "choice")
        btnDownload.isEnabled = true
        btnDownload.requestFocusInWindow()
        video.thumbnail?.let { loadThumbnail(it) }
    }

    private fun loadThumbnail(url: String) {
        Thread {
            runCatching {
                val conn = AppContext.config.toProxy()?.let { URL(url).openConnection(it) } ?: URL(url).openConnection()
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.getInputStream().use { ImageIO.read(it) }
            }.getOrNull()?.let { img ->
                val scaled = img.getScaledInstance(128, 72, Image.SCALE_SMOOTH)
                SwingUtilities.invokeLater { thumb.icon = ImageIcon(scaled) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Keeps the typed name but switches its extension to the chosen quality's. */
    private fun updateName() {
        val option = list.selectedValue ?: return
        val current = txtName.text.trim()
        val stem = if (current.isEmpty()) baseName else current.substringBeforeLast('.', current)
        txtName.text = "$stem.${option.ext}"
    }

    private fun download() {
        val option = list.selectedValue ?: return
        val video = info ?: return
        val name = FileUtils.sanitizeFileName(txtName.text.trim().ifEmpty { "$baseName.${option.ext}" }) ?: return
        val auto = isAutoCategorySelected(cmbSaveIn)
        rememberFolderChoice(cmbSaveIn)
        AppContext.downloader.startYtDlpDownload(
            YtDlpDownloadTaskInfo(
                id = CoreUtils.uniqueId(),
                fileName = name,
                tempDir = AppContext.config.tempFolder,
                respectFileName = true,
                cookie = null,
                headers = null,
                origin = video.pageUrl,
                autoCategorize = auto,
                defaultDownloadFolder = selectedBaseFolder(cmbSaveIn),
                userSelectedDownloadFolder = null,
                maxPiece = 1,
                authInfo = null,
                pageUrl = video.pageUrl,
                formatIds = option.formatIds,
                outputExt = option.ext,
                expectedSize = option.size,
            )
        )
        dispose()
    }

    /** A quality as a card: "1080p" in bold, what it contains, and its size. */
    private class OptionRenderer : JPanel(BorderLayout(10, 0)), ListCellRenderer<DownloadOption> {
        private val main = JLabel()
        private val sub = JLabel()
        private val size = JLabel()
        private var selected = false

        init {
            isOpaque = false
            border = EmptyBorder(8, 16, 8, 16)
            main.font = main.font.deriveFont(Font.BOLD, main.font.size2D + 1f)
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
                add(main)
                add(sub)
            }, BorderLayout.CENTER)
            add(size, BorderLayout.LINE_END)
        }

        override fun getListCellRendererComponent(
            list: JList<out DownloadOption>, value: DownloadOption, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            selected = isSelected
            componentOrientation = list.componentOrientation
            main.text = when {
                value.audioOnly -> text("YT_AUDIO_ONLY")
                value.height != null -> UiLocale.ltr("${value.height}p")
                else -> text("YT_BEST")
            }
            sub.text = (if (value.audioOnly) "" else text("YT_VIDEO_AUDIO") + "  ·  ") + value.ext.uppercase()
            size.text = value.size?.let { UiLocale.ltr(FormatHelper.formatSize(it.toDouble())) } ?: text("YT_SIZE_UNKNOWN")
            main.foreground = if (isSelected) Blazma.accentText else Blazma.text
            sub.foreground = Blazma.muted
            size.foreground = Blazma.muted
            return this
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (selected) Blazma.accentTint(34) else Blazma.panel
            g2.fillRoundRect(4, 3, width - 8, height - 6, 12, 12)
            if (selected) {
                g2.color = Blazma.accent
                g2.drawRoundRect(4, 3, width - 9, height - 7, 12, 12)
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
