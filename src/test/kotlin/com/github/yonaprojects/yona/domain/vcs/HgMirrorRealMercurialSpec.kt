package com.github.yonaprojects.yona.domain.vcs

import com.sun.net.httpserver.HttpServer
import io.github.search5.hg4j.api.PullCommand
import io.github.search5.hg4j.lib.HgRepository as NativeHgRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.spyk
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Wire interop with the real `hg` binary. Skipped where Mercurial is not installed. */
class HgMirrorRealMercurialSpec : DescribeSpec({
    fun hg(directory: File?, vararg args: String): String {
        val command = listOf("hg") + args
        val process = ProcessBuilder(command).apply {
            directory?.let { directory(it) }
            environment()["HGRCPATH"] = ""
            environment()["HGPLAIN"] = "1"
            environment()["HGUSER"] = "tester <tester@example.com>"
            redirectErrorStream(true)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        check(process.exitValue() == 0) { "hg ${args.toList()} failed: $output" }
        return output
    }

    val available = try { ProcessBuilder("hg", "--version").start().waitFor() == 0 } catch (_: Exception) { false }

    fun settings(port: Int) = RepositoryMirrorProperties().apply {
        enabled = true
        egressRestricted = true
        nodeId = "writer"
        writerNodeId = "writer"
        proxyHost = "127.0.0.1"
        proxyPort = 3128
        allowedOrigins = listOf("https://localhost:$port")
    }

    fun <T> serve(repository: File, vararg config: String, block: (Int) -> T): T {
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(listOf("hg", "serve", "-R", repository.absolutePath, "-p", port.toString(), "-a", "127.0.0.1") + config.flatMap { listOf("--config", it) })
            .apply { environment()["HGRCPATH"] = ""; redirectErrorStream(true); redirectOutput(ProcessBuilder.Redirect.DISCARD) }
            .start()
        try {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                try { java.net.Socket("127.0.0.1", port).close(); break } catch (_: Exception) { Thread.sleep(100) }
            }
            return block(port)
        } finally {
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    fun source(port: Int): HgHttpsMirrorSource {
        val policy = spyk(MirrorSourcePolicy(settings(port)))
        every { policy.checkHost(any()) } just runs
        val connector = HgConnector { url: URL, _: Proxy, _: String ->
            URI("http://127.0.0.1:$port${url.path}?${url.query}").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        }
        return HgHttpsMirrorSource(policy, "https://localhost:$port/", null, connector)
    }

    describe("a real Mercurial server") {
        it("reports an empty repository, then serves heads, root, known, bundles and bookmarks with escaped names").config(enabled = available) {
            val root = Files.createTempDirectory("hg-real").toFile()
            try {
                val origin = root.resolve("origin").also { it.mkdirs() }
                hg(origin, "init")
                serve(origin) { port ->
                    val client = source(port)
                    client.heads() shouldBe emptyList()
                }
                File(origin, "a.txt").writeText("a")
                hg(origin, "add", "a.txt")
                hg(origin, "commit", "-m", "first #1", "-u", "원본 <one@example.com>", "-d", "1700000000 0")
                File(origin, "b.txt").writeText("b")
                hg(origin, "add", "b.txt")
                hg(origin, "commit", "-m", "second\n\nbody", "-u", "plain-author", "-d", "1700000100 0")
                val names = listOf("main", "기능/50% off", "a+b", "back\\slash", "quote'name")
                for (name in names) hg(origin, "bookmark", "-r", "0", name)
                hg(origin, "bookmark", "-f", "-r", "1", "main")
                val nodes = hg(origin, "log", "-T", "{node}\n").trim().lines().reversed()
                serve(origin) { port ->
                    val client = source(port)
                    client.heads() shouldBe listOf(nodes[1])
                    client.root() shouldBe nodes[0]
                    client.known(listOf(nodes[0], "1".repeat(40))) shouldBe listOf(true, false)
                    client.bookmarks() shouldBe names.associateWith { if (it == "main") nodes[1] else nodes[0] }

                    val target = HgMirrorTestSupport.init(root.resolve("target"))
                    fun pull(common: List<String>) {
                        val repository = NativeHgRepository(target)
                        try { PullCommand(repository).applyBundle(HgMirrorEngine.parseBundle(client.bundle(common, client.heads()))) } finally { repository.close() }
                    }
                    pull(emptyList())
                    HgMirrorChangelog(target).use { log ->
                        log.count shouldBe 2
                        log.node(0) shouldBe nodes[0]
                        log.node(1) shouldBe nodes[1]
                        log.commit(0).apply {
                            message shouldBe "first #1"
                            authorName shouldBe "원본"
                            authorKey shouldBe "one@example.com"
                            timestamp.epochSecond shouldBe 1_700_000_000L
                        }
                        log.commit(1).apply {
                            message shouldBe "second\n\nbody"
                            authorName shouldBe "plain-author"
                            authorKey shouldBe null
                        }
                    }
                    // Incremental pull after a new changeset only transfers what is missing.
                    File(origin, "c.txt").writeText("c")
                    hg(origin, "add", "c.txt")
                    hg(origin, "commit", "-m", "third", "-d", "1700000200 0")
                    val third = hg(origin, "log", "-r", "tip", "-T", "{node}").trim()
                    pull(listOf(nodes[1]))
                    HgMirrorChangelog(target).use { it.node(2) shouldBe third }
                }
            } finally { root.deleteRecursively() }
        }

        it("maps a wire error that arrives as HTTP 200 to a fixed failure").config(enabled = available) {
            val root = Files.createTempDirectory("hg-real-error").toFile()
            try {
                val origin = root.resolve("origin").also { it.mkdirs() }
                hg(origin, "init")
                File(origin, "a.txt").writeText("a")
                hg(origin, "add", "a.txt")
                hg(origin, "commit", "-m", "one")
                // bundle1 disabled: a client that does not offer bundle2 gets an application/hg-error answer.
                serve(origin, "server.bundle1=false") { port ->
                    val client = source(port)
                    val failure = shouldThrow<MirrorFailure> { client.bundle(emptyList(), client.heads()) }
                    failure.code shouldBe "SOURCE_PROTOCOL"
                    failure.message shouldBe "SOURCE_PROTOCOL"
                }
            } finally { root.deleteRecursively() }
        }
    }
})
