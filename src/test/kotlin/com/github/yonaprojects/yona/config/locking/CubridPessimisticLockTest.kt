package com.github.yonaprojects.yona.config.locking

import com.github.yonaprojects.yona.config.SchemaTestDatabase
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Entity
@Table(name = "pessimistic_lock_probe")
class LockProbe(@Id var id: Long = 1, var revision: Int = 0)

class CubridPessimisticLockTest {
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    fun competingPessimisticWriteWaitsUntilTheOwnerCommits() {
        assumeTrue(SchemaTestDatabase.kind == "cubrid")
        SchemaTestDatabase.dataSource().use { dataSource ->
            val factory = SchemaTestDatabase.factory(dataSource, "com.github.yonaprojects.yona.config.locking")
            factory.afterPropertiesSet()
            try {
                val emf = factory.`object`!!
                emf.createEntityManager().use { setup ->
                    setup.transaction.begin()
                    setup.persist(LockProbe())
                    setup.transaction.commit()
                }
                Executors.newSingleThreadExecutor().use { executor ->
                    emf.createEntityManager().use { owner ->
                        owner.transaction.begin()
                        val row = owner.find(LockProbe::class.java, 1L, LockModeType.PESSIMISTIC_WRITE)
                        val attempting = CountDownLatch(1)
                        val contender = executor.submit<Int> {
                            emf.createEntityManager().use { other ->
                                other.transaction.begin()
                                try {
                                    attempting.countDown()
                                    val locked = other.find(LockProbe::class.java, 1L, LockModeType.PESSIMISTIC_WRITE)
                                    val revision = locked.revision
                                    other.transaction.commit()
                                    revision
                                } finally {
                                    if (other.transaction.isActive) other.transaction.rollback()
                                }
                            }
                        }
                        try {
                            check(attempting.await(5, TimeUnit.SECONDS))
                            assertThrows(TimeoutException::class.java) { contender.get(300, TimeUnit.MILLISECONDS) }
                            row.revision = 42
                            owner.transaction.commit()
                            assertEquals(42, contender.get(5, TimeUnit.SECONDS))
                        } finally {
                            if (owner.transaction.isActive) owner.transaction.rollback()
                        }
                    }
                }
            } finally {
                factory.destroy()
            }
        }
    }
}
