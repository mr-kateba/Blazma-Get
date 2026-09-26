package xdm.app

import java.net.URI

/** Finds download addresses in text from the clipboard or a drag and drop. */
object DownloadLinks {
    private val urlPattern = Regex("""(?i)\b(?:https?|ftp)://[^\s"'<>]+""")

    /** Every http/https/ftp address in [text], in order, without duplicates. */
    fun extract(text: String): List<String> =
        urlPattern.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', ';') }.distinct().toList()

    /**
     * True when [url] points at a file with one of [extensions] (compared without case or dot),
     * e.g. `https://site/files/game.zip?token=1` for `zip`. Web pages (`.html`, no extension) are not
     * downloads, which keeps the clipboard monitor quiet when the user copies ordinary links.
     */
    fun looksLikeDownload(url: String, extensions: Collection<String>): Boolean {
        val path = runCatching { URI(url).path }.getOrNull() ?: return false
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return false
        val ext = name.substringAfterLast('.').lowercase()
        return extensions.any { it.trim().trimStart('.').lowercase() == ext }
    }
}
