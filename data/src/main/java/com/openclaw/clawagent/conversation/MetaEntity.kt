package com.openclaw.clawagent.conversation

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tiny key/value table inside the conversation database — currently holds
 * only `active_branch_id` so a "switch branch" survives restarts even when
 * the tree itself is unchanged between saves.
 */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
