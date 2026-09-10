package com.openclaw.clawagent.agent

import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull

class ReminderStoreTest {

    private lateinit var store: ReminderStore

    @Before
    fun setUp() {
        store = ReminderStore.inMemory("[]")
    }

    @Test
    fun emptyInitially() {
        assertEquals(0, store.listAll().size)
        assertEquals(0, store.listPending(0L).size)
        assertEquals(0, store.listExpired(0L).size)
    }

    @Test
    fun registerAndList() {
        store.register(1, 1000L, "喝水")
        assertEquals(1, store.listAll().size)
        val entry = store.listAll()[0]
        assertEquals(1, entry.requestCode)
        assertEquals(1000L, entry.triggerAtMillis)
        assertEquals("喝水", entry.message)
    }

    @Test
    fun pendingFilter() {
        store.register(1, 2000L, "todo1")
        store.register(2, 3000L, "todo2")
        // now=1500: both pending
        val pending = store.listPending(1500L)
        assertEquals(2, pending.size)
        // now=2500: only #2 pending
        val pending2 = store.listPending(2500L)
        assertEquals(1, pending2.size)
        assertEquals(2, pending2[0].requestCode)
    }

    @Test
    fun expiredFilter() {
        store.register(1, 1000L, "old")
        store.register(2, 5000L, "new")
        // now=3000: only #1 expired
        val expired = store.listExpired(3000L)
        assertEquals(1, expired.size)
        assertEquals(1, expired[0].requestCode)
    }

    @Test
    fun remove() {
        store.register(1, 9999L, "task")
        store.remove(1)
        assertEquals(0, store.listAll().size)
    }

    @Test
    fun noOpRemoveUnknown() {
        store.remove(999)
        assertEquals(0, store.listAll().size)
    }

    @Test
    fun replaceExistingCode() {
        store.register(1, 1000L, "v1")
        store.register(1, 2000L, "v2")
        val list = store.listAll()
        assertEquals(1, list.size)
        assertEquals("v2", list[0].message)
        assertEquals(2000L, list[0].triggerAtMillis)
    }

    @Test
    fun bootReplayLogic() {
        // Simulate: register 3 reminders, then "boot" at T=5000
        store.register(1, 3000L, "早会")   // expired at boot
        store.register(2, 6000L, "开会")   // pending -> re-schedule
        store.register(3, 9000L, "下班")   // pending -> re-schedule

        val now = 5000L
        val pending = store.listPending(now)  // #2, #3
        val expired = store.listExpired(now)  // #1

        assertEquals(2, pending.size)
        assertEquals(1, expired.size)
        assertEquals(1, expired[0].requestCode)

        // "remove" expired and keep pending in store (simulating boot logic)
        for (e in expired) store.remove(e.requestCode)
        assertEquals(2, store.listAll().size)
        assertTrue(store.listAll().map { it.requestCode }.contains(2))
        assertTrue(store.listAll().map { it.requestCode }.contains(3))
    }
}
