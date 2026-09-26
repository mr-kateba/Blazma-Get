package xdm.app.ui.screens

import xdm.app.UiLocale
import com.formdev.flatlaf.FlatLaf
import xdm.app.AppContext
import xdm.app.AppContext.app
import xdm.app.XDM_WINDOW_TITLE
import xdm.app.DbRecord
import xdm.app.DownloadLinks
import xdm.app.utils.getClipBoardText
import xdm.app.I8N.text
import xdm.app.OS
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.components.AppToolBar
import xdm.app.ui.components.FilterListPanel
import xdm.app.ui.components.MainListView
import xdm.app.ui.components.UpdatePanel
import xdm.app.ui.components.SegmentedTabs
import xdm.app.ui.components.StatusBar
import xdm.app.ui.components.FilterState
import xdm.app.ui.Blazma
import xdm.app.RecordStatus
import xdm.app.update.UpdateChecker
import xdm.app.utils.applyMacOSWindowCustomizations
import xdm.app.utils.detectOS
import xdm.core.util.Logger
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

private const val SIDEBAR_WIDTH = 230

class AppWindow(image: Image) : JFrame(), ActionListener {
    private var listView = MainListView()
    private val updatePanel = UpdatePanel()
    private var filterPanelRef: FilterListPanel? = null
    private lateinit var stateTabs: SegmentedTabs
    private var statusBar = StatusBar()
    private var summaryTimer: Timer? = null


    init {
        title = XDM_WINDOW_TITLE
        iconImage = image
        val os = detectOS()
        if (os == OS.Windows) {
            getRootPane().putClientProperty("JRootPane.titleBarBackground", UIManager.getColor("Table.background"))
        } else if (os == OS.MacOS) {
            applyMacOSWindowCustomizations(image)
        }

        setWindowSizeAndPosition()
        initWindow()
        checkForUpdates()

        //ProgressWindow().isVisible = true
    }

    /**
     * Kicks off a background update check at startup; if a newer release is
     * found, reveals the update banner at the bottom of the window (on the EDT).
     */
    private fun checkForUpdates() {
        UpdateChecker.checkForUpdate { info ->
            if (info != null) {
                SwingUtilities.invokeLater {
                    updatePanel.showUpdate(info.latestVersion, info.downloadUrl)
                }
            }
        }
    }

