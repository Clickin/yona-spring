package com.github.yonaprojects.yona.domain.vcs

import org.springframework.stereotype.Component

/** Mercurial adapter for [MirrorCommitIndexer]: one verified local revision at a time, strictly in order. */
@Component
class HgMirrorIndexer(private val indexer: MirrorCommitIndexer) {
    fun index(id: Long, owner: String, fence: Long, revision: Long, commit: MirrorCommit) {
        indexer.index(id, owner, fence, listOf(commit), accept = accept@{ mirror ->
            mirror.initialImportTargetRevision ?: throw MirrorFailure("INVALID_CHECKPOINT", attention = true)
            if (mirror.sourceRepositoryUuid.isNullOrBlank() || mirror.generation < 1 || revision < 0) {
                throw MirrorFailure("INVALID_CHECKPOINT", attention = true)
            }
            if (revision <= mirror.lastIndexedRevision) return@accept false
            // Local revision numbers start at 0 and have no gaps: the next one is always last indexed + 1.
            if (revision > mirror.lastVerifiedRevision || revision != mirror.lastIndexedRevision + 1) {
                throw MirrorFailure("INVALID_INDEX_REVISION", attention = true)
            }
            true
        }, notify = { mirror ->
            // An initially empty source completes its first run with no R; its first revisions notify.
            mirror.initialImportDone || revision > mirror.initialImportTargetRevision!!
        }) { mirror -> mirror.lastIndexedRevision = revision }
    }
}
