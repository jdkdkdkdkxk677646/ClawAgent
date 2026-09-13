package com.openclaw.clawagent.conversation

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Room representation of one branch of the conversation tree.
 *
 * The in-memory model ([ConversationBranch]) keeps messages inside the
 * object; Room normalises them into [MessageEntity] rows instead, so the
 * entity carries only branch identity/shape. [sortOrder] preserves the
 * branch ordering the user sees in the picker (the old JSON blob kept
 * insertion order implicitly).
 */
@Entity(tableName = "branches")
data class BranchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val forkAtMessageIndex: Int,
    val createdAt: Long,
    val sortOrder: Int,
)

/**
 * One message inside a branch. Primary key (branchId, idx) — idx is the
 * 0-based position within the branch, which makes full-tree replacement and
 * ordered reads trivial.
 */
@Entity(tableName = "messages", primaryKeys = ["branchId", "idx"])
data class MessageEntity(
    val branchId: String,
    val idx: Int,
    val role: String,
    val content: String,
    val timestamp: Long,
)

/**
 * FTS4 virtual table for searching message content across all branches.
 *
 * External content FTS: [contentEntity] points back to [MessageEntity], so
 * triggers keep the FTS index in sync automatically (defined in the migration
 * SQL). Query via [ConversationDao.searchMessages].
 */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "message_fts")
data class MessageFtsEntity(
    @PrimaryKey val rowid: Long = 0,
    val branchId: String = "",
    val idx: Int = 0,
    val role: String = "",
    val content: String = "",
    val timestamp: Long = 0,
)

