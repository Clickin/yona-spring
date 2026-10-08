package com.github.yonaprojects.yona.domain.vcs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class RepositoryMirrorLockSpec : DescribeSpec({
    describe("physical SVN writer exclusivity") {
        it("retains the same inode across lease loss and releases only when the writer exits") {
            val base = Files.createTempDirectory("mirror-lock").toRealPath()
            val locks = RepositoryMirrorLock(base.toString())
            try {
                locks.withLock(1) {
                    shouldThrow<MirrorFailure> { locks.withLock(1) {} }.code shouldBe "WRITER_STILL_ACTIVE"
                }
                val path = base.resolve(".mirror-locks/1.lock")
                val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
                locks.withLock(1) {}
                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey() shouldBe before
            } finally { base.toFile().deleteRecursively() }
        }
        it("prevents another JVM writing until a crashed process has actually exited") {
            val base = Files.createTempDirectory("mirror-process-lock").toRealPath()
            val locks = RepositoryMirrorLock(base.toString())
            val source = base.resolve("LockProcess.java")
            Files.writeString(source, """
                import java.nio.channels.*;
                import java.nio.file.*;
                public class LockProcess {
                    public static void main(String[] args) throws Exception {
                        try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                            var lock = channel.tryLock();
                            System.out.println(lock == null ? "BUSY" : "LOCKED");
                            System.out.flush();
                            if (lock != null) { System.in.read(); lock.release(); }
                        }
                    }
                }
            """.trimIndent())
            locks.withLock(1) {}
            val path = base.resolve(".mirror-locks/1.lock")
            fun child() = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), source.toString(), path.toString()).redirectErrorStream(true).start()
            var holder: Process? = null
            try {
                locks.withLock(1) {
                    val blocked = child()
                    blocked.inputStream.bufferedReader().readLine() shouldBe "BUSY"
                    blocked.waitFor(20, TimeUnit.SECONDS) shouldBe true
                    blocked.exitValue() shouldBe 0
                }
                holder = child()
                holder.inputStream.bufferedReader().readLine() shouldBe "LOCKED"
                shouldThrow<MirrorFailure> { locks.withLock(1) {} }.code shouldBe "WRITER_STILL_ACTIVE"
                holder.destroyForcibly()
                holder.waitFor(20, TimeUnit.SECONDS) shouldBe true
                locks.withLock(1) { Files.exists(path) shouldBe true }
            } finally { holder?.destroyForcibly()?.waitFor(); base.toFile().deleteRecursively() }
        }
        it("rejects a symlink that could give two workers different lock inodes") {
            val base = Files.createTempDirectory("mirror-lock-symlink").toRealPath()
            val other = Files.createTempDirectory("mirror-lock-other").toRealPath()
            try {
                Files.createSymbolicLink(base.resolve(".mirror-locks"), other)
                shouldThrow<MirrorFailure> { RepositoryMirrorLock(base.toString()).withLock(1) {} }.code shouldBe "UNSAFE_LOCK_PATH"
            } finally { base.toFile().deleteRecursively(); other.toFile().deleteRecursively() }
        }
    }
})
