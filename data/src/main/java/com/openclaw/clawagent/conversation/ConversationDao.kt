package com.openclaw.clawagent.conversation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Whole-tree persistence. The tree is small (≤ a few hundred messages), so
 * saving is a full replace inside one transaction — same semantics as the
 * old JSON blob, minus the size ceiling and with real queryability later.
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM branches ORDER BY sortOrder ASC")
    fun branches(): List<BranchEntity>

    @Query("SELECT * FROM messages ORDER BY branchId ASC, idx ASC")
    fun messages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBranches(branches: List<BranchEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM branches")
    fun clearBranches()

    @Query("DELETE FROM messages")
    fun clearMessages()

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun getMeta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putMeta(entry: MetaEntity)

    /** Atomically swap the whole tree. */
    @Transaction
    fun replaceAll(branches: List<BranchEntity>, messages: List<MessageEntity>) {
        clearBranches()
        clearMessages()
        insertBranches(branches)
        insertMessages(messages)
    }

    @Transaction
    fun replaceAllWithMeta(
        branches: List<BranchEntity>,
        messages: List<MessageEntity>,
        meta: List<MetaEntity>,
    ) {
        replaceAll(branches, messages)
        meta.forEach { putMeta(it) }
    }
}
