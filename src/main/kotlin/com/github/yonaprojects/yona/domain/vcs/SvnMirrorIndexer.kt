package com.github.yonaprojects.yona.domain.vcs

import org.springframework.stereotype.Component
import org.tmatesoft.svn.core.SVNProperties
import org.tmatesoft.svn.core.SVNRevisionProperty

/** SVN adapter for [MirrorCommitIndexer]: one verified revision at a time, strictly in order. */
@Component
class SvnMirrorIndexer(private val indexer: MirrorCommitIndexer) {
    fun index(id: Long, owner: String, fence: Long, revision: Long, properties: SVNProperties) {
        // r0 only advances the cursor; data references start at r1.
        val commits = if (revision == 0L) emptyList() else listOf(MirrorCommit(
            id = revision.toString(),
            message = properties.getStringValue(SVNRevisionProperty.LOG) ?: "",
            authorName = properties.getStringValue(SVNRevisionProperty.AUTHOR),
            authorEmail = null,
            authorKey = properties.getStringValue(SVNRevisionProperty.AUTHOR),
            timestamp = svnMirrorRevisionDate(properties)
        ))
        indexer.index(id, owner, fence, commits, accept = accept@{ mirror ->
            mirror.initialImportTargetRevision ?: throw MirrorFailure("INVALID_CHECKPOINT", attention = true)
            if (mirror.sourceRepositoryUuid.isNullOrBlank() || mirror.generation < 1 || revision < 0) {
                throw MirrorFailure("INVALID_CHECKPOINT", attention = true)
            }
            if (revision <= mirror.lastIndexedRevision) return@accept false
            if (revision > mirror.lastVerifiedRevision ||
                (revision != 0L && revision != maxOf(1L, mirror.lastIndexedRevision + 1)) ||
                (revision == 0L && mirror.lastIndexedRevision != -1L)) {
                throw MirrorFailure("INVALID_INDEX_REVISION", attention = true)
            }
            true
        }, notify = { mirror -> revision > mirror.initialImportTargetRevision!! }) { mirror -> mirror.lastIndexedRevision = revision }
    }
}
