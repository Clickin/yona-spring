package com.github.yonaprojects.yona.domain.vcs

import io.github.search5.hg4j.lib.HgRepository as NativeHgRepository
import io.github.search5.hg4j.storage.Revlog
import io.github.search5.hg4j.util.NodeIdUtil
import java.io.Closeable
import java.io.File
import java.time.Instant

/** Local, read-only view of a mirrored repository's changelog. No network access. */
internal class HgMirrorChangelog(directory: File) : Closeable {
    private val repository = NativeHgRepository(directory)
    private val revlog: Revlog = HgBookmarkAncestry.changelogRevlogOf(repository)

    val count: Int get() = revlog.revisionCount

    fun node(revision: Int): String = NodeIdUtil.toHex(revlog.getIndexRecord(revision).nodeId)

    /** Local revision of [node], or -1. */
    fun revisionOf(node: String): Int {
        if (!NODE.matches(node)) return -1
        return revlog.findRevision(NodeIdUtil.fromHex(node))
    }

    /** Nodes without children. */
    fun heads(): List<String> {
        val hasChild = BooleanArray(count)
        for (revision in 0 until count) {
            val record = revlog.getIndexRecord(revision)
            if (record.parent1 >= 0) hasChild[record.parent1] = true
            if (record.parent2 >= 0) hasChild[record.parent2] = true
        }
        return (0 until count).filterNot { hasChild[it] }.map(::node)
    }

    fun author(revision: Int): String = parse(revision).author

    fun commit(revision: Int): MirrorCommit {
        val parsed = parse(revision)
        val email = HgCommit.parseAuthorEmail(parsed.author)
        val name = if (email != null) parsed.author.substringBefore("<").trim() else parsed.author
        return MirrorCommit(
            id = node(revision),
            message = parsed.message,
            authorName = name.ifEmpty { null },
            authorEmail = email?.ifEmpty { null },
            authorKey = email?.ifEmpty { null },
            timestamp = parsed.timestamp
        )
    }

    private class Parsed(val author: String, val timestamp: Instant, val message: String)

    private fun parse(revision: Int): Parsed {
        val text = String(revlog.getRevisionContent(revision), Charsets.UTF_8)
        // manifest\nauthor\ntime tz [extra]\nfiles...\n\nmessage
        val split = text.indexOf("\n\n")
        val header = (if (split < 0) text else text.substring(0, split)).split('\n')
        if (header.size < 3) throw MirrorFailure("REPOSITORY_DAMAGED", attention = true)
        val seconds = header[2].substringBefore(' ').toDoubleOrNull()?.toLong()
            ?: throw MirrorFailure("INVALID_REVISION_DATE", attention = true)
        return Parsed(header[1], Instant.ofEpochSecond(seconds), if (split < 0) "" else text.substring(split + 2))
    }

    override fun close() = repository.close()

    companion object {
        private val NODE = Regex("[0-9a-f]{40}")
    }
}
