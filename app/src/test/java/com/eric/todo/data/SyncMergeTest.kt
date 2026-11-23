package com.eric.todo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private fun t(id: String, text: String = id, created: Long = 1, updated: Long = 0, done: Boolean = false) =
        Task(id = id, text = text, list = BUSINESS, done = done, createdAt = created, updatedAt = updated)

    private fun s(vararg tasks: Task, deleted: Map<String, Long> = emptyMap(), order: Long = 0) =
        SyncState(tasks.toList(), deleted, order)

    private val now = 1_000_000L

    @Test fun firstSyncToEmptyFileKeepsEverything() {
        val m = SyncMerge.merge(s(t("a"), t("b")), s(), now)
        assertEquals(listOf("a", "b"), m.tasks.map { it.id })
    }

    @Test fun tasksAddedOnBothDevicesAreAllKept() {
        val m = SyncMerge.merge(s(t("a", created = 10)), s(t("b", created = 20)), now)
        assertEquals(setOf("a", "b"), m.tasks.map { it.id }.toSet())
    }

    @Test fun newestEditWins() {
        val phone = s(t("a", text = "Call Juan", updated = 100))
        val drive = s(t("a", text = "Call Juan about the dock", updated = 200))
        assertEquals("Call Juan about the dock", SyncMerge.merge(phone, drive, now).tasks.single().text)
        assertEquals("Call Juan about the dock", SyncMerge.merge(drive, phone, now).tasks.single().text)
    }

    @Test fun checkedOffOnOtherDeviceComesThrough() {
        val phone = s(t("a", updated = 100))
        val tablet = s(t("a", updated = 300, done = true))
        assertTrue(SyncMerge.merge(phone, tablet, now).tasks.single().done)
    }

    @Test fun deleteOnOneDeviceRemovesEverywhere() {
        val phone = s(t("b"), deleted = mapOf("a" to 500))
        val drive = s(t("a", updated = 100), t("b"))
        val m = SyncMerge.merge(phone, drive, now)
        assertEquals(listOf("b"), m.tasks.map { it.id })
        assertTrue("a" in m.deleted)
    }

    @Test fun editAfterDeleteKeepsTheTask() {
        val phone = s(deleted = mapOf("a" to 500))
        val tablet = s(t("a", updated = 900))
        val m = SyncMerge.merge(phone, tablet, now)
        assertEquals(listOf("a"), m.tasks.map { it.id })
        assertFalse("a" in m.deleted)
    }

    @Test fun newestOrderWinsAndNewTasksGoOnTop() {
        val phone = s(t("a"), t("b"), t("c"), order = 100)
        val tablet = s(t("x", created = 999), t("c"), t("b"), t("a"), order = 200)
        val m = SyncMerge.merge(phone, tablet, now)
        assertEquals(listOf("x", "c", "b", "a"), m.tasks.map { it.id })

        val phoneNewer = s(t("a"), t("b"), t("c"), t("y", created = 999), order = 300)
        val m2 = SyncMerge.merge(phoneNewer, s(t("c"), t("b"), t("a"), order = 200), now)
        assertEquals(listOf("a", "b", "c", "y"), m2.tasks.map { it.id })
    }

    @Test fun syncingTwiceChangesNothing() {
        val phone = s(t("a", updated = 5), t("b"), deleted = mapOf("z" to 7), order = 3)
        val drive = s(t("b", updated = 9), t("c"), order = 4)
        val once = SyncMerge.merge(phone, drive, now)
        val twice = SyncMerge.merge(once, once, now)
        assertEquals(once, twice)
    }

    @Test fun oldDeletionsAreForgotten() {
        val old = now - 200L * 24 * 60 * 60 * 1000
        val m = SyncMerge.merge(s(deleted = mapOf("a" to old)), s(), now)
        assertTrue(m.deleted.isEmpty())
    }
}
