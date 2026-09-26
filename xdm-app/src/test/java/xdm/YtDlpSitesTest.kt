package xdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.app.ytdlp.YtDlpSites
import xdm.integration.ExtensionMessage
import xdm.integration.VideoHelper

class YtDlpSitesTest {
    @Test
    fun videoPages_areRecognised() {
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://youtube.com/shorts/abc123",
            "https://youtu.be/dQw4w9WgXcQ?t=10",
            "https://x.com/someone/status/1234567890",
            "https://www.instagram.com/reel/Cxyz/",
            "https://www.tiktok.com/@user/video/123",
            "https://vimeo.com/123456",
        ).forEach { assertTrue(it, YtDlpSites.isVideoPage(it)) }
    }

    @Test
    fun filesAndOrdinaryPages_areNot() {
        listOf(
            "https://www.youtube.com/",
            "https://www.youtube.com/@channel",
            "https://example.com/video.mp4",
            "https://github.com/yt-dlp/yt-dlp",
            "not a url",
        ).forEach { assertFalse(it, YtDlpSites.isVideoPage(it)) }
    }

    @Test
    fun youtubeStreamPieces_areNotListedAsVideos() {
        val piece = ExtensionMessage(
            url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&itag=140&range=0-6000",
            tabUrl = "https://www.youtube.com/watch?v=abc",
        )
        assertTrue(VideoHelper.isStreamPieceOfVideoSite(piece))
        val onVideoPage = ExtensionMessage(url = "https://video.twimg.com/a.mp4", tabUrl = "https://x.com/u/status/1")
        assertTrue(VideoHelper.isStreamPieceOfVideoSite(onVideoPage))
        val elsewhere = ExtensionMessage(url = "https://cdn.example.com/movie.mp4", tabUrl = "https://example.com/page")
        assertFalse(VideoHelper.isStreamPieceOfVideoSite(elsewhere))
    }

    @Test
    fun playlists_areRecognised() {
        val listPage = "https://www.youtube.com/playlist?list=PLabc_123"
        assertTrue(YtDlpSites.isVideoPage(listPage))
        assertTrue(YtDlpSites.isPlaylistOnly(listPage))
        assertTrue(YtDlpSites.hasPlaylist(listPage))
        val fromList = "https://www.youtube.com/watch?v=abc&list=PLabc_123&index=2"
        assertTrue(YtDlpSites.hasPlaylist(fromList))
        assertFalse(YtDlpSites.isPlaylistOnly(fromList))
        assertFalse(YtDlpSites.hasPlaylist("https://www.youtube.com/watch?v=abc"))
        assertFalse(YtDlpSites.hasPlaylist("https://example.com/page?list=abc"))
    }
}
