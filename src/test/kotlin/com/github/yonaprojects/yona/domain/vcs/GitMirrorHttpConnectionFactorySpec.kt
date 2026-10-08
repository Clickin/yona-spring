package com.github.yonaprojects.yona.domain.vcs

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.jgit.transport.HttpTransport
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager

class GitMirrorHttpConnectionFactorySpec : DescribeSpec({
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 3128))
    val source = "https://git.example.test:443/owner/repo.git"
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/owner/repo.git/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "https://elsewhere.example.test/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        createContext("/owner/repo.git/ok") { exchange ->
            exchange.responseHeaders.add("www-authenticate", "Basic realm=\"git\"")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        start()
    }
    val port = server.address.port
    afterSpec { server.stop(0) }

    fun policy() = mockk<MirrorSourcePolicy>(relaxed = true).also {
        every { it.proxy() } returns proxy
        every { it.checkHost(any()) } returns Unit
    }

    describe("Git mirror HTTP connection factory") {
        it("always uses the policy proxy, whatever proxy the caller asks for, and checks the host first") {
            val policy = policy()
            val proxies = mutableListOf<Proxy>()
            val order = mutableListOf<String>()
            every { policy.checkHost(any()) } answers { order += "checkHost" }
            val factory = GitMirrorHttpConnectionFactory(policy, source) { url, used ->
                order += "open"
                proxies += used
                URL("http://127.0.0.1:$port${url.path}").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            }
            factory.create(URL("https://git.example.test/owner/repo.git/ok"))
            factory.create(URL("https://git.example.test/owner/repo.git/ok"), Proxy.NO_PROXY)
            factory.create(URL("https://git.example.test/owner/repo.git/ok"), null)
            proxies shouldContainOnly listOf(proxy)
            proxies.size shouldBe 3
            order shouldBe listOf("checkHost", "open", "checkHost", "open", "checkHost", "open")
            verify(exactly = 3) { policy.checkHost("git.example.test") }
        }

        it("refuses every 3xx as SOURCE_REDIRECT without exposing the Location") {
            val factory = GitMirrorHttpConnectionFactory(policy(), source) { url, _ ->
                URL("http://127.0.0.1:$port${url.path}").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            }
            val connection = factory.create(URL("https://git.example.test/owner/repo.git/redirect"))
            connection.setInstanceFollowRedirects(true)
            val failure = shouldThrow<MirrorFailure> { connection.responseCode }
            failure.code shouldBe "SOURCE_REDIRECT"
            failure.message shouldBe "SOURCE_REDIRECT"
        }

        it("only talks to the normalized origin and repository path, before any DNS or connection") {
            val policy = policy()
            var opened = 0
            val factory = GitMirrorHttpConnectionFactory(policy, source) { url, _ -> opened++; url.openConnection(Proxy.NO_PROXY) as HttpURLConnection }
            listOf(
                "http://git.example.test/owner/repo.git/info/refs",
                "https://other.example.test/owner/repo.git/info/refs",
                "https://git.example.test:8443/owner/repo.git/info/refs",
                "https://user:secret@git.example.test/owner/repo.git/info/refs",
                "https://git.example.test/owner/other.git/info/refs",
                "https://git.example.test/owner/repo.gitx/info/refs"
            ).forEach { shouldThrow<MirrorFailure> { factory.create(URL(it)) }.code shouldBe "SOURCE_POLICY" }
            opened shouldBe 0
            verify(exactly = 0) { policy.checkHost(any()) }
        }

        it("does not connect when the host check fails") {
            val policy = policy()
            every { policy.checkHost(any()) } throws MirrorFailure("SOURCE_POLICY")
            var opened = 0
            val factory = GitMirrorHttpConnectionFactory(policy, source) { url, _ -> opened++; url.openConnection(Proxy.NO_PROXY) as HttpURLConnection }
            shouldThrow<MirrorFailure> { factory.create(URL("https://git.example.test/owner/repo.git/info/refs")) }.code shouldBe "SOURCE_POLICY"
            opened shouldBe 0
        }

        it("verifies TLS with the policy trust manager and ignores weaker settings from the caller") {
            val policy = policy()
            val trust = mockk<X509ExtendedTrustManager>(relaxed = true)
            every { policy.trustManager("git.example.test") } returns trust
            val tls = mockk<HttpsURLConnection>(relaxed = true)
            val factory = GitMirrorHttpConnectionFactory(policy, source) { _, _ -> tls }
            val connection = factory.create(URL("https://git.example.test/owner/repo.git/info/refs"))
            verify(exactly = 1) { policy.trustManager("git.example.test") }
            verify(exactly = 1) { tls.sslSocketFactory = any<SSLSocketFactory>() }
            verify { tls.instanceFollowRedirects = false }
            verify { tls.connectTimeout = 10_000 }
            verify { tls.readTimeout = 30_000 }
            connection.configure(null, arrayOf<javax.net.ssl.TrustManager>(), null)
            connection.setHostnameVerifier { _, _ -> true }
            connection.setInstanceFollowRedirects(true)
            connection.setConnectTimeout(0)
            connection.setReadTimeout(600_000)
            connection.setConnectTimeout(2_000)
            verify(exactly = 1) { tls.sslSocketFactory = any<SSLSocketFactory>() }
            verify(exactly = 0) { tls.hostnameVerifier = any() }
            verify(exactly = 0) { tls.instanceFollowRedirects = true }
            verify(exactly = 0) { tls.readTimeout = 600_000 }
            verify(exactly = 2) { tls.connectTimeout = 10_000 }
            verify { tls.connectTimeout = 2_000 }
            verify(exactly = 2) { tls.readTimeout = 30_000 }
        }

        it("matches response header names case-insensitively, as JGit expects for WWW-Authenticate") {
            val factory = GitMirrorHttpConnectionFactory(policy(), source) { url, _ ->
                URL("http://127.0.0.1:$port${url.path}").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            }
            val connection = factory.create(URL("https://git.example.test/owner/repo.git/ok"))
            connection.responseCode shouldBe 204
            connection.getHeaderFields("WWW-Authenticate") shouldBe listOf("Basic realm=\"git\"")
        }

        it("changes no JVM-wide JGit or HttpsURLConnection default") {
            val jgit = HttpTransport.getConnectionFactory()
            val sockets = HttpsURLConnection.getDefaultSSLSocketFactory()
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            val tls = mockk<HttpsURLConnection>(relaxed = true)
            GitMirrorHttpConnectionFactory(policy(), source) { _, _ -> tls }.create(URL("https://git.example.test/owner/repo.git/info/refs"))
            (HttpTransport.getConnectionFactory() === jgit) shouldBe true
            (HttpsURLConnection.getDefaultSSLSocketFactory() === sockets) shouldBe true
            (HttpsURLConnection.getDefaultHostnameVerifier() === verifier) shouldBe true
        }
    }
})
