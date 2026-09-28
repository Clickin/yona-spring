package com.github.yonaprojects.yona.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpSession
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class QueueEventStreamTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun blockedSendAndCompletionDoNotBlockOtherStreamsAndNoPendingSendSurvivesClosure(virtualThreads: Boolean) {
        val senders = if (virtualThreads) Executors.newVirtualThreadPerTaskExecutor() else Executors.newFixedThreadPool(32)
        val completions = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon().factory())
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val completionEntered = CountDownLatch(1)
        val slowWrites = LinkedBlockingQueue<String>()
        val healthyWrites = LinkedBlockingQueue<String>()
        val block = AtomicBoolean(false)
        val unregistered = AtomicInteger()
        val slowEmitter = object : SseEmitter() {
            private lateinit var completed: Runnable
            override fun onCompletion(callback: Runnable) { completed = callback }
            override fun send(event: SseEventBuilder) {
                slowWrites.add(event.build().joinToString("") { it.data.toString() })
                if (block.get()) {
                    entered.countDown()
                    released.await()
                }
            }
            override fun complete() {
                completed.run()
                completionEntered.countDown()
                released.await()
            }
        }
        val healthyEmitter = object : SseEmitter() {
            override fun send(event: SseEventBuilder) {
                healthyWrites.add(event.build().joinToString("") { it.data.toString() })
            }
        }
        val slow = QueueEventStream(1, "slow", MockHttpSession(), slowEmitter, senders, completions) { unregistered.incrementAndGet() }
        val healthy = QueueEventStream(2, "healthy", MockHttpSession(), healthyEmitter, senders, completions) {}
        try {
            slow.start(false)
            healthy.start(false)
            assertTrue(slowWrites.poll(1, TimeUnit.SECONDS).contains("reset"))
            assertTrue(healthyWrites.poll(1, TimeUnit.SECONDS).contains("reset"))
            block.set(true)
            slow.changed(1)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(slowWrites.poll(1, TimeUnit.SECONDS).contains("\"generation\":\"1\""))
            slow.changed(2)
            slow.changed(3)
            healthy.changed(3)
            assertTrue(healthyWrites.poll(1, TimeUnit.SECONDS).contains("\"generation\":\"3\""))
            assertTrue(completionEntered.await(2_100, TimeUnit.MILLISECONDS), "Blocked write must request completion independently")
            assertFalse(slow.active)
            assertEquals(0, unregistered.get(), "An early container callback must not free a blocked sender's quota")
            slow.changed(4)
            healthy.authorize(System.nanoTime())
            healthy.changed(4)
            assertTrue(healthyWrites.poll(1, TimeUnit.SECONDS).contains("\"generation\":\"4\""))
            released.countDown()
            senders.shutdown()
            assertTrue(senders.awaitTermination(1, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), slowWrites.toList(), "Closed stream must discard all pending invalidations")
            assertEquals(1, unregistered.get())
        } finally {
            released.countDown()
            slow.close()
            healthy.close()
            senders.shutdownNow()
            completions.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun pendingInvalidationsCoalesceAndAnExpiredAuthoritySampleCannotSend(virtualThreads: Boolean) {
        val senders = if (virtualThreads) Executors.newVirtualThreadPerTaskExecutor() else Executors.newFixedThreadPool(32)
        val completions = Executors.newSingleThreadExecutor()
        val firstChanged = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = LinkedBlockingQueue<String>()
        val completed = CountDownLatch(1)
        val emitter = object : SseEmitter() {
            override fun send(event: SseEventBuilder) {
                val frame = event.build().joinToString("") { it.data.toString() }
                writes.add(frame)
                if (frame.contains("\"generation\":\"1\"")) {
                    firstChanged.countDown()
                    release.await()
                }
            }
            override fun complete() { completed.countDown() }
        }
        val stream = QueueEventStream(1, "admin", MockHttpSession(), emitter, senders, completions) {}
        try {
            stream.start(false)
            assertTrue(writes.poll(1, TimeUnit.SECONDS).contains("\"reason\":\"connect\""))
            stream.changed(1)
            assertTrue(firstChanged.await(1, TimeUnit.SECONDS))
            assertTrue(writes.poll(1, TimeUnit.SECONDS).contains("\"generation\":\"1\""))
            stream.changed(2)
            stream.heartbeat()
            stream.changed(3)
            release.countDown()
            assertTrue(writes.poll(1, TimeUnit.SECONDS).contains("\"reason\":\"buffer-overflow\""))
            stream.authorize(System.nanoTime() - TimeUnit.SECONDS.toNanos(3))
            stream.changed(4)
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            assertFalse(stream.active)
            assertEquals(emptyList<String>(), writes.toList(), "Stale authority must never produce another frame")
        } finally {
            release.countDown()
            stream.close()
            senders.shutdownNow()
            completions.shutdownNow()
        }
    }
}
