package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.app.ytdlp.YtDlpFormats

/** The quality picker, from `yt-dlp -J` output shaped like YouTube's and like a generic page's. */
class YtDlpFormatsTest {

    private fun f(id: String, ext: String, v: String?, a: String?, h: Int?, size: Long?, tbr: Double) =
        """{"format_id":"$id","ext":"$ext","vcodec":${v?.let { "\"$it\"" }},"acodec":${a?.let { "\"$it\"" }},""" +
            """"height":$h,"filesize":$size,"tbr":$tbr,"protocol":"https"}"""

    private val youtube = """
        {"title":"فيديو تجربة","webpage_url":"https://www.youtube.com/watch?v=abc","duration":212.0,
         "extractor_key":"Youtube","ext":"mp4",
         "thumbnails":[{"url":"https://i.ytimg.com/vi/abc/hqdefault.jpg"},{"url":"https://i.ytimg.com/vi_webp/abc/maxresdefault.webp"}],
         "formats":[
           {"format_id":"sb0","ext":"mhtml","protocol":"mhtml","vcodec":"none","acodec":"none"},
           ${f("139", "m4a", "none", "mp4a.40.5", null, 1_000_000, 48.0)},
           ${f("140", "m4a", "none", "mp4a.40.2", null, 3_000_000, 129.0)},
           ${f("251", "webm", "none", "opus", null, 3_200_000, 140.0)},
           ${f("160", "mp4", "avc1.4d400c", "none", 144, 2_000_000, 80.0)},
           ${f("134", "mp4", "avc1.4d401e", "none", 360, 8_000_000, 300.0)},
           ${f("136", "mp4", "avc1.4d401f", "none", 720, 20_000_000, 1200.0)},
           ${f("398", "mp4", "av01.0.05M.08", "none", 720, 15_000_000, 1300.0)},
           ${f("137", "mp4", "avc1.640028", "none", 1080, 40_000_000, 2500.0)},
           ${f("248", "webm", "vp9", "none", 1080, 35_000_000, 2600.0)},
           ${f("313", "webm", "vp9", "none", 2160, 200_000_000, 16000.0)},
           ${f("18", "mp4", "avc1.42001E", "mp4a.40.2", 360, 9_000_000, 500.0)}
         ]}
    """.trimIndent()

    @Test
    fun youtube_mp4QualitiesMergedWithBestM4a_thenAudio() {
        val info = YtDlpFormats.parse(youtube, "x")
        assertEquals("فيديو تجربة", info.title)
        assertEquals(212L, info.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", info.thumbnail)

        val heights = info.options.filter { !it.audioOnly }.map { it.height }
        // 2160p is WebM only (cannot be merged without ffmpeg); 360p comes merged, not the old "18".
        assertEquals(listOf(1080, 720, 360, 144), heights)
        val hd = info.options.first()
        assertEquals(listOf("137", "140"), hd.formatIds)
        assertEquals(43_000_000L, hd.size)
        assertEquals("mp4", hd.ext)
        // H.264 is preferred over AV1 at the same height, for compatibility.
        assertEquals(listOf("136", "140"), info.options[1].formatIds)

        val audio = info.options.last()
        assertTrue(audio.audioOnly)
        assertEquals(listOf("140"), audio.formatIds)
        assertEquals("m4a", audio.ext)
    }

    @Test
    fun genericPage_singleFileWithoutCodecs_isOffered() {
        val info = YtDlpFormats.parse(
            """{"title":"clip","webpage_url":"http://site/page","formats":[
                {"format_id":"0","ext":"mp4","vcodec":null,"acodec":null,"protocol":"http"}]}""",
            "http://site/page"
        )
        assertEquals(1, info.options.size)
        assertEquals(listOf("0"), info.options[0].formatIds)
        assertEquals("mp4", info.options[0].ext)
    }

    @Test
    fun noUsableFormats_fallsBackToYtDlpBest() {
        val info = YtDlpFormats.parse("""{"title":"t","ext":"webm","formats":[]}""", "u")
        // No formats list: the top-level entry itself is the file.
        assertEquals(1, info.options.size)
    }

    @Test
    fun webmOnlySite_offersCompleteFilesAndAudio() {
        val info = YtDlpFormats.parse(
            """{"title":"t","formats":[
                ${f("v1", "webm", "vp9", "none", 720, null, 900.0)},
                ${f("a1", "webm", "none", "opus", null, null, 120.0)},
                ${f("c1", "mp4", "avc1", "mp4a.40.2", 480, null, 700.0)}]}""",
            "u"
        )
        assertEquals(listOf(listOf("c1"), listOf("a1")), info.options.map { it.formatIds })
    }
}
