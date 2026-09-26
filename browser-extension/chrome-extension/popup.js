"use strict";
// The toolbar window: in Arabic when the browser is, in the Blazma design, and live while open.

const AR = ((chrome.i18n && chrome.i18n.getUILanguage()) || navigator.language || "").toLowerCase().startsWith("ar");

const TEXT = AR ? {
    online: "متصل",
    offline: "غير متصل",
    offlineTitle: "Blazma Get مو شغّال",
    offlineHint: "شغّل البرنامج عشان الإضافة تقدر ترسل له التحميلات.",
    launch: "افتح Blazma Get",
    thisVideo: "فيديو هذي الصفحة",
    downloadVideo: "حمّل هذا الفيديو",
    qualityHint: "تختار الجودة (مثل 1080p) أو صوت فقط في النافذة اللي بتطلع.",
    found: "الملفات اللي لقيناها",
    emptyTitle: "ما لقينا فيديو في هذي الصفحة",
    emptyHint: "شغّل الفيديو، أو اضغط تحديث الصفحة.",
    reload: "تحديث الصفحة",
    clear: "مسح القائمة",
    monitor: "التقاط التحميلات",
    openApp: "افتح البرنامج",
    sent: "انفتحت نافذة التحميل في Blazma Get",
    reloading: "جاري تحديث الصفحة...",
    cleared: "انمسحت القائمة",
} : {
    online: "Connected",
    offline: "Not connected",
    offlineTitle: "Blazma Get is not running",
    offlineHint: "Start the app so the extension can hand it your downloads.",
    launch: "Open Blazma Get",
    thisVideo: "Video on this page",
    downloadVideo: "Download this video",
    qualityHint: "Pick the quality (e.g. 1080p) or audio only in the window that opens.",
    found: "Found on this page",
    emptyTitle: "No video found on this page",
    emptyHint: "Play the video, or click Reload page.",
    reload: "Reload page",
    clear: "Clear list",
    monitor: "Catch downloads",
    openApp: "Open app",
    sent: "The download window opened in Blazma Get",
    reloading: "Reloading the page...",
    cleared: "List cleared",
};

const ICON_VIDEO = '<svg viewBox="0 0 24 24"><path d="M16 4a1 1 0 0 1 1 1v4.2l5.21-3.65a.5.5 0 0 1 .79.41v12.08a.5.5 0 0 1-.79.41L17 14.8V19a1 1 0 0 1-1 1H2a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h14zm-1 2H3v12h12V6zm-4.5 2 3.5 4h-2.5v4h-2v-4H7l3.5-4z"/></svg>';
const ICON_GO = '<svg viewBox="0 0 24 24"><path d="M13 10h5l-6 6-6-6h5V3h2v7zM4 19h16v-7h2v8a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-8h2v7z"/></svg>';

class Popup {
    run() {
        document.addEventListener("DOMContentLoaded", () => this.init());
    }

    init() {
        document.documentElement.lang = AR ? "ar" : "en";
        document.documentElement.dir = AR ? "rtl" : "ltr";
        document.querySelectorAll("[data-t]").forEach(el => el.textContent = TEXT[el.dataset.t]);
        this.listKey = null;

        document.getElementById("downloadPage").addEventListener("click", () => {
            chrome.runtime.sendMessage({ type: "ytdl" });
            this.toast(TEXT.sent);
            setTimeout(() => window.close(), 900);
        });
        document.getElementById("reload").addEventListener("click", () => {
            chrome.runtime.sendMessage({ type: "reload" });
            this.listKey = null;
            this.toast(TEXT.reloading);
        });
        document.getElementById("clear").addEventListener("click", () => {
            chrome.runtime.sendMessage({ type: "clear" });
            this.toast(TEXT.cleared);
        });
        document.getElementById("monitor").addEventListener("change", e => {
            chrome.runtime.sendMessage({ type: "cmd", enabled: e.target.checked });
        });
        document.getElementById("openApp").addEventListener("click", () => {
            chrome.runtime.sendMessage({ type: "show" });
            window.close();
        });
        document.getElementById("launch").addEventListener("click", () => {
            chrome.runtime.sendMessage({ type: "launch" });
        });

        this.refresh();
        // Live while open: videos appear as the page plays them, no need to reopen the window.
        setInterval(() => this.refresh(), 1500);
    }

    refresh() {
        chrome.runtime.sendMessage({ type: "stat" }, response => {
            if (chrome.runtime.lastError || !response) return;
            this.render(response);
        });
    }

    render(r) {
        const status = document.getElementById("status");
        status.textContent = r.connected ? TEXT.online : TEXT.offline;
        status.className = "status " + (r.connected ? "on" : "off");
        document.getElementById("offline").hidden = r.connected;
        document.getElementById("online").hidden = !r.connected;
        document.getElementById("monitor").checked = !r.userDisabled;
        document.getElementById("openApp").hidden = !r.connected;

        const page = document.getElementById("page");
        page.hidden = !r.videoPage;
        if (r.videoPage) {
            document.getElementById("pageTitle").textContent = r.tabTitle || r.tabUrl || "";
        }

        const list = r.list || [];
        document.getElementById("count").textContent = list.length ? String(list.length) : "";
        document.getElementById("empty").hidden = list.length > 0 || r.videoPage;
        // On a video page the button above is the way to download; an empty list adds nothing there.
        document.querySelector(".section-title").hidden = list.length === 0 && r.videoPage;
        const key = JSON.stringify(list.map(v => [v.id, v.text, v.info]));
        if (key === this.listKey) return;
        this.listKey = key;
        const ul = document.getElementById("list");
        ul.textContent = "";
        list.slice().reverse().forEach(item => ul.appendChild(this.row(item)));
    }

    row(item) {
        const li = document.createElement("li");
        const badge = document.createElement("span");
        badge.className = "badge";
        badge.innerHTML = ICON_VIDEO;
        const text = document.createElement("div");
        text.className = "item-text";
        const name = document.createElement("div");
        name.className = "item-name";
        name.textContent = item.text;
        name.title = item.text;
        const info = document.createElement("div");
        info.className = "item-info";
        info.textContent = item.info || "";
        text.append(name, info);
        const go = document.createElement("span");
        go.className = "go";
        go.innerHTML = ICON_GO;
        li.append(badge, text, go);
        li.addEventListener("click", () => {
            chrome.runtime.sendMessage({ type: "vid", itemId: item.id });
            this.toast(TEXT.sent);
        });
        return li;
    }

    toast(message) {
        const t = document.getElementById("toast");
        t.textContent = message;
        t.hidden = false;
        clearTimeout(this.toastTimer);
        this.toastTimer = setTimeout(() => t.hidden = true, 1800);
    }
}

new Popup().run();