    private fun initWindow() {
        // Orange section title, like Blazma Boost's group headers.
        val pageTitle = JLabel().apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 7f)
            foreground = Blazma.accentText
        }
        val filterPanel = FilterListPanel(
            categoryChanged = {
                listView.filterCategoryChanged(it)
                pageTitle.text = filterPanelRef?.selectedTitle ?: ""
            }
        )
        filterPanelRef = filterPanel
        pageTitle.text = filterPanel.selectedTitle
        val toolbar = AppToolBar(
            { listView.searchTextChanged(it) },
            this,
            { key, asc -> listView.sort(key, asc) },
            {
                filterPanel.reloadCategories()
                // The theme chosen in Settings applies at once, like the toolbar's moon button.
                if ((AppContext.config.theme == "light") == FlatLaf.isLafDark()) applyTheme()
            },
            { toggleTheme() }
        )
        toolbar.setMultiSelectView(false)
        listView.selectModeCallback = { toolbar.setMultiSelectView(it) }

        val states = listOf(FilterState.All, FilterState.Incomplete, FilterState.Completed)
        stateTabs = SegmentedTabs(
            listOf(text("ALL_DOWNLOADS"), text("ALL_UNFINISHED"), text("ALL_FINISHED"))
        ) { listView.filterStateChanged(states[it]) }

        // Page header: the category as a title, and the state filter as segmented tabs.
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(UiLocale.mirrored(14, 20, 6, 16))
            add(pageTitle, BorderLayout.LINE_START)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 0, 0)).apply {
                isOpaque = false
                add(stateTabs)
            }, BorderLayout.LINE_END)
        }

        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            isOpaque = false
            add(toolbar.component)
            add(header)
        }

        val panel = JPanel(BorderLayout(10, 0)).apply {
            add(top, BorderLayout.NORTH)
            add(listView.component)
            add(statusBar, BorderLayout.SOUTH)
            border = EmptyBorder(7, 0, 0, 0)
            background = Blazma.background
        }
        startSummaryTimer()

        // Keep the black top border in the dark theme (as before); use FlatLaf's
        // border color in the light theme so it isn't a harsh black line.
        val topBorderColor = Blazma.border
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            border = MatteBorder(1, 0, 0, 0, topBorderColor)
            if (UiLocale.isRtl) {
                // JSplitPane does not mirror: put the sidebar on the right and let the
                // list take all extra width, so the sidebar keeps its size on resize.
                leftComponent = panel
                rightComponent = filterPanel.component
                resizeWeight = 1.0
                addComponentListener(object : java.awt.event.ComponentAdapter() {
                    private var placed = false
                    override fun componentResized(e: java.awt.event.ComponentEvent) {
                        if (!placed && width > 0) {
                            placed = true
                            dividerLocation = width - SIDEBAR_WIDTH - dividerSize
                        }
                    }
                })
            } else {
                dividerLocation = SIDEBAR_WIDTH
                leftComponent = filterPanel.component
                rightComponent = panel
            }
            background = Blazma.background
            dividerSize = 1
        }
        add(splitPane, BorderLayout.CENTER)
        add(updatePanel, BorderLayout.SOUTH)

        installLinkDrop(this)
        installShortcuts()

        ToolTipManager.sharedInstance().initialDelay = 500
    }

    /**
     * Dropping a link (or text holding one) anywhere on the window opens a new download. Set on
     * every component, because Swing delivers a drop to the innermost one (the list's JTable would
     * otherwise refuse it); text fields keep their own handler so dragging text into them works.
     */
    private fun installLinkDrop(root: java.awt.Container) {
        val handler = object : TransferHandler() {
            override fun canImport(support: TransferSupport) =
                support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)

            override fun importData(support: TransferSupport): Boolean {
                val text = runCatching {
                    support.transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
                }.getOrNull() ?: return false
                val url = DownloadLinks.extract(text).firstOrNull() ?: return false
                app.addDownloadFromUrl(url)
                return true
            }
        }
        fun apply(c: java.awt.Component) {
            if (c is javax.swing.text.JTextComponent) return
            if (c is JComponent) c.transferHandler = handler
            if (c is java.awt.Container) c.components.forEach { apply(it) }
        }
        rootPane.transferHandler = handler
        apply(root)
    }

    /** Ctrl+N new download, Ctrl+V download the copied link, Delete remove the selected downloads. */
    private fun installShortcuts() {
        val menuKey = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        fun bind(key: KeyStroke, name: String, action: () -> Unit) {
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name)
            rootPane.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = action()
            })
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuKey), "blazma.new") { app.addDownload(null) }
        // Text fields handle their own Ctrl+V and Delete first, so these only fire on the list.
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuKey), "blazma.paste") {
            getClipBoardText()?.let { DownloadLinks.extract(it).firstOrNull() }
                ?.let { app.addDownloadFromUrl(it) }
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "blazma.delete") {
            val selected = listView.selectedItems
            if (selected.isNotEmpty()) AppMenuHandler.deleteSelectedDownloads(selected, this)
        }
    }

    /**
     * Once a second: counts on the state tabs and the totals in the status bar, from a snapshot
     * of the records (cheap: a few hundred rows at most).
     */
    private fun startSummaryTimer() {
        fun refresh() {
            val all = AppContext.db.snapshot()
            val done = all.count { it.status == RecordStatus.FINISHED }
            stateTabs.setCounts(listOf(all.size, all.size - done, done))
            statusBar.update(all)
        }
        refresh()
        summaryTimer?.stop()
        summaryTimer = Timer(1000) { refresh() }.apply { start() }
    }

    /** The toolbar's moon/sun button: flips between the dark and the light theme. */
    private fun toggleTheme() {
        AppContext.config.theme = if (FlatLaf.isLafDark()) "light" else "dark"
        AppContext.config.save()
        applyTheme()
    }

    /**
     * Switches the look to the configured theme without a restart: FlatLaf repaints every open
     * window, and this window's content is built again so the colors set in code (sidebar,
     * cards, status bar) follow. The old look fades out over the new one.
     */
    private fun applyTheme() {
        val config = AppContext.config
        val fade = xdm.app.ui.components.SnapshotFade.cover(layeredPane)
        xdm.app.AppMain.setupTheme(config.theme, config.lang)
        FlatLaf.updateUI()
        contentPane.removeAll()
        listView = MainListView()
        statusBar = StatusBar()
        if (detectOS() == OS.Windows) {
            getRootPane().putClientProperty("JRootPane.titleBarBackground", UIManager.getColor("Table.background"))
        }
        initWindow()
        contentPane.revalidate()
        contentPane.repaint()
        fade.play()
    }

    fun updateDownloadInView(index: Int) {
        listView.rowUpdated(index)
    }

    fun deleteDownloadInView(index: Int) {
        listView.rowDeleted(index)
    }

    fun addDownloadInView(index: Int) {
        listView.rowAdded(index)
    }


    override fun actionPerformed(e: ActionEvent) {
        Logger.info("XDM", "Command: ${e.actionCommand}")
        if (e.source is JComponent) {
            val name: String = (e.source as JComponent).name ?: return

            when (name) {
                "TOOL_DOWNLOAD" -> {
                    app.addDownload(null)
                    return
                }

                "TOOL_CLEAR" -> {
                    clearDownloads()
                }
            }

            if (name.startsWith("STOP")) {
                AppMenuHandler.stopQueue(name)
            } else if (name.startsWith("START")) {
                AppMenuHandler.startQueue(name)
            } else if ("TOOL_DOWNLOAD" == name || "MENU_ADD_URL" == name) {
                app.addDownload(null)
            } else if ("PAUSE" == name || "MENU_PAUSE" == name) {
                // AppMenuHandler.pauseDownloads(this);
            } else if ("LBL_SHOW_PROGRESS" == name) {
                AppMenuHandler.showProgressWindow(this)
            } else if ("MENU_RESTART" == name) {
                //        AppMenuHandler.restartDownloads(this);
            } else if ("RESUME" == name || "MENU_RESUME" == name) {
                //        AppMenuHandler.resumeDownloads(this);
            } else if ("CTX_OPEN_FILE" == name) {
                AppMenuHandler.openFile(this)
            } else if ("CTX_OPEN_FOLDER" == name) {
                //        AppMenuHandler.openFolder(this);
            } else if ("MENU_EXIT" == name) {
                // XDMApp.getInstance().exit();
            } else if ("MENU_OPTIONS" == name || "OPTIONS" == name) {
                // SettingsPage.getInstance().showPanel(this, "PG_SETTINGS");
            } else if ("MENU_REFRESH_LINK" == name) {
                AppMenuHandler.openRefreshPage(this)
            } else if ("MENU_PROPERTIES" == name) {
                //AppMenuHandler.showProperties(this)
            } else if ("MENU_BROWSER_INT" == name) {
                // SettingsPage.getInstance().showPanel(this, "BTN_MONITORING");
            } else if ("MENU_SPEED_LIMITER" == name) {
                //        int ret = SpeedLimiter.getSpeedLimit();
                //        if (ret >= 0) {
                //          Config.getInstance().setSpeedLimit(ret);
                //        }
            } else if ("DESC_Q_TITLE" == name) {
                // SettingsPage.getInstance().showPanel(this, "Q_MAN");
            } else if ("TOOL_DELETE" == name) {
                Logger.info("Selected items: ${listView.selectedItems}")
                AppMenuHandler.deleteSelectedDownloads(listView.selectedItems, this)
            } else if ("MENU_DELETE_COMPLETED" == name) {
                AppMenuHandler.deleteCompleted(this)
            } else if ("MENU_ABOUT" == name) {
                //				AboutPage aboutPage = new AboutPage(this);
                //				aboutPage.showPanel();
            } else if ("CTX_SAVE_AS" == name) {
                AppMenuHandler.changeFile(this)
            } else if ("MENU_IMPORT" == name) {
                //        JFileChooser jfc = new JFileChooser();
                //        if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                //          File file = jfc.getSelectedFile();
                //          XDMApp.getInstance().loadDownloadList(file);
                //        }
            } else if ("MENU_EXPORT" == name) {
                //        JFileChooser jfc = new JFileChooser();
                //        if (jfc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                //          File file = jfc.getSelectedFile();
                //          XDMApp.getInstance().saveDownloadList(file);
                //        }
            } else if ("MENU_CONTENTS" == name) {
                //        XDMUtils.browseURL(XDMApp.APP_WIKI_URL);
            } else if ("MENU_HOME_PAGE" == name) {
                //        XDMUtils.browseURL(XDMApp.APP_HOME_URL);
            } else if ("MENU_UPDATE" == name) {
                //        XDMUtils.browseURL(XDMApp.APP_UPDATE_CHK_URL + XDMApp.APP_VERSION);
            } else if ("MENU_LANG" == name) {
                AppMenuHandler.showLanguageDlg(this)
            } else if ("MENU_BATCH_DOWNLOAD" == name) {
                AppMenuHandler.showBatchPatternDialog()
            } else if ("MENU_CLIP_ADD_MENU" == name) {
                AppMenuHandler.showBatchDialog(this)
            } else if ("LBL_OPTIMIZE_NETWORK" == name) {
                AppMenuHandler.optimizeRWin()
            } else if ("LBL_TRANSLATE" == name) {
                AppMenuHandler.openTranslationPage()
            } else if ("LBL_SUPPORT_PAGE" == name) {
                AppMenuHandler.openSupportPage()
            } else if ("LBL_REPORT_PROBLEM" == name) {
                AppMenuHandler.openBugReportPage()
            }
        }
    }

    private fun clearDownloads() {
        listView.clear()
    }

    private fun createMainMenu() {
        val bar = JMenuBar()

        val file = JMenu(text("MENU_FILE"))

        addMenuItem("MENU_ADD_URL", file)
        addMenuItem("MENU_VIDEO_DWN", file)
        addMenuItem("MENU_CLIP_ADD_MENU", file)
        addMenuItem("MENU_BATCH_DOWNLOAD", file)
        addMenuItem("MENU_DELETE_DWN", file)
        addMenuItem("MENU_DELETE_COMPLETED", file)
        addMenuItem("MENU_EXPORT", file)
        addMenuItem("MENU_IMPORT", file)
        addMenuItem("MENU_EXIT", file)

        val dwn = JMenu(text("MENU_DOWNLOAD"))

        addMenuItem("MENU_PAUSE", dwn)
        addMenuItem("MENU_RESUME", dwn)
        addMenuItem("MENU_RESTART", dwn)
        addMenuItem("DESC_Q_TITLE", dwn)

        val popupListener: PopupMenuListener =
            object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
//                    loadQueueMenu(startQMenu!!)
//                    loadQueueMenu(stopQMenu!!)
                }

                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}

                override fun popupMenuCanceled(e: PopupMenuEvent) {}
            }

