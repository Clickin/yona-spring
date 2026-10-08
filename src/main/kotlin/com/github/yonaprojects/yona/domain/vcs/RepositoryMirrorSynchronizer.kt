package com.github.yonaprojects.yona.domain.vcs

import org.springframework.stereotype.Service

/** Runs one claimed mirror with the engine for its VCS, under the physical writer lock. */
@Service
class RepositoryMirrorSynchronizer(
    private val store: RepositoryMirrorStore,
    private val storage: RepositoryMirrorStorage,
    private val locks: RepositoryMirrorLock,
    private val properties: RepositoryMirrorProperties,
    engines: List<MirrorEngine>
) {
    private val engines = engines.associateBy { it.vcs }

    fun engine(vcs: MirrorVcs): MirrorEngine = engines[vcs] ?: throw MirrorFailure("UNSUPPORTED_VCS", attention = true)

    fun syncOneMirror(id: Long, owner: String, fence: Long, cancelled: () -> Boolean) {
        properties.requireActivation()
        val initial = store.snapshot(id)
        val engine = engine(MirrorVcs.of(initial.project))
        locks.withLock(requireNotNull(initial.project.id)) {
            val checkActive = {
                if (cancelled()) throw MirrorLeaseLost()
                store.fenced(id, owner, fence) { }
            }
            checkActive()
            engine.sync(MirrorSyncContext(id, owner, fence, initial, storage.directory(initial), checkActive, cancelled))
            store.complete(id, owner, fence, properties.syncSeconds)
        }
    }
}
