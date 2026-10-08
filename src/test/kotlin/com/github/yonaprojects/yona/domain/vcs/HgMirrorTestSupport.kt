package com.github.yonaprojects.yona.domain.vcs

import io.github.search5.hg4j.api.Hg
import io.github.search5.hg4j.lib.HgRepository as NativeHgRepository
import io.github.search5.hg4j.transport.HgLocalClient
import java.io.File

/** Builds throwaway Mercurial source repositories for mirror specs. */
internal object HgMirrorTestSupport {
    fun init(directory: File): File {
        directory.mkdirs()
        Hg.init().setDirectory(directory).call().close()
        return directory
    }

    /** Commits one file change and returns the new changeset node. */
    fun commit(directory: File, path: String, content: String, message: String, author: String = "tester <tester@example.com>", seconds: Long? = null): String {
        val file = File(directory, path)
        file.parentFile.mkdirs()
        file.writeText(content)
        return Hg.open(directory).use { hg ->
            hg.add().addFile(path).call()
            val command = hg.commit().setAuthor(author).setMessage(message)
            if (seconds != null) command.setDate(seconds, 0)
            io.github.search5.hg4j.util.NodeIdUtil.toHex(command.call())
        }
    }

    fun bookmark(directory: File, name: String, node: String? = null) {
        Hg.open(directory).use { hg ->
            val command = hg.bookmark().setBookmarkName(name).setForce(true)
            if (node != null) command.setNodeId(io.github.search5.hg4j.util.NodeIdUtil.fromHex(node))
            command.call()
        }
    }

    fun deleteBookmark(directory: File, name: String) {
        Hg.open(directory).use { it.bookmark().setBookmarkName(name).setDelete(true).call() }
    }

    fun bookmarks(directory: File): Map<String, String> {
        val repository = NativeHgRepository(directory)
        try { return HgLocalClient(repository).listKeys("bookmarks") } finally { repository.close() }
    }

    /** A source backed by a local repository, for engine specs that do not exercise HTTPS. */
    fun localSource(directory: File) = object : HgMirrorSource {
        override fun heads(): List<String> {
            val repository = NativeHgRepository(directory)
            try { return HgLocalClient(repository).heads.filter { it != HgHttpsMirrorSource.NULL_NODE } } finally { repository.close() }
        }
        override fun root(): String? = HgMirrorChangelog(directory).use { if (it.count == 0) null else it.node(0) }
        override fun known(nodes: List<String>): List<Boolean> = HgMirrorChangelog(directory).use { log -> nodes.map { log.revisionOf(it) >= 0 } }
        override fun bundle(common: List<String>, heads: List<String>): ByteArray {
            val repository = NativeHgRepository(directory)
            try { return HgLocalClient(repository).getBundle(common, heads, emptyList()) } finally { repository.close() }
        }
        override fun bookmarks(): Map<String, String> = bookmarks(directory)
    }
}
