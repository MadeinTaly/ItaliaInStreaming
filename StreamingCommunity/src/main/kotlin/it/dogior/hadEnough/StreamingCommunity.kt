package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StreamingCommunity(
    override var lang: String = "it",
    private val showLogo: Boolean = true
) : MainAPI() {
    private val siteRootUrl = "https://streamingcommunityz.build/"
    private val cdnHost = "cdn.streamingcommunityz.build"
    private val setupLock = Mutex()
    private var inertiaVersion = ""
    private var decodedXsrfToken = ""
    private val headers = mapOf(
        "Cookie" to "",
        "X-Inertia" to true.toString(),
        "X-Inertia-Version" to inertiaVersion,
        "X-Requested-With" to "XMLHttpRequest",
    ).toMutableMap()

    override var mainUrl = siteRootUrl + lang
    override var name = Companion.name
    override var supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override val hasMainPage = true

    companion object {
        var name = "StreamingCommunity"
        val TAG = "SCommunity"
        const val BLOCKED_MESSAGE =
            "Cloudflare blocca questo IP (VPN?). Cambia server VPN o escludi CloudStream dalla VPN"
    }

    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbApiKey = BuildConfig.TMDB_API
    
    private val tmdbHeaders = mapOf(
        "Authorization" to "Bearer $tmdbApiKey",
        "Accept" to "application/json"
    )

    override val mainPage = mainPageOf(
        SliderFetchRequestSlider(name = "top10", genre = null).toJson() to "Slider",
        SliderFetchRequestSlider(name = "trending", genre = null).toJson() to "Slider",
        SliderFetchRequestSlider(name = "latest", genre = null).toJson() to "Slider",
        SliderFetchRequestSlider(name = "upcoming", genre = null).toJson() to "Slider",
        GenreRequest(nameEN = "Animation", nameIT = "Animazione", id = 19).toJson() to "Genre",
        GenreRequest(nameEN = "Adventure", nameIT = "Avventura", id = 11).toJson() to "Genre",
        GenreRequest(nameEN = "Action", nameIT = "Azione", id = 4).toJson() to "Genre",
        GenreRequest(nameEN = "Comedy", nameIT = "Commedia", id = 12).toJson() to "Genre",
        GenreRequest(nameEN = "Crime", nameIT = "Crime", id = 2).toJson() to "Genre",
        GenreRequest(nameEN = "Documentary", nameIT = "Documentario", id = 24).toJson() to "Genre",
        GenreRequest(nameEN = "Drama", nameIT = "Dramma", id = 1).toJson() to "Genre",
        GenreRequest(nameEN = "Family", nameIT = "Famiglia", id = 16).toJson() to "Genre",
        GenreRequest(nameEN = "Science Fiction", nameIT = "Fantascienza", id = 10).toJson() to "Genre",
        GenreRequest(nameEN = "Fantasy", nameIT = "Fantasy", id = 8).toJson() to "Genre",
        GenreRequest(nameEN = "Horror", nameIT = "Horror", id = 7).toJson() to "Genre",
        GenreRequest(nameEN = "Reality", nameIT = "Reality", id = 18).toJson() to "Genre",
        GenreRequest(nameEN = "Romance", nameIT = "Romance", id = 15).toJson() to "Genre",
        GenreRequest(nameEN = "Thriller", nameIT = "Thriller", id = 5).toJson() to "Genre",
    )

    private fun isHtmlPayload(payload: String): Boolean {
        val trimmed = payload.trimStart()
        return trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)
    }

    private fun extractInertiaPageJson(html: String): String? {
        val dataPageRaw = org.jsoup.Jsoup.parse(html).selectFirst("#app")?.attr("data-page")
        if (dataPageRaw.isNullOrBlank()) return null
        return Parser.unescapeEntities(dataPageRaw, true)
    }

    private fun parseInertiaPayload(payload: String, logContext: String): InertiaResponse? {
        if (payload.isBlank()) {
            Log.e(TAG, "$logContext: empty payload")
            return null
        }
        if (isHtmlPayload(payload)) {
            Log.e(TAG, "$logContext: expected JSON but received HTML payload")
            return null
        }
        return runCatching { parseJson<InertiaResponse>(payload) }
            .onFailure { Log.e(TAG, "$logContext: invalid JSON payload - ${it.message}") }
            .getOrNull()
    }

    private fun parseBrowseTitles(payload: String, logContext: String): List<Title> {
        val jsonPayload = if (isHtmlPayload(payload)) {
            Log.e(TAG, "$logContext: received HTML payload, attempting embedded data-page fallback")
            extractInertiaPageJson(payload) ?: return emptyList()
        } else {
            payload
        }

        val result = parseInertiaPayload(jsonPayload, logContext) ?: return emptyList()
        return result.props.titles ?: emptyList()
    }

    private fun parseSliderFetchSections(payload: String): HomePageList? {
        if (payload.isBlank()) return null
        val trimmedPayload = payload.trimStart()
        if (trimmedPayload.startsWith("{") || trimmedPayload.contains("\"message\"")) {
            Log.e(
                TAG,
                "Sliders fetch: received error object instead of slider array: ${payload.take(300)}"
            )
            return null
        }
        if (isHtmlPayload(payload)) {
            Log.e(TAG, "Sliders fetch: expected JSON array but received HTML payload")
            return null
        }

        val slider = runCatching { parseJson<List<Slider>>(payload) }
            .onFailure { Log.e(TAG, "Sliders fetch: invalid JSON payload - ${it.message}") }
            .getOrNull()?.first()
            ?: return null
        val items = searchResponseBuilder(slider.titles)
        if (items.isEmpty()) return null
        return HomePageList(
            name = slider.label.ifBlank { slider.name },
            list = items,
            isHorizontalImages = false
        )
    }

    /** Blocco duro di Cloudflare: messaggio leggibile in CloudStream invece di liste vuote. */
    private fun blockedCheck(url: String, body: String) {
        try {
            Cloudflare.check(url.toHttpUrl().host, body)
        } catch (e: NetworkBlockedException) {
            throw ErrorLoadingException(BLOCKED_MESSAGE)
        }
    }

    private suspend fun ensureHeaders() {
        if (inertiaVersion.isNotEmpty()) return
        setupLock.withLock { if (inertiaVersion.isEmpty()) setupHeaders() }
    }

    private suspend fun setupHeaders() {
        val response = app.get("$mainUrl/archive", interceptor = Cloudflare)
        blockedCheck(response.url, response.text)
        val cookieJar = linkedMapOf<String, String>()
        response.cookies.forEach { cookieJar[it.key] = it.value }

        val csrfResponse = app.get(
            "${siteRootUrl}sanctum/csrf-cookie",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            interceptor = Cloudflare
        )
        blockedCheck(csrfResponse.url, csrfResponse.text)
        csrfResponse.cookies.forEach { cookieJar[it.key] = it.value }

        headers["Cookie"] = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        decodedXsrfToken = cookieJar["XSRF-TOKEN"]
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?: ""

        val page = response.document
        val inertiaPageObject = page.select("#app").attr("data-page")
        inertiaVersion = inertiaPageObject
            .substringAfter("\"version\":\"")
            .substringBefore("\"")
        headers["X-Inertia-Version"] = inertiaVersion
    }

    private fun getSliderFetchHeaders(): Map<String, String> {
        return mapOf(
            "Cookie" to (headers["Cookie"] ?: ""),
            "X-Requested-With" to "XMLHttpRequest",
            "X-XSRF-TOKEN" to decodedXsrfToken,
            "Referer" to "$mainUrl/",
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "Origin" to siteRootUrl.removeSuffix("/")
        )
    }

    private fun searchResponseBuilder(listJson: List<Title>): List<SearchResponse> {
        val list: List<SearchResponse> =
            listJson.filter { it.type == "movie" || it.type == "tv" }.map { title ->
                val url = "$mainUrl/titles/${title.id}-${title.slug}"

                if (title.type == "tv") {
                    newTvSeriesSearchResponse(title.name, url) {
                        posterUrl = "https://$cdnHost/images/" + title.getPoster()
                    }
                } else {
                    newMovieSearchResponse(title.name, url) {
                        posterUrl = "https://$cdnHost/images/" + title.getPoster()
                    }
                }
            }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        ensureHeaders()

        val hasNext = page < 17
        when (request.name) {
            "Slider" -> {
                val slider = parseJson<SliderFetchRequestSlider>(request.data)
                val body = "{\"sliders\":[${slider.toJson()}]}"
                val response = app.post(
                    "${siteRootUrl}api/sliders/fetch?lang=$lang",
                    headers = getSliderFetchHeaders(),
                    requestBody = body.toRequestBody(),
                    interceptor = Cloudflare
                )
                val payload = response.body.string()
                val r = parseSliderFetchSections(payload) ?: return null
                return newHomePageResponse(r, hasNext = false)
            }
            "Genre" -> {
                val genre = parseJson<GenreRequest>(request.data)
                val response = app.get(
                    "${siteRootUrl}$lang/archive",
                    params = mapOf(
                        "page" to page.toString(),
                        "lang" to lang,
                        "genre[]" to genre.id.toString()
                    ),
                    headers = getSliderFetchHeaders(),
                    interceptor = Cloudflare
                )
                val payload = response.body.string()
                val data =
                    tryParseJson<it.dogior.hadEnough.SearchResponse>(payload)?.data ?: return null
                val name = if (lang == "en") genre.nameEN else genre.nameIT
                return newHomePageResponse(
                    HomePageList(
                        name = name,
                        list = searchResponseBuilder(data)
                    ), hasNext = hasNext
                )
            }
            else -> {
                return null
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search"
        val response = app.get(url, params = mapOf("q" to query), interceptor = Cloudflare).text
        blockedCheck(url, response)
        val titles = parseBrowseTitles(response, "Search")
        return searchResponseBuilder(titles)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val params = mutableMapOf("q" to query)
        if (page > 1) params["page"] = page.toString()
        val response = app.get("$mainUrl/search", params = params, interceptor = Cloudflare).text
        blockedCheck(mainUrl, response)
        val titles = parseBrowseTitles(response, "Search page=$page")
        val items = searchResponseBuilder(titles)
        val hasNext = items.isNotEmpty() && items.size >= 60
        return newSearchResponseList(items, hasNext = hasNext)
    }

    private fun getPoster(title: TitleProp): String? =
        (title.getPosterImageId() ?: title.getBackgroundImageId())
            ?.let { "https://$cdnHost/images/$it" }

    
    private suspend fun fetchTmdbLogoUrl(
        type: TvType,
        tmdbId: Int?,
        appLangCode: String?
    ): String? {
        if (tmdbId == null || tmdbApiKey.isBlank() || tmdbApiKey == "null") return null
        
        return try {
            val appLang = appLangCode?.substringBefore("-")?.lowercase()
            val url = if (type == TvType.Movie) {
                "$tmdbAPI/movie/$tmdbId/images"
            } else {
                "$tmdbAPI/tv/$tmdbId/images"
            }
            
            
            val response = app.get(url, headers = tmdbHeaders)
            if (!response.isSuccessful) {
                Log.d(TAG, "TMDB API error: ${response.code}")
                return null
            }
            
            val jsonText = response.body?.string() ?: return null
            val json = JSONObject(jsonText)
            val logos = json.optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null
            
            fun logoUrlAt(i: Int): String {
                val logo = logos.getJSONObject(i)
                val filePath = logo.optString("file_path", "")
                return "https://image.tmdb.org/t/p/w500$filePath"
            }
            
            fun isSvg(i: Int): Boolean {
                val logo = logos.getJSONObject(i)
                val filePath = logo.optString("file_path", "")
                return filePath.endsWith(".svg", ignoreCase = true)
            }
            
            if (!appLang.isNullOrBlank()) {
                var svgFallback: String? = null
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    if (logo.optString("iso_639_1") == appLang) {
                        if (isSvg(i)) {
                            if (svgFallback == null) svgFallback = logoUrlAt(i)
                        } else {
                            return logoUrlAt(i)
                        }
                    }
                }
                if (svgFallback != null) return svgFallback
            }
            
            var enSvgFallback: String? = null
            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                if (logo.optString("iso_639_1") == "en") {
                    if (isSvg(i)) {
                        if (enSvgFallback == null) enSvgFallback = logoUrlAt(i)
                    } else {
                        return logoUrlAt(i)
                    }
                }
            }
            if (enSvgFallback != null) return enSvgFallback
            
            for (i in 0 until logos.length()) {
                if (!isSvg(i)) {
                    return logoUrlAt(i)
                }
            }
            
            if (logos.length() > 0) logoUrlAt(0) else null
        } catch (e: Exception) {
            Log.d(TAG, "Error fetching TMDB logo: ${e.message}")
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val actualUrl = getActualUrl(url)
        ensureHeaders()
        val response = app.get(actualUrl, headers = headers, interceptor = Cloudflare)
        val responseBody = response.text
        blockedCheck(response.url, responseBody)

        val props = parseJson<InertiaResponse>(responseBody).props
        val title = props.title!!
        val genres = title.genres.map { it.name.capitalize() }
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val related = props.sliders?.getOrNull(0)
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }
        val poster = getPoster(title)

        val (logoUrl, episodes) = coroutineScope {
            val logo = async {
                if (showLogo && title.tmdbId != null) {
                    val type = if (title.type == "tv") TvType.TvSeries else TvType.Movie
                    fetchTmdbLogoUrl(type = type, tmdbId = title.tmdbId, appLangCode = lang)
                } else null
            }
            val eps = async { if (title.type == "tv") getEpisodes(props) else emptyList() }
            logo.await() to eps.await()
        }

        if (title.type == "tv") {

            val tvShow = newTvSeriesLoadResponse(
                title.name,
                actualUrl,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = "https://$cdnHost/images/$it" }

                if (logoUrl != null) {
                    this.logoUrl = logoUrl
                }
                this.tags = genres
                this.episodes = episodes
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }

            }
            return tvShow
        } else {
            val data = LoadData(
                "$mainUrl/iframe/${title.id}?canPlayFHD=1",
                "movie",
                title.tmdbId
            )
            val movie = newMovieLoadResponse(
                title.name,
                actualUrl,
                TvType.Movie,
                dataUrl = data.toJson()
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = "https://$cdnHost/images/$it" }

                if (logoUrl != null) {
                    this.logoUrl = logoUrl
                }
                this.tags = genres
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)

                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }

                title.runtime?.let { this.duration = it }
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }
            }
            return movie
        }
    }

    private fun getActualUrl(url: String) =
        if (!url.contains(mainUrl)) {
            val replacingValue =
                if (url.contains("/it/") || url.contains("/en/")) mainUrl.toHttpUrl().host else mainUrl.toHttpUrl().host + "/$lang"
            val actualUrl = url.replace(url.toHttpUrl().host, replacingValue)

            Log.d("$TAG:UrlFix", "Old: $url\nNew: $actualUrl")
            actualUrl
        } else {
            url
        }

    private suspend fun getEpisodes(props: Props): List<Episode> {
        val title = props.title ?: return emptyList()

        return title.seasons.orEmpty().amap { season ->
            val seasonEpisodes = if (season.id == props.loadedSeason?.id) {
                props.loadedSeason?.episodes.orEmpty()
            } else {
                ensureHeaders()
                val url = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                runCatching {
                    val body = app.get(url, headers = headers, interceptor = Cloudflare).body.string()
                    parseJson<InertiaResponse>(body).props.loadedSeason?.episodes.orEmpty()
                }.onFailure { Log.e(TAG, "Stagione ${season.number}: ${it.message}") }
                    .getOrDefault(emptyList())
            }
            seasonEpisodes.map { ep ->
                val loadData = LoadData(
                    "$mainUrl/iframe/${title.id}?episode_id=${ep.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = season.number,
                    episodeNumber = ep.number)
                newEpisode(loadData.toJson()) {
                    this.name = ep.name
                    this.posterUrl = props.cdnUrl + "/images/" + ep.getCover()
                    this.description = ep.plot
                    this.episode = ep.number
                    this.season = season.number
                    this.runTime = ep.duration
                }
            }
        }.flatten()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Load Data : $data")
        if (data.isEmpty()) return false
        val loadData = parseJson<LoadData>(data)

        val foundLinks = AtomicBoolean(false)
        val blocked = AtomicBoolean(false)
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            foundLinks.set(true)
            callback(link)
        }

        val vixsrcUrl = if(loadData.type == "movie"){
            "https://vixsrc.to/movie/${loadData.tmdbId}?lang=$lang"
        } else{
            "https://vixsrc.to/tv/${loadData.tmdbId}/${loadData.seasonNumber}/${loadData.episodeNumber}?lang=$lang"
        }

        // le due sorgenti sono indipendenti: in parallelo, e un errore in una non uccide l'altra
        val sources = listOf<Pair<String, suspend () -> Unit>>(
            "VixCloud" to {
                val page = app.get(loadData.url, referer = "$mainUrl/", interceptor = Cloudflare)
                val body = page.body.string()
                Cloudflare.check(page.url.toHttpUrl().host, body)
                val iframeSrc = org.jsoup.Jsoup.parse(body).select("iframe").attr("src")
                if (iframeSrc.isBlank()) throw ErrorLoadingException("nessun iframe in ${loadData.url}")
                VixCloudExtractor.extract(
                    embedUrl = iframeSrc,
                    referer = siteRootUrl,
                    source = "VixCloud",
                    linkName = "Streaming Community - VixCloud",
                    callback = linkCallback
                )
            },
            "VixSrc" to {
                if (loadData.tmdbId != null) VixSrcExtractor().getUrl(
                    url = vixsrcUrl,
                    referer = "https://vixsrc.to/",
                    subtitleCallback = subtitleCallback,
                    callback = linkCallback
                )
            }
        )
        sources.amap { (name, source) ->
            runCatching { source() }.onFailure {
                if (it is NetworkBlockedException) blocked.set(true)
                Log.e(TAG, "$name: ${it.message}")
            }
        }

        if (!foundLinks.get() && blocked.get()) {
            throw ErrorLoadingException(BLOCKED_MESSAGE)
        }
        return foundLinks.get()
    }
}
