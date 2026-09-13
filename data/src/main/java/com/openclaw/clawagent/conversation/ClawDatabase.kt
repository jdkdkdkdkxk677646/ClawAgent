package com.openclaw.clawagent.conversation

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * One-time migration from schema version 1 (branches/messages/meta only) to
 * version 2 (adds message_fts FTS4 table with triggers + backfill).
 *
 * Triggers keep the FTS index synced on INSERT/UPDATE/DELETE. The backfill
 * seeds existing messages so an upgrade from v1 immediately gets searchability.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: androidx.room.SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE VIRTUAL TABLE message_fts USING fts4(
                branchId, idx, role, content, timestamp,
                content='messages',
                content_rowid='rowid'
            )
        """.trimIndent())
        // Triggers keep FTS in sync.
        db.execSQL("""
            CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO message_fts(rowid, branchId, idx, role, content, timestamp)
                VALUES (new.rowid, new.branchId, new.idx, new.role, new.content, new.timestamp);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
                DELETE FROM message_fts WHERE rowid = old.rowid;
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER messages_au AFTER UPDATE ON messages BEGIN
                DELETE FROM message_fts WHERE rowid = old.rowid;
                INSERT INTO message_fts(rowid, branchId, idx, role, content, timestamp)
                VALUES (new.rowid, new.branchId, new.idx, new.role, new.content, new.timestamp);
            END
        """.trimIndent())
        // Backfill existing messages.
        db.execSQL("""
            INSERT INTO message_fts(rowid, branchId, idx, role, content, timestamp)
            SELECT rowid, branchId, idx, role, content, timestamp FROM messages
        """.trimIndent())
    }
}

@Database(
    entities = [BranchEntity::class, MessageEntity::class, MetaEntity::class, MessageFtsEntity::class],
    version = 2,
    exportSchema = false,
    migrations = [MIGRATION_1_2]
)
abstract class ClawDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        const val NAME = "claw.db"
    }
}
