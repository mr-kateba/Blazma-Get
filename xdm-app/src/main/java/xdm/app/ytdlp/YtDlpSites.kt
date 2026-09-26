package xdm.app.ytdlp

import java.net.URI

/**
 * Video pages that go to yt-dlp instead of a plain HTTP download (which would only fetch the page's
 * HTML). The same list lives in the browser extension (`videoPage()` in app.js), which shows its
 * "Download this video" button for them.
 */
object YtDlpSites {
    private val patterns = listOf(
        Regex("^https?://(www\\.|m\\.|music\\.)?youtube\\.com/(watch\\?.*v=|shorts/|live/|embed/)", RegexOption.IGNORE_CASE),
        Regex("^https?://youtu\\.be/[\\w-]+", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.|mobile\\.)?(twitter|x)\\.com/[^/]+/status/\\d+", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.)?instagram\\.com/(p|reel|reels|tv)/", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.|vm\\.|vt\\.)?tiktok\\.com/", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.|m\\.|web\\.)?facebook\\.com/(.+/videos/|watch|reel/|share/)", RegexOption.IGNORE_CASE),
        Regex("^https?://fb\\.watch/", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.)?vimeo\\.com/\\d+", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.)?dailymotion\\.com/video/", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.)?twitch\\.tv/(videos/|[^/]+/clip/)|^https?://clips\\.twitch\\.tv/", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.)?reddit\\.com/r/[^/]+/comments/", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.)?soundcloud\\.com/[^/]+/[^/?#]+", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.)?bilibili\\.com/video/", RegexOption.IGNORE_CASE),
    )

    /** True for a page of a known video site (not for a direct file link). */
    fun isVideoPage(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host.isNotEmpty() && patterns.any { it.containsMatchIn(url) }
    }
}
