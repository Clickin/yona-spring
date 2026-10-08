package com.github.yonaprojects.yona.domain.vcs

import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * JGit connection factory for one mirror fetch. It is installed on a single `TransportHttp` instance, so
 * no JVM-wide JGit or `HttpsURLConnection` default changes. Every connection it hands out
 *  - targets the normalized source origin and repository path only (anything else is `SOURCE_POLICY`),
 *  - passes `policy.checkHost` first and always goes through `policy.proxy()`, whatever proxy JGit asks for,
 *  - verifies TLS with `policy.trustManager(host)` and ignores JGit's own trust/hostname overrides,
 *  - never follows redirects: any 3xx response is `SOURCE_REDIRECT`,
 *  - uses a 10 s connect and a 30 s read timeout, which JGit cannot raise.
 */
internal class GitMirrorHttpConnectionFactory(
    private val policy: MirrorSourcePolicy,
    normalizedUrl: String,
    private val opener: (URL, Proxy) -> HttpURLConnection = { url, proxy -> url.openConnection(proxy) as HttpURLConnection }
) : HttpConnectionFactory {
    private val origin = URI(normalizedUrl)
    private val port = if (origin.port == -1) 443 else origin.port

    override fun create(url: URL): HttpConnection = open(url)

    override fun create(url: URL, proxy: Proxy?): HttpConnection = open(url)

    private fun open(url: URL): HttpConnection {
        val host = url.host.lowercase().removeSurrounding("[", "]")
        val urlPort = if (url.port == -1) url.defaultPort else url.port
        val path = origin.rawPath
        if (url.protocol != "https" || url.userInfo != null || host != origin.host.lowercase().removeSurrounding("[", "]") || urlPort != port ||
            !(url.path == path || url.path.startsWith("$path/"))
        ) throw MirrorFailure("SOURCE_POLICY")
        policy.checkHost(host)
        val connection = opener(url, policy.proxy())
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.useCaches = false
        // A JVM-wide default Authenticator must not answer 401/407 for this connection.
        connection.setAuthenticator(NO_AUTHENTICATOR)
        if (connection is HttpsURLConnection) {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(policy.trustManager(host)), SecureRandom())
            connection.sslSocketFactory = context.socketFactory
        }
        return Connection(connection)
    }

    private class Connection(private val wrapped: HttpURLConnection) : HttpConnection {
        private fun <T> checked(action: () -> T): T {
            val value = action()
            if (wrapped.responseCode in 300..399) {
                wrapped.disconnect()
                throw MirrorFailure("SOURCE_REDIRECT")
            }
            return value
        }

        override fun getResponseCode(): Int {
            val code = wrapped.responseCode
            if (code in 300..399) {
                wrapped.disconnect()
                throw MirrorFailure("SOURCE_REDIRECT")
            }
            return code
        }
        override fun getURL(): URL = wrapped.url
        override fun getResponseMessage(): String? = wrapped.responseMessage
        override fun getHeaderFields(): Map<String, List<String>> = wrapped.headerFields
        override fun setRequestProperty(key: String, value: String) = wrapped.setRequestProperty(key, value)
        override fun setRequestMethod(method: String) { wrapped.requestMethod = method }
        override fun setUseCaches(usecaches: Boolean) { wrapped.useCaches = false }
        override fun setConnectTimeout(timeout: Int) { wrapped.connectTimeout = clamp(timeout, CONNECT_TIMEOUT_MILLIS) }
        override fun setReadTimeout(timeout: Int) { wrapped.readTimeout = clamp(timeout, READ_TIMEOUT_MILLIS) }
        override fun getContentType(): String? = wrapped.contentType
        override fun getInputStream(): InputStream = checked { wrapped.inputStream }
        override fun getHeaderField(name: String): String? = wrapped.getHeaderField(name)
        override fun getHeaderFields(name: String): List<String> = wrapped.headerFields.orEmpty().entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
        override fun getContentLength(): Int = wrapped.contentLength
        override fun setInstanceFollowRedirects(followRedirects: Boolean) { wrapped.instanceFollowRedirects = false }
        override fun setDoOutput(dooutput: Boolean) { wrapped.doOutput = dooutput }
        override fun setFixedLengthStreamingMode(contentLength: Int) { wrapped.setFixedLengthStreamingMode(contentLength) }
        override fun getOutputStream(): OutputStream = wrapped.outputStream
        override fun setChunkedStreamingMode(chunklen: Int) { wrapped.setChunkedStreamingMode(chunklen) }
        override fun getRequestMethod(): String = wrapped.requestMethod
        override fun usingProxy(): Boolean = wrapped.usingProxy()
        override fun connect() = wrapped.connect()

        // JGit calls these for http.sslVerify=false and similar; the policy trust manager stays in force.
        override fun configure(km: Array<out KeyManager>?, tm: Array<out TrustManager>?, random: SecureRandom?) {}
        override fun setHostnameVerifier(hostnameverifier: HostnameVerifier?) {}

        private fun clamp(requested: Int, limit: Int) = if (requested in 1 until limit) requested else limit
    }

    companion object {
        private val NO_AUTHENTICATOR = object : java.net.Authenticator() {}
        internal const val CONNECT_TIMEOUT_MILLIS = 10_000
        internal const val READ_TIMEOUT_MILLIS = 30_000
    }
}
