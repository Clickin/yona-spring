package com.github.yonaprojects.yona.domain.vcs

import org.springframework.stereotype.Service
import org.tmatesoft.svn.core.*
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory
import org.tmatesoft.svn.core.internal.io.fs.FSFS
import org.tmatesoft.svn.core.internal.io.fs.FSRepository
import org.tmatesoft.svn.core.internal.io.fs.FSWriteLock
import org.tmatesoft.svn.core.internal.wc.SVNWCProperties
import org.tmatesoft.svn.core.io.SVNRepository
import org.tmatesoft.svn.core.io.SVNRepositoryFactory
import org.tmatesoft.svn.core.replicator.ISVNReplicationHandler
import org.tmatesoft.svn.core.replicator.SVNRepositoryReplicator
import java.nio.file.Files

class SvnMirrorSynchronizer {
    companion object {
        private fun cancelledException() = SVNCancelException(SVNErrorMessage.create(SVNErrorCode.CANCELLED, "Mirror execution cancelled"))

        /** Network/file work only. Each supplied checkpoint is a separate short fenced transaction. */
        internal fun synchronize(
            source: SVNRepository,
            target: SVNRepository,
            mirror: RepositoryMirror,
            goal: Long,
            batchSize: Int,
            checkActive: () -> Unit,
            cancelled: () -> Boolean,
            verified: (Long, Long) -> Unit,
            indexed: (Long, SVNProperties) -> Unit
        ) {
            require(batchSize in 1..1000)
            var youngest = target.latestRevision
            if (youngest < mirror.lastVerifiedRevision || youngest > source.latestRevision || goal < mirror.lastVerifiedRevision) throw MirrorFailure("REVISION_HISTORY_CHANGED", attention = true)
            var verifiedRevision = mirror.lastVerifiedRevision
            var indexedRevision = mirror.lastIndexedRevision
            fun verifyAndIndexThrough(end: Long) {
                while (indexedRevision < minOf(verifiedRevision, end)) {
                    checkActive()
                    val revision = indexedRevision + 1
                    val props = source.getRevisionProperties(revision, null)
                    if (!sameProperties(props, target.getRevisionProperties(revision, null))) throw MirrorFailure("VERIFIED_REVPROPS_CHANGED", attention = true)
                    if (revision > 0) svnMirrorRevisionDate(props)
                    indexed(revision, props)
                    indexedRevision = revision
                }
                while (verifiedRevision < end) {
                    checkActive()
                    val revision = verifiedRevision + 1
                    val props = repairRevisionProperties(source, target, revision)
                    verified(revision, youngest)
                    verifiedRevision = revision
                    indexed(revision, props)
                    indexedRevision = revision
                }
            }
            // A prior process may have committed revisions without finishing their properties/checkpoints.
            verifyAndIndexThrough(youngest)
            val replicator = SVNRepositoryReplicator.newInstance()
            replicator.setReplicationHandler(object : ISVNReplicationHandler {
                override fun revisionReplicating(replicator: SVNRepositoryReplicator, entry: SVNLogEntry) { checkActive() }
                override fun revisionReplicated(replicator: SVNRepositoryReplicator, info: SVNCommitInfo) { checkCancelled() }
                override fun checkCancelled() { if (cancelled()) throw cancelledException() }
            })
            while (youngest < goal) {
                checkActive()
                val end = youngest + minOf(batchSize.toLong(), goal - youngest)
                try {
                    replicator.replicateRepository(source, target, youngest + 1, end)
                } catch (failure: SVNException) {
                    // SVNKit can refuse a missing date before committing or invoking its handler.
                    if (failure !is SVNCancelException && !cancelled()) {
                        val next = target.latestRevision + 1
                        if (next <= end) svnMirrorRevisionDate(source.getRevisionProperties(next, null))
                    }
                    throw failure
                }
                val actual = target.latestRevision
                if (actual != end) throw MirrorFailure("UNEXPECTED_TARGET_REVISION", attention = true)
                youngest = actual
                verifyAndIndexThrough(youngest)
            }
        }

        internal fun repairRevisionProperties(source: SVNRepository, target: SVNRepository, revision: Long): SVNProperties {
            val expected = source.getRevisionProperties(revision, null)
            // Reject unsupported source data before property writes or a verified checkpoint.
            if (revision > 0) svnMirrorRevisionDate(expected)
            val actual = target.getRevisionProperties(revision, null)
            // SVNKit 1.10.11's single-property writer deletes the revprops file when it becomes
            // empty. Install desired values first, then delete extras, so replacements stay valid.
            for (name in expected.nameSet()) {
                val desired = expected.getSVNPropertyValue(name)
                if (!SVNPropertyValue.areEqual(actual.getSVNPropertyValue(name), desired)) target.setRevisionPropertyValue(revision, name, desired)
            }
            if (expected.isEmpty && !actual.isEmpty) {
                writeEmptyRevisionZero(target)
            } else {
                for (name in actual.nameSet()) {
                    if (!expected.containsName(name)) target.setRevisionPropertyValue(revision, name, null)
                }
            }
            if (!sameProperties(expected, target.getRevisionProperties(revision, null))) throw MirrorFailure("REVPROP_VERIFICATION_FAILED", attention = true)
            // Don't silently verify a moving property snapshot.
            if (!sameProperties(expected, source.getRevisionProperties(revision, null))) throw MirrorFailure("SOURCE_REVPROPS_CHANGED", attention = true)
            return expected
        }

        private fun writeEmptyRevisionZero(target: SVNRepository) {
            val root = (target as? FSRepository)?.repositoryRootDir
                ?: throw MirrorFailure("TARGET_NOT_LOCAL", attention = true)
            val fs = FSFS(root)
            fs.open()
            try {
                val lock = FSWriteLock.getWriteLockForDB(fs)
                try {
                    synchronized(lock) {
                        lock.lock()
                        try {
                            // r0 may legitimately have no properties. The bulk writer retains its
                            // required END hash file; the public per-property setter would unlink it.
                            val file = fs.getRevisionPropertiesFile(0, false)
                            val temporary = Files.createTempFile(file.parentFile.toPath(), ".mirror-r0-", ".tmp")
                            try {
                                SVNWCProperties.setProperties(SVNProperties(), file, temporary.toFile(), "END")
                            } finally {
                                Files.deleteIfExists(temporary)
                            }
                        } finally {
                            lock.unlock()
                        }
                    }
                } finally {
                    FSWriteLock.release(lock)
                }
            } finally {
                fs.close()
            }
        }

        private fun sameProperties(first: SVNProperties, second: SVNProperties): Boolean =
            first.nameSet() == second.nameSet() && first.nameSet().all { SVNPropertyValue.areEqual(first.getSVNPropertyValue(it), second.getSVNPropertyValue(it)) }
    }
}
