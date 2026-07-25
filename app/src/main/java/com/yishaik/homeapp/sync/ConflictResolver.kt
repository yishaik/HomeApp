package com.yishaik.homeapp.sync

import com.yishaik.homeapp.domain.HomeItem

data class MergeConflict(val field: String, val local: Any?, val remote: Any?)
data class MergeResult(val merged: HomeItem, val conflicts: List<MergeConflict>)

object ConflictResolver {
    fun merge(base: HomeItem, local: HomeItem, remote: HomeItem): MergeResult {
        val conflicts = mutableListOf<MergeConflict>()

        fun <T> choose(field: String, baseValue: T, localValue: T, remoteValue: T): T {
            val localChanged = localValue != baseValue
            val remoteChanged = remoteValue != baseValue
            return when {
                localChanged && remoteChanged && localValue != remoteValue -> {
                    conflicts += MergeConflict(field, localValue, remoteValue)
                    if (local.updatedAt >= remote.updatedAt) localValue else remoteValue
                }
                localChanged -> localValue
                remoteChanged -> remoteValue
                else -> baseValue
            }
        }

        val merged = base.copy(
            title = choose("title", base.title, local.title, remote.title),
            body = choose("body", base.body, local.body, remote.body),
            link = choose("link", base.link, local.link, remote.link),
            assignee = choose("assignee", base.assignee, local.assignee, remote.assignee),
            participantIds = choose("participantIds", base.participantIds, local.participantIds, remote.participantIds),
            tags = choose("tags", base.tags, local.tags, remote.tags),
            status = choose("status", base.status, local.status, remote.status),
            startAt = choose("startAt", base.startAt, local.startAt, remote.startAt),
            endAt = choose("endAt", base.endAt, local.endAt, remote.endAt),
            dueAt = choose("dueAt", base.dueAt, local.dueAt, remote.dueAt),
            checklist = mergeChecklist(base, local, remote),
            comments = (local.comments + remote.comments).distinctBy { it.id }.sortedBy { it.createdAt },
            readReceipts = (local.readReceipts + remote.readReceipts)
                .groupBy { it.userId }
                .map { (_, values) -> values.maxByOrNull { it.readAt ?: java.time.Instant.EPOCH }!! },
            revision = maxOf(local.revision, remote.revision) + 1,
            updatedAt = maxOf(local.updatedAt, remote.updatedAt),
        )
        return MergeResult(merged, conflicts)
    }

    private fun mergeChecklist(base: HomeItem, local: HomeItem, remote: HomeItem) =
        (base.checklist + local.checklist + remote.checklist)
            .groupBy { it.id }
            .map { (_, versions) -> versions.last() }
            .sortedBy { it.orderIndex }
}
