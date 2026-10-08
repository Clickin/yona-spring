package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.project.ProjectService
import com.github.yonaprojects.yona.domain.project.ProjectUser
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.project.VcsResetConfirmation
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.PlayRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.spyk
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.filter.OncePerRequestFilter
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RepositoryArchiveHttpIntegrationSpec.StreamConfiguration::class)
class RepositoryArchiveHttpIntegrationSpec @Autowired constructor(
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val projectService: ProjectService,
    private val repositoryService: RepositoryService,
    private val projectUserRepository: ProjectUserRepository,
    private val roleRepository: RoleRepository,
    private val context: WebApplicationContext,
    private val accessFilter: RepositoryAccessFilter,
    private val streamPause: StreamPause,
) : AbstractIntegrationTest() {
    override fun extensions() = listOf(SpringExtension)

    @LocalServerPort
    private var port: Int = 0
    private val errorMapper = tools.jackson.databind.ObjectMapper()

    companion object {
        private const val PASSWORD = "archive-http-password"
        private const val SALT = "archive-http-salt"
        private const val MARKER_FILE = "archive-marker.txt"
        private val root = Files.createTempDirectory("yona-archive-http-").toRealPath()
        private val archiveRoot = Files.createTempDirectory(root, "private-archives-").toFile()
        private val clients = Files.createDirectory(root.resolve("clients"))
        private val home = Files.createDirectory(root.resolve("home"))
        private val svnConfig = Files.createDirectory(root.resolve("svn-config"))
        private val vcsNames = mapOf("GIT" to "git", "SUBVERSION" to "svn", "MERCURIAL" to "hg")

        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            listOf("git", "svn", "hg", "lfs", "upload").forEach { name ->
                val directory = Files.createDirectories(root.resolve(name))
                registry.add("yona.$name.base-dir") { directory.toString() }
            }
            Files.createDirectories(root.resolve("data"))
            Files.createDirectories(root.resolve("static"))
            listOf("git", "svn", "hg").forEach { name ->
                registry.add("yona.repository-archive.$name-dir") { "" }
            }
            registry.add("yona.data") { root.resolve("data").toString() }
            registry.add("yona.repository-archive.base-dir") { archiveRoot.absolutePath }
            registry.add("yona.repository-archive.single-serving-process") { true }
            registry.add("spring.web.resources.static-locations") {
                "classpath:/static/,${root.resolve("static").toUri()}"
            }
        }
    }

    private data class Fixture(
        var project: Project,
        val vcs: String,
        val manager: User,
        val member: User,
        val source: File,
        val work: File,
        val stale: File,
        val marker: String,
        var revision: String = "",
    ) {
        val actors: List<User?> get() = listOf(null, member, manager)
        val protocol: String get() = vcsNames.getValue(vcs)
        val path: String get() = "/$protocol/${project.owner}/${project.name}${if (vcs == "GIT") ".git" else ""}"
        val browser: String get() = "/${project.owner}/${project.name}"
        val branch: String get() = when (vcs) { "GIT" -> "main"; "SUBVERSION" -> "1"; else -> "tip" }
    }

    private data class Command(val exit: Int, val output: String) {
        fun succeeds(): String {
            withClue(output) { exit shouldBe 0 }
            return output
        }
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    private fun temp(prefix: String): File = Files.createTempDirectory(clients, prefix).toFile()

    private fun cli(directory: File?, executable: String, vararg args: String): Command {
        val options = if (args.contentEquals(arrayOf("--version"))) emptyList() else when (executable) {
            "git" -> listOf("-c", "credential.helper=", "-c", "http.followRedirects=false", "-c", "commit.gpgsign=false")
            "svn" -> listOf("--non-interactive", "--no-auth-cache", "--config-dir", svnConfig.toString())
            "hg" -> listOf("--noninteractive", "--config", "ui.username=Archive HTTP <archive-http@yona.io>")
            else -> emptyList()
        }
        val output = Files.createTempFile(clients, "cli-", ".log").toFile()
        val builder = ProcessBuilder(listOf(executable) + options + args).directory(directory)
            .redirectErrorStream(true).redirectOutput(output)
        builder.environment().apply {
            put("HOME", home.toString())
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", File(home.toFile(), "gitconfig").absolutePath)
            put("GIT_TERMINAL_PROMPT", "0")
            put("HGPLAIN", "1")
            put("HGRCPATH", "")
        }
        val process = try {
            builder.start()
        } catch (failure: IOException) {
            throw AssertionError("Required archive HTTP integration prerequisite missing: $executable on PATH", failure)
        }
        try {
            check(process.waitFor(45, TimeUnit.SECONDS)) { "$executable timed out: ${args.joinToString(" ")}" }
            return Command(process.exitValue(), output.readText())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            output.delete()
        }
    }

    private fun url(path: String, actor: User? = null): String =
        "http://${actor?.let { "${it.loginId}:$PASSWORD@" } ?: ""}127.0.0.1:$port$path"

    private fun svnAuth(actor: User?): Array<String> = if (actor == null) emptyArray()
        else arrayOf("--username", actor.loginId, "--password", PASSWORD)

    private fun clone(f: Fixture, destination: File, actor: User?, path: String = f.path): Command = when (f.vcs) {
        "GIT" -> cli(null, "git", "clone", url(path, actor), destination.absolutePath)
        "SUBVERSION" -> cli(null, "svn", "checkout", *svnAuth(actor), url(path), destination.absolutePath)
        else -> cli(null, "hg", "clone", url(path, actor), destination.absolutePath)
    }

    private fun commitAndPush(f: Fixture, working: File, actor: User, filename: String, contents: String) {
        File(working, filename).writeText(contents)
        when (f.vcs) {
            "GIT" -> {
                cli(working, "git", "config", "user.name", "Archive HTTP").succeeds()
                cli(working, "git", "config", "user.email", "archive-http@yona.io").succeeds()
                cli(working, "git", "add", filename).succeeds()
                cli(working, "git", "commit", "-m", contents).succeeds()
                cli(working, "git", "branch", "-M", "main").succeeds()
                cli(working, "git", "push", url(f.path, actor), "main").succeeds()
            }
            "SUBVERSION" -> {
                cli(working, "svn", "add", filename).succeeds()
                cli(working, "svn", "commit", *svnAuth(actor), "-m", contents).succeeds()
            }
            else -> {
                cli(working, "hg", "add", filename).succeeds()
                cli(working, "hg", "commit", "-m", contents).succeeds()
                cli(working, "hg", "push", "--new-branch", url(f.path, actor)).succeeds()
            }
        }
    }

    private fun fixture(vcs: String, scope: ProjectScope): Fixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(SALT.toByteArray())
        var password = digest.digest(PASSWORD.toByteArray())
        repeat(1023) { password = digest.digest(password) }
        fun account(name: String) = userRepository.save(User(
            loginId = "$name-$suffix", name = name, email = "$name-$suffix@yona.io",
            password = Base64.getEncoder().encodeToString(password), passwordSalt = SALT,
        ))
        val manager = account("archive-manager")
        val member = account("archive-member")
        val project = projectRepository.save(Project(owner = manager.loginId, name = "repository-$suffix", vcs = vcs, projectScope = scope))
        listOf(Triple(manager, 1L, "manager"), Triple(member, 2L, "member")).forEach { (actor, id, name) ->
            val role = roleRepository.findById(id).orElseGet { roleRepository.save(Role(id = id, name = name, active = true)) }
            projectUserRepository.save(ProjectUser(project = project, user = actor, role = role))
        }
        val repository = repositoryService.getRepository(project)
        repository.create()
        val f = Fixture(project, vcs, manager, member, repository.getDirectory(), temp("seed-"), temp("stale-"), "ARCHIVE-SECRET-$suffix")
        clone(f, f.stale, manager).succeeds()
        clone(f, f.work, manager).succeeds()
        commitAndPush(f, f.work, manager, MARKER_FILE, f.marker)
        f.revision = when (vcs) {
            "GIT" -> cli(f.work, "git", "rev-parse", "HEAD").succeeds().trim()
            "SUBVERSION" -> cli(f.work, "svn", "info", "--show-item", "revision", MARKER_FILE).succeeds().trim()
            else -> cli(f.work, "hg", "log", "-r", "tip", "--template", "{node}").succeeds().trim()
        }
        // A plain canary in the real repository also exercises LFS FileSystemResource, not a mock body.
        File(f.source, "archive-canary.txt").writeText(f.marker)
        if (vcs == "MERCURIAL") {
            // An unpushed changeset makes a forbidden push a real write, not "no changes found".
            File(f.work, "archive-write.txt").writeText("must never reach archive")
            cli(f.work, "hg", "add", "archive-write.txt").succeeds()
            cli(f.work, "hg", "commit", "-m", "archive write probe").succeeds()
        }
        return f
    }

    private fun reset(f: Fixture): File {
        val previous = f.project
        val prefix = "${previous.id}-${vcsNames.getValue(previous.vcs!!)}-"
        val before = archiveRoot.listFiles().orEmpty().map { it.name }.toSet()
        f.project = projectService.changeVCS(previous.id!!, VcsResetConfirmation(previous.id, previous.name, previous.vcs, true))
        f.project.id shouldBe previous.id
        val active = repositoryService.getRepository(f.project)
        active.isEmpty() shouldBe true
        val directory = active.getDirectory()
        when (f.project.vcs) {
            "GIT" -> cli(null, "git", "--git-dir=${directory.absolutePath}", "for-each-ref", "--format=%(objectname)").succeeds() shouldBe ""
            "SUBVERSION" -> cli(null, "svn", "info", "--show-item", "revision", directory.toPath().toUri().toString()).succeeds().trim() shouldBe "0"
            "MERCURIAL" -> cli(directory, "hg", "log", "--template", "{node}").succeeds() shouldBe ""
        }
        val operation = archiveRoot.listFiles().orEmpty().single { it.name.startsWith(prefix) && it.name !in before }
        return File(operation, "repository").also { it.isDirectory shouldBe true }
    }

    private fun archivedMarker(f: Fixture, archive: File): String = when (f.vcs) {
        "GIT" -> cli(null, "git", "--git-dir=${archive.absolutePath}", "show", "${f.revision}:$MARKER_FILE").succeeds()
        "SUBVERSION" -> cli(null, "svn", "cat", "-r", f.revision, archive.toPath().resolve(MARKER_FILE).toUri().toString()).succeeds()
        else -> cli(archive, "hg", "cat", "-r", f.revision, MARKER_FILE).succeeds()
    }

    private fun snapshot(directory: File): Map<String, String> = directory.walkTopDown().associate { file ->
        val value = when {
            Files.isSymbolicLink(file.toPath()) -> "link:${Files.readSymbolicLink(file.toPath())}"
            file.isDirectory -> "directory"
            else -> "${file.lastModified()}:${Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))}"
        }
        file.relativeTo(directory).path to value
    }

    private fun noMarker(f: Fixture, text: String) {
        withClue("Archived content escaped: $text") { text.contains(f.marker) shouldBe false }
    }

    private fun noDownloadedMarker(f: Fixture, directory: File) {
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            withClue(file.absolutePath) { noMarker(f, file.readBytes().toString(StandardCharsets.ISO_8859_1)) }
        }
        if (f.vcs == "GIT" && File(directory, ".git").isDirectory) {
            cli(directory, "git", "cat-file", "-e", "${f.revision}^{commit}").exit.let { (it != 0) shouldBe true }
        }
        if (f.vcs == "MERCURIAL" && File(directory, ".hg").isDirectory) {
            noMarker(f, cli(directory, "hg", "log", "--template", "{desc}\n").succeeds())
        }
    }

    private data class ReceivedResponse(val status: Int, val bytes: ByteArray)

    private fun request(path: String, actor: User?, method: String = "GET", body: String = ""): ReceivedResponse {
        // Preserve the caller's literal/encoded traversal; do not normalize the URI in the test.
        val builder = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(30))
            .header("Accept-Encoding", "identity")
        if (actor != null) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("${actor.loginId}:$PASSWORD".toByteArray()))
        if (method == "PROPFIND") builder.header("Depth", "1")
        if (method == "PROPFIND" || method == "REPORT") builder.header("Content-Type", "text/xml")
        val response = http.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = java.io.ByteArrayOutputStream()
        response.body().use { stream ->
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
            } catch (failure: IOException) {
                // Existing invalid-revision handlers may close after sending a complete error
                // document with status 200. Inspect that document, never discard received bytes.
                val error = runCatching { errorMapper.readTree(bytes.toByteArray()) }.getOrNull()
                val errorDocument = error?.has("timestamp") == true && error.has("message") &&
                    error.has("error") && error.get("path")?.asText() == URI.create(url(path)).rawPath
                if (!errorDocument && response.statusCode() < 400) throw failure
            }
        }
        return ReceivedResponse(response.statusCode(), bytes.toByteArray())
    }

    private fun responseText(response: ReceivedResponse): String {
        val bytes = response.bytes
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            return ZipInputStream(bytes.inputStream()).use { zip ->
                buildString {
                    var entry = zip.nextEntry
                    while (entry != null) {
                        append(entry.name).append(zip.readBytes().toString(StandardCharsets.UTF_8))
                        entry = zip.nextEntry
                    }
                }
            }
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun browserPaths(f: Fixture): List<String> = listOf(
        "${f.browser}/code", "${f.browser}/code/${f.branch}/$MARKER_FILE",
        "${f.browser}/rawcode/${f.revision}/$MARKER_FILE", "${f.browser}/commits/${f.branch}",
        "${f.browser}/code/${f.branch}/download", "${f.browser}/code/download/${f.branch}",
        "/api/vcs${f.browser}/raw?revision=${f.revision}&path=$MARKER_FILE",
        "/api/vcs${f.browser}/history?branch=${f.branch}",
        "/api/vcs${f.browser}/meta?branch=${f.branch}&path=$MARKER_FILE",
    )

    private fun assertBrowserHidden(f: Fixture, actor: User?, archive: File) {
        val relative = "../../${archiveRoot.name}/${archive.parentFile.name}/repository/archive-canary.txt"
        val encoded = relative.replace("..", "%2e%2e").replace("/", "%2f")
        val paths = browserPaths(f) + listOf(
            "${f.browser}/rawcode/${f.revision}/$relative",
            "${f.browser}/rawcode/${f.revision}/$encoded",
            "${f.browser}/rawcode/${f.revision}/${archive.absolutePath}/archive-canary.txt",
            "${f.browser}/code/${f.branch}/download?path=${URLEncoder.encode(relative, StandardCharsets.UTF_8)}",
            "/${f.project.owner}/${archive.parentFile.name}/rawcode/${f.revision}/$MARKER_FILE",
            "/${archiveRoot.name}/${archive.parentFile.name}/repository/archive-canary.txt",
        )
        paths.forEach { path -> withClue("${actor?.loginId ?: "anonymous"} $path") { noMarker(f, responseText(request(path, actor))) } }
    }

    private fun assertProtocolHidden(f: Fixture, path: String, actor: User?) {
        val discovery = when (f.vcs) {
            "GIT" -> "$path/info/refs?service=git-upload-pack"
            "SUBVERSION" -> "$path/$MARKER_FILE"
            else -> "$path?cmd=capabilities"
        }
        noMarker(f, responseText(request(discovery, actor)))
        val destination = temp("denied-clone-")
        val copy = clone(f, destination, actor, path)
        noMarker(f, copy.output)
        noDownloadedMarker(f, destination)
        val receiver = temp("denied-fetch-")
        val read: Command
        val write: Command
        when (f.vcs) {
            "GIT" -> {
                cli(receiver, "git", "init").succeeds()
                read = cli(receiver, "git", "fetch", url(path, actor), "+refs/heads/*:refs/remotes/probe/*")
                File(receiver, "probe.txt").writeText("fresh protocol write probe")
                cli(receiver, "git", "add", "probe.txt").succeeds()
                cli(receiver, "git", "-c", "user.name=Archive probe", "-c", "user.email=probe@example.invalid",
                    "commit", "-m", "fresh write probe").succeeds()
                write = cli(receiver, "git", "push", url(path, actor), "HEAD:refs/heads/archive-write")
            }
            "SUBVERSION" -> {
                // This revision-zero working copy has never received the archived revision.
                f.stale.copyRecursively(receiver, overwrite = true)
                read = if (path == f.path) cli(receiver, "svn", "update", *svnAuth(actor), "-r", f.revision)
                    else cli(null, "svn", "cat", *svnAuth(actor), "-r", f.revision, "${url(path)}/$MARKER_FILE")
                val imported = temp("denied-import-")
                File(imported, "archive-write.txt").writeText("must never reach archive")
                write = cli(null, "svn", "import", *svnAuth(actor), imported.absolutePath, "${url(path)}/archive-write", "-m", "archive write probe")
                listOf(
                    "PROPFIND" to """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>""",
                    "REPORT" to """<?xml version="1.0"?><S:update-report xmlns:S="svn:"><S:src-path>${url(path)}</S:src-path><S:target-revision>${f.revision}</S:target-revision><S:depth>infinity</S:depth><S:entry rev="0"/></S:update-report>""",
                ).forEach { (method, body) -> noMarker(f, responseText(request(path, actor, method, body))) }
            }
            else -> {
                cli(receiver, "hg", "init").succeeds()
                read = cli(receiver, "hg", "pull", url(path, actor))
                write = cli(f.work, "hg", "push", "--force", url(path, actor))
            }
        }
        listOf(read, write).forEach { result ->
            noMarker(f, result.output)
        }
        noDownloadedMarker(f, receiver)
    }

    private fun guessedPaths(f: Fixture, archive: File): List<String> {
        val relative = "../../${archiveRoot.name}/${archive.parentFile.name}/repository"
        return listOf(
            "/${f.protocol}/${f.project.owner}/${archive.parentFile.name}/repository",
            "/${f.protocol}/${archiveRoot.name}/${archive.parentFile.name}/repository",
            "/${f.protocol}/${f.project.owner}/$relative",
            "/${f.protocol}/${archive.absolutePath}",
            "/${f.protocol}/${f.project.owner}/${relative.replace("..", "%2e%2e")}",
            "/${f.protocol}/${URLEncoder.encode(archive.absolutePath, StandardCharsets.UTF_8)}",
        )
    }

    private fun assertLfsHidden(f: Fixture, archive: File) {
        val oid = "aabb" + "1".repeat(60)
        val objectFile = root.resolve("lfs/${f.project.owner}/${f.project.name}/objects/aa/bb/$oid")
        Files.createDirectories(objectFile.parent)
        Files.createSymbolicLink(objectFile, archive.resolve("archive-canary.txt").toPath())
        val mvc = MockMvcBuilders.webAppContextSetup(context).addFilters<DefaultMockMvcBuilder>(accessFilter)
            .apply<DefaultMockMvcBuilder>(springSecurity()).build()
        val path = "/git-lfs${f.browser}/objects/$oid"
        val relative = "../../${archiveRoot.name}/${archive.parentFile.name}/repository/archive-canary.txt"
        val paths = listOf(
            path,
            "/git-lfs${f.browser}/objects/$relative",
            "/git-lfs${f.browser}/objects/${relative.replace("..", "%2e%2e").replace("/", "%2f")}",
            "/git-lfs${f.browser}/objects/${URLEncoder.encode(archive.resolve("archive-canary.txt").absolutePath, StandardCharsets.UTF_8)}",
            "/git-lfs/${archiveRoot.name}/${archive.parentFile.name}/objects/$oid",
        )
        try {
            f.actors.forEach { actor ->
                paths.forEach { candidate ->
                    noMarker(f, responseText(request(candidate, actor)))
                    val download = get(URI.create(candidate))
                    val upload = put(URI.create(candidate)).with(csrf()).content("overwrite archived object")
                    if (actor != null) { download.with(user(actor.loginId)); upload.with(user(actor.loginId)) }
                    listOf(mvc.perform(download).andReturn().response, mvc.perform(upload).andReturn().response).forEach { response ->
                        noMarker(f, response.contentAsString)
                        if (candidate == path) (response.status in 400..499) shouldBe true
                    }
                    archive.resolve("archive-canary.txt").readText() shouldBe f.marker
                }
            }
        } finally {
            Files.deleteIfExists(objectFile)
        }
        // The object controller is actually reached, including PUT's CSRF/authentication path.
        val upload = mvc.perform(put(path).with(user(f.manager.loginId)).with(csrf()).content("active-lfs-object")).andReturn().response
        upload.status shouldBe 201
        responseText(request(path, f.manager)) shouldBe "active-lfs-object"
    }

    private fun assertActiveUsable(f: Fixture, markerExpected: Boolean) {
        val working = temp("active-write-")
        clone(f, working, f.manager).succeeds()
        if (markerExpected) File(working, MARKER_FILE).readText() shouldBe f.marker else noDownloadedMarker(f, working)
        listOf(f.manager, f.member).forEachIndexed { index, actor ->
            commitAndPush(f, working, actor, "active-$index.txt", "active-${f.project.id}-$index")
        }
        f.actors.forEach { actor ->
            val destination = temp("active-read-")
            val result = clone(f, destination, actor)
            if (actor != null || f.project.projectScope == ProjectScope.PUBLIC) {
                result.succeeds()
                File(destination, "active-1.txt").readText() shouldBe "active-${f.project.id}-1"
                if (markerExpected) File(destination, MARKER_FILE).readText() shouldBe f.marker else noDownloadedMarker(f, destination)
            } else {
                (result.exit != 0) shouldBe true
                noDownloadedMarker(f, destination)
            }
        }
    }

    init {
        beforeSpec {
            listOf("git", "svn", "hg").forEach { executable ->
                withClue("Required archive HTTP integration prerequisite: $executable on PATH") { cli(null, executable, "--version").succeeds() }
            }
        }
        afterSpec { root.toFile().deleteRecursively() }

        describe("archive isolation through real HTTP servers and CLI clients") {
            vcsNames.keys.forEach { vcs ->
                listOf(ProjectScope.PUBLIC, ProjectScope.PRIVATE).forEach { scope ->
                    it("$vcs $scope keeps archived history unreadable and unwritable for anonymous, member and manager") {
                        val f = fixture(vcs, scope)
                        // Positive controls use the same credentials/routes, not only rejected requests.
                        f.actors.forEach { actor ->
                            val destination = temp("before-reset-")
                            val result = clone(f, destination, actor)
                            if (actor != null || scope == ProjectScope.PUBLIC) {
                                result.succeeds()
                                File(destination, MARKER_FILE).readText() shouldBe f.marker
                            } else {
                                (result.exit != 0) shouldBe true
                                noDownloadedMarker(f, destination)
                            }
                        }
                        // SVN's existing getArchive is a no-op; its ZIP routes are still probed
                        // for nonexposure below, while Git/Hg also prove decompressed ZIP contents.
                        val baselineRoutes = if (vcs == "SUBVERSION") listOf(2, 3) else listOf(2, 3, 4, 5)
                        baselineRoutes.forEach { index ->
                            val response = request(browserPaths(f)[index], f.manager)
                            response.status shouldBe 200
                            responseText(response).contains(f.marker) shouldBe true
                        }

                        val archive = reset(f)
                        archivedMarker(f, archive) shouldBe f.marker
                        val untouched = snapshot(archive)
                        f.actors.forEach { actor ->
                            assertProtocolHidden(f, f.path, actor)
                            assertBrowserHidden(f, actor, archive)
                        }
                        snapshot(archive) shouldBe untouched
                        // Old protocol resolvers can create a fresh empty path. Remove only this
                        // fixture's newly created path before testing another VCS cycle.
                        if (f.project.vcs != f.vcs && f.source.exists()) {
                            Files.isSymbolicLink(f.source.toPath()) shouldBe false
                            f.source.deleteRecursively() shouldBe true
                        }

                        // Return to the original VCS with a new empty repository. A wrong-VCS
                        // rejection alone must not make the canonical-path regression pass.
                        repeat(2) { reset(f) }
                        f.project.vcs shouldBe vcs
                        f.actors.forEach { actor ->
                            guessedPaths(f, archive).forEach { path ->
                                assertProtocolHidden(f, path, actor)
                                snapshot(archive) shouldBe untouched
                            }
                        }
                        val parked = clients.resolve("active-${f.project.id}")
                        Files.move(f.source.toPath(), parked)
                        Files.createSymbolicLink(f.source.toPath(), archive.toPath())
                        try {
                            f.actors.forEach { actor ->
                                assertProtocolHidden(f, f.path, actor)
                                assertBrowserHidden(f, actor, archive)
                            }
                        } finally {
                            Files.deleteIfExists(f.source.toPath())
                            Files.move(parked, f.source.toPath())
                        }
                        assertLfsHidden(f, archive)
                        snapshot(archive) shouldBe untouched
                        archivedMarker(f, archive) shouldBe f.marker
                        assertActiveUsable(f, markerExpected = false)
                        snapshot(archive) shouldBe untouched
                    }
                }
            }

            vcsNames.keys.forEach { vcs ->
                it("$vcs remains usable over HTTP after a partial replacement creation is rolled back") {
                    val f = fixture(vcs, ProjectScope.PRIVATE)
                    val original = snapshot(f.source)
                    val archivesBefore = archiveRoot.listFiles().orEmpty().filter { it.resolve("repository").isDirectory }.map { it.name }.toSet()
                    val nextVcs = when (vcs) { "GIT" -> "SUBVERSION"; "SUBVERSION" -> "MERCURIAL"; else -> "GIT" }
                    val replacement = repositoryService.getRepository(f.project, nextVcs)
                    var replacementCreated = false
                    val failing = object : PlayRepository by replacement {
                        override fun create() {
                            f.source.exists() shouldBe false
                            replacement.create()
                            replacementCreated = true
                            throw IOException("HTTP regression: failure after replacement creation")
                        }
                    }
                    every { repositoryService.getRepository(match { it.id == f.project.id }, nextVcs) } returns failing
                    try {
                        shouldThrow<Exception> {
                            projectService.changeVCS(f.project.id!!, VcsResetConfirmation(f.project.id, f.project.name, vcs, true))
                        }
                    } finally {
                        clearMocks(repositoryService, answers = true)
                    }
                    replacementCreated shouldBe true
                    projectRepository.findById(f.project.id!!).orElseThrow().vcs shouldBe vcs
                    snapshot(f.source) shouldBe original
                    replacement.getDirectory().exists() shouldBe false
                    val archivesAfter = archiveRoot.listFiles().orEmpty().filter { it.resolve("repository").isDirectory }.map { it.name }.toSet()
                    archivesAfter.containsAll(archivesBefore) shouldBe true
                    val retainedTargets = archivesAfter - archivesBefore
                    retainedTargets.size shouldBe 1
                    retainedTargets.single().startsWith("${f.project.id}-failed-") shouldBe true
                    assertActiveUsable(f, markerExpected = true)
                }
            }

            it("reset waits for an actual Git upload-pack response stream before moving its open repository") {
                val f = fixture("GIT", ProjectScope.PUBLIC)
                val pause = StreamPause.Pending("${f.path}/git-upload-pack")
                streamPause.pending.set(pause)
                val executor = Executors.newFixedThreadPool(2)
                val destination = temp("in-flight-clone-")
                try {
                    val cloning = executor.submit<Command> {
                        cli(null, "git", "-c", "protocol.version=0", "clone", url(f.path), destination.absolutePath)
                    }
                    pause.open.await(20, TimeUnit.SECONDS) shouldBe true
                    val started = CountDownLatch(1)
                    val resetting = executor.submit<File> { started.countDown(); reset(f) }
                    started.await(10, TimeUnit.SECONDS) shouldBe true
                    shouldThrow<TimeoutException> { resetting.get(300, TimeUnit.MILLISECONDS) }
                    f.source.isDirectory shouldBe true
                    archiveRoot.listFiles().orEmpty().none { it.name.startsWith("${f.project.id}-git-") } shouldBe true
                    pause.release.countDown()
                    cloning.get(45, TimeUnit.SECONDS).succeeds()
                    File(destination, MARKER_FILE).readText() shouldBe f.marker
                    val archive = resetting.get(45, TimeUnit.SECONDS)
                    archivedMarker(f, archive) shouldBe f.marker
                    assertProtocolHidden(f, f.path, f.manager)
                } finally {
                    pause.release.countDown()
                    streamPause.pending.set(null)
                    executor.shutdownNow()
                    executor.awaitTermination(50, TimeUnit.SECONDS) shouldBe true
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class StreamConfiguration {
        @Bean
        fun archiveHttpStreamPause() = StreamPause()

        @Bean
        @Primary
        fun archiveHttpRepositoryService(@Qualifier("repositoryService") original: RepositoryService): RepositoryService = spyk(original)
    }

    /** Blocks a real JGit response write while its repository is open, inside RepositoryAccessFilter. */
    @Order(Ordered.HIGHEST_PRECEDENCE + 30)
    class StreamPause : OncePerRequestFilter() {
        data class Pending(val path: String, val open: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
        val pending = AtomicReference<Pending?>()

        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            val pause = pending.get()
            if (pause == null || pause.path != request.requestURI) {
                chain.doFilter(request, response)
                return
            }
            val output = response.outputStream
            val wrapped = object : ServletOutputStream() {
                private var paused = false
                private fun awaitRelease() {
                    if (!paused) {
                        paused = true
                        pause.open.countDown()
                        check(pause.release.await(30, TimeUnit.SECONDS)) { "Timed out awaiting test response release" }
                    }
                }
                override fun isReady() = output.isReady
                override fun setWriteListener(listener: WriteListener?) = output.setWriteListener(listener)
                override fun write(value: Int) { awaitRelease(); output.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { awaitRelease(); output.write(bytes, offset, length) }
                override fun flush() = output.flush()
                override fun close() = output.close()
            }
            chain.doFilter(request, object : HttpServletResponseWrapper(response) {
                override fun getOutputStream() = wrapped
            })
        }
    }
}
