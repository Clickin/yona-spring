package com.github.yonaprojects.yona.domain.vcs

import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The read-only part of a Mercurial source the mirror needs. Hex node IDs are 40 lowercase hex digits.
 * Implementations never return data from another origin than the configured source.
 */
interface HgMirrorSource : Closeable {
    /** Topological heads; empty when the source has no changesets. */
    fun heads(): List<String>

    /** Node of local revision 0, or null for an empty source. */
    fun root(): String?

    /** For each node, whether the source has it. */
    fun known(nodes: List<String>): List<Boolean>

    /** Raw getbundle response (possibly wrapped) for [heads] without what is reachable from [common]. */
    fun bundle(common: List<String>, heads: List<String>): ByteArray

    /** Bookmark name to node. */
    fun bookmarks(): Map<String, String>

    override fun close() {}
}

/** Opens [HgMirrorSource]s. A seam so engine logic is testable without HTTPS. */
@Component
class HgMirrorTransport(
    private val policy: MirrorSourcePolicy,
    private val settings: RepositoryMirrorProperties
) {
    fun open(sourceUrl: String, credentialRef: String?): HgMirrorSource {
        settings.requireActivation() // Must precede DNS, secret reads, and any source connection.
        return HgHttpsMirrorSource(policy, sourceUrl, credentialRef)
    }
}

/** How a request connection is created. Production uses [HTTPS]; tests may substitute a loopback opener. */
internal fun interface HgConnector {
    fun open(url: URL, proxy: Proxy, host: String): HttpURLConnection

    companion object {
        fun https(policy: MirrorSourcePolicy) = HgConnector { url, proxy, host ->
            // Per connection: the proxy, TLS context and hostname are bound to this one request; nothing
            // touches HttpsURLConnection.setDefault* or hg4j's global connection registry.
            val connection = url.openConnection(proxy) as? HttpsURLConnection ?: throw MirrorFailure("SOURCE_POLICY")
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<javax.net.ssl.TrustManager>(policy.trustManager(host)), null)
            connection.sslSocketFactory = context.socketFactory
            connection
        }
    }
}

/**
 * Mercurial wire protocol over HTTPS with the mirror transport policy. hg4j's own HTTP client follows
 * redirects, uses the JVM default TLS context and has no per-connection hook, so the few read-only
 * commands are spoken here and hg4j only parses and applies the resulting bundle.
 */
