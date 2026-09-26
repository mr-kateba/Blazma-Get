package xdm.app.ytdlp

import java.net.URI

/**
 * Video pages that go to yt-dlp instead of a plain HTTP download (which would only fetch the page's
 * HTML). The same list lives in the browser extension (`videoPage()` in app.js), which shows its
 * "Download this video" button for them.
 */
object YtDlpSites {
    private val patterns = listOf(
        Regex("^https?://(www\\.|m\\.|music\\.)?youtube\\.com/(watch\\?.*v=|shorts/|live/|embed/|playlist\\?.*list=)", RegexOption.IGNORE_CASE),
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

    /** A page that has a playlist: YouTube's playlist page, or a video played from one (`&list=`). */
    fun hasPlaylist(url: String): Boolean =
        isVideoPage(url) && Regex("[?&]list=[\\w-]+", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** YouTube's playlist page itself, which has no single video to offer. */
    fun isPlaylistOnly(url: String): Boolean =
        Regex("^https?://(www\\.|m\\.|music\\.)?youtube\\.com/playlist\\?", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** True for a page of a known video site (not for a direct file link). */
    fun isVideoPage(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host.isNotEmpty() && patterns.any { it.containsMatchIn(url) }
    }
}
