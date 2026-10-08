package com.github.yonaprojects.yona.domain.vcs

import io.github.search5.hg4j.api.PullCommand
import io.github.search5.hg4j.lib.HgRepository as NativeHgRepository
import io.github.search5.hg4j.transport.HgHttpWireServer
import io.github.search5.hg4j.transport.HgRemoteConnectionFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import com.sun.net.httpserver.HttpServer
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

class HgMirrorSourceSpec : DescribeSpec({
    fun settings(port: Int) = RepositoryMirrorProperties().apply {
        enabled = true
        egressRestricted = true
        nodeId = "writer"
        writerNodeId = "writer"
        proxyHost = "127.0.0.1"
        proxyPort = 3128
        allowedOrigins = listOf("https://localhost:$port")
    }

    /** Serves requests through hg4j's wire server (or a canned reply) on plain loopback HTTP. */
    class Fixture(handler: (MockHttpServletRequest, MockHttpServletResponse) -> Unit) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val uri = exchange.requestURI
                val request = MockHttpServletRequest(exchange.requestMethod, uri.path).apply {
                    queryString = uri.rawQuery
                    uri.rawQuery?.split('&')?.forEach { pair ->
                        val (key, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                        addParameter(key, java.net.URLDecoder.decode(value, Charsets.UTF_8))
                    }
                    exchange.requestHeaders.forEach { (name, values) -> values.forEach { addHeader(name, it) } }
                    setContent(exchange.requestBody.readAllBytes())
                }
                requests.add(uri.toString() to exchange.requestHeaders.entries.associate { it.key to it.value.first() })
                val response = MockHttpServletResponse()
                handler(request, response)
                response.headerNames.forEach { name -> exchange.responseHeaders.add(name, response.getHeader(name)) }
                val body = response.contentAsByteArray
                exchange.sendResponseHeaders(response.status, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            start()
        }
        val port get() = server.address.port
        override fun close() = server.stop(0)
    }

    /** Plain-HTTP loopback connector that records what the policy asked for. */
    class RecordingConnector(private val port: Int) : HgConnector {
        val proxies = CopyOnWriteArrayList<Proxy>()
        val connections = CopyOnWriteArrayList<HttpURLConnection>()
        override fun open(url: URL, proxy: Proxy, host: String): HttpURLConnection {
            proxies.add(proxy)
            val plain = URI("http://127.0.0.1:$port${url.path}?${url.query}").toURL()
            return (plain.openConnection(Proxy.NO_PROXY) as HttpURLConnection).also { connections.add(it) }
        }
    }

    fun source(policy: MirrorSourcePolicy, port: Int, connector: HgConnector, ref: String? = null) =
        HgHttpsMirrorSource(policy, "https://localhost:$port/repo", ref, connector)

    fun wire(directory: File): (MockHttpServletRequest, MockHttpServletResponse) -> Unit = { request, response ->
        val repository = NativeHgRepository(directory)
        try { HgHttpWireServer(repository).service(request, response) } finally { repository.close() }
    }

    describe("Mercurial source transport policy") {
        it("speaks the wire protocol well enough to mirror a repository through the policy") {
            val root = Files.createTempDirectory("hg-wire").toFile()
            try {
                val origin = HgMirrorTestSupport.init(root.resolve("origin"))
                val first = HgMirrorTestSupport.commit(origin, "a.txt", "a", "first #1", "원본 <one@example.com>")
                val second = HgMirrorTestSupport.commit(origin, "b.txt", "b", "second", "two@example.com")
                HgMirrorTestSupport.bookmark(origin, "main", second)
                Fixture(wire(origin)).use { fixture ->
                    val policy = spyk(MirrorSourcePolicy(settings(fixture.port)))
                    every { policy.checkHost(any()) } just runs
                    val connector = RecordingConnector(fixture.port)
                    val source = source(policy, fixture.port, connector)
                    source.heads() shouldBe listOf(second)
                    source.root() shouldBe first
                    source.known(listOf(first, "1".repeat(40))) shouldBe listOf(true, false)
                    source.bookmarks() shouldBe mapOf("main" to second)
                    val target = HgMirrorTestSupport.init(root.resolve("target"))
                    val bundle = HgMirrorEngine.parseBundle(source.bundle(emptyList(), source.heads()))
                    val repository = NativeHgRepository(target)
                    try { PullCommand(repository).applyBundle(bundle) } finally { repository.close() }
                    HgMirrorChangelog(target).use { log ->
                        log.count shouldBe 2
                        log.node(0) shouldBe first
                        log.node(1) shouldBe second
                        log.commit(0).message shouldBe "first #1"
                        log.commit(0).authorKey shouldBe "one@example.com"
                        log.commit(0).authorName shouldBe "원본"
                    }
                    // Incremental: only what the mirror lacks is requested.
                    val third = HgMirrorTestSupport.commit(origin, "c.txt", "c", "third")
                    val incremental = HgMirrorEngine.parseBundle(source.bundle(listOf(second), listOf(third)))
                    val again = NativeHgRepository(target)
                    try { PullCommand(again).applyBundle(incremental) } finally { again.close() }
                    HgMirrorChangelog(target).use { it.count shouldBe 3 }
                    connector.proxies.isNotEmpty() shouldBe true
                }
            } finally { root.deleteRecursively() }
        }

        it("uses the policy proxy and host check for every request, with fixed timeouts and no redirects") {
            Fixture { _, response -> response.status = 200; response.writer.print("0000000000000000000000000000000000000000\n") }.use { fixture ->
                val policy = spyk(MirrorSourcePolicy(settings(fixture.port)))
                every { policy.checkHost(any()) } just runs
                val connector = RecordingConnector(fixture.port)
                val source = source(policy, fixture.port, connector)
                source.heads() shouldBe emptyList()
                source.heads() shouldBe emptyList()
                connector.proxies.size shouldBe 2
                connector.proxies.all { it == policy.proxy() } shouldBe true
                verify(exactly = 2) { policy.checkHost("localhost") }
                connector.connections.forEach {
                    it.connectTimeout shouldBe 10_000
                    it.readTimeout shouldBe 30_000
                    it.instanceFollowRedirects shouldBe false
                }
                fixture.requests.all { it.first == "/repo?cmd=heads" } shouldBe true
            }
        }

        it("never lets a JVM default Authenticator answer a 401 and bounds bundles by the configured size") {
            Fixture { request, response ->
                if (request.getParameter("cmd") == "getbundle") {
                    response.status = 200
                    response.outputStream.write(ByteArray(4096))
                } else {
                    response.status = 401
                    response.setHeader("WWW-Authenticate", "Basic realm=\"hg\"")
                }
            }.use { fixture ->
                val policy = spyk(MirrorSourcePolicy(settings(fixture.port)))
                every { policy.checkHost(any()) } just runs
                var asked = 0
                val previous = java.net.Authenticator.getDefault()
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                        asked++
                        return java.net.PasswordAuthentication("jvm-wide", "secret".toCharArray())
                    }
                })
                try {
                    val source = source(policy, fixture.port, RecordingConnector(fixture.port))
                    shouldThrow<MirrorFailure> { source.heads() }.code shouldBe "SOURCE_AUTH_REJECTED"
                    asked shouldBe 0
                    fixture.requests.none { it.second.keys.any { key -> key.equals("Authorization", ignoreCase = true) } } shouldBe true
                } finally {
                    java.net.Authenticator.setDefault(previous)
                }
                val small = HgHttpsMirrorSource(policy, "https://localhost:${fixture.port}/repo", null, RecordingConnector(fixture.port), bundleLimit = 1024)
                shouldThrow<MirrorFailure> { small.bundle(emptyList(), listOf("1".repeat(40))) }.code shouldBe "SOURCE_RESPONSE_TOO_LARGE"
                HgHttpsMirrorSource(policy, "https://localhost:${fixture.port}/repo", null, RecordingConnector(fixture.port), bundleLimit = 8192)
                    .bundle(emptyList(), listOf("1".repeat(40))).size shouldBe 4096
            }
        }

        it("refuses every redirect without following it or forwarding credentials") {
            val directory = Files.createTempDirectory("hg-redirect-credential").toRealPath()
            val credential = directory.resolve("source.properties")
            Files.writeString(credential, "username=reader\npassword=s3cret\n")
            Files.setPosixFilePermissions(credential, PosixFilePermissions.fromString("rw-------"))
            try {
                Fixture { _, response -> response.status = 200 }.use { elsewhere ->
                    for (status in listOf(301, 302, 303, 307, 308)) {
                        Fixture { _, response ->
                            response.status = status
                            response.setHeader("Location", "http://127.0.0.1:${elsewhere.port}/elsewhere")
                        }.use { moved ->
                            val config = settings(moved.port).apply { credentials = mapOf("source" to credential.toString()) }
                            val policy = spyk(MirrorSourcePolicy(config))
                            every { policy.checkHost(any()) } just runs
                            val failure = shouldThrow<MirrorFailure> { source(policy, moved.port, RecordingConnector(moved.port), "source").heads() }
                            failure.code shouldBe "SOURCE_REDIRECT"
                            failure.transient shouldBe false
                            moved.requests.size shouldBe 1
                        }
                    }
                    // The redirect target never saw a request, so no credential reached another location.
                    elsewhere.requests.size shouldBe 0
                }
            } finally { directory.toFile().deleteRecursively() }
        }

        it("sends credentials only from the operator reference, clears the password and maps HTTP errors to fixed codes") {
            val password = charArrayOf('s', '3', 'c', 'r', 'e', 't')
            Fixture { request, response ->
                when (request.getParameter("cmd")) {
                    "heads" -> { response.status = 200; response.writer.print("0000000000000000000000000000000000000000\n") }
                    "lookup" -> response.status = 401
                    "known" -> response.status = 503
                    else -> response.status = 404
                }
            }.use { fixture ->
                val policy = spyk(MirrorSourcePolicy(settings(fixture.port)))
                every { policy.checkHost(any()) } just runs
                every { policy.credential("ref") } returns ("reader" to password)
                val connector = RecordingConnector(fixture.port)
                val withCredential = source(policy, fixture.port, connector, "ref")
                withCredential.heads()
                val header = fixture.requests.single().second.entries.first { it.key.equals("Authorization", true) }.value
                header shouldBe "Basic " + java.util.Base64.getEncoder().encodeToString("reader:s3cret".toByteArray())
                password.all { it == '\u0000' } shouldBe true
                shouldThrow<MirrorFailure> { withCredential.root() }.code shouldBe "SOURCE_AUTH_REJECTED"
                val unavailable = shouldThrow<MirrorFailure> { withCredential.known(listOf("1".repeat(40))) }
                unavailable.code shouldBe "SOURCE_HTTP_ERROR"
                unavailable.transient shouldBe true
                val missing = shouldThrow<MirrorFailure> { withCredential.bookmarks() }
                missing.code shouldBe "SOURCE_HTTP_ERROR"
                missing.transient shouldBe false
                // No credential reference: no Authorization header at all.
                fixture.requests.clear()
                source(policy, fixture.port, RecordingConnector(fixture.port)).heads()
                fixture.requests.single().second.keys.none { it.equals("Authorization", true) } shouldBe true
            }
        }

        it("reports an unreachable source as a transient failure without the JDK message") {
            val port = java.net.ServerSocket(0).use { it.localPort }
            val policy = spyk(MirrorSourcePolicy(settings(port)))
            every { policy.checkHost(any()) } just runs
            val failure = shouldThrow<MirrorFailure> { source(policy, port, RecordingConnector(port)).heads() }
            failure.transient shouldBe true
            failure.message shouldBe failure.code
        }

        it("denies hosts the policy rejects before any connection is created") {
            val policy = spyk(MirrorSourcePolicy(settings(443)))
            every { policy.checkHost(any()) } throws MirrorFailure("SOURCE_POLICY")
            var opened = 0
            val failure = shouldThrow<MirrorFailure> {
                source(policy, 443, { _, _, _ -> opened++; throw AssertionError() }).heads()
            }
            failure.code shouldBe "SOURCE_POLICY"
            opened shouldBe 0
        }

        it("binds TLS verification to the policy trust manager per connection and leaves JVM defaults alone") {
            val policy = spyk(MirrorSourcePolicy(settings(443)))
            val factory = HttpsURLConnection.getDefaultSSLSocketFactory()
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            val redirects = HttpURLConnection.getFollowRedirects()
            val protocols = HgRemoteConnectionFactory.getRegisteredProtocols().toList()
            val connection = HgConnector.https(policy).open(URL("https://localhost/repo?cmd=heads"), policy.proxy(), "localhost")
            connection shouldNotBe null
            (connection as HttpsURLConnection).sslSocketFactory shouldNotBe factory
            verify(exactly = 1) { policy.trustManager("localhost") }
            HttpsURLConnection.getDefaultSSLSocketFactory() shouldBe factory
            HttpsURLConnection.getDefaultHostnameVerifier() shouldBe verifier
            HttpURLConnection.getFollowRedirects() shouldBe redirects
            HgRemoteConnectionFactory.getRegisteredProtocols() shouldBe protocols
        }

        it("keeps listkeys names exactly as sent and drops entries without a valid node") {
            Fixture { _, response ->
                response.status = 200
                response.setContentType("application/mercurial-0.1")
                response.setCharacterEncoding("UTF-8")
                response.writer.print("50% off\t" + "a".repeat(40) + "\nback\\slash\t" + "b".repeat(40) + "\n기능+x\t" + "c".repeat(40) + "\nbroken\tnot-a-node\n")
            }.use { fixture ->
                val policy = spyk(MirrorSourcePolicy(settings(fixture.port)))
                every { policy.checkHost(any()) } just runs
                source(policy, fixture.port, RecordingConnector(fixture.port)).bookmarks() shouldBe
                    mapOf("50% off" to "a".repeat(40), "back\\slash" to "b".repeat(40), "기능+x" to "c".repeat(40))
            }
            Fixture { _, response -> response.status = 200; response.writer.print("one\ttwo\tthree\n") }.use { fixture ->
                val policy = spyk(MirrorSourcePolicy(settings(fixture.port)))
                every { policy.checkHost(any()) } just runs
                shouldThrow<MirrorFailure> { source(policy, fixture.port, RecordingConnector(fixture.port)).bookmarks() }.code shouldBe "SOURCE_PROTOCOL"
            }
        }

        it("only accepts the normalized HTTPS origin") {
            val policy = MirrorSourcePolicy(settings(443))
            shouldThrow<MirrorFailure> { HgHttpsMirrorSource(policy, "http://localhost:443/repo", null) }.code shouldBe "SOURCE_POLICY"
            shouldThrow<MirrorFailure> { HgHttpsMirrorSource(policy, "https://user:pw@localhost:443/repo", null) }.code shouldBe "SOURCE_POLICY"
            shouldThrow<MirrorFailure> { HgHttpsMirrorSource(policy, "https://other.example/repo", null) }.code shouldBe "SOURCE_POLICY"
        }
    }
})
