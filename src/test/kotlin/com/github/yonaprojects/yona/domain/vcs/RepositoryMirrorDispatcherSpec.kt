package com.github.yonaprojects.yona.domain.vcs

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RepositoryMirrorDispatcherSpec : DescribeSpec({
    fun properties() = RepositoryMirrorProperties().apply {
        enabled = true; egressRestricted = true; nodeId = "writer"; writerNodeId = "writer"
        proxyHost = "127.0.0.1"; proxyPort = 3128; allowedOrigins = listOf("https://svn.example.test")
        workers = 1
    }
    describe("bounded mirror dispatcher") {
        it("does not claim or execute while disabled or on a nondesignated node") {
            val store = mockk<RepositoryMirrorStore>()
            val sync = mockk<SvnMirrorSynchronizer>()
            val config = properties().apply { enabled = false }
            val dispatcher = RepositoryMirrorDispatcher(store, sync, config)
            try {
                dispatcher.dispatch()
                config.enabled = true
                config.nodeId = "reader"
                dispatcher.dispatch()
                verify { store wasNot Called; sync wasNot Called }
            } finally { dispatcher.close() }
        }
        it("releases only its submitted fence on executor rejection and recovers the slot") {
            val store = mockk<RepositoryMirrorStore>(relaxed = true)
            val sync = mockk<SvnMirrorSynchronizer>()
            every { store.due(1) } returns listOf(7)
            every { store.claim(7, any(), any()) } returnsMany listOf(2, 3)
            val dispatcher = RepositoryMirrorDispatcher(store, sync, properties())
            try {
                dispatcher.executor.shutdown()
                dispatcher.dispatch()
                dispatcher.dispatch()
                verify(exactly = 1) { store.release(7, any(), 2) }
                verify(exactly = 1) { store.release(7, any(), 3) }
                verify { sync wasNot Called }
            } finally { dispatcher.close() }
        }
        it("bounds admitted work and waits for the actual writer on shutdown") {
            val store = mockk<RepositoryMirrorStore>(relaxed = true)
            val sync = mockk<SvnMirrorSynchronizer>()
            every { store.due(1) } returns listOf(1, 2)
            every { store.claim(1, any(), any()) } returns 1
            every { store.heartbeat(any(), any(), any(), any()) } returns true
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            every { sync.syncOneMirror(1, any(), 1, any()) } answers { started.countDown(); finish.await(20, TimeUnit.SECONDS); Unit }
            val dispatcher = RepositoryMirrorDispatcher(store, sync, properties())
            val closer = Executors.newSingleThreadExecutor()
            try {
                dispatcher.dispatch()
                started.await(10, TimeUnit.SECONDS) shouldBe true
                dispatcher.dispatch()
                verify(exactly = 1) { store.claim(any(), any(), any()) }
                val shutdownStarted = CountDownLatch(1)
                val shutdown = closer.submit { shutdownStarted.countDown(); dispatcher.close() }
                shutdownStarted.await(5, TimeUnit.SECONDS) shouldBe true
                shutdown.isDone shouldBe false
                verify(exactly = 0) { store.release(any(), any(), any()) }
                finish.countDown()
                shutdown.get(10, TimeUnit.SECONDS)
                verify(exactly = 1) { store.release(1, any(), 1) }
            } finally { finish.countDown(); dispatcher.close(); closer.shutdownNow() }
        }
        it("keeps authentication TLS and corruption out of transient retries") {
            RepositoryMirrorDispatcher.classify(java.net.SocketTimeoutException()).transient shouldBe true
            RepositoryMirrorDispatcher.classify(javax.net.ssl.SSLHandshakeException("secret")).transient shouldBe false
            RepositoryMirrorDispatcher.classify(javax.net.ssl.SSLHandshakeException("secret")).code shouldBe "SOURCE_TLS_REJECTED"
            RepositoryMirrorDispatcher.classify(MirrorFailure("TARGET_IDENTITY_CHANGED", attention = true)).attention shouldBe true
        }
    }
})
