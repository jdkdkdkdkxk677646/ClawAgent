package com.openclaw.clawagent.conversation

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BranchEntity::class, MessageEntity::class, MetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ClawDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        const val NAME = "claw.db"
    }
}
