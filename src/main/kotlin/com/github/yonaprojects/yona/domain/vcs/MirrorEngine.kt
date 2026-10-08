package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import java.io.File
import java.time.Instant

/** VCS kinds a repository mirror can follow, as stored in [Project.vcs]. */
enum class MirrorVcs(val projectVcs: String) {
    SUBVERSION("SUBVERSION"),
    GIT("GIT"),
    MERCURIAL("MERCURIAL");

    companion object {
        fun of(project: Project): MirrorVcs = when (project.vcs?.uppercase()) {
            "SVN", "SUBVERSION" -> SUBVERSION
            "HG", "MERCURIAL" -> MERCURIAL
            null, "GIT" -> GIT
            else -> throw MirrorFailure("UNSUPPORTED_VCS", attention = true)
        }

        fun parse(value: String?): MirrorVcs =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: throw MirrorFailure("UNSUPPORTED_VCS")
    }
}

/**
 * A commit read from the local mirror copy after it was fetched and verified.
 * [authorKey] is what an administrator maps to a Yona account: the SVN user name, or the Git/Mercurial
 * author e-mail. It is never matched against Yona login IDs or e-mails directly.
 */
data class MirrorCommit(
    val id: String,
    val message: String,
    val authorName: String?,
    val authorEmail: String?,
    val authorKey: String?,
    val timestamp: Instant
)

/** One synchronization run, executed while the run holds the mirror lease and the physical writer lock. */
class MirrorSyncContext(
    val id: Long,
    val owner: String,
    val fence: Long,
    /** Detached snapshot taken before the run; re-read state through the fenced store/indexer calls. */
    val mirror: RepositoryMirror,
    /** The reserved, ownership-checked local repository directory (empty before the first run). */
    val directory: File,
    /** Throws [MirrorLeaseLost] once the lease is gone or the run was cancelled. */
    val checkActive: () -> Unit,
    val cancelled: () -> Boolean
)

/**
 * Per-VCS fetch, verification and commit discovery. Engines never write issue references, notifications
 * or cursors directly: they hand verified commits to [MirrorCommitIndexer] together with the cursor
 * change, which commits both in one fenced transaction.
 */
interface MirrorEngine {
    val vcs: MirrorVcs

    /** Fetches from the source into [MirrorSyncContext.directory] and indexes every new verified commit. */
    fun sync(context: MirrorSyncContext)

    /** Author key of an already verified local commit, or null when unknown. Local read only. */
    fun authorKeyOf(mirror: RepositoryMirror, directory: File, commitId: String): String?

    /** Distinct author keys of verified local history, at most [limit]. Local read only. */
    fun authorKeys(mirror: RepositoryMirror, directory: File, limit: Int): List<String>
}
