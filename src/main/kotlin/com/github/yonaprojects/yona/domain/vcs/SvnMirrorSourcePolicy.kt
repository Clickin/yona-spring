package com.github.yonaprojects.yona.domain.vcs

import org.springframework.stereotype.Component
import org.tmatesoft.svn.core.*
import org.tmatesoft.svn.core.auth.*
import org.tmatesoft.svn.core.internal.io.dav.DAVRepository
import org.tmatesoft.svn.core.internal.io.dav.http.*
import org.tmatesoft.svn.core.io.ISVNSession
import org.tmatesoft.svn.core.io.SVNRepository
import org.tmatesoft.svn.util.SVNDebugLogAdapter
import org.tmatesoft.svn.util.SVNLogType
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.logging.Level
import javax.net.ssl.*

@Component
class SvnMirrorSourcePolicy(private val settings: RepositoryMirrorProperties) {
    fun normalize(sourceUrl: String): String = policy {
        if (sourceUrl.length > 2048) denied()
        val uri = URI(sourceUrl)
        val host = uri.host?.lowercase() ?: denied()
        if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || host.endsWith('.') || '%' in host ||
            uri.port !in -1..65535 || uri.port == 0 || uri.path.any { it.isISOControl() || it == '\\' } ||
            uri.path.split('/').any { it == "." || it == ".." } ||
            Regex("(?i)%2f|%5c").containsMatchIn(uri.rawPath)
        ) denied()
        val port = if (uri.port == -1) 443 else uri.port
        val origin = "https://$host:$port"
        if (settings.allowedOrigins.none { allowed ->
                val candidate = URI(allowed)
                candidate.scheme == "https" && candidate.host?.lowercase() == host &&
                    (if (candidate.port == -1) 443 else candidate.port) == port &&
                    candidate.rawUserInfo == null && candidate.rawQuery == null && candidate.rawFragment == null &&
                    candidate.path in listOf("", "/")
            }) denied()
        origin + uri.rawPath.trimEnd('/')
    }

    fun credentialRefs(): Set<String> = settings.credentials.keys.filter { REF.matches(it) }.toSortedSet()

    fun validateCredentialRef(ref: String?) {
        if (ref != null && ref !in credentialRefs()) throw MirrorFailure("CREDENTIAL_UNAVAILABLE")
        if (ref != null) credentials(ref).second.fill('\u0000') // Provisioning check only; no network.
    }

    fun open(sourceUrl: String, credentialRef: String?): SVNRepository {
        settings.requireActivation() // Must precede DNS, secret reads, and any source connection.
        val normalized = normalize(sourceUrl)
        val url = SVNURL.parseURIEncoded(normalized)
        checkAddresses(url)
        val secret = credentialRef?.let { credentials(it) }
        val auth = object : BasicAuthenticationManager(emptyArray<SVNAuthentication>()) {
            override fun getProxyManager(target: SVNURL): ISVNProxyManager {
                settings.requireActivation()
                if (normalize(target.toString()) != normalized) denied()
                checkAddresses(target)
                return this // Always CONNECT. No environment/servers-file bypass or direct fallback.
            }
            override fun getTrustManager(target: SVNURL): TrustManager {
                if (target.host != url.host || target.port != url.port || target.protocol != "https") denied()
                return strictTrustManager(url.host.removeSurrounding("[", "]"))
            }
            override fun getFirstAuthentication(kind: String, realm: String, target: SVNURL): SVNAuthentication? {
                if (normalize(target.toString()) != normalized) denied()
                return super.getFirstAuthentication(kind, realm, target)
            }
            override fun getReadTimeout(repository: SVNRepository) = 30_000
            override fun getConnectTimeout(repository: SVNRepository) = 10_000
        }
        auth.setProxy(settings.proxyHost, settings.proxyPort, null, null as CharArray?)
        if (secret != null) {
            auth.setAuthentications(arrayOf(
                SVNPasswordAuthentication.newInstance(secret.first, secret.second, false, url, false),
                SVNUserNameAuthentication.newInstance(secret.first, false, url, false)
            ))
        }
        val factory = object : IHTTPConnectionFactory {
            override fun useSendAllForDiff(repository: SVNRepository) = true
            override fun createHTTPConnection(repository: SVNRepository): IHTTPConnection =
                object : HTTPConnection(repository, "UTF-8", null, false) {
                    // SVNKit 1.10.11's other three overloads dispatch to this method. Intercept before
                    // DAVRepository can follow a redirect or retain credentials for another location.
                    override fun request(method: String, path: String?, header: HTTPHeader?, body: InputStream?,
                        ok1: Int, ok2: Int, output: OutputStream?, handler: DefaultHandler?, error: SVNErrorMessage?): HTTPStatus {
                        if (method !in READ_METHODS || normalize(repository.location.toString()) != normalized) denied()
                        try {
                            return super.request(method, path, header, body, ok1, ok2, output, handler, error)
                        } finally {
                            if (lastStatus?.code?.let { it in 300..399 } == true) {
                                close()
                                throw MirrorFailure("SOURCE_REDIRECT")
                            }
                        }
                    }
                }
        }
        val repository = object : DAVRepository(factory, url, ISVNSession.KEEP_ALIVE) {
            override fun openConnection() {
                if (normalize(location.toString()) != normalized) denied()
                checkAddresses(location)
                super.openConnection()
            }
            override fun closeSession() {
                try { super.closeSession() } finally { auth.dismissSensitiveData() }
            }
        }
        repository.authenticationManager = auth
        repository.setDebugLog(SILENT_LOG) // DAV wire logs can contain Authorization headers.
        try {
            if (normalize(repository.getRepositoryRoot(true).toString()) != normalized) {
                throw MirrorFailure("SOURCE_ROOT_REQUIRED")
            }
            return repository
        } catch (e: Exception) {
            repository.closeSession()
            if (e is MirrorFailure) throw e
            var cause: Throwable? = e
            var networkFailure = false
            var tlsFailure = false
            var depth = 0
            while (cause != null && depth++ < 12) {
                networkFailure = networkFailure || cause is java.net.ConnectException || cause is java.net.SocketTimeoutException
                tlsFailure = tlsFailure || cause is SSLException
                cause = cause.cause
            }
            throw MirrorFailure("SOURCE_CONNECTION", transient = networkFailure && !tlsFailure)
        }
    }

    internal fun checkAddresses(url: SVNURL) = policy {
        val addresses = try {
            InetAddress.getAllByName(url.host.removeSurrounding("[", "]"))
        } catch (_: java.net.UnknownHostException) {
            throw MirrorFailure("SOURCE_CONNECTION", transient = true)
        }
        if (addresses.isEmpty() || addresses.any { address ->
                !isPublic(address) && settings.allowedCidrs.none { contains(it, address) }
            }) denied()
    }

    private fun credentials(ref: String): Pair<String, CharArray> = try {
        if (!REF.matches(ref)) throw MirrorFailure("CREDENTIAL_UNAVAILABLE")
        val path = Path.of(settings.credentials[ref] ?: throw MirrorFailure("CREDENTIAL_UNAVAILABLE"))
        if (!path.isAbsolute || !Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path.normalize() ||
            Files.size(path) !in 1..16384 ||
            Files.getPosixFilePermissions(path).any { it !in SECRET_PERMISSIONS } ||
            Files.getOwner(path).name !in setOf(System.getProperty("user.name"), "root")
        ) throw MirrorFailure("CREDENTIAL_UNAVAILABLE")
        val values = Properties().apply { Files.newBufferedReader(path).use { load(it) } }
        val username = values.getProperty("username") ?: throw MirrorFailure("CREDENTIAL_UNAVAILABLE")
        val password = values.getProperty("password") ?: throw MirrorFailure("CREDENTIAL_UNAVAILABLE")
        if (values.stringPropertyNames() != setOf("username", "password") || username.isBlank() ||
            username.any { it.isISOControl() } || password.isEmpty()
        ) throw MirrorFailure("CREDENTIAL_UNAVAILABLE")
        username to password.toCharArray()
    } catch (_: Exception) { throw MirrorFailure("CREDENTIAL_UNAVAILABLE") }

    private fun strictTrustManager(host: String): X509ExtendedTrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?) // JVM truststore only; never SVNKit's accept/store certificate cache.
        val delegate = factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
        return object : X509ExtendedTrustManager() {
            override fun getAcceptedIssuers() = delegate.acceptedIssuers
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String, socket: Socket) {
                val tls = socket as? SSLSocket ?: throw CertificateException("TLS required")
                if (!tls.handshakeSession.peerHost.equals(host, true)) throw CertificateException("TLS identity mismatch")
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                delegate.checkServerTrusted(chain, type, tls)
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) { throw CertificateException("TLS endpoint required") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String, engine: SSLEngine) { throw CertificateException("TLS endpoint required") }
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) { throw CertificateException("Client TLS unsupported") }
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String, socket: Socket) { throw CertificateException("Client TLS unsupported") }
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String, engine: SSLEngine) { throw CertificateException("Client TLS unsupported") }
        }
    }

    private fun <T> policy(action: () -> T): T = try { action() } catch (e: MirrorFailure) { throw e } catch (_: Exception) { denied() }
    private fun denied(): Nothing = throw MirrorFailure("SOURCE_POLICY")

    companion object {
        private val REF = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        private val SECRET_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val READ_METHODS = setOf("OPTIONS", "PROPFIND", "REPORT", "GET", "HEAD")
        private val IPV4 = Regex("[0-9.]+")
        private val IPV6 = Regex("[0-9a-fA-F:]+")
        private val GLOBAL_V6 = parseCidr("2000::/3")
        private val NON_PUBLIC = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3", "2001::/32", "2001:db8::/32", "2002::/16").map(::parseCidr)
        internal fun isPublic(address: InetAddress): Boolean = !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress &&
            (address.address.size == 4 || contains(GLOBAL_V6, address)) && NON_PUBLIC.none { contains(it, address) }

        internal fun contains(cidr: String, address: InetAddress): Boolean = contains(parseCidr(cidr), address)

        private fun parseCidr(cidr: String): Pair<ByteArray, Int> {
            val parts = cidr.split('/')
            require(parts.size == 2 && (IPV4.matches(parts[0]) || IPV6.matches(parts[0])))
            val network = InetAddress.getByName(parts[0]).address
            val bits = parts[1].toInt()
            require(bits in 0..network.size * 8)
            return network to bits
        }

        private fun contains(cidr: Pair<ByteArray, Int>, address: InetAddress): Boolean {
            val (network, bits) = cidr
            val bytes = address.address
            if (bytes.size != network.size) return false
            for (i in bytes.indices) {
                val mask = (0xff shl (8 - (bits - i * 8).coerceIn(0, 8))) and 0xff
                if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            }
            return true
        }

        private val SILENT_LOG = object : SVNDebugLogAdapter() {
            override fun log(type: SVNLogType, message: String?, level: Level) {}
            override fun log(type: SVNLogType, message: Throwable?, level: Level) {}
            override fun log(type: SVNLogType, message: String?, data: ByteArray?) {}
        }
    }
}
