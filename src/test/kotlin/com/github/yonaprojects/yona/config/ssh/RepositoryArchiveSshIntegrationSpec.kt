package com.github.yonaprojects.yona.config.ssh

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.project.ProjectService
import com.github.yonaprojects.yona.domain.project.ProjectUser
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.project.RepositoryResetFailure
import com.github.yonaprojects.yona.domain.project.VcsResetConfirmation
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.sshkey.SshAuthService
import com.github.yonaprojects.yona.domain.sshkey.SshKeyService
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.BareCommit
import com.github.yonaprojects.yona.domain.vcs.PlayRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.spyk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Real MINA SSH and the external sshd forced-command relay; no host sshd configuration is touched. */
@Import(RepositoryArchiveSshIntegrationSpec.FailureConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RepositoryArchiveSshIntegrationSpec @Autowired constructor(
    private val mina: YonaMinaSshServer,
    private val relay: SshRelayServer,
    private val sshAuthService: SshAuthService,
    private val sshKeyService: SshKeyService,
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val projectUserRepository: ProjectUserRepository,
    private val roleRepository: RoleRepository,
    private val projectService: ProjectService,
    private val repositoryService: RepositoryService
) : AbstractIntegrationTest() {
    override fun extensions() = listOf(SpringExtension)

    @TestConfiguration(proxyBeanMethods = false)
    class FailureConfiguration {
        @Bean
        @Primary
        fun resetSshRepositoryService(@Qualifier("repositoryService") original: RepositoryService): RepositoryService =
            spyk(original)
    }

    companion object {
        // A short private parent also avoids macOS's Unix socket path-length limit.
        private val root = Files.createTempDirectory(Path.of("/tmp"), "yona-ssh-reset-")
        private val archiveRoot = Files.createTempDirectory(root, "archive-")
        private val socket = root.resolve("relay.sock")
        private val wrapper = root.resolve("relay.py").toFile().apply { writeText(RELAY_WRAPPER) }

        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            listOf("git", "svn", "hg", "lfs", "upload").forEach { kind ->
                registry.add("yona.$kind.base-dir") { root.resolve(kind).toString() }
            }
            registry.add("spring.web.resources.static-locations") { root.resolve("static").toUri().toString() }
            registry.add("yona.repository-archive.base-dir") { archiveRoot.toString() }
            registry.add("yona.repository-archive.single-serving-process") { "true" }
            registry.add("yona.git.default-branch") { "main" }
            registry.add("yona.ssh.mina.enabled") { "true" }
            registry.add("yona.ssh.mina.port") { "0" }
            registry.add("yona.ssh.mina.host-key-path") { root.resolve("host-key").toString() }
            registry.add("yona.ssh.internal-secret-path") { root.resolve("internal-secret").toString() }
            registry.add("yona.ssh.relay.enabled") { "true" }
            registry.add("yona.ssh.relay.socket-path") { socket.toString() }
        }

        // Same CLI hook as SshRelayServerIntegrationSpec: the relay receives an already authenticated
        // principal. It is not an anonymous SSH endpoint, and this harness does not impersonate sshd.
        private const val RELAY_WRAPPER = """import os
import socket
import sys
import threading

sock_path, principal = sys.argv[1:3]
args = sys.argv[3:]
if '-G' in args:
    sys.exit(0)
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(sock_path)
s.sendall((principal + '\n' + args[-1] + '\n').encode())

def pump():
    try:
        while True:
            chunk = os.read(0, 65536)
            if not chunk:
                break
            s.sendall(chunk)
    except OSError:
        pass
    finally:
        try:
            s.shutdown(socket.SHUT_WR)
        except OSError:
            pass

threading.Thread(target=pump, daemon=True).start()
try:
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        sys.stdout.buffer.write(chunk)
        sys.stdout.buffer.flush()
finally:
    s.close()
"""
    }

    private fun builder(vararg args: String, directory: File? = null, ssh: String? = null): ProcessBuilder =
        ProcessBuilder(*args).apply {
            if (directory != null) directory(directory)
            environment()["LC_ALL"] = "C"
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            environment()["GIT_TERMINAL_PROMPT"] = "0"
            environment()["HGPLAIN"] = "1"
            environment()["HGRCPATH"] = "/dev/null"
            if (ssh != null) environment()["GIT_SSH_COMMAND"] = ssh
        }

    private fun run(vararg args: String, directory: File? = null, ssh: String? = null): Pair<Int, String> {
        val log = Files.createTempFile(root, "cli-", ".log").toFile()
        val process = builder(*args, directory = directory, ssh = ssh)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            process.outputStream.close()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            withClue("${args.joinToString(" ")}\n${log.readText()}") { finished shouldBe true }
            return process.exitValue() to log.readText()
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            log.delete()
        }
    }

    private fun success(result: Pair<Int, String>): String {
        withClue(result.second) { result.first shouldBe 0 }
        return result.second
    }

    private fun keyPair(): Pair<File, String> {
        val key = Files.createTempDirectory(root, "key-").resolve("id_ed25519").toFile()
        success(run("ssh-keygen", "-t", "ed25519", "-N", "", "-f", key.absolutePath))
        return key to File("${key.absolutePath}.pub").readText().trim()
    }

    private fun sshArguments(key: File): List<String> = listOf(
        "ssh", "-F", "/dev/null", "-i", key.absolutePath, "-o", "BatchMode=yes",
        "-o", "IdentityAgent=none", "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null", "-o", "ConnectTimeout=5", "-p", mina.boundPort.toString()
    )

    private fun sshCommand(transport: String, key: File, principal: String): String =
        if (transport == "embedded") sshArguments(key).joinToString(" ")
        else "python3 ${wrapper.absolutePath} $socket $principal"

    private fun remote(vcs: String, ssh: String, vararg args: String, directory: File? = null): Pair<Int, String> =
        if (vcs == "GIT") run("git", *args, directory = directory, ssh = ssh)
        else run("hg", "--config", "ui.ssh=$ssh", *args, directory = directory)

    private fun commit(vcs: String, directory: File, filename: String, content: String) {
        File(directory, filename).writeText(content)
        if (vcs == "GIT") {
            success(run("git", "add", filename, directory = directory))
            success(run("git", "-c", "user.name=Archive SSH", "-c", "user.email=archive@example.com",
                "commit", "-m", filename, directory = directory))
        } else {
            success(run("hg", "addremove", filename, directory = directory))
            success(run("hg", "--config", "ui.username=Archive SSH <archive@example.com>",
                "commit", "-m", filename, directory = directory))
        }
    }

    private fun push(vcs: String, ssh: String, url: String, directory: File): Pair<Int, String> =
        if (vcs == "GIT") remote(vcs, ssh, "push", url, "HEAD:refs/heads/main", directory = directory)
        else remote(vcs, ssh, "push", "--force", url, directory = directory)

    private fun reset(project: Project): Project = projectService.changeVCS(
        project.id!!, VcsResetConfirmation(project.id, project.name, project.vcs, true)
    )

    private fun archived(project: Project, vcs: String): File = archiveRoot.toFile().listFiles()!!
        .single {
            it.name.startsWith("${project.id}-${if (vcs == "GIT") "git" else "hg"}-") &&
                it.resolve("repository").isDirectory
        }.resolve("repository")

    private fun snapshot(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256")
            .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) } }

    private fun marker(vcs: String, directory: File): String = if (vcs == "GIT") {
        success(run("git", "--git-dir=${directory.absolutePath}", "show", "main:archive-marker.txt"))
    } else {
        success(run("hg", "--cwd", directory.absolutePath, "cat", "-r", "tip", "archive-marker.txt"))
    }

    private fun advertisement(input: InputStream): String = buildString {
        while (true) {
            val header = input.readNBytes(4)
            header.size shouldBe 4
            val size = header.toString(Charsets.US_ASCII).toInt(16)
            if (size == 0) break
            val packet = input.readNBytes(size - 4)
            packet.size shouldBe size - 4
            append(packet.toString(Charsets.UTF_8))
        }
    }

    init {
        beforeSpec {
            mina.isEnabled shouldBe true
            (mina.boundPort > 0) shouldBe true
            relay.isRunning shouldBe true
            // Missing real-client prerequisites fail the suite rather than silently skipping security cases.
            listOf(listOf("git", "--version"), listOf("hg", "--version"), listOf("python3", "--version"))
                .forEach { success(run(*it.toTypedArray())) }
        }
        afterEach { clearMocks(repositoryService, answers = true) }
        afterSpec {
            mina.stop()
            relay.stop()
            root.toFile().deleteRecursively()
        }

        describe("archived repositories are unreachable through SSH, including canonical symlink escapes") {
            for ((transport, vcs) in listOf("embedded" to "GIT", "relay" to "GIT", "relay" to "MERCURIAL")) {
                for (scope in listOf(ProjectScope.PUBLIC, ProjectScope.PRIVATE)) {
                    it("$transport $vcs $scope: member and manager clone/push, rollback, archive denial, and fresh active access") {
                        val suffix = UUID.randomUUID().toString().take(8)
                        val owner = userRepository.save(User(loginId = "ssh-owner-$suffix", name = "Manager", email = "owner-$suffix@example.com"))
                        val member = userRepository.save(User(loginId = "ssh-member-$suffix", name = "Member", email = "member-$suffix@example.com"))
                        val project = projectRepository.save(Project(owner = owner.loginId, name = "reset-$suffix", vcs = vcs, projectScope = scope))
                        repositoryService.getRepository(project).create()
                        val source = repositoryService.getRepository(project).getDirectory()
                        val secret = "archived-ssh-content-$suffix"
                        if (vcs == "GIT") {
                            BareCommit(project, owner, root.resolve("git").toString())
                                .commitTextFile("archive-marker.txt", secret, "archive marker")
                        } else {
                            commit(vcs, source, "archive-marker.txt", secret)
                        }
                        val expectedMarker = marker(vcs, source)
                        val actors = listOf(owner to RoleType.MANAGER, member to RoleType.MEMBER).map { (user, type) ->
                            val role = roleRepository.findById(type.roleType).orElseGet {
                                roleRepository.save(Role(id = type.roleType, name = type.name.lowercase(), active = true))
                            }
                            projectUserRepository.save(ProjectUser(project = project, user = user, role = role))
                            val (key, publicKey) = keyPair()
                            sshKeyService.create(user, "archive test", publicKey)
                            val principal = sshAuthService.encodePrincipal(sshAuthService.authenticate(publicKey)!!)
                            Triple(user, key, sshCommand(transport, key, principal))
                        }
                        val oldPath = "${project.owner}/${project.name}${if (vcs == "GIT") ".git" else ""}"
                        val oldUrl = "ssh://git@127.0.0.1:${mina.boundPort}/$oldPath"

                        // SSH has no anonymous repository session: fail at authentication, even for PUBLIC.
                        fun anonymousDenied(path: String) {
                            val commands = if (vcs == "GIT") listOf("git-upload-pack '$path'", "git-receive-pack '$path'")
                                else listOf("hg -R '$path' serve --stdio")
                            for (command in commands) {
                                val anonymous = run("ssh", "-F", "/dev/null", "-o", "BatchMode=yes", "-o", "PreferredAuthentications=none",
                                    "-o", "PubkeyAuthentication=no", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
                                    "-o", "ConnectTimeout=5", "-p", mina.boundPort.toString(), "anonymous@127.0.0.1", command)
                                anonymous.first shouldBe 255
                                anonymous.second shouldContain "Permission denied"
                                anonymous.second shouldNotContain secret
                            }
                        }
                        anonymousDenied(oldPath)

                        for ((user, key, ssh) in actors) {
                            val clone = Files.createTempDirectory(root, "active-").toFile()
                            success(remote(vcs, ssh, "clone", oldUrl, clone.absolutePath))
                            File(clone, "archive-marker.txt").readText() shouldBe expectedMarker
                            commit(vcs, clone, "active-${user.loginId}.txt", "active push")
                            success(push(vcs, ssh, oldUrl, clone))
                            val fetched = Files.createTempDirectory(root, "active-check-").toFile()
                            success(remote(vcs, ssh, "clone", oldUrl, fetched.absolutePath))
                            File(fetched, "active-${user.loginId}.txt").readText() shouldBe "active push"
                            if (vcs == "MERCURIAL") {
                                val unsupported = run(*(sshArguments(key) + listOf("git@127.0.0.1", "hg -R '$oldPath' serve --stdio")).toTypedArray())
                                (unsupported.first != 0) shouldBe true
                                unsupported.second shouldNotContain secret
                            }
                        }

                        // Fail after the real target exists and the original has actually moved.
                        val nextVcs = if (vcs == "GIT") "SUBVERSION" else "GIT"
                        val target = repositoryService.getRepository(project, nextVcs)
                        val beforeFailure = snapshot(source)
                        var failureInjected = false
                        every { repositoryService.getRepository(match { it.id == project.id }, nextVcs) } answers {
                            val actual = callOriginal() as PlayRepository
                            object : PlayRepository by actual {
                                override fun create() {
                                    source.exists() shouldBe false
                                    archived(project, vcs).isDirectory shouldBe true
                                    actual.create()
                                    actual.getDirectory().isDirectory shouldBe true
                                    failureInjected = true
                                    throw IOException("injected after real target creation")
                                }
                            }
                        }
                        try {
                            shouldThrow<RepositoryResetFailure> { reset(project) }
                        } finally {
                            clearMocks(repositoryService, answers = true)
                        }
                        failureInjected shouldBe true
                        projectRepository.findById(project.id!!).orElseThrow().vcs shouldBe vcs
                        target.getDirectory().exists() shouldBe false
                        snapshot(source) shouldBe beforeFailure
                        archiveRoot.toFile().listFiles()!!.none {
                            it.name.startsWith("${project.id}-${if (vcs == "GIT") "git" else "hg"}-") && it.resolve("repository").exists()
                        } shouldBe true
                        val failedTarget = archiveRoot.toFile().listFiles()!!.single {
                            it.name.startsWith("${project.id}-failed-") && it.resolve("repository").isDirectory
                        }.resolve("repository")
                        failedTarget.walkTopDown().any { it.isFile } shouldBe true

                        val writers = actors.map { (user, _, ssh) ->
                            val clone = Files.createTempDirectory(root, "restored-").toFile()
                            success(remote(vcs, ssh, "clone", oldUrl, clone.absolutePath))
                            File(clone, "archive-marker.txt").readText() shouldBe expectedMarker
                            commit(vcs, clone, "restored-${user.loginId}.txt", "push after rollback")
                            success(push(vcs, ssh, oldUrl, clone))
                            val fetched = Files.createTempDirectory(root, "restored-check-").toFile()
                            success(remote(vcs, ssh, "clone", oldUrl, fetched.absolutePath))
                            File(fetched, "restored-${user.loginId}.txt").readText() shouldBe "push after rollback"
                            commit(vcs, clone, "archive-marker.txt", "unauthorized overwrite")
                            ssh to clone
                        }
                        val changed = reset(project)
                        changed.id shouldBe project.id
                        changed.vcs shouldBe nextVcs
                        val archive = archived(project, vcs)
                        marker(vcs, archive) shouldBe expectedMarker
                        val preserved = snapshot(archive)
                        source.exists() shouldBe false

                        val guessedPaths = listOf(
                            oldPath,
                            archive.absolutePath,
                            "archive/${archive.parentFile.name}/repository",
                            "../${archiveRoot.fileName}/${archive.parentFile.name}/repository",
                            "${project.owner}/../../${archiveRoot.fileName}/${archive.parentFile.name}/repository",
                            "%2e%2e/%2e%2e/${archiveRoot.fileName}/${archive.parentFile.name}/repository",
                            "${project.owner}/%2e%2e/%2e%2e/${archiveRoot.fileName}/${archive.parentFile.name}/repository"
                        )
                        guessedPaths.forEach(::anonymousDenied)
                        for ((ssh, writer) in writers) {
                            for (path in guessedPaths) {
                                val url = "ssh://git@127.0.0.1:${mina.boundPort}/$path"
                                val destination = Files.createTempDirectory(root, "denied-").toFile()
                                val read = remote(vcs, ssh, "clone", url, destination.absolutePath)
                                withClue("$transport $vcs read $path: ${read.second}") { (read.first != 0) shouldBe true }
                                read.second shouldNotContain secret
                                File(destination, "archive-marker.txt").exists() shouldBe false
                                val write = if (vcs == "GIT") remote(vcs, ssh, "push", "--force", url,
                                    "HEAD:refs/heads/archive-intrusion", directory = writer) else push(vcs, ssh, url, writer)
                                withClue("$transport $vcs write $path: ${write.second}") { (write.first != 0) shouldBe true }
                                write.second shouldNotContain secret
                                snapshot(archive) shouldBe preserved
                            }
                        }

                        // A valid, authorized original owner/project path now resolves to the archive.
                        // This reaches the actual protocol handler instead of merely failing project lookup.
                        Files.createSymbolicLink(source.toPath(), archive.toPath())
                        try {
                            anonymousDenied(oldPath)
                            for ((ssh, writer) in writers) {
                                val destination = Files.createTempDirectory(root, "symlink-denied-").toFile()
                                val read = remote(vcs, ssh, "clone", oldUrl, destination.absolutePath)
                                withClue(read.second) { (read.first != 0) shouldBe true }
                                read.second shouldNotContain secret
                                read.second shouldNotContain archive.absolutePath
                                File(destination, "archive-marker.txt").exists() shouldBe false
                                val write = if (vcs == "GIT") remote(vcs, ssh, "push", "--force", oldUrl,
                                    "HEAD:refs/heads/archive-intrusion", directory = writer) else push(vcs, ssh, oldUrl, writer)
                                withClue(write.second) { (write.first != 0) shouldBe true }
                                write.second shouldNotContain secret
                                write.second shouldNotContain archive.absolutePath
                                snapshot(archive) shouldBe preserved
                            }
                            if (vcs == "MERCURIAL") {
                                for ((_, key, _) in actors) {
                                    val unsupported = run(*(sshArguments(key) + listOf("git@127.0.0.1", "hg -R '$oldPath' serve --stdio")).toTypedArray())
                                    (unsupported.first != 0) shouldBe true
                                    unsupported.second shouldNotContain secret
                                }
                            }
                        } finally {
                            Files.delete(source.toPath())
                        }
                        snapshot(archive) shouldBe preserved
                        marker(vcs, archive) shouldBe expectedMarker

                        // Complete the real VCS cycle: the same URL serves a fresh repository, never the archive.
                        var active = changed
                        while (active.vcs != vcs) active = reset(active)
                        for ((user, _, ssh) in actors) {
                            val clone = Files.createTempDirectory(root, "fresh-active-").toFile()
                            success(remote(vcs, ssh, "clone", oldUrl, clone.absolutePath))
                            File(clone, "archive-marker.txt").exists() shouldBe false
                            commit(vcs, clone, "new-${user.loginId}.txt", "fresh repository")
                            success(push(vcs, ssh, oldUrl, clone))
                            val fetched = Files.createTempDirectory(root, "fresh-check-").toFile()
                            success(remote(vcs, ssh, "clone", oldUrl, fetched.absolutePath))
                            File(fetched, "new-${user.loginId}.txt").readText() shouldBe "fresh repository"
                            File(fetched, "archive-marker.txt").exists() shouldBe false
                        }
                        snapshot(archive) shouldBe preserved
                    }
                }
            }
        }

        describe("real open SSH repository handles drain before archive isolation") {
            for (transport in listOf("embedded", "relay")) {
                for (service in listOf("git-upload-pack", "git-receive-pack")) {
                    it("$transport $service holds the original until protocol completion") {
                        val suffix = UUID.randomUUID().toString().take(8)
                        val owner = userRepository.save(User(loginId = "drain-$suffix", name = "Drain", email = "drain-$suffix@example.com"))
                        val role = roleRepository.findById(RoleType.MANAGER.roleType).orElseGet {
                            roleRepository.save(Role(id = RoleType.MANAGER.roleType, name = "manager", active = true))
                        }
                        val project = projectRepository.save(Project(owner = owner.loginId, name = "held-$suffix", vcs = "GIT", projectScope = ProjectScope.PRIVATE))
                        val other = projectRepository.save(Project(owner = owner.loginId, name = "other-$suffix", vcs = "GIT", projectScope = ProjectScope.PRIVATE))
                        for (p in listOf(project, other)) {
                            projectUserRepository.save(ProjectUser(project = p, user = owner, role = role))
                            repositoryService.getRepository(p).create()
                            BareCommit(p, owner, root.resolve("git").toString())
                                .commitTextFile("archive-marker.txt", "held-${p.name}", "held marker")
                        }
                        val source = repositoryService.getRepository(project).getDirectory()
                        val expectedHeldMarker = marker("GIT", source)
                        val expectedOtherMarker = marker("GIT", repositoryService.getRepository(other).getDirectory())
                        val head = success(run("git", "--git-dir=${source.absolutePath}", "rev-parse", "main")).trim()
                        val (key, publicKey) = keyPair()
                        sshKeyService.create(owner, "drain", publicKey)
                        val principal = sshAuthService.encodePrincipal(sshAuthService.authenticate(publicKey)!!)
                        val ssh = sshCommand(transport, key, principal)
                        val command = "$service '${project.owner}/${project.name}.git'"
                        val args = if (transport == "embedded") sshArguments(key) + listOf("git@127.0.0.1", command)
                            else listOf("python3", wrapper.absolutePath, socket.toString(), principal, "localhost", command)
                        val errors = Files.createTempFile(root, "session-", ".log").toFile()
                        val session = builder(*args.toTypedArray()).redirectError(errors).start()
                        val completed = CompletableFuture<Project>()
                        val resetThread = Thread({
                            try { completed.complete(reset(project)) } catch (failure: Throwable) { completed.completeExceptionally(failure) }
                        }, "ssh-reset-drain-$suffix").apply { isDaemon = true }
                        try {
                            // Reading the actual ref advertisement proves JGit opened this repository and
                            // is now waiting on this real channel's input, not a mocked lock callback.
                            val advertised = CompletableFuture.supplyAsync { advertisement(session.inputStream) }
                                .get(10, TimeUnit.SECONDS)
                            advertised shouldContain head
                            resetThread.start()
                            shouldThrow<TimeoutException> { completed.get(250, TimeUnit.MILLISECONDS) }
                            source.isDirectory shouldBe true
                            archiveRoot.toFile().listFiles()!!.none { it.name.startsWith("${project.id}-") } shouldBe true

                            val otherUrl = "ssh://git@127.0.0.1:${mina.boundPort}/${other.owner}/${other.name}.git"
                            val independent = Files.createTempDirectory(root, "independent-").toFile()
                            val waitingReader = CompletableFuture.supplyAsync {
                                success(remote("GIT", ssh, "clone", otherUrl, independent.absolutePath))
                                File(independent, "archive-marker.txt").readText()
                            }
                            shouldThrow<TimeoutException> { waitingReader.get(250, TimeUnit.MILLISECONDS) }
                            source.isDirectory shouldBe true

                            // An empty want/update list ends upload-pack/receive-pack normally. Keep the
                            // channel open until that protocol flush; resetting before this is a failure.
                            session.outputStream.write("0000".toByteArray())
                            session.outputStream.flush()
                            session.outputStream.close()
                            withClue(errors.readText()) { session.waitFor(10, TimeUnit.SECONDS) shouldBe true }
                            withClue(errors.readText()) { session.exitValue() shouldBe 0 }
                            completed.get(10, TimeUnit.SECONDS).vcs shouldBe "SUBVERSION"
                            waitingReader.get(20, TimeUnit.SECONDS) shouldBe expectedOtherMarker
                            source.exists() shouldBe false
                            marker("GIT", archived(project, "GIT")) shouldBe expectedHeldMarker
                        } finally {
                            session.outputStream.close()
                            if (session.isAlive) {
                                session.descendants().forEach { it.destroyForcibly() }
                                session.destroyForcibly()
                                session.waitFor(5, TimeUnit.SECONDS)
                            }
                            session.inputStream.close()
                            if (resetThread.state != Thread.State.NEW) resetThread.join(10_000)
                            errors.delete()
                        }
                    }
                }
            }
        }
    }
}
