package it.dogior.hadEnough

import android.webkit.CookieManager
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

class NetworkBlockedException(host: String) :
    Exception("$host risponde con una pagina di blocco Cloudflare")

/**
 * Un solo CloudflareKiller per tutto il plugin (sito, VixCloud, VixSrc): la challenge si
 * risolve una volta per host e il cookie cf_clearance resta valido per tutte le richieste.
 *
 * CloudflareKiller da solo non si accorge quando cf_clearance scade o smette di valere
 * (cambio server VPN = IP nuovo): continua a rimandare il cookie vecchio e prende 403 per
 * sempre. Qui, se dopo il cookie arriva ancora una challenge, il cookie si butta e la
 * WebView la risolve di nuovo, una volta sola.
 */
object Cloudflare : Interceptor {
    private val killer = CloudflareKiller()

    private val BLOCK_MARKERS = listOf(
        "Sorry, you have been blocked",
        "cf-error-details",
        "Attention Required! | Cloudflare",
    )

    private fun isChallenge(response: Response) =
        response.code in listOf(403, 503) &&
            response.header("Server")?.startsWith("cloudflare") == true

    private fun isHardBlock(response: Response) =
        runCatching { response.peekBody(256 * 1024).string() }
            .getOrDefault("").let { body -> BLOCK_MARKERS.any { it in body } }

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (killer.savedCookies[host] == null) {
            // Prima la richiesta normale: se non e' una challenge non serve CloudflareKiller.
            // Se e' il blocco duro la WebView non lo risolve mai e resterebbe appesa 120 s
            // per richiesta: la pagina torna al chiamante, che con check() da' l'errore.
            val first = chain.proceed(chain.request())
            if (!isChallenge(first) || isHardBlock(first)) return first
            first.close()
        }
        val response = killer.intercept(chain)
        if (!isChallenge(response) || killer.savedCookies[host] == null) return response
        response.close()
        killer.savedCookies.remove(host)
        runCatching {
            CookieManager.getInstance()
                .setCookie("https://$host", "cf_clearance=; Max-Age=0; Path=/")
        }
        return killer.intercept(chain)
    }

    /** Il blocco duro (regola WAF, IP della VPN bannato) non si risolve: lo si dice chiaro. */
    fun check(host: String, body: String) {
        if (BLOCK_MARKERS.any { it in body }) throw NetworkBlockedException(host)
    }
}
