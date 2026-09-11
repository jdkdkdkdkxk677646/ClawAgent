package com.openclaw.clawagent.conversation

import android.content.Context
import android.util.Log
import androidx.room.Room
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists a [ConversationTree].
 *
 * v4.0 Phase 2: the backing store is Room (`claw.db`, one row per branch /
 * message, plus a tiny meta table for the active-branch pointer) instead of
 * a single JSON blob in SharedPreferences — no more 300-message ceiling,
 * real queryability, and atomic transactions.
 *
 * v4.1: [load] / [save] are `suspend` and Room runs them on its own
 * transaction executor — `allowMainThreadQueries()` is gone. The public API
 * shape is otherwise unchanged, so callers only add `await`/`launch`.
 *
 * Legacy migration: the first load after upgrading detects the old
 * `claw_branches` SharedPreferences JSON, imports it into Room, and deletes
 * the blob only after the import committed. A corrupt legacy blob is ignored
 * exactly like before — a bad save must never crash the app on launch.
 */
class ConversationStorage(context: Context) {

    private val db: ClawDatabase = Room.databaseBuilder(
        context.applicationContext,
        ClawDatabase::class.java,
        ClawDatabase.NAME,
    ).build()

    private val legacyPrefs =
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns null if no save exists or the save is corrupt. */
    suspend fun load(): Pair<List<ConversationBranch>, String>? {
        migrateLegacyIfAny()
        return loadFromRoom()
    }

    suspend fun save(tree: ConversationTree) {
        try {
            val (branches, messages) = toEntities(tree)
            db.conversationDao().replaceAllWithMeta(
                branches,
                messages,
                listOf(MetaEntity(KEY_ACTIVE, tree.activeBranchId)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tree", e)
        }
    }

    // ── Room ↔ memory model ──────────────────────────────────────

    private fun toEntities(tree: ConversationTree): Pair<List<BranchEntity>, List<MessageEntity>> {
        val branches = ArrayList<BranchEntity>()
        val messages = ArrayList<MessageEntity>()
        tree.allBranches.forEachIndexed { order, b ->
            branches += BranchEntity(
                id = b.id,
                name = b.name,
                parentId = b.parentId,
                forkAtMessageIndex = b.forkAtMessageIndex,
                createdAt = b.createdAt,
                sortOrder = order,
            )
            b.messages.forEachIndexed { idx, m ->
                messages += MessageEntity(
                    branchId = b.id,
                    idx = idx,
                    role = m.role,
                    content = m.content,
                    timestamp = m.timestamp,
                )
            }
        }
        return branches to messages
    }

    private suspend fun loadFromRoom(): Pair<List<ConversationBranch>, String>? {
        val dao = db.conversationDao()
        val branchRows = dao.branches()
        if (branchRows.isEmpty()) return null
        val messagesByBranch = dao.messages().groupBy { it.branchId }

        val branches = branchRows.map { row ->
            val msgs = messagesByBranch[row.id].orEmpty()
                .sortedBy { it.idx }
                .map { BranchMessage(role = it.role, content = it.content, timestamp = it.timestamp) }
            ConversationBranch(
                id = row.id,
                name = row.name,
                parentId = row.parentId,
                forkAtMessageIndex = row.forkAtMessageIndex,
                messages = ArrayList(msgs),
                createdAt = row.createdAt,
            )
        }
        val activeId = dao.getMeta(KEY_ACTIVE) ?: branches.first().id
        return branches to activeId
    }

    // ── legacy migration ─────────────────────────────────────────

    private suspend fun migrateLegacyIfAny() {
        val raw = legacyPrefs.getString(LEGACY_KEY_TREE, null) ?: return
        val imported = try {
            parseLegacy(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Legacy tree unparseable, dropping it", e)
            null
        }
        if (imported != null) {
            db.conversationDao().replaceAllWithMeta(
                imported.branches,
                imported.messages,
                listOf(MetaEntity(KEY_ACTIVE, imported.activeId)),
            )
            Log.i(TAG, "Legacy conversation tree migrated to Room")
        } else {
            Log.w(TAG, "Legacy conversation tree unparseable, dropping it")
        }
        // Either way the blob's job is done — the DB is now the source of
        // truth, and a successful import is its own migration flag.
        legacyPrefs.edit().clear().apply()
    }

    private class LegacyImport(
        val branches: List<BranchEntity>,
        val messages: List<MessageEntity>,
        val activeId: String,
    )

    /** Old format: {"active": id, "branches": [ {..., "messages": [...]} ]}. */
    private fun parseLegacy(raw: String): LegacyImport {
        val root = JSONObject(raw)
        val activeId = root.optString(LEGACY_KEY_ACTIVE, "")
        val branchArray = root.getJSONArray(LEGACY_KEY_BRANCHES)
        val branches = ArrayList<BranchEntity>(branchArray.length())
        val messages = ArrayList<MessageEntity>()
        for (i in 0 until branchArray.length()) {
            val obj = branchArray.getJSONObject(i)
            val id = obj.optString("id")
            branches += BranchEntity(
                id = id,
                name = obj.optString("name", "main"),
                parentId = obj.opt("parentId")?.takeIf { it != JSONObject.NULL } as? String,
                forkAtMessageIndex = obj.optInt("forkAtMessageIndex", 0),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                sortOrder = i,
            )
            val msgs = obj.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until msgs.length()) {
                val m = msgs.getJSONObject(j)
                messages += MessageEntity(
                    branchId = id,
                    idx = j,
                    role = m.optString("role", "user"),
                    content = m.optString("content", ""),
                    timestamp = m.optLong("timestamp", 0L),
                )
            }
        }
        return LegacyImport(branches, messages, activeId)
    }

    companion object {
        private const val TAG = "ConversationStorage"
        private const val KEY_ACTIVE = "active_branch_id"
        private const val LEGACY_PREFS_NAME = "claw_branches"
        private const val LEGACY_KEY_TREE = "tree"
        private const val LEGACY_KEY_BRANCHES = "branches"
        private const val LEGACY_KEY_ACTIVE = "active"
    }
}
