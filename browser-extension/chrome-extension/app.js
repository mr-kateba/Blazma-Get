"use strict";
import Logger from './logger.js';
import RequestWatcher from './request-watcher.js';
import Connector from './connector.js';

/**
 * Video pages Blazma Get downloads with yt-dlp (quality picker), kept in sync with YtDlpSites.kt.
 */
const VIDEO_PAGES = [
    /^https?:\/\/(www\.|m\.|music\.)?youtube\.com\/(watch\?.*v=|shorts\/|live\/|embed\/|playlist\?.*list=)/i,
    /^https?:\/\/youtu\.be\/[\w-]+/i,
    /^https?:\/\/(www\.|mobile\.)?(twitter|x)\.com\/[^/]+\/status\/\d+/i,
    /^https?:\/\/(www\.)?instagram\.com\/(p|reel|reels|tv)\//i,
    /^https?:\/\/(www\.|vm\.|vt\.)?tiktok\.com\//i,
    /^https?:\/\/(www\.|m\.|web\.)?facebook\.com\/(.+\/videos\/|watch|reel\/|share\/)/i,
    /^https?:\/\/fb\.watch\//i,
    /^https?:\/\/(www\.)?vimeo\.com\/\d+/i,
    /^https?:\/\/(www\.)?dailymotion\.com\/video\//i,
    /^https?:\/\/(www\.)?twitch\.tv\/(videos\/|[^/]+\/clip\/)|^https?:\/\/clips\.twitch\.tv\//i,
    /^https?:\/\/(www\.)?reddit\.com\/r\/[^/]+\/comments\//i,
    /^https?:\/\/(www\.)?soundcloud\.com\/[^/]+\/[^/?#]+/i,
    /^https?:\/\/(www\.)?bilibili\.com\/video\//i,
];

export function videoPage(url) {
    return !!url && VIDEO_PAGES.some(re => re.test(url));
}

export default class App {
    constructor() {
        this.logger = new Logger();
        this.videoList = [];
        this.blockedHosts = [];
        this.fileExts = [];
        this.requestWatcher = new RequestWatcher(this.onRequestDataReceived.bind(this));
        this.tabsWatcher = [];
        this.userDisabled = false;
        this.appEnabled = false;
        this.onDownloadCreatedCallback = this.onDownloadCreated.bind(this);
        this.onDeterminingFilenameCallback = this.onDeterminingFilename.bind(this);
        this.onTabUpdateCallback = this.onTabUpdate.bind(this);
        this.activeTabId = -1;
        this.connector = new Connector(this.onMessage.bind(this), this.onDisconnect.bind(this));
    }

    start() {
        this.logger.log("starting...");
        this.starAppConnector();
        this.register();
        this.logger.log("started.");
    }

    starAppConnector() {
        this.connector.connect();
    }

    onMessage(msg) {
        this.logger.log("message from XDM");
        this.logger.log(msg);
        this.appEnabled = msg.enabled === true;
        this.fileExts = msg.fileExts;
        this.blockedHosts = msg.blockedHosts;
        this.tabsWatcher = msg.tabsWatcher;
        this.videoList = msg.videoList;
        this.requestWatcher.updateConfig({
            mediaExts: msg.requestFileExts,
            blockedHosts: msg.blockedHosts,
            matchingHosts: msg.matchingHosts,
            mediaTypes: msg.mediaTypes
        });
        this.updateActionIcon();
    }

    onDisconnect() {
        this.logger.log("Disconnected from native host!");
        this.logger.log("Disconnected...");
        this.updateActionIcon();
    }

    isMonitoringEnabled() {
        this.logger.log(this.appEnabled + " " + this.userDisabled);
        return this.appEnabled === true && this.userDisabled === false && this.connector.isConnected();
    }

    onRequestDataReceived(data) {
        //Streaming video data received, send to native messaging application
        this.logger.log("onRequestDataReceived");
        this.logger.log(data);
        this.isMonitoringEnabled() && this.connector.isConnected() && this.connector.postMessage("/media", data);
    }

    onDeterminingFilename(download, suggest) {
        this.logger.log("onDeterminingFilename");
        if (!this.isMonitoringEnabled()) {
            return;
        }
        this.logger.log(download);
        let url = download.finalUrl || download.url;
        this.logger.log(url);
        if (this.requestWatcher.wasPostRequest(download.url) ||
            this.requestWatcher.wasPostRequest(download.finalUrl)) {
            this.logger.log("Skipping POST-originated download: " + url);
            return;
        }
        if (this.isMonitoringEnabled() && this.shouldTakeOver(url, download.filename)) {
            chrome.downloads.cancel(
                download.id,
                () => chrome.downloads.erase({ id: download.id })
            );
            let referrer = download.referrer;
            if (!referrer && download.finalUrl !== download.url) {
                referrer = download.url;
            }
            this.triggerDownload(url, download.filename,
                referrer, download.fileSize, download.mime);
        }
    }

    onDownloadCreated(download) {
        this.logger.log("onDownloadCreated");
        this.logger.log(download);
    }

    onTabUpdate(tabId, changeInfo, tab) {
        // YouTube changes video without reloading: refresh the badge when the address changes.
        if (changeInfo.url) {
            this.updateActionIcon();
        }
        if (!this.isMonitoringEnabled()) {
            return;
        }
        if (changeInfo.title) {
            if (this.tabsWatcher &&
                this.tabsWatcher.find(t => tab.url.indexOf(t) > 0)) {
                this.logger.log("Tab changed: " + changeInfo.title + " => " + tab.url);
                try {
                    this.connector.postMessage("/tab-update", {
                        tabUrl: tab.url,
                        tabTitle: changeInfo.title
                    });
                } catch (ex) {
                    console.log(ex);
                }
            }
        }
    }

    register() {
        chrome.downloads.onCreated.addListener(
            this.onDownloadCreatedCallback
        );
        chrome.downloads.onDeterminingFilename.addListener(
            this.onDeterminingFilenameCallback
        );
        chrome.tabs.onUpdated.addListener(
            this.onTabUpdateCallback
        );
        chrome.runtime.onMessage.addListener(this.onPopupMessage.bind(this));
        this.requestWatcher.register();
        this.attachContextMenu();
        chrome.tabs.onActivated.addListener(this.onTabActivated.bind(this));
    }

    isSupportedProtocol(url) {
        if (!url) return false;
        let u = new URL(url);
        return u.protocol === 'http:' || u.protocol === 'https:';
    }

    shouldTakeOver(url, file) {
        let u = new URL(url);
        if (!this.isSupportedProtocol(url)) {
            return false;
        }
        let hostName = u.host;
        if (this.blockedHosts.find(item => hostName.indexOf(item) >= 0)) {
            return false;
        }
        let path = file || u.pathname;
        let upath = path.toUpperCase();
        // fileExts arrive without a leading dot; match on ".EXT" so names that merely
        // end in an extension substring (e.g. "...NEWEXE") don't false-positive.
        if (this.fileExts.find(ext => upath.endsWith("." + ext.toUpperCase()))) {
            return true;
        }
        return false;
    }

    // A video belongs to the given tab when its tabId matches, or when it has
    // no meaningful tabId (untabbed / background capture: missing, "-1" or "0"),
    // in which case it is shown in every tab.
    isVideoForTab(vid, tabId) {
        if (!vid.tabId || vid.tabId == '-1' || vid.tabId == '0') {
            return true;
        }
        return vid.tabId == tabId;
    }

    videosForTab(tabId) {
        if (!this.videoList) {
            return [];
        }
        return this.videoList.filter(vid => this.isVideoForTab(vid, tabId));
    }

    updateActionIcon() {
        chrome.action.setIcon({ path: this.getActionIcon() });
        chrome.action.setBadgeBackgroundColor({ color: "#FF6D00" });
        this.withActiveTab(tab => {
            let len = this.videosForTab(tab ? tab.id + "" : this.activeTabId).length;
            // A count of the videos found, or a play mark on a page the quality picker handles.
            let text = len > 0 ? len + "" : (tab && videoPage(tab.url) && this.connector.isConnected() ? "▶" : "");
            chrome.action.setBadgeText({ text: text });
        });
    }

    getActionIconName(icon) {
        return this.isMonitoringEnabled() ? icon + ".png" : icon + "-mono.png";
    }

    getActionIcon() {
        return {
            "16": this.getActionIconName("icon16"),
            "48": this.getActionIconName("icon48"),
            "128": this.getActionIconName("icon128")
        }
    }

    triggerDownload(url, file, referer, size, mime) {
        chrome.cookies.getAll({ "url": url }, cookies => {
            let cookieStr = undefined;
            if (cookies) {
                cookieStr = cookies.map(cookie => cookie.name + "=" + cookie.value).join("; ");
            }
            let requestHeaders = { "User-Agent": [navigator.userAgent] };
            if (referer) {
                requestHeaders["Referer"] = [referer];
            }
            let responseHeaders = {};
            if (size) {
                let fz = +size;
                if (fz > 0) {
                    responseHeaders["Content-Length"] = [fz];
                }
            }
            if (mime) {
                responseHeaders["Content-Type"] = [mime];
            }
            let data = {
                url: url,
                cookie: cookieStr,
                requestHeaders: requestHeaders,
                responseHeaders: responseHeaders,
                filename: file,
                fileSize: size,
                mimeType: mime
            };
            this.logger.log(data);
            this.connector.postMessage("/download", data);
        });
    }

    diconnect() {
        this.onDisconnect();
    }

    /** The active tab of the focused window, or null. */
    withActiveTab(callback) {
        chrome.tabs.query({ active: true, currentWindow: true }, tabs => callback(tabs && tabs[0] ? tabs[0] : null));
    }

    onPopupMessage(request, sender, sendResponse) {
        this.logger.log(request.type);
        if (request.type === "stat") {
            // Resolve the active tab fresh: the MV3 service worker can be torn
            // down, resetting this.activeTabId, so don't rely on it here.
            this.withActiveTab(tab => {
                let tabId = tab ? tab.id + "" : this.activeTabId;
                this.activeTabId = tabId;
                sendResponse({
                    enabled: this.isMonitoringEnabled(),
                    connected: this.connector.isConnected(),
                    userDisabled: this.userDisabled,
                    list: this.videosForTab(tabId),
                    videoPage: !!tab && videoPage(tab.url),
                    tabUrl: tab ? tab.url : null,
                    tabTitle: tab ? tab.title : null,
                });
            });
            return true; // keep the message channel open for the async response
        }
        else if (request.type === "ytdl") {
            // "Download this video": the app looks the page up with yt-dlp and shows the qualities.
            this.withActiveTab(tab => {
                if (tab && this.isSupportedProtocol(tab.url)) {
                    this.connector.postMessage("/ytdl", { url: tab.url, tabTitle: tab.title });
                }
            });
        }
        else if (request.type === "reload") {
            // Videos are found from the page's own requests; a reload makes it request them again.
            this.withActiveTab(tab => tab && chrome.tabs.reload(tab.id));
        }
        else if (request.type === "show") {
            this.connector.postQuiet("/show", {});
        }
        else if (request.type === "launch") {
            // Registered by the Windows installer; the app shows its window when started this way.
            chrome.tabs.create({ url: "blazma-get://open" });
        }
        else if (request.type === "cmd") {
            this.userDisabled = request.enabled === false;
            this.logger.log("request.enabled:" + request.enabled);
            if (request.enabled && !this.connector.isConnected()) {
                this.connector.launchApp();
                return;
            }
            this.updateActionIcon();
        }
        else if (request.type === "vid") {
            let vid = request.itemId;
            this.connector.postMessage("/vid", {
                vid: vid + "",
            });
        }
        else if (request.type === "clear") {
            this.connector.postMessage("/clear", {});
        }
    }

    sendLinkToXDM(info, tab) {
        let url = info.linkUrl;
        if (!this.isSupportedProtocol(url)) {
            url = info.srcUrl;
        }
        if (!this.isSupportedProtocol(url)) {
            url = info.pageUrl;
        }
        if (!this.isSupportedProtocol(url)) {
            return;
        }
        this.triggerDownload(url, null, info.pageUrl, null, null);
    }

    sendImageToXDM(info, tab) {
        let url = info.srcUrl;
        if (!this.isSupportedProtocol(url))
            url = info.linkUrl;
        if (!this.isSupportedProtocol(url)) {
            url = info.pageUrl;
        }
        if (!this.isSupportedProtocol(url)) {
            return;
        }
        this.triggerDownload(url, null, info.pageUrl, null, null);
    }

    onMenuClicked(info, tab) {
        if (info.menuItemId == "download-any-link") {
            this.sendLinkToXDM(info, tab);
        }
        if (info.menuItemId == "download-image-link") {
            this.sendImageToXDM(info, tab);
        }
    }

    /** Right-click menu text, in Arabic when the browser runs in Arabic. */
    menuTitle(kind) {
        const arabic = (chrome.i18n.getUILanguage() || "").toLowerCase().startsWith("ar");
        if (kind === "image") {
            return arabic ? "تحميل الصورة بـ Blazma Get" : "Download image with Blazma Get";
        }
        return arabic ? "تحميل بـ Blazma Get" : "Download with Blazma Get";
    }

    attachContextMenu() {
        chrome.contextMenus.create({
            id: 'download-any-link',
            title: this.menuTitle("link"),
            contexts: ["link", "video", "audio", "all"]
        });

        chrome.contextMenus.create({
            id: 'download-image-link',
            title: this.menuTitle("image"),
            contexts: ["image"]
        });

        chrome.contextMenus.onClicked.addListener(this.onMenuClicked.bind(this));
    }

    onTabActivated(activeInfo) {
        this.activeTabId = activeInfo.tabId + "";
        this.logger.log("Active tab: " + this.activeTabId);
        this.updateActionIcon();
    }
}
