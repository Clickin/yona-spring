package com.github.yonaprojects.yona.domain.vcs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import org.tmatesoft.svn.core.*
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory
import org.tmatesoft.svn.core.internal.io.fs.FSFS
import org.tmatesoft.svn.core.internal.io.fs.FSRepository
import org.tmatesoft.svn.core.internal.wc.SVNWCProperties
import org.tmatesoft.svn.core.io.SVNRepository
import org.tmatesoft.svn.core.io.SVNRepositoryFactory
import org.tmatesoft.svn.core.replicator.SVNRepositoryReplicator
import java.nio.file.Files
import java.time.Instant

class SvnMirrorSynchronizerSpec : DescribeSpec({
    FSRepositoryFactory.setup()
    fun repository(name: String, root: java.nio.file.Path): SVNRepository = SVNRepositoryFactory.create(
        SVNRepositoryFactory.createLocalRepository(root.resolve(name).toFile(), true, false)
    )
    fun commit(repository: SVNRepository, number: Int): Long {
        val editor = repository.getCommitEditor("revision $number #1", null)
        editor.openRoot(-1)
        editor.addDir("directory-$number", null, -1)
        editor.closeDir()
        editor.closeDir()
        val revision = editor.closeEdit().newRevision
        repository.setRevisionPropertyValue(revision, "svn:author", SVNPropertyValue.create("원본-author"))
        repository.setRevisionPropertyValue(revision, "svn:date", SVNPropertyValue.create("2026-10-08T01:02:03.123456Z"))
        repository.setRevisionPropertyValue(revision, "custom:binary", SVNPropertyValue.create("custom:binary", byteArrayOf(0, 1, -1)))
        return revision
    }
    fun run(source: SVNRepository, target: SVNRepository, state: RepositoryMirror, goal: Long, afterIndex: (Long, SVNProperties) -> Unit = { _, _ -> }) {
        SvnMirrorSynchronizer.synchronize(source, target, state, goal, 1, {}, { false },
            verified = { revision, youngest -> state.lastVerifiedRevision = revision; state.localYoungestRevision = youngest },
            indexed = { revision, props -> afterIndex(revision, props); state.lastIndexedRevision = revision })
    }
    describe("SVN mirror revision metadata") {
        it("accepts nullable authors and at most 255 UTF-16 units without changing the date") {
            val date = "2026-10-08T01:02:03.123456Z"
            val props = SVNProperties().apply { put(SVNRevisionProperty.DATE, date) }
            svnMirrorRevisionDate(props) shouldBe Instant.parse(date)
            props.put(SVNRevisionProperty.AUTHOR, "원".repeat(253) + "\uD801\uDC00")
            svnMirrorRevisionDate(props) shouldBe Instant.parse(date)
            props.put(SVNRevisionProperty.AUTHOR, "원".repeat(254) + "\uD801\uDC00")
            val failure = shouldThrow<MirrorFailure> { svnMirrorRevisionDate(props) }
            failure.code shouldBe "AUTHOR_TOO_LONG"
            failure.attention shouldBe true
            failure.message shouldBe "AUTHOR_TOO_LONG"
        }
        it("rejects missing and malformed dates with a safe attention error") {
            val props = SVNProperties()
            for (date in listOf(null, "", "not-a-date")) {
                if (date == null) props.remove(SVNRevisionProperty.DATE) else props.put(SVNRevisionProperty.DATE, date)
                val failure = shouldThrow<MirrorFailure> { svnMirrorRevisionDate(props) }
                failure.code shouldBe "INVALID_REVISION_DATE"
                failure.attention shouldBe true
                failure.message shouldBe "INVALID_REVISION_DATE"
            }
        }
    }
    describe("SVNRepositoryReplicator mirror recovery") {
        it("verifies empty r0 and copies custom additions and deletions exactly") {
            val root = Files.createTempDirectory("mirror-r0")
            val source = repository("source", root)
            val target = repository("target", root)
            try {
                source.setRevisionPropertyValue(0, "custom:source", SVNPropertyValue.create("source"))
                source.setRevisionPropertyValue(0, SVNRevisionProperty.DATE, null)
                target.setRevisionPropertyValue(0, "custom:deleted", SVNPropertyValue.create("remove"))
                val state = RepositoryMirror()
                run(source, target, state, 0)
                state.lastVerifiedRevision shouldBe 0
                state.lastIndexedRevision shouldBe 0
                target.getRevisionProperties(0, null) shouldBe source.getRevisionProperties(0, null)
                (source.getRepositoryUUID(true) != target.getRepositoryUUID(true)) shouldBe true
            } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
        }
        it("preserves a valid completely empty r0 property hash without deleting its file") {
            val root = Files.createTempDirectory("mirror-empty-r0")
            val source = repository("source", root)
            val target = repository("target", root)
            try {
                source.latestRevision shouldBe 0L
                val fs = FSFS((source as FSRepository).repositoryRootDir)
                fs.open()
                try {
                    val file = fs.getRevisionPropertiesFile(0, false)
                    val temporary = Files.createTempFile(file.parentFile.toPath(), "empty-r0-", ".tmp")
                    try {
                        SVNWCProperties.setProperties(SVNProperties(), file, temporary.toFile(), "END")
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                } finally { fs.close() }
                source.getRevisionProperties(0, null).isEmpty shouldBe true
                val state = RepositoryMirror()
                run(source, target, state, 0)
                state.lastVerifiedRevision shouldBe 0L
                state.lastIndexedRevision shouldBe 0L
                target.getRevisionProperties(0, null).isEmpty shouldBe true
                target.setRevisionPropertyValue(0, "custom:later", SVNPropertyValue.create("still writable"))
                run(source, target, RepositoryMirror(), 0)
                target.getRevisionProperties(0, null).isEmpty shouldBe true
            } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
        }
        it("fixes initial R and leaves newly added source revisions for the next run") {
            val root = Files.createTempDirectory("mirror-fixed-head")
            val source = repository("source", root)
            val target = repository("target", root)
            try {
                commit(source, 1)
                val state = RepositoryMirror(initialImportTargetRevision = 1)
                run(source, target, state, 1) { revision, _ -> if (revision == 0L) commit(source, 2) }
                target.latestRevision shouldBe 1
                source.latestRevision shouldBe 2
                state.initialImportTargetRevision shouldBe 1
                state.lastIndexedRevision shouldBe 1
                run(source, target, state, source.latestRevision)
                target.latestRevision shouldBe 2
                for (revision in 0L..2L) target.getRevisionProperties(revision, null) shouldBe source.getRevisionProperties(revision, null)
                target.checkPath("directory-2", 2) shouldBe SVNNodeKind.DIR
            } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
        }
        it("repairs committed revisions when the replicator could not write revision properties") {
            val root = Files.createTempDirectory("mirror-revprop-failure")
            val source = repository("source", root)
            val target = repository("target", root)
            try {
                commit(source, 1)
                val broken = spyk(target)
                every { broken.setRevisionPropertyValue(1, any(), any()) } throws SVNException(SVNErrorMessage.create(SVNErrorCode.REPOS_HOOK_FAILURE, "injected revprop failure"))
                SVNRepositoryReplicator.newInstance().replicateRepository(source, broken, 1, 1)
                target.latestRevision shouldBe 1
                target.setRevisionPropertyValue(1, "unexpected", SVNPropertyValue.create("delete me"))
                val state = RepositoryMirror()
                run(source, target, state, 1)
                target.getRevisionProperties(1, null) shouldBe source.getRevisionProperties(1, null)
                state.lastVerifiedRevision shouldBe 1
                state.lastIndexedRevision shouldBe 1
            } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
        }
        for ((property, invalidValue, code) in listOf(
            Triple(SVNRevisionProperty.AUTHOR, "원".repeat(254) + "\uD801\uDC00", "AUTHOR_TOO_LONG"),
            Triple(SVNRevisionProperty.DATE, null, "INVALID_REVISION_DATE")
        )) {
            it("repairs unverified $property after a source fix without advancing or rewinding cursors") {
                val root = Files.createTempDirectory("mirror-unsupported-revprop")
                val source = repository("source", root)
                val target = repository("target", root)
                try {
                    commit(source, 1)
                    val state = RepositoryMirror(initialImportTargetRevision = 2)
                    run(source, target, state, 1)
                    commit(source, 2)
                    // SVNKit can reject missing dates before copying; cover a committed, unverified revision.
                    if (property == SVNRevisionProperty.DATE) {
                        SVNRepositoryReplicator.newInstance().replicateRepository(source, target, 2, 2)
                        target.setRevisionPropertyValue(2, SVNRevisionProperty.DATE, null)
                    }
                    source.setRevisionPropertyValue(2, property, invalidValue?.let { SVNPropertyValue.create(it) })
                    val indexed = mutableListOf<Long>()
                    repeat(2) {
                        val failure = shouldThrow<MirrorFailure> {
                            run(source, target, state, 2) { revision, _ -> indexed += revision }
                        }
                        failure.code shouldBe code
                        failure.attention shouldBe true
                        state.lastVerifiedRevision shouldBe 1
                        state.lastIndexedRevision shouldBe 1
                        // The dispatcher, not the file synchronizer, persists the failure status.
                        state.status shouldBe RepositoryMirrorStatus.INITIAL_IMPORT
                        target.latestRevision shouldBe 2
                        target.getRevisionProperties(2, null).getStringValue(property) shouldBe invalidValue
                        indexed shouldBe emptyList()
                    }
                    val corrected = if (property == SVNRevisionProperty.AUTHOR) "원".repeat(253) + "\uD801\uDC00" else "2026-10-08T04:05:06.987654Z"
                    source.setRevisionPropertyValue(2, property, SVNPropertyValue.create(corrected))
                    // Correction is source-only: the next run must repair the existing target revision.
                    target.getRevisionProperties(2, null).getStringValue(property) shouldBe invalidValue
                    run(source, target, state, 2) { revision, props ->
                        props shouldBe source.getRevisionProperties(revision, null)
                        props.getStringValue(property) shouldBe corrected
                        indexed += revision
                    }
                    indexed shouldBe listOf(2L)
                    state.lastVerifiedRevision shouldBe 2
                    state.lastIndexedRevision shouldBe 2
                    state.initialImportTargetRevision shouldBe 2
                    target.latestRevision shouldBe 2
                    target.getRevisionProperties(2, null) shouldBe source.getRevisionProperties(2, null)
                } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
            }
            it("quarantines already-verified $property and checks mismatch before validation on retry") {
                val root = Files.createTempDirectory("mirror-verified-unsupported-revprop")
                val source = repository("source", root)
                val target = repository("target", root)
                try {
                    commit(source, 1)
                    val state = RepositoryMirror()
                    run(source, target, state, 1)
                    // Model a backlog verified by an older version before source validation existed.
                    state.lastIndexedRevision = 0
                    source.setRevisionPropertyValue(1, property, invalidValue?.let { SVNPropertyValue.create(it) })
                    target.setRevisionPropertyValue(1, property, invalidValue?.let { SVNPropertyValue.create(it) })
                    val failure = shouldThrow<MirrorFailure> { run(source, target, state, 1) }
                    failure.code shouldBe code
                    failure.attention shouldBe true
                    state.lastVerifiedRevision shouldBe 1
                    state.lastIndexedRevision shouldBe 0
                    target.setRevisionPropertyValue(1, "custom:changed", SVNPropertyValue.create("different"))
                    shouldThrow<MirrorFailure> { run(source, target, state, 1) }.code shouldBe "VERIFIED_REVPROPS_CHANGED"
                    state.lastVerifiedRevision shouldBe 1
                    state.lastIndexedRevision shouldBe 0
                    target.getRevisionProperties(1, null).getStringValue("custom:changed") shouldBe "different"
                } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
            }
        }
        it("classifies a fresh missing date after a partially committed batch and resumes after correction") {
            val root = Files.createTempDirectory("mirror-fresh-missing-date")
            val source = repository("source", root)
            val target = repository("target", root)
            try {
                commit(source, 1)
                commit(source, 2)
                source.setRevisionPropertyValue(2, SVNRevisionProperty.DATE, null)
                val state = RepositoryMirror(initialImportTargetRevision = 2)
                val failure = shouldThrow<MirrorFailure> {
                    SvnMirrorSynchronizer.synchronize(source, target, state, 2, 2, {}, { false },
                        verified = { revision, youngest -> state.lastVerifiedRevision = revision; state.localYoungestRevision = youngest },
                        indexed = { revision, _ -> state.lastIndexedRevision = revision })
                }
                failure.code shouldBe "INVALID_REVISION_DATE"
                failure.attention shouldBe true
                target.latestRevision shouldBe 1
                state.lastVerifiedRevision shouldBe 0
                state.lastIndexedRevision shouldBe 0
                // A repeated attempt can verify r1, but must still stop before r2.
                shouldThrow<MirrorFailure> { run(source, target, state, 2) }.code shouldBe "INVALID_REVISION_DATE"
                state.lastVerifiedRevision shouldBe 1
                state.lastIndexedRevision shouldBe 1
                val corrected = "2026-10-08T04:05:06.987654Z"
                source.setRevisionPropertyValue(2, SVNRevisionProperty.DATE, SVNPropertyValue.create(corrected))
                val indexed = mutableListOf<Long>()
                run(source, target, state, 2) { revision, props ->
                    props.getStringValue(SVNRevisionProperty.DATE) shouldBe corrected
                    indexed += revision
                }
                indexed shouldBe listOf(2L)
                state.lastVerifiedRevision shouldBe 2
                state.lastIndexedRevision shouldBe 2
                state.initialImportTargetRevision shouldBe 2
                target.getRevisionProperties(2, null) shouldBe source.getRevisionProperties(2, null)
            } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
        }
        it("preserves replication errors and never reclassifies cancellation as unsupported metadata") {
            for (failure in listOf(
                SVNException(SVNErrorMessage.create(SVNErrorCode.IO_ERROR, "injected replication failure")),
                SVNCancelException(SVNErrorMessage.create(SVNErrorCode.CANCELLED, "injected cancellation"))
            )) {
                val root = Files.createTempDirectory("mirror-replication-error")
                val source = repository("source", root)
                val target = repository("target", root)
                try {
                    commit(source, 1)
                    if (failure is SVNCancelException) source.setRevisionPropertyValue(1, SVNRevisionProperty.AUTHOR, SVNPropertyValue.create("a".repeat(256)))
                    val broken = spyk(target)
                    every { broken.getCommitEditor(any(), any()) } throws failure
                    shouldThrow<SVNException> { run(source, broken, RepositoryMirror(), 1) } shouldBe failure
                } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
            }
        }
        it("restarts after verified checkpoint but before indexing without rewinding verified data") {
            val root = Files.createTempDirectory("mirror-index-restart")
            val source = repository("source", root)
            val target = repository("target", root)
            try {
                commit(source, 1)
                val state = RepositoryMirror()
                shouldThrow<IllegalStateException> {
                    SvnMirrorSynchronizer.synchronize(source, target, state, 1, 1, {}, { false },
                        verified = { r, youngest -> state.lastVerifiedRevision = r; state.localYoungestRevision = youngest },
                        indexed = { r, _ -> if (r == 1L) error("crash"); state.lastIndexedRevision = r })
                }
                state.lastVerifiedRevision shouldBe 1
                state.lastIndexedRevision shouldBe 0
                run(source, target, state, 1)
                state.lastIndexedRevision shouldBe 1
                // Already-verified history is never automatically repaired/reindexed.
                state.lastIndexedRevision = 0
                source.setRevisionPropertyValue(1, "svn:log", SVNPropertyValue.create("changed #2"))
                shouldThrow<MirrorFailure> { run(source, target, state, 1) }.code shouldBe "VERIFIED_REVPROPS_CHANGED"
            } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
        }
        it("stops damaged target and cancellation before beginning another revision") {
            val root = Files.createTempDirectory("mirror-cancel")
            val source = repository("source", root)
            val target = repository("target", root)
            try {
                commit(source, 1)
                shouldThrow<MirrorFailure> { run(source, target, RepositoryMirror(lastVerifiedRevision = 2), 2) }
                shouldThrow<MirrorLeaseLost> {
                    SvnMirrorSynchronizer.synchronize(source, target, RepositoryMirror(), 1, 1, { throw MirrorLeaseLost() }, { true }, { _, _ -> }, { _, _ -> })
                }
                target.latestRevision shouldBe 0
            } finally { source.closeSession(); target.closeSession(); root.toFile().deleteRecursively() }
        }
    }
})
