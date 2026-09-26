package xdm.app.ytdlp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One entry of yt-dlp's `formats` list, with only what the quality picker needs. */
data class YtFormat(
    val id: String,
    val ext: String,
    val vcodec: String?,
    val acodec: String?,
    val height: Int?,
    val size: Long?,
    val tbr: Double?,
    val protocol: String?,
) {
    val hasVideo get() = vcodec != null && vcodec != "none"
    val hasAudio get() = acodec != null && acodec != "none"

    /** Direct files from generic pages come without codec details: a complete file. */
    val isComplete get() = (hasVideo && hasAudio) || (vcodec == null && acodec == null)
}

/** A choice in the quality picker: one yt-dlp format, or video + audio to merge into MP4. */
data class DownloadOption(
    val audioOnly: Boolean,
    val height: Int?,
    val formatIds: List<String>,
    val ext: String,
    val size: Long?,
)

data class VideoInfo(
    val title: String,
    val pageUrl: String,
    val durationSeconds: Long?,
    val thumbnail: String?,
    val site: String?,
    val options: List<DownloadOption>,
)

object YtDlpFormats {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses `yt-dlp -J` output. */
    fun parse(jsonText: String, fallbackUrl: String): VideoInfo {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val formats = (root["formats"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::toFormat) } ?: emptyList()
        val single = if (formats.isEmpty()) toFormat(root) else null
        val options = choose(if (single != null) listOf(single) else formats, root.str("ext"))
        return VideoInfo(
            title = root.str("title")?.takeIf { it.isNotBlank() } ?: "video",
            pageUrl = root.str("webpage_url") ?: fallbackUrl,
            durationSeconds = (root["duration"] as? JsonPrimitive)?.doubleOrNull?.toLong(),
            thumbnail = pickThumbnail(root),
            site = root.str("extractor_key") ?: root.str("extractor"),
            options = options,
        )
    }

    private fun toFormat(o: JsonObject): YtFormat? {
        val id = o.str("format_id") ?: return null
        val ext = o.str("ext") ?: return null
        // Storyboard images and DRM-protected streams cannot be downloaded as video.
        if (ext == "mhtml" || o.str("protocol") == "mhtml") return null
        if ((o["has_drm"] as? JsonPrimitive)?.booleanOrNull == true) return null
        return YtFormat(
            id = id,
            ext = ext,
            vcodec = o.str("vcodec"),
            acodec = o.str("acodec"),
            height = (o["height"] as? JsonPrimitive)?.longOrNull?.toInt(),
            size = (o["filesize"] as? JsonPrimitive)?.longOrNull ?: (o["filesize_approx"] as? JsonPrimitive)?.longOrNull,
            tbr = (o["tbr"] as? JsonPrimitive)?.doubleOrNull,
            protocol = o.str("protocol"),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JPEG thumbnail if there is one (Swing cannot show WebP), else the default one. */
    private fun pickThumbnail(root: JsonObject): String? {
        val list = (root["thumbnails"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("url") } ?: emptyList()
        return list.lastOrNull { it.contains(".jpg") } ?: root.str("thumbnail")?.takeIf { !it.contains(".webp") }
    }

    /**
     * The quality list, best first:
     * - each height of MP4 video (H.264 preferred, it plays everywhere) merged with the best M4A
     *   audio, which the in-house transmuxer can join without ffmpeg;
     * - complete files for heights not covered that way (sites that serve one file per quality);
     * - the best audio on its own.
     * WebM-only video is left out: it cannot be merged without ffmpeg.
     */
    fun choose(formats: List<YtFormat>, defaultExt: String?): List<DownloadOption> {
        val options = ArrayList<DownloadOption>()
        val bestM4a = formats.filter { !it.hasVideo && it.hasAudio && (it.ext == "m4a" || it.ext == "mp4") }
            .maxByOrNull { it.tbr ?: 0.0 }
        val covered = HashSet<Int>()
        if (bestM4a != null) {
            formats.filter { it.hasVideo && !it.hasAudio && it.ext == "mp4" && it.height != null }
                .groupBy { it.height!! }
                .toSortedMap(compareByDescending { it })
                .forEach { (height, group) ->
                    val video = group.maxWith(compareBy<YtFormat>({ it.vcodec!!.startsWith("avc1") }, { it.tbr ?: 0.0 }))
                    val size = if (video.size != null && bestM4a.size != null) video.size + bestM4a.size else null
                    options += DownloadOption(false, height, listOf(video.id, bestM4a.id), "mp4", size)
                    covered += height
                }
        }
        formats.filter { it.isComplete && (it.height == null || it.height !in covered) }
            .groupBy { it.height }
            .toSortedMap(compareByDescending<Int?> { it ?: -1 })
            .forEach { (height, group) ->
                val best = group.maxWith(compareBy<YtFormat>({ it.ext == "mp4" }, { it.tbr ?: 0.0 }))
                options += DownloadOption(false, height, listOf(best.id), best.ext, best.size)
            }
        val audio = bestM4a ?: formats.filter { !it.hasVideo && it.hasAudio }.maxByOrNull { it.tbr ?: 0.0 }
        audio?.let { options += DownloadOption(true, null, listOf(it.id), it.ext, it.size) }
        if (options.isEmpty()) options += DownloadOption(false, null, listOf("b"), defaultExt ?: "mp4", null)
        return options.sortedWith(compareBy<DownloadOption>({ it.audioOnly }, { -(it.height ?: 0) }))
    }
}
