package com.github.yonaprojects.yona.domain.vcs

import io.github.search5.hg4j.api.BookmarkCommand
import io.github.search5.hg4j.api.Hg
import io.github.search5.hg4j.api.PullCommand
import io.github.search5.hg4j.bundle.Bundle2Parser
import io.github.search5.hg4j.bundle.ChangegroupParser
import io.github.search5.hg4j.lib.HgRepository as NativeHgRepository
import io.github.search5.hg4j.transport.HgLocalClient
import io.github.search5.hg4j.util.NodeIdUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.InflaterInputStream

@Service
class HgMirrorEngine(
    private val store: RepositoryMirrorStore,
    private val transport: HgMirrorTransport,
    private val indexer: HgMirrorIndexer,
    private val properties: RepositoryMirrorProperties
) : MirrorEngine {
    override val vcs = MirrorVcs.MERCURIAL

    private val logger = LoggerFactory.getLogger(HgMirrorEngine::class.java)

    override fun sync(context: MirrorSyncContext) {
        properties.requireActivation()
        val (id, owner, fence) = Triple(context.id, context.owner, context.fence)
        val initial = context.mirror
        val directory = context.directory
        transport.open(initial.sourceUrl, initial.credentialRef).use { source ->
            val sourceHeads = source.heads()
            val sourceRoot = if (sourceHeads.isEmpty()) null else source.root() ?: throw MirrorFailure("SOURCE_PROTOCOL")
            context.checkActive()
            if (!isRepository(directory)) {
                if (initial.localRepositoryUuid != null || directory.list()?.isNotEmpty() != false) throw MirrorFailure("TARGET_INCOMPLETE", attention = true)
                context.checkActive()
                Hg.init().setDirectory(directory).call().close()
            }
            val localHeads: List<String>
            HgMirrorChangelog(directory).use { local ->
                val localRoot = if (local.count > 0) local.node(0) else null
                if (initial.localRepositoryUuid != null && localRoot != initial.localRepositoryUuid) {
                    throw MirrorFailure(if (localRoot == null) "TARGET_INCOMPLETE" else "TARGET_IDENTITY_CHANGED", attention = true)
                }
                if (local.count - 1 < initial.lastVerifiedRevision) throw MirrorFailure("REVISION_HISTORY_CHANGED", attention = true)
                if (sourceRoot == null) {
                    // An empty source has nothing to observe: no root, so no identity and no R. Once it has
                    // had changesets, going back to empty means its history was replaced.
                    if (local.count > 0 || initial.sourceRepositoryUuid != null) throw MirrorFailure("REVISION_HISTORY_CHANGED", attention = true)
                    return
                }
                // Refuse an unrelated or replaced source before fetching anything: its bundle would add a second root.
                if (initial.sourceRepositoryUuid != null && initial.sourceRepositoryUuid != sourceRoot) throw MirrorFailure("SOURCE_IDENTITY_CHANGED", attention = true)
                if (localRoot != null && localRoot != sourceRoot) throw MirrorFailure("SOURCE_IDENTITY_CHANGED", attention = true)
                localHeads = local.heads()
            }
            context.checkActive()
            // The mirror never strips: a local head the source no longer has means the source rewrote history.
            if (source.known(localHeads).any { !it }) throw MirrorFailure("REVISION_HISTORY_CHANGED", attention = true)
            context.checkActive()
            val missing = HgMirrorChangelog(directory).use { local -> sourceHeads.any { local.revisionOf(it) < 0 } }
            if (missing) {
                val bundle = parseBundle(source.bundle(localHeads, sourceHeads))
                context.checkActive()
                val repository = NativeHgRepository(directory)
                try { PullCommand(repository).applyBundle(bundle) } finally { repository.close() }
            }
            context.checkActive()
            HgMirrorChangelog(directory).use { local ->
                if (sourceHeads.any { local.revisionOf(it) < 0 }) throw MirrorFailure("UNEXPECTED_TARGET_REVISION", attention = true)
                val tip = (local.count - 1).toLong()
                val mirror = store.observe(id, owner, fence, checkNotNull(sourceRoot), tip, local.node(0), tip)
                followBookmarks(source.bookmarks(), directory, local)
                var verified = mirror.lastVerifiedRevision
                var indexed = mirror.lastIndexedRevision
                // A prior process may have verified revisions without finishing their checkpoints.
                while (indexed < minOf(verified, tip)) {
                    context.checkActive()
                    if (context.cancelled()) throw MirrorLeaseLost()
                    val revision = indexed + 1
                    indexer.index(id, owner, fence, revision, local.commit(revision.toInt()))
                    indexed = revision
                }
                while (verified < tip) {
                    context.checkActive()
                    if (context.cancelled()) throw MirrorLeaseLost()
                    val revision = verified + 1
                    val commit = local.commit(revision.toInt())
                    store.verified(id, owner, fence, revision, tip)
                    verified = revision
                    indexer.index(id, owner, fence, revision, commit)
                    indexed = revision
                }
            }
        }
    }

    /** Makes the local bookmarks equal the source's, like Git mirrors follow refs. Names Mercurial cannot store are skipped. */
    private fun followBookmarks(remote: Map<String, String>, directory: File, local: HgMirrorChangelog) {
        val repository = NativeHgRepository(directory)
        try {
            val current = HgLocalClient(repository).listKeys("bookmarks")
            for (name in current.keys - remote.keys) {
                try {
                    BookmarkCommand(repository).setBookmarkName(name).setDelete(true).setForce(true).call()
                } catch (e: Exception) {
                    logger.warn("A local bookmark could not be removed: {}", e.javaClass.simpleName)
                }
            }
            for ((name, node) in remote) {
                if (current[name] == node) continue
                if (name.isEmpty() || name.length > 255 || name.any { it.isISOControl() } || local.revisionOf(node) < 0) {
                    logger.warn("Skipping a source bookmark that cannot be mirrored")
                    continue
                }
                try {
                    BookmarkCommand(repository).setBookmarkName(name).setNodeId(NodeIdUtil.fromHex(node)).setForce(true).call()
                } catch (e: Exception) {
                    // A name Mercurial cannot store must not stop the mirror; never log the remote message.
                    logger.warn("A source bookmark could not be mirrored: {}", e.javaClass.simpleName)
                }
            }
        } finally {
            repository.close()
        }
    }

    override fun authorKeyOf(mirror: RepositoryMirror, directory: File, commitId: String): String? {
        if (!isRepository(directory) || mirror.lastVerifiedRevision < 0) return null
        return HgMirrorChangelog(directory).use { local ->
            val revision = local.revisionOf(commitId.lowercase()).takeIf { it in 0..mirror.lastVerifiedRevision } ?: return null
            local.commit(revision).authorKey
        }
    }

    override fun authorKeys(mirror: RepositoryMirror, directory: File, limit: Int): List<String> {
        if (!isRepository(directory) || mirror.lastVerifiedRevision < 0) return emptyList()
        val found = sortedSetOf<String>()
        HgMirrorChangelog(directory).use { local ->
            val last = minOf(mirror.lastVerifiedRevision, (local.count - 1).toLong()).toInt()
            for (revision in 0..last) {
                if (found.size >= limit) break
                local.commit(revision).authorKey?.let(found::add)
            }
        }
        return found.toList()
    }

    private fun isRepository(directory: File) = directory.resolve(".hg").isDirectory && directory.resolve(".hg/store").isDirectory

    companion object {
        /** getbundle answers a bare changegroup, or a bundle1/bundle2 container; returns the parsed changegroup. */
        internal fun parseBundle(bytes: ByteArray): ChangegroupParser.ChangegroupBundle {
            val header = if (bytes.size >= 6) String(bytes, 0, 6, Charsets.ISO_8859_1) else ""
            return when {
                header.startsWith("HG20") -> {
                    val extracted = Bundle2Parser.extractChangegroupDetailed(ByteArrayInputStream(bytes))
                    ChangegroupParser.parseBundle(ByteArrayInputStream(extracted.changegroupBytes), extracted.cgVersion)
                }
                header == "HG10UN" -> ChangegroupParser.parseBundle(ByteArrayInputStream(bytes, 6, bytes.size - 6), "01")
                header == "HG10GZ" -> ChangegroupParser.parseBundle(InflaterInputStream(ByteArrayInputStream(bytes, 6, bytes.size - 6)), "01")
                header.startsWith("HG10") -> throw MirrorFailure("SOURCE_PROTOCOL")
                else -> ChangegroupParser.parseBundle(ByteArrayInputStream(bytes), "01")
            }
        }
    }
}
