package com.eric.todo.data

/** Everything a device knows about the list, as kept in tasks.json and the Drive sync file. */
data class SyncState(
    val tasks: List<Task>,
    val deleted: Map<String, Long>,   // task id -> when it was deleted
    val orderChangedAt: Long,         // last time the order was changed by hand
)

/**
 * Combines this device's list with the shared list in Google Drive.
 * Rules:
 *  - A task changed on both devices: the newest change wins.
 *  - A task deleted on one device is deleted everywhere, unless it was changed again after the delete.
 *  - Ties (same time on both) always go to the shared online copy, so every device ends up identical.
 *  - Order: the device that rearranged its list most recently decides the order; tasks the other device
 *    added since then go on top (new tasks always go on top).
 */
object SyncMerge {

    private const val KEEP_DELETIONS_MS = 120L * 24 * 60 * 60 * 1000   // 120 days

    fun stamp(t: Task): Long = if (t.updatedAt > 0) t.updatedAt else t.createdAt

    fun merge(local: SyncState, remote: SyncState, now: Long = System.currentTimeMillis()): SyncState {
        val deleted = HashMap<String, Long>(local.deleted)
        remote.deleted.forEach { (id, at) -> deleted[id] = maxOf(at, deleted[id] ?: 0L) }

        val localById = local.tasks.associateBy { it.id }
        val remoteById = remote.tasks.associateBy { it.id }
        val chosen = LinkedHashMap<String, Task>()
        for (id in localById.keys + remoteById.keys) {
            val l = localById[id]
            val r = remoteById[id]
            val pick = when {
                l == null -> r
                r == null -> l
                stamp(r) >= stamp(l) -> r   // tie: the shared copy wins
                else -> l
            } ?: continue
            val deletedAt = deleted[id]
            if (deletedAt != null && deletedAt >= stamp(pick)) continue
            chosen[id] = pick
            deleted.remove(id)   // changed after the delete: it lives
        }

        val base = if (remote.orderChangedAt >= local.orderChangedAt) remote.tasks else local.tasks   // tie: shared order
        val baseIds = base.map { it.id }.filter { it in chosen }
        val inBase = baseIds.toHashSet()
        val newOnes = chosen.values.filter { it.id !in inBase }.sortedByDescending { it.createdAt }
        val ordered = newOnes + baseIds.map { chosen.getValue(it) }

        return SyncState(
            tasks = ordered,
            deleted = prune(deleted, now),
            orderChangedAt = maxOf(local.orderChangedAt, remote.orderChangedAt),
        )
    }

    fun prune(deleted: Map<String, Long>, now: Long): Map<String, Long> =
        deleted.filterValues { now - it < KEEP_DELETIONS_MS }
}
