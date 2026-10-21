package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class VixCloudExtractor : ExtractorApi() {
    override val mainUrl = "vixcloud.co"
    override val name = "VixCloud"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, name, "Streaming Community - VixCloud", callback)
    }

    companion object {
        private const val TAG = "VixCloudExtractor"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"

        /** Embed VixCloud/VixSrc -> master playlist m3u8 con token. */
        suspend fun extract(
            embedUrl: String,
            referer: String?,
            source: String,
            linkName: String,
            callback: (ExtractorLink) -> Unit
        ) {
            val headers = mapOf("User-Agent" to USER_AGENT) +
                (referer?.let { mapOf("Referer" to it) } ?: emptyMap())
            val response = app.get(embedUrl, headers = headers, interceptor = Cloudflare)
            val body = response.body.string()
            val host = response.url.toHttpUrl().host
            Cloudflare.check(host, body)

            val script = org.jsoup.Jsoup.parse(body).select("script")
                .find { it.data().contains("masterPlaylist") }
                ?.data()?.replace("\n", "\t")
                ?: throw Exception("masterPlaylist non trovato in $embedUrl")
            val json = JSONObject(getSanitisedScript(script))
            val master = json.getJSONObject("masterPlaylist")
            val params = master.getJSONObject("params")
            val playlistUrl = master.getString("url")
            val auth = "token=${params.getString("token")}&expires=${params.getString("expires")}"

            var url = if ("?b" in playlistUrl) {
                "${playlistUrl.replace("?b:1", "?b=1")}&$auth"
            } else {
                "$playlistUrl?$auth"
            }
            // h=1 sblocca il 1080p, ma solo quando l'embed lo concede: altrimenti 403
            val fhd = json.optBoolean("canPlayFHD", false)
            if (fhd) url += "&h=1"
            Log.d(TAG, "$source master playlist: $url")

            callback(
                newExtractorLink(source, linkName, url, ExtractorLinkType.M3U8) {
                    this.referer = "https://$host/"
                    // la qualita' massima del master: CloudStream mette davanti il link migliore
                    this.quality = if (fhd) Qualities.P1080.value else Qualities.P720.value
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
        }

        private fun getSanitisedScript(script: String): String {
            // Split by top-level assignments like window.xxx =
            val parts = Regex("""window\.(\w+)\s*=""")
                .split(script)
                .drop(1) // first split part is empty before first assignment

            val keys = Regex("""window\.(\w+)\s*=""")
                .findAll(script)
                .map { it.groupValues[1] }
                .toList()

            val jsonObjects = keys.zip(parts).map { (key, value) ->
                // Clean up the value
                val cleaned = value
                    .replace(";", "")
                    // Quote keys only inside objects
                    .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                    // Remove trailing commas before } or ]
                    .replace(Regex(""",(\s*[}\]])"""), "$1")
                    .trim()

                "\"$key\": $cleaned"
            }
            val finalObject =
                "{\n${jsonObjects.joinToString(",\n")}\n}"
                    .replace("'", "\"")

            return finalObject
        }
    }
}
