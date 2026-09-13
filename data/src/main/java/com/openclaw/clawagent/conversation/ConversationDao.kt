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
 *
 * v4.1: every method is `suspend` — Room runs them on its own transaction
 * executor, so the UI thread never touches SQLite. (Phase 2 shipped these as
 * blocking methods plus `allowMainThreadQueries()` because the call sites
 * were synchronous; the Phase 4 ViewModel makes them async and the flag is
 * gone.)
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM branches ORDER BY sortOrder ASC")
    suspend fun branches(): List<BranchEntity>

    @Query("SELECT * FROM messages ORDER BY branchId ASC, idx ASC")
    suspend fun messages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBranches(branches: List<BranchEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM branches")
    suspend fun clearBranches()

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun getMeta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(entry: MetaEntity)

    /** Atomically swap the whole tree. */
    @Transaction
    suspend fun replaceAll(branches: List<BranchEntity>, messages: List<MessageEntity>) {
        clearBranches()
        clearMessages()
        insertBranches(branches)
        insertMessages(messages)
    }

    @Transaction
    suspend fun replaceAllWithMeta(
        branches: List<BranchEntity>,
        messages: List<MessageEntity>,
        meta: List<MetaEntity>,
    ) {
        replaceAll(branches, messages)
        meta.forEach { putMeta(it) }
    }

    /**
     * FTS search across all messages. Returns hits ordered by relevance (rank)
     * then recency. [limit] caps results (caller-enforced, default 5, max 20).
     *
     * Uses `MATCH` on the FTS4 virtual table; Chinese CJK text is tokenized by
     * SQLite's built-in tokenizer (unigram). For better CJK recall, a custom
     * tokenizer can be swapped in later.
     */
    data class MessageHit(
        val branchId: String,
        val idx: Int,
        val role: String,
        val content: String,
        val timestamp: Long,
        val rowid: Long,
    )

    @Query("""
        SELECT m.branchId, m.idx, m.role, m.content, m.timestamp, fts.rowid
        FROM message_fts fts
        JOIN messages m ON m.rowid = fts.rowid
        WHERE message_fts MATCH :query
        ORDER BY rank, m.timestamp DESC
        LIMIT :limit
    """)
    suspend fun searchMessages(query: String, limit: Int): List<MessageHit>
}
