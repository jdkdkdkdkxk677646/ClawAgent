package com.openclaw.clawagent.conversation

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists a [ConversationTree] as a single JSON blob in SharedPreferences.
 *
 * The on-disk format is intentionally hand-rolled JSON rather than
 * kotlinx-serialization because we already depend on the Android `org.json`
 * stack and the schema is small. Future versions can fall back to a default
 * tree if the JSON is unparseable — we never want a corrupt save to crash
 * the app on launch.
 */
class ConversationStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns null if no save exists or the save is corrupt. */
    fun load(): Pair<List<ConversationBranch>, String>? {
        val raw = prefs.getString(KEY_TREE, null) ?: return null
        return try {
            val root = JSONObject(raw)
            val activeId = root.optString(KEY_ACTIVE, "")
            val branchArray = root.getJSONArray(KEY_BRANCHES)
            val branches = ArrayList<ConversationBranch>(branchArray.length())
            for (i in 0 until branchArray.length()) {
                branches.add(parseBranch(branchArray.getJSONObject(i)))
            }
            branches to activeId
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved tree, ignoring", e)
            null
        }
    }

    fun save(tree: ConversationTree) {
        try {
            val root = JSONObject()
            root.put(KEY_ACTIVE, tree.activeBranchId)
            val arr = JSONArray()
            for (branch in tree.allBranches) {
                arr.put(serializeBranch(branch))
            }
            root.put(KEY_BRANCHES, arr)
            prefs.edit().putString(KEY_TREE, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tree", e)
        }
    }

    private fun serializeBranch(b: ConversationBranch): JSONObject {
        val obj = JSONObject()
        obj.put("id", b.id)
        obj.put("name", b.name)
        obj.put("parentId", b.parentId ?: JSONObject.NULL)
        obj.put("forkAtMessageIndex", b.forkAtMessageIndex)
        obj.put("createdAt", b.createdAt)
        val msgs = JSONArray()
        for (m in b.messages) {
            val mj = JSONObject()
            mj.put("role", m.role)
            mj.put("content", m.content)
            mj.put("timestamp", m.timestamp)
            msgs.put(mj)
        }
        obj.put("messages", msgs)
        return obj
    }

    private fun parseBranch(obj: JSONObject): ConversationBranch {
        val msgs = obj.optJSONArray("messages")
        val list = if (msgs != null) {
            ArrayList<BranchMessage>(msgs.length()).also { out ->
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    out.add(
                        BranchMessage(
                            role = m.optString("role", "user"),
                            content = m.optString("content", ""),
                            timestamp = m.optLong("timestamp", 0L),
                        )
                    )
                }
            }
        } else {
            mutableListOf()
        }
        return ConversationBranch(
            id = obj.optString("id"),
            name = obj.optString("name", "main"),
            parentId = obj.opt("parentId")?.takeIf { it != JSONObject.NULL } as? String,
            forkAtMessageIndex = obj.optInt("forkAtMessageIndex", 0),
            messages = list,
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
        )
    }

    companion object {
        private const val TAG = "ConversationStorage"
        private const val PREFS_NAME = "claw_branches"
        private const val KEY_TREE = "tree"
        private const val KEY_BRANCHES = "branches"
        private const val KEY_ACTIVE = "active"
    }
}
