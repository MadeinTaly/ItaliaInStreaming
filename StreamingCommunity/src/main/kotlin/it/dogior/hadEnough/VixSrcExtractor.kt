package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * vixsrc.to/{movie|tv}/... e' un'app Next.js renderizzata lato client; /api/... restituisce
 * subito l'embed, che e' lo stesso player di VixCloud ma con canPlayFHD attivo (1080p).
 * Niente WebView: due richieste invece di ~15 s di pagina caricata.
 */
class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vixsrc.to"
    override val name = "VixSrc"
    override val requiresReferer = false

    private data class ApiResponse(val src: String)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // url: https://vixsrc.to/movie/{tmdb}?lang=it oppure /tv/{tmdb}/{s}/{e}?lang=it
        val apiUrl = url.replace("$mainUrl/", "$mainUrl/api/")
        val api = app.get(apiUrl, referer = "$mainUrl/", interceptor = Cloudflare)
        val body = api.body.string()
        Cloudflare.check("vixsrc.to", body)
        val src = parseJson<ApiResponse>(body).src
        VixCloudExtractor.extract(
            embedUrl = mainUrl + src,
            referer = "$mainUrl/",
            source = name,
            linkName = "Streaming Community - VixSrc",
            callback = callback
        )
    }
}
