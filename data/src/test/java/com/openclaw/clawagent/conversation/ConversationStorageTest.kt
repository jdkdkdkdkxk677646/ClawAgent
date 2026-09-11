package com.openclaw.clawagent.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 2 data-layer tests, run on Robolectric with an on-disk database so
 * the migration path (legacy SharedPreferences JSON → Room) is exercised
 * through the real [ConversationStorage] public API.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationStorageTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 每个用例一个全新的库与 legacy prefs,避免互相污染。
        context.deleteDatabase(ClawDatabase.NAME)
        context.getSharedPreferences("claw_branches", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun legacyJson(activeId: String, branchesJson: String): String =
        """{"active":"$activeId","branches":[$branchesJson]}"""

    private fun legacyBranch(id: String, name: String, parentId: String?, fork: Int, msgs: String) =
        """{"id":"$id","name":"$name","parentId":""" +
            (parentId?.let { "\"$it\"" } ?: "null") +
            ""","forkAtMessageIndex":$fork,"createdAt":1234567890,"messages":[$msgs]}"""

    private fun msg(role: String, content: String) =
        """{"role":"$role","content":"$content","timestamp":42}"""

    @Test
    fun `save and load round-trips branches, messages and active id`() {
        val storage = ConversationStorage(context)
        val tree = ConversationTree()
        tree.appendMessage("user", "hello")
        tree.appendMessage("assistant", "world")
        val forkedId = tree.forkAt(messageIndex = 1, name = "branch-b")
        tree.appendMessage("user", "on the branch")

        storage.save(tree)

        val loaded = storage.load()
        val (branches, activeId) = loaded!!
        assertEquals(forkedId.id, activeId)
        assertEquals(2, branches.size)

        val root = branches.first { it.parentId == null }
        assertEquals(listOf("hello", "world"), root.messages.map { it.content })

        val forked = branches.first { it.id == forkedId.id }
        assertEquals("branch-b", forked.name)
        assertEquals(root.id, forked.parentId)
        assertEquals(1, forked.forkAtMessageIndex)
        assertEquals(listOf("on the branch"), forked.messages.map { it.content })
        assertTrue(forked.messages.all { it.timestamp > 0 })
    }

    @Test
    fun `empty database loads as null`() {
        assertNull(ConversationStorage(context).load())
    }

    @Test
    fun `legacy json blob is migrated into room and cleared`() {
        val legacy = context.getSharedPreferences("claw_branches", Context.MODE_PRIVATE)
        legacy.edit().putString(
            "tree",
            legacyJson(
                // 激活分支是 child-1 —— 迁移后 load() 必须如实还原它
                "child-1",
                legacyBranch("root-1", "历史对话", null, 0, msg("user", "旧消息一")) + "," +
                    legacyBranch("child-1", "分叉", "root-1", 1, msg("user", "分支消息")),
            ),
        ).commit()

        val storage = ConversationStorage(context)
        val (branches, activeId) = storage.load()!!

        assertEquals("child-1", activeId)
        assertEquals(2, branches.size)
        val root = branches.first { it.id == "root-1" }
        assertEquals(listOf("旧消息一"), root.messages.map { it.content })
        val child = branches.first { it.id == "child-1" }
        assertEquals(1, child.forkAtMessageIndex)
        assertEquals(listOf("分支消息"), child.messages.map { it.content })

        // The blob is gone — a second load comes from Room, not the legacy path.
        assertNull(legacy.getString("tree", null))
        val again = storage.load()!!
        assertEquals(2, again.first.size)
    }

    @Test
    fun `corrupt legacy blob is dropped without crashing`() {
        val legacy = context.getSharedPreferences("claw_branches", Context.MODE_PRIVATE)
        legacy.edit().putString("tree", "{not valid json").commit()

        assertNull(ConversationStorage(context).load())
        // Dropped so we don't retry a doomed parse on every launch.
        assertNull(legacy.getString("tree", null))
    }

    @Test
    fun `second save replaces instead of duplicating`() {
        val storage = ConversationStorage(context)
        val tree = ConversationTree()
        tree.appendMessage("user", "v1")
        storage.save(tree)
        tree.appendMessage("assistant", "v2")
        storage.save(tree)

        val (branches, _) = storage.load()!!
        val root = branches.first { it.parentId == null }
        assertEquals(listOf("v1", "v2"), root.messages.map { it.content })
        assertEquals(1, branches.size)
    }
}