internal class HgHttpsMirrorSource(
    private val policy: MirrorSourcePolicy,
    sourceUrl: String,
    private val credentialRef: String?,
    private val connector: HgConnector = HgConnector.https(policy)
) : HgMirrorSource {
    private val base = policy.normalize(sourceUrl)
    private val host = URI(base).host.lowercase()

    override fun heads(): List<String> = nodes(text(request("heads", emptyMap(), SMALL_LIMIT)))
        .filterNot { it == NULL_NODE }

    override fun root(): String? {
        val answer = text(request("lookup", mapOf("key" to "0"), SMALL_LIMIT)).trim()
        val node = answer.removePrefix("1 ").trim()
        if (!answer.startsWith("1 ") || !NODE.matches(node)) {
            // An empty source has no revision 0; any other answer is a protocol problem.
            if (answer.startsWith("0 ")) return null
            throw MirrorFailure("SOURCE_PROTOCOL")
        }
        return node
    }

    override fun known(nodes: List<String>): List<Boolean> {
        if (nodes.isEmpty()) return emptyList()
        val answer = text(request("known", mapOf("nodes" to nodes.joinToString(" ")), SMALL_LIMIT)).trim()
        if (answer.length != nodes.size || answer.any { it != '0' && it != '1' }) throw MirrorFailure("SOURCE_PROTOCOL")
        return answer.map { it == '1' }
    }

    override fun bundle(common: List<String>, heads: List<String>): ByteArray = request(
        "getbundle",
        mapOf("heads" to heads.joinToString(" "), "common" to common.ifEmpty { listOf(NULL_NODE) }.joinToString(" ")),
        BUNDLE_LIMIT
    )

    override fun bookmarks(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (line in text(request("listkeys", mapOf("namespace" to "bookmarks"), SMALL_LIMIT)).split('\n')) {
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size != 2) throw MirrorFailure("SOURCE_PROTOCOL")
            // Mercurial sends names as raw UTF-8 (no escaping); only a malformed node is dropped.
            if (NODE.matches(parts[1])) result[parts[0]] = parts[1]
        }
        return result
    }

    private fun nodes(answer: String): List<String> {
        val result = answer.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (result.any { !NODE.matches(it) }) throw MirrorFailure("SOURCE_PROTOCOL")
        return result
    }

    private fun text(bytes: ByteArray) = String(bytes, StandardCharsets.UTF_8)

    /** One GET with a fresh connection. Arguments travel in X-HgArg-N headers, as Mercurial's own client sends them. */
    private fun request(command: String, args: Map<String, String>, limit: Long): ByteArray {
        // Resolve and screen the address before every connection, then connect only to the normalized origin.
        policy.checkHost(host)
        val url = URI("$base?cmd=$command").toURL()
        val authorization = credentialRef?.let { basicAuthorization(it) }
        val connection = try {
            connector.open(url, policy.proxy(), host)
        } catch (e: IOException) {
            throw transportFailure(e)
        }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/mercurial-0.1")
            connection.setRequestProperty("User-Agent", "yona-mirror")
            encodeArguments(args).chunked(ARGUMENT_CHUNK).forEachIndexed { index, chunk ->
                connection.setRequestProperty("X-HgArg-${index + 1}", chunk)
            }
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            val status = connection.responseCode
            when {
                status in 300..399 -> throw MirrorFailure("SOURCE_REDIRECT")
                status == 401 || status == 403 -> throw MirrorFailure("SOURCE_AUTH_REJECTED")
                status != 200 -> throw MirrorFailure("SOURCE_HTTP_ERROR", transient = status == 408 || status == 429 || status >= 500)
            }
            // hgweb reports wire errors (for example "bundle2 required") as 200 with this content type.
            if (connection.contentType?.startsWith("application/hg-error", ignoreCase = true) == true) throw MirrorFailure("SOURCE_PROTOCOL")
            return connection.inputStream.use { readLimited(unwrap(it), limit) }
        } catch (e: IOException) {
            throw transportFailure(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun basicAuthorization(ref: String): String {
        val (user, password) = policy.credential(ref)
        val secret = ByteArrayOutputStream()
        try {
            secret.write(user.toByteArray(StandardCharsets.UTF_8))
            secret.write(':'.code)
            val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
            try {
                secret.write(encoded.array(), encoded.arrayOffset() + encoded.position(), encoded.remaining())
            } finally {
                encoded.array().fill(0)
            }
            return "Basic " + Base64.getEncoder().encodeToString(secret.toByteArray())
        } finally {
            password.fill('\u0000')
            // ByteArrayOutputStream cannot be wiped; keep its lifetime to this call only.
        }
    }

    /** Version 0.1 responses are either plain or one zlib stream; sniff the zlib header like hg4j does. */
    private fun unwrap(stream: InputStream): InputStream {
        val pushback = PushbackInputStream(stream, 2)
        val first = pushback.read()
        if (first < 0) return pushback
        val second = pushback.read()
        if (second >= 0) pushback.unread(second)
        pushback.unread(first)
        return if (first == 0x78 && second in ZLIB_SECOND_BYTES) InflaterInputStream(pushback) else pushback
    }

    private fun readLimited(stream: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw MirrorFailure("SOURCE_RESPONSE_TOO_LARGE")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun transportFailure(error: IOException): MirrorFailure {
        // Never carry the remote or JDK message: it can contain URLs or credentials.
        val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
        return when {
            causes.any { it is SSLException } -> MirrorFailure("SOURCE_TLS_REJECTED")
            causes.any { it is SocketTimeoutException || it is ConnectException || it is UnknownHostException } ->
                MirrorFailure("SOURCE_UNAVAILABLE", transient = true)
            else -> MirrorFailure("SOURCE_CONNECTION", transient = true)
        }
    }

    companion object {
        internal const val CONNECT_TIMEOUT_MILLIS = 10_000
        internal const val READ_TIMEOUT_MILLIS = 30_000
        private const val SMALL_LIMIT = 4L * 1024 * 1024
        private const val BUNDLE_LIMIT = 100L * 1024 * 1024
        private const val ARGUMENT_CHUNK = 900
        internal const val NULL_NODE = "0000000000000000000000000000000000000000"
        private val NODE = Regex("[0-9a-f]{40}")
        private val ZLIB_SECOND_BYTES = setOf(0x01, 0x5e, 0x9c, 0xda)

        private fun encodeArguments(args: Map<String, String>): String = args.toSortedMap().entries.joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        }
    }
}
