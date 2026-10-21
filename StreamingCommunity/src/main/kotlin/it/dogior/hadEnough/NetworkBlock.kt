package it.dogior.hadEnough

class NetworkBlockedException(host: String) :
    Exception("$host risponde con una pagina di blocco Cloudflare invece del player")

object NetworkBlock {
    private val MARKERS = listOf(
        "Sorry, you have been blocked",
        "cdn-cgi/styles/cf.errors",
        "cf-error-details",
        "Attention Required! | Cloudflare",
    )

    fun check(host: String, body: String) {
        if (MARKERS.any { it in body }) throw NetworkBlockedException(host)
    }
}
