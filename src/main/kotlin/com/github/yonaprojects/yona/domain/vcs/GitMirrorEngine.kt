package com.github.yonaprojects.yona.domain.vcs

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.errors.CorruptObjectException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevFlag
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.util.FS
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Follows a Git source like a Forgejo pull mirror: branches and tags are fetched into a bare repository
 * (forced updates and deleted refs included) and every commit new to the mirror is indexed once. Progress
 * is kept per ref in `repository_mirror_ref`; the linear cursors of [RepositoryMirror] stay at -1.
 */
@Service
class GitMirrorEngine(
    private val sourcePolicy: MirrorSourcePolicy,
    private val indexer: GitMirrorIndexer,
    private val refs: RepositoryMirrorRefRepository,
    private val properties: RepositoryMirrorProperties
) : MirrorEngine {
    private val logger = LoggerFactory.getLogger(GitMirrorEngine::class.java)
    override val vcs = MirrorVcs.GIT

    /** Network step; tests replace it with a local repository so the rest runs without HTTPS. */
    internal var fetcher: (Git, MirrorSyncContext) -> FetchResult? = ::fetchOverHttps

    /** How the policy factory opens a connection; tests point it at a local server. */
    internal var connectionOpener: (java.net.URL, java.net.Proxy) -> java.net.HttpURLConnection =
        { url, proxy -> url.openConnection(proxy) as java.net.HttpURLConnection }

    override fun sync(context: MirrorSyncContext) {
        properties.requireActivation() // Must precede DNS, secret reads, and any source connection.
        val repository = openOrCreate(context)
        try {
            val git = Git(repository)
            val result = try {
                fetcher(git, context)
            } catch (e: Exception) {
                throw fetchFailure(e, context)
            }
            context.checkActive()
            checkFetched(result)
            followSourceHead(repository, result)
            indexRefs(context, repository)
        } finally {
            repository.close()
        }
    }

    override fun authorKeyOf(mirror: RepositoryMirror, directory: File, commitId: String): String? {
        if (!ObjectId.isId(commitId)) return null
        return withLocal(directory) { repository ->
            try {
                RevWalk(repository).use { it.parseCommit(ObjectId.fromString(commitId)).authorIdent?.emailAddress?.takeIf(String::isNotEmpty) }
            } catch (_: MissingObjectException) {
                null
            } catch (_: org.eclipse.jgit.errors.IncorrectObjectTypeException) {
                null
            }
        }
    }

    override fun authorKeys(mirror: RepositoryMirror, directory: File, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val found = sortedSetOf<String>()
        withLocal(directory) { repository ->
            RevWalk(repository).use { walk ->
                for (ref in mirroredRefs(repository)) {
                    val tip = peeledCommit(walk, repository, ref) ?: continue
                    walk.markStart(tip)
                }
                for (commit in walk) {
                    commit.authorIdent?.emailAddress?.takeIf(String::isNotEmpty)?.let(found::add)
                    commit.disposeBody()
                    if (found.size >= limit) break
                }
            }
        }
        return found.toList()
    }

    private fun <T> withLocal(directory: File, action: (Repository) -> T): T {
        if (!RepositoryCache.FileKey.isGitRepository(directory, FS.DETECTED)) throw MirrorFailure("TARGET_INCOMPLETE", attention = true)
        val repository = FileRepositoryBuilder().setGitDir(directory).setMustExist(true).build()
        try { return action(repository) } finally { repository.close() }
    }

    private fun openOrCreate(context: MirrorSyncContext): Repository {
        val directory = context.directory
        if (!RepositoryCache.FileKey.isGitRepository(directory, FS.DETECTED)) {
            // An empty directory is only a first run when nothing was ever indexed: positions without a
            // repository mean the local copy was lost, and it must not be silently recreated.
            if (directory.list()?.isNotEmpty() != false || context.mirror.initialImportDone || refs.findAllByMirrorId(context.id).isNotEmpty()) {
                throw MirrorFailure("TARGET_INCOMPLETE", attention = true)
            }
            context.checkActive()
            Git.init().setBare(true).setDirectory(directory).call().close()
        }
        return FileRepositoryBuilder().setGitDir(directory).setMustExist(true).build()
    }

    /** Network work. Credentials live only inside this call; no URL or remote message leaves it. */
    private fun fetchOverHttps(git: Git, context: MirrorSyncContext): FetchResult? {
        val mirror = context.mirror
        val normalized = sourcePolicy.normalize(mirror.sourceUrl)
        val secret = mirror.credentialRef?.let { sourcePolicy.credential(it) }
        val provider = secret?.let { UsernamePasswordCredentialsProvider(it.first, it.second) }
        try {
            val factory = GitMirrorHttpConnectionFactory(sourcePolicy, normalized, connectionOpener)
            return git.fetch()
                .setRemote(normalized)
                .setRefSpecs(*REFSPECS)
                .setRemoveDeletedRefs(true)
                .setTagOpt(TagOpt.NO_TAGS)
                .setTimeout(READ_TIMEOUT_SECONDS)
                .setProgressMonitor(monitor(context))
                .also { command -> provider?.let(command::setCredentialsProvider) }
                .setTransportConfigCallback { transport ->
                    // Only a plain HTTP transport honours the policy factory; refuse anything else.
                    (transport as? TransportHttp ?: throw MirrorFailure("SOURCE_POLICY")).setHttpConnectionFactory(factory)
                }
                .call()
        } finally {
            provider?.clear()
            secret?.second?.fill('\u0000')
        }
    }

    private fun monitor(context: MirrorSyncContext) = object : ProgressMonitor {
        override fun start(totalTasks: Int) {}
        override fun beginTask(title: String?, totalWork: Int) {}
        override fun update(completed: Int) {}
        override fun endTask() {}
        override fun isCancelled() = context.cancelled()
        override fun showDuration(enabled: Boolean) {}
    }

    /** Fixed failure codes only: remote exception messages can contain URLs or credentials. */
    private fun fetchFailure(error: Exception, context: MirrorSyncContext): Exception {
        val causes = generateSequence(error as Throwable?) { it.cause }.take(12).toList()
        causes.firstOrNull { it is MirrorFailure || it is MirrorLeaseLost }?.let { return it as Exception }
        if (context.cancelled() || causes.any { it is CanceledException }) {
            return try { context.checkActive(); MirrorLeaseLost() } catch (lost: MirrorLeaseLost) { lost }
        }
        if (causes.any { it is SSLException }) return MirrorFailure("SOURCE_TLS_REJECTED")
        if (causes.any { it is NoRemoteRepositoryException }) return MirrorFailure("SOURCE_NOT_FOUND")
        if (causes.any { it is SocketTimeoutException || it is ConnectException || it is UnknownHostException }) {
            return MirrorFailure("SOURCE_UNAVAILABLE", transient = true)
        }
        val message = causes.filterIsInstance<TransportException>().mapNotNull { it.message?.lowercase() }.joinToString(" ")
        if (AUTH_HINTS.any { it in message }) return MirrorFailure("SOURCE_AUTH_REJECTED")
        if (causes.any { it is IOException || it is org.eclipse.jgit.api.errors.GitAPIException }) {
            return MirrorFailure("SOURCE_UNAVAILABLE", transient = true)
        }
        return MirrorFailure("SYNC_FAILED")
    }

    /** FetchCommand reports a failed local ref update in the result instead of throwing. */
    private fun checkFetched(result: FetchResult?) {
        val failed = result?.trackingRefUpdates.orEmpty().any { it.result !in FETCHED }
        if (failed) throw MirrorFailure("TARGET_UPDATE_REJECTED", attention = true)
    }

    /** Lets `git clone` of the served mirror check out the source's default branch. */
    private fun followSourceHead(repository: Repository, result: FetchResult?) {
        val head = result?.getAdvertisedRef(Constants.HEAD)?.takeIf { it.isSymbolic } ?: return
        val target = head.target.name
        if (!target.startsWith(Constants.R_HEADS) || repository.exactRef(target) == null) return
        if (repository.exactRef(Constants.HEAD)?.target?.name == target) return
        try { repository.updateRef(Constants.HEAD).link(target) } catch (_: IOException) { /* cosmetic only */ }
    }

    private fun mirroredRefs(repository: Repository) =
        repository.refDatabase.getRefsByPrefix(Constants.R_HEADS, Constants.R_TAGS).sortedBy { it.name }

    private fun peeledCommit(walk: RevWalk, repository: Repository, ref: org.eclipse.jgit.lib.Ref): RevCommit? {
        val peeled = repository.refDatabase.peel(ref)
        val id = peeled.peeledObjectId ?: peeled.objectId ?: return null
        // A tag of a tree or blob has no commit history to index.
        return walk.parseAny(id).let { walk.peel(it) } as? RevCommit
    }

    private fun indexRefs(context: MirrorSyncContext, repository: Repository) {
        val batchSize = properties.batchSize
        val known = refs.findAllByMirrorId(context.id).associate { it.refName to it.commitId }.toMutableMap()
        val current = mirroredRefs(repository)
        val currentNames = current.mapTo(HashSet()) { it.name }

        val walk = RevWalk(repository)
        try {
            walk.isRetainBody = false
            val fresh = walk.newFlag("FRESH")
            val handled = walk.newFlag("HANDLED")

            // Stored positions of any ref (even a deleted or rewritten one) are indexed, with all ancestors.
            // Repository maintenance may prune history no ref reaches anymore: a retired or pending position
            // whose commit is gone is dropped (no ref can reach it without fetching it again). A missing live
            // ref position means the repository lost data.
            val vanished = ArrayList<String>()
            for ((name, position) in known.entries.sortedBy { it.key }) {
                val commit = try {
                    walk.parseCommit(ObjectId.fromString(position))
                } catch (_: MissingObjectException) {
                    if (name.startsWith(RETIRED_PREFIX) || name.startsWith(PENDING_PREFIX)) { vanished.add(name); continue }
                    throw MirrorFailure("TARGET_INCOMPLETE", attention = true)
                }
                walk.markUninteresting(commit)
            }
            if (vanished.isNotEmpty()) {
                context.checkActive()
                indexer.index(context.id, context.owner, context.fence, emptyList(), emptyList(), emptyMap(), vanished)
                known.keys.removeAll(vanished.toSet())
            }
            val tips = LinkedHashMap<String, RevCommit>()
            val skippedNames = HashSet<String>()
            for (ref in current) {
                if (ref.name.length > MAX_REF_NAME) {
                    logger.warn("Mirror {} skips a ref name longer than {} characters", context.id, MAX_REF_NAME)
                    skippedNames.add(ref.name)
                    continue
                }
                val tip = peeledCommit(walk, repository, ref) ?: continue
                tips[ref.name] = tip
                if (known[ref.name] != tip.name) walk.markStart(tip)
            }
            // A ref that moved off its old position (forced update) or disappeared would otherwise take the
            // coverage of that position with it, and a later ref reaching the same commits would index them
            // again. Keep such positions as retired rows first; they only ever mark indexed history.
            val retired = LinkedHashMap<String, String>()
            RevWalk(repository).use { ancestry ->
                for ((name, position) in known) {
                    if (name.startsWith(PENDING_PREFIX) || name.startsWith(RETIRED_PREFIX)) continue
                    val tip = tips[name]
                    val kept = tip != null && (tip.name == position ||
                        ancestry.isMergedInto(ancestry.parseCommit(ObjectId.fromString(position)), ancestry.parseCommit(tip)))
                    if (!kept && name !in skippedNames) retired[RETIRED_PREFIX + position] = position
                }
            }
            retired.keys.removeAll(known.keys)
            if (retired.isNotEmpty()) {
                context.checkActive()
                indexer.index(context.id, context.owner, context.fence, emptyList(), emptyList(), retired)
                known.putAll(retired)
            }

            // One walk finds everything new to the mirror; refs then take their share of it in order.
            var visited = 0
            for (commit in walk) {
                commit.add(fresh)
                if (++visited % CANCEL_CHECK_INTERVAL == 0 && context.cancelled()) context.checkActive()
            }

            val positions = LinkedHashMap<String, String>()
            fun flushPositions() {
                if (positions.isEmpty()) return
                context.checkActive()
                indexer.index(context.id, context.owner, context.fence, emptyList(), emptyList(), positions)
                known.putAll(positions)
                positions.clear()
            }

            for ((name, tip) in tips) {
                context.checkActive()
                if (known[name] == tip.name) continue
                val own = newCommits(tip, fresh, handled)
                if (own.isEmpty()) {
                    // Fast-forward to, or a forced update back to, an already indexed commit.
                    positions[name] = tip.name
                    continue
                }
                flushPositions()
                val chunks = own.chunked(batchSize)
                // Commits no later commit of this ref's processed prefix builds on. A chunk's last commit does not
                // cover a merged side branch that sorted earlier, so those get their own temporary rows.
                val frontier = LinkedHashSet<String>()
                var extras: Set<String> = emptySet()
                for ((number, chunk) in chunks.withIndex()) {
                    context.checkActive()
                    for (commit in chunk) {
                        commit.parents.forEach { frontier.remove(it.name) }
                        frontier.add(commit.name)
                    }
                    val last = number == chunks.lastIndex
                    val rows = LinkedHashMap<String, String>()
                    rows[name] = if (last) tip.name else chunk.last().name
                    val nextExtras = LinkedHashSet<String>()
                    if (!last) {
                        for (id in frontier) {
                            if (id == chunk.last().name) continue
                            rows[PENDING_PREFIX + id] = id
                            nextExtras.add(PENDING_PREFIX + id)
                        }
                    }
                    indexer.index(context.id, context.owner, context.fence, readCommits(repository, chunk), listOf(name), rows, extras - nextExtras)
                    known.putAll(rows)
                    known.keys.removeAll(extras - nextExtras)
                    extras = nextExtras
                }
            }
            flushPositions()

            val removed = known.keys.filter { it !in currentNames && !it.startsWith(RETIRED_PREFIX) }
            if (removed.isNotEmpty()) {
                context.checkActive()
                indexer.index(context.id, context.owner, context.fence, emptyList(), emptyList(), emptyMap(), removed)
            }
        } catch (_: MissingObjectException) {
            throw MirrorFailure("REPOSITORY_DAMAGED", attention = true)
        } catch (_: CorruptObjectException) {
            throw MirrorFailure("REPOSITORY_DAMAGED", attention = true)
        } finally {
            walk.close()
        }
    }

    /**
     * Fresh commits reachable from [tip] that no earlier ref took, parents before children (iterative
     * post-order, so history depth does not matter).
     */
    private fun newCommits(tip: RevCommit, fresh: RevFlag, handled: RevFlag): List<RevCommit> {
        val out = ArrayList<RevCommit>()
        if (!tip.has(fresh) || tip.has(handled)) return out
        tip.add(handled)
        val stack = ArrayDeque<Pair<RevCommit, IntArray>>()
        stack.addLast(tip to intArrayOf(0))
        while (stack.isNotEmpty()) {
            val (commit, next) = stack.last()
            val parents = commit.parents
            if (next[0] < parents.size) {
                val parent = parents[next[0]++]
                if (parent.has(fresh) && !parent.has(handled)) {
                    parent.add(handled)
                    stack.addLast(parent to intArrayOf(0))
                }
            } else {
                stack.removeLast()
                out.add(commit)
            }
        }
        return out
    }

    private fun readCommits(repository: Repository, ids: List<RevCommit>): List<MirrorCommit> = RevWalk(repository).use { reader ->
        ids.map { id ->
            val commit = reader.parseCommit(id)
            val author = commit.authorIdent
            val email = author?.emailAddress?.takeIf(String::isNotEmpty)
            MirrorCommit(
                id = commit.name,
                message = commit.fullMessage,
                authorName = author?.name?.takeIf(String::isNotEmpty),
                authorEmail = email,
                authorKey = email,
                timestamp = author?.whenAsInstant ?: commit.commitTime.let { java.time.Instant.ofEpochSecond(it.toLong()) }
            )
        }
    }

    companion object {
        private val REFSPECS = arrayOf(RefSpec("+refs/heads/*:refs/heads/*"), RefSpec("+refs/tags/*:refs/tags/*"))
        private val AUTH_HINTS = listOf("not authorized", "authentication", "401", "403", "forbidden")
        private val FETCHED = setOf(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE)
        private const val MAX_REF_NAME = 255
        private const val CANCEL_CHECK_INTERVAL = 1000
        // Not under refs/, so it can never collide with a source ref and is always removed at the end of a run.
        private const val PENDING_PREFIX = "mirror-pending/"
        private const val RETIRED_PREFIX = "mirror-retired/"
        private const val READ_TIMEOUT_SECONDS = 30
    }
}
