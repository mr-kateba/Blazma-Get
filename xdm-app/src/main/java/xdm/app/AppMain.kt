package xdm.app

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import org.conscrypt.Conscrypt
import xdm.core.downloaders.TaskInfoDB
import xdm.core.util.Logger
import java.awt.Insets
import java.io.File
import java.security.Security
import javax.swing.UIManager

//java -XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions -XX:MinMetaspaceFreeRatio=1 -XX:MaxMetaspaceFreeRatio=2 -XX:ShenandoahGuaranteedGCInterval=30 -XX:ShenandoahUncommitDelay=10 -XX:+ClassUnloading -XX:+ClassUnloadingWithConcurrentMark  -XX:-DisableExplicitGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xms4m -XX:-AlwaysActAsServerClassMachine -jar /Users/subhro/Documents/xdm-app.jar
//java.exe  -XX:+UseZGC -XX:MinMetaspaceFreeRatio=1 -XX:MaxMetaspaceFreeRatio=2 -XX:ZCollectionInterval=30 -XX:ZUncommitDelay=10 -XX:+ClassUnloading -XX:+ClassUnloadingWithConcurrentMark -XX:-AlwaysPreTouch -XX:-ZProactive -XX:-DisableExplicitGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xms4m -jar C:\Users\subhro\Desktop\xdm-app_5.jar
//xdm-app -Xms5m -XX:MaxHeapFree=5m -XX:MaximumYoungGenerationSizePercent=5
object AppMain {
    init {
        System.setProperty("http.KeepAlive.remainingData", "0")
        System.setProperty("http.KeepAlive.queuedConnections", "0")
        System.setProperty("awt.useSystemAAFontSettings", "lcd")
        System.setProperty("swing.aatext", "true")
        System.setProperty("sun.java2d.d3d", "false")
        System.setProperty("sun.java2d.opengl", "false")
        System.setProperty("sun.java2d.xrender", "false")
        System.setProperty("sun.java2d.metal", "false")
        System.setProperty("sun.java2d.pmoffscreen", "false")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val homeDir = System.getProperty("user.home")
        val configDir = "$homeDir${File.separatorChar}$CONFIG_DIR"
        val tempDir = "$configDir${File.separatorChar}tmp"

        File(configDir).mkdirs()
        Logger.init(File(configDir))

        Logger.info("Use OKHttp..")
        Logger.info("loading...")
        Logger.info(System.getProperty("java.version") + " " + System.getProperty("os.version"))

        installConscrypt()

        System.setProperty("apple.awt.application.appearance", "system")
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", APP_NAME)
        System.setProperty("apple.awt.enableTemplateImages", "true")

        Logger.info("Creating dir: $tempDir")
        File(tempDir).mkdirs()

        val appDB = AppDB(configDir)
        val taskDB = TaskInfoDB(configDir)

        if (!args.contains("--no-gc")) {
            Logger.info("Registering periodic GC")
            Thread {
                while (true) {
                    Thread.sleep(15000)
                    //Logger.info("XDM", "Triggering GC")
                    System.gc()
                }
            }.apply {
                name = "periodic-gc"
                isDaemon = true // must never keep the JVM alive on its own
            }.start()
        }

        AppContext.apply {
            db = appDB
            app = AppInstance()
            config = AppConfig(configDir)
            queue = QueueManager()
            platform = PlatformInvoke()
            downloader = DownloadManager(appDB = appDB, taskInfoDB = taskDB, configDir = configDir)
            videoTracker = CapturedVideoTracker()
            taskInfoDB = taskDB
            scheduler = DownloadScheduler(appDB, configDir)
        }.init(args, configDir, tempDir)

        AppContext.scheduler.start()
    }

    /**
     * Makes Conscrypt the highest-priority security provider so all TLS (OkHttp in xdm-core included)
     * uses it instead of the JDK's SunJSSE. OkHttp picks its platform once, on first use, and only
     * selects Conscrypt when it is provider #1, so this must run before any HTTP client is created.
     * Falls back to the JDK provider if the native library is unavailable on this OS/arch.
     */
    private fun installConscrypt() {
        try {
            if (Conscrypt.isAvailable()) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                val v = Conscrypt.version()
                Logger.info("XDM", "Using Conscrypt ${v.major()}.${v.minor()}.${v.patch()} for TLS")
            } else {
                Logger.info("XDM", "Conscrypt unavailable on this platform, using JDK TLS")
            }
        } catch (e: Throwable) {
            Logger.info("XDM", "Failed to install Conscrypt, using JDK TLS: $e")
        }
    }

    /**
     * Installs the FlatLaf look-and-feel for the configured theme. Must be called
     * before any Swing UI is created (i.e. before [AppInstance.run]).
     */
    @JvmStatic
    fun setupTheme(theme: String, lang: String = "en") {
        val dark = theme.lowercase() != "light"
        // Blazma design tokens (see ui/Blazma.kt): the same background, panels, text and orange
        // as Blazma Boost, blazma.nt and blazma.online, with rounded controls.
        val shared = mapOf(
            "Component.arc" to "10",
            "Button.arc" to "12",
            "Button.borderWidth" to "1",
            "TextComponent.arc" to "10",
            "ProgressBar.arc" to "999",
            "ScrollBar.thumbArc" to "999",
            "ScrollBar.thumbInsets" to "2,2,2,2",
            "ScrollBar.width" to "10",
            "PopupMenu.borderCornerRadius" to "10",
            "Popup.borderCornerRadius" to "10",
            // Plain arrow buttons like the spinners, instead of the macOS orange block.
            "ComboBox.background" to "@componentBackground",
            "ComboBox.buttonBackground" to "@buttonBackground",
            "ComboBox.buttonArrowColor" to "@foreground",
        )
        FlatLaf.setGlobalExtraDefaults(
            shared + if (dark) mapOf(
                "@accentColor" to "#FF6D00",
                "@background" to "#121216",
                "@foreground" to "#F2F2F5",
                "@componentBackground" to "#1C1C22",
                "@buttonBackground" to "#1C1C22",
                "@disabledForeground" to "#A0A0AB",
                "Component.borderColor" to "#2A2A33",
                // Blazma Boost's buttons: a light outline around a dark key.
                "Button.borderColor" to "#C8C8CE",
                "Button.hoverBorderColor" to "#F2F2F5",
                "Button.hoverBackground" to "#2E2E38",
                "Button.pressedBackground" to "#3A3A45",
                "Separator.foreground" to "#2A2A33",
                "Table.background" to "#121216",
                "List.background" to "#121216",
                "ScrollPane.background" to "#121216",
            ) else mapOf(
                "@accentColor" to "#E65100",
                "@background" to "#F7F7F7",
                "@foreground" to "#232629",
                "Button.borderColor" to "#5A5D62",
                "Button.hoverBackground" to "#FFE0C2",
                "Table.background" to "#F7F7F7",
                "List.background" to "#F7F7F7",
            )
        )
        UiLocale.registerFont(lang)?.let { FlatLaf.setPreferredFontFamily(it) }
        if (dark) FlatMacDarkLaf.setup() else FlatMacLightLaf.setup()
        UIManager.put("TableHeader.cellMargins", Insets(0, 10, 0, 0))
        UIManager.put("SplitPaneDivider.gripDotCount", 0)
        UIManager.put("SplitPane.dividerSize", 10)
    }
}
