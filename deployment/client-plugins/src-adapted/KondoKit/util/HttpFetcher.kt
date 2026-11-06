package KondoKit.util

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object HttpFetcher {
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
        "Accept" to "*/*"
    )

    fun openGetConnection(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMillis: Int? = null,
        readTimeoutMillis: Int? = null
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        (defaultHeaders + headers).forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }
        connectTimeoutMillis?.let { connection.connectTimeout = it }
        readTimeoutMillis?.let { connection.readTimeout = it }
        return connection
    }

    fun fetchString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMillis: Int? = null,
        readTimeoutMillis: Int? = null
    ): String {
        val connection = openGetConnection(url, headers, connectTimeoutMillis, readTimeoutMillis)
        val responseCode = connection.responseCode
        val responseStream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: throw IOException("No response stream available for $url (HTTP $responseCode)")

        val payload = responseStream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) {
            throw HttpStatusException(url, responseCode, payload)
        }
        return payload
    }
}

class HttpStatusException(
    val url: String,
    val statusCode: Int,
    val body: String
) : IOException("Request to $url failed with HTTP $statusCode")
