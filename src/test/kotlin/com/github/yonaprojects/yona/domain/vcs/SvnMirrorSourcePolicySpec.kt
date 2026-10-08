package com.github.yonaprojects.yona.domain.vcs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.tmatesoft.svn.core.SVNURL
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SvnMirrorSourcePolicySpec : DescribeSpec({
    fun settings() = RepositoryMirrorProperties().apply {
        enabled = true
        egressRestricted = true
        nodeId = "writer"
        writerNodeId = "writer"
        proxyHost = "127.0.0.1"
        proxyPort = 1
        allowedOrigins = listOf("https://localhost:19443", "https://example.org")
    }

    describe("source trust boundary") {
        it("requires actual forced proxy configuration and a designated writer before network") {
            val config = settings()
            config.proxyHost = ""
            shouldThrow<MirrorFailure> { SvnMirrorSourcePolicy(config).open("https://localhost:19443/svn", null) }.code shouldBe "ACTIVATION_REQUIRED"
            config.proxyHost = "127.0.0.1"
            config.egressRestricted = false
            shouldThrow<MirrorFailure> { config.requireActivation() }.code shouldBe "ACTIVATION_REQUIRED"
            config.egressRestricted = true
            config.nodeId = "reader"
            shouldThrow<MirrorFailure> { config.requireActivation() }.code shouldBe "ACTIVATION_REQUIRED"
            config.enabled = false
            shouldThrow<MirrorFailure> { config.requireActivation() }.code shouldBe "FEATURE_DISABLED"
        }

        it("normalizes only exact HTTPS origins and rejects credential/redirect-capable URL forms") {
            val policy = SvnMirrorSourcePolicy(settings())
            policy.normalize("https://EXAMPLE.org/repo/") shouldBe "https://example.org:443/repo"
            listOf("http://example.org/repo", "file:///repo", "svn+ssh://example.org/repo", "https://example.org:8443/repo",
                "https://other.example/repo", "https://user:secret@example.org/repo", "https://example.org/repo?secret=x",
                "https://example.org/repo#secret", "https://example.org/%2e%2e/repo", "https://example.org/repo%2fsecret",
                "https://example.org/repo%0aheader", "https://example.org./repo").forEach {
                shouldThrow<MirrorFailure> { policy.normalize(it) }.message shouldBe "SOURCE_POLICY"
            }
        }

        it("blocks private IPv4 IPv6 metadata multicast and transition addresses by default") {
            listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "192.168.1.1", "0.0.0.0",
                "224.0.0.1", "::1", "fc00::1", "fe80::1", "ff02::1", "2002:7f00:1::1", "2001:db8::1").forEach {
                SvnMirrorSourcePolicy.isPublic(InetAddress.getByName(it)) shouldBe false
            }
            SvnMirrorSourcePolicy.isPublic(InetAddress.getByName("8.8.8.8")) shouldBe true
            SvnMirrorSourcePolicy.contains("10.0.0.0/24", InetAddress.getByName("10.0.0.255")) shouldBe true
            SvnMirrorSourcePolicy.contains("10.0.0.0/24", InetAddress.getByName("10.0.1.0")) shouldBe false
            SvnMirrorSourcePolicy.contains("::1/128", InetAddress.getByName("::1")) shouldBe true
        }

        it("requires CIDR exceptions for every resolved address and rechecks changed policy") {
            val config = settings()
            val policy = SvnMirrorSourcePolicy(config)
            val url = SVNURL.parseURIEncoded("https://localhost:19443/svn")
            shouldThrow<MirrorFailure> { policy.checkAddresses(url) }
            config.allowedCidrs = listOf("127.0.0.0/8", "::1/128")
            policy.checkAddresses(url)
            config.allowedCidrs = emptyList()
            shouldThrow<MirrorFailure> { policy.checkAddresses(url) }
        }

        it("opens only the configured CONNECT proxy and never falls back on a denied tunnel") {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { proxy ->
                proxy.soTimeout = 5000
                val request = CompletableFuture.supplyAsync {
                    proxy.accept().use { connection ->
                        connection.soTimeout = 5000
                        val reader = connection.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        connection.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        line
                    }
                }
                val config = settings().apply {
                    proxyPort = proxy.localPort
                    allowedCidrs = listOf("127.0.0.0/8", "::1/128")
                }
                shouldThrow<MirrorFailure> { SvnMirrorSourcePolicy(config).open("https://localhost:19443/svn", null) }
                request.get(10, TimeUnit.SECONDS) shouldBe "CONNECT localhost:19443 HTTP/1.1"
            }
        }

        it("accepts only mapped private server secret files and never includes secret contents in errors") {
            val directory = Files.createTempDirectory("mirror-secret-").toRealPath()
            try {
                val file = directory.resolve("account.properties")
                Files.writeString(file, "username=readonly\npassword=never-display-this\n")
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
                val config = settings().apply { credentials = mapOf("readonly" to file.toString()) }
                val policy = SvnMirrorSourcePolicy(config)
                policy.credentialRefs() shouldBe setOf("readonly")
                policy.validateCredentialRef("readonly")
                shouldThrow<MirrorFailure> { policy.validateCredentialRef(file.toString()) }.message shouldBe "CREDENTIAL_UNAVAILABLE"
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
                shouldThrow<MirrorFailure> { policy.validateCredentialRef("readonly") }.message shouldBe "CREDENTIAL_UNAVAILABLE"
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
                val link = directory.resolve("link")
                Files.createSymbolicLink(link, file)
                config.credentials = mapOf("readonly" to link.toString())
                shouldThrow<MirrorFailure> { policy.validateCredentialRef("readonly") }.message shouldBe "CREDENTIAL_UNAVAILABLE"
            } finally { directory.toFile().deleteRecursively() }
        }
    }
})