//        startQMenu = addSubMenu("MENU_START_Q", dwn, popupListener)
//        stopQMenu = addSubMenu("MENU_STOP_Q", dwn, popupListener)

        val tools = JMenu(text("MENU_TOOLS"))

        addMenuItem("MENU_OPTIONS", tools)
        addMenuItem("MENU_REFRESH_LINK", tools)
        addMenuItem("MENU_PROPERTIES", tools)
        addMenuItem("MENU_SPEED_LIMITER", tools)
        addMenuItem("MENU_LANG", tools)
        addMenuItem("MENU_MEDIA_CONVERTER", tools)
        addMenuItem("LBL_OPTIMIZE_NETWORK", tools)
        addMenuItem("MENU_BROWSER_INT", tools)

        val help = JMenu(text("MENU_HELP"))
        addMenuItem("MENU_CONTENTS", help)
        addMenuItem("MENU_HOME_PAGE", help)
        addMenuItem("LBL_SUPPORT_PAGE", help)
        addMenuItem("LBL_REPORT_PROBLEM", help)
        addMenuItem("LBL_TRANSLATE", help)
        addMenuItem("MENU_UPDATE", help)
        addMenuItem("MENU_ABOUT", help)

        bar.add(file)
        bar.add(dwn)
        bar.add(tools)
        bar.add(help)

        jMenuBar = bar
    }

    private fun addMenuItem(id: String, menu: JComponent) {
        val mItem = JMenuItem(text(id))
        mItem.name = id
        mItem.addActionListener(this)
        menu.add(mItem)
    }

    private fun addSubMenu(id: String, parentMenu: JMenu, popupListener: PopupMenuListener): JMenu {
        val menu = JMenu(text(id))
        menu.name = id
        menu.addActionListener(this)
        menu.popupMenu.addPopupMenuListener(popupListener)
        parentMenu.add(menu)
        return menu
    }

    private fun setWindowSizeAndPosition() {
        // Room for the sidebar, the toolbar with its search box and the page header, without
        // outgrowing a 1366x768 laptop screen.
        val screen = Toolkit.getDefaultToolkit().screenSize
        setSize(minOf(1040, screen.width * 9 / 10), minOf(640, screen.height * 85 / 100))
        minimumSize = Dimension(860, 520)
        setLocationRelativeTo(null)
//        if (Config.getInstance().width < 0 || Config.getInstance().height < 0) setSize(800, 500)
//        if (Config.getInstance().x < 0 || Config.getInstance().y < 0) setLocationRelativeTo(null)
    }

    private fun loadQueueMenu(menu: JMenu) {
        if (menu.name == "MENU_START_Q") {
            loadStartQueueMenu(menu)
        } else if (menu.name == "MENU_STOP_Q") {
            loadStopQueueMenu(menu)
        }
    }

    private fun loadStopQueueMenu(menu: JMenu) {
        //    menu.removeAll();
        //    ArrayList<DownloadQueue> queues = XDMApp.getInstance().getQueueList();
        //    for (int i = 0; i < queues.size(); i++) {
        //      DownloadQueue q = queues.get(i);
        //      if (q.isRunning()) {
        //        JMenuItem mitem = new JMenuItem(q.getName());
        //        mitem.setForeground(ColorResource.getLightFontColor());
        //        mitem.setName("STOP:" + q.getQueueId());
        //        mitem.addActionListener(this);
        //        menu.add(mitem);
        //      }
        //    }
    }

    private fun loadStartQueueMenu(menu: JMenu) {
        //    menu.removeAll();
        //    ArrayList<DownloadQueue> queues = XDMApp.getInstance().getQueueList();
        //    for (int i = 0; i < queues.size(); i++) {
        //      DownloadQueue q = queues.get(i);
        //      if (!q.isRunning()) {
        //        JMenuItem mitem = new JMenuItem(q.getName());
        //        mitem.setForeground(ColorResource.getLightFontColor());
        //        mitem.setName("START:" + q.getQueueId());
        //        mitem.addActionListener(this);
        //        menu.add(mitem);
        //      }
        //    }
    }

    private fun createPopupMenu() {
//        popupCtx = JPopupMenu()
//        addMenuItem("CTX_OPEN_FILE", popupCtx!!)
//        addMenuItem("CTX_OPEN_FOLDER", popupCtx!!)
//        addMenuItem("CTX_SAVE_AS", popupCtx!!)
//        addMenuItem("MENU_PAUSE", popupCtx!!)
//        addMenuItem("MENU_RESUME", popupCtx!!)
//        addMenuItem("MENU_DELETE_DWN", popupCtx!!)
//        addMenuItem("MENU_REFRESH_LINK", popupCtx!!)
//        addMenuItem("LBL_SHOW_PROGRESS", popupCtx!!)
//        addMenuItem("CTX_COPY_URL", popupCtx!!)
//        addMenuItem("CTX_COPY_FILE", popupCtx!!)
//        addMenuItem("MENU_PROPERTIES", popupCtx!!)
        //    popupCtx.setInvoker(listView.getComponent());
        //    listView.installPopupMenu(popupCtx, this);
    }

    val selectedDownloads: List<DbRecord>
        get() = emptyList() // listView.getSelectedItems();

}
