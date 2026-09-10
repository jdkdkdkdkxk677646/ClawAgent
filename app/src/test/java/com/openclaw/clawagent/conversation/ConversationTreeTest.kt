package com.openclaw.clawagent.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the conversation tree. No Android dependencies, so
 * these run in the same fast lane as the SSE / markdown tests.
 */
class ConversationTreeTest {

    @Test
    fun `fresh tree has a main branch and no messages`() {
        val tree = ConversationTree()
        assertEquals(1, tree.allBranches.size)
        assertEquals(ConversationTree.DEFAULT_BRANCH_NAME, tree.activeBranch.name)
        assertTrue(tree.visibleMessages().isEmpty())
    }

    @Test
    fun `appendMessage adds to active branch`() {
        val tree = ConversationTree()
        tree.appendMessage("user", "hi")
        tree.appendMessage("assistant", "hello")
        val msgs = tree.visibleMessages()
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("assistant", msgs[1].role)
    }

    @Test
    fun `fork inherits leading messages and switches to new branch`() {
        val tree = ConversationTree()
        tree.appendMessage("user", "A")
        tree.appendMessage("assistant", "B")
        tree.appendMessage("user", "C")
        tree.appendMessage("assistant", "D")
        // Fork at index 2 — new branch inherits [A, B] from the parent.
        // The inherited messages are *not* copied into the new branch's
        // own list; visibleMessages() computes the effective list by
        // walking the parent chain.
        val newBranch = tree.forkAt(messageIndex = 2)
        assertNotNull(newBranch)
        assertEquals(newBranch.id, tree.activeBranchId)
        // The new branch's own message list is empty — inheritance is
        // virtual, not copied.
        assertTrue(tree.activeBranch.messages.isEmpty())
        assertEquals(2, newBranch.forkAtMessageIndex)
        val visible = tree.visibleMessages()
        assertEquals(2, visible.size)
        assertEquals("A", visible[0].content)
        assertEquals("B", visible[1].content)
    }

    @Test
    fun `forks do not mutate the parent branch`() {
        val tree = ConversationTree()
        tree.appendMessage("user", "hello")
        val parentSize = tree.activeBranch.messages.size
        tree.forkAt(messageIndex = 1)
        tree.appendMessage("user", "fork-side message")
        // Switch back to the main branch and confirm it is untouched.
        val main = tree.allBranches.first { it.parentId == null }
        tree.switchTo(main.id)
        assertEquals(parentSize, tree.activeBranch.messages.size)
        assertEquals(1, tree.visibleMessages().size)
    }

    @Test
    fun `switchTo updates the visible message list`() {
        val tree = ConversationTree()
        tree.appendMessage("user", "1")
        tree.appendMessage("assistant", "2")
        val branchA = tree.activeBranch
        tree.forkAt(messageIndex = 2, name = "alt")
        tree.appendMessage("user", "alt-3")
        assertEquals(3, tree.visibleMessages().size)
        tree.switchTo(branchA.id)
        assertEquals(2, tree.visibleMessages().size)
    }

    @Test
    fun `root branch cannot be deleted`() {
        val tree = ConversationTree()
        val rootId = tree.activeBranchId
        tree.deleteBranch(rootId)
        // Still there, still active.
        assertEquals(rootId, tree.activeBranchId)
        assertEquals(1, tree.allBranches.size)
    }

    @Test
    fun `deleteBranch reparents children to grandparent`() {
        val tree = ConversationTree()
        tree.appendMessage("user", "u1")
        val b1 = tree.forkAt(messageIndex = 1, name = "b1")
        tree.appendMessage("user", "b1-only")
        val b2 = tree.forkAt(messageIndex = 1, name = "b2")
        tree.appendMessage("user", "b2-only")
        // b2's parent is b1, b1's parent is root. Delete b1.
        tree.deleteBranch(b1.id)
        // b2 should now have root as parent, with forkAtMessageIndex
        // adjusted to b1's (1) + b2's (1) = 2.
        val reparented = tree.allBranches.first { it.id == b2.id }
        val rootId = tree.allBranches.first { it.parentId == null }.id
        assertEquals(rootId, reparented.parentId)
        assertEquals(2, reparented.forkAtMessageIndex)
        // Effective view: root's [u1] (only one message, take 2 yields
        // same) + b2's own [b2-only] = 2 messages.
        val visible = tree.visibleMessages()
        assertEquals(2, visible.size)
        assertEquals("u1", visible[0].content)
        assertEquals("b2-only", visible[1].content)
    }

    @Test
    fun `renameBranch ignores blank input`() {
        val tree = ConversationTree()
        val id = tree.activeBranchId
        tree.renameBranch(id, "   ")
        assertEquals(ConversationTree.DEFAULT_BRANCH_NAME, tree.activeBranch.name)
        tree.renameBranch(id, "Custom")
        assertEquals("Custom", tree.activeBranch.name)
    }

    @Test
    fun `clearActiveBranch keeps inherited prefix`() {
        val tree = ConversationTree()
        tree.appendMessage("user", "shared-1")
        tree.appendMessage("assistant", "shared-2")
        tree.forkAt(messageIndex = 2)
        tree.appendMessage("user", "branch-only")
        assertEquals(3, tree.visibleMessages().size)
        tree.clearActiveBranch()
        // The inherited prefix from root must remain.
        val visible = tree.visibleMessages()
        assertEquals(2, visible.size)
        assertEquals("shared-1", visible[0].content)
        assertEquals("shared-2", visible[1].content)
    }

    @Test
    fun `defaultBranchName increments past collisions`() {
        val tree = ConversationTree()
        tree.forkAt(messageIndex = 0) // "Branch 2"
        tree.forkAt(messageIndex = 0) // "Branch 3"
        val names = tree.allBranches.map { it.name }
        assertTrue("Branch 2" in names)
        assertTrue("Branch 3" in names)
    }

    @Test
    fun `replaceAll restores tree from persistence`() {
        val tree = ConversationTree()
        tree.appendMessage("user", "alpha")
        tree.forkAt(messageIndex = 1, name = "B")
        tree.appendMessage("user", "beta")
        val saved = tree.allBranches.toList() to tree.activeBranchId

        val fresh = ConversationTree()
        fresh.replaceAll(saved.first, saved.second)
        assertEquals(2, fresh.allBranches.size)
        assertEquals("B", fresh.activeBranch.name)
        assertEquals("beta", fresh.visibleMessages().last().content)
    }

    @Test
    fun `active sibling that is not the first child shows its own messages`() {
        // Regression: the old root→active walk picked the *first* child at
        // every level, so being on a later sibling showed the wrong branch.
        val tree = ConversationTree()
        tree.appendMessage("user", "m1")
        tree.appendMessage("assistant", "m2")
        tree.forkAt(messageIndex = 1)              // first child of root
        tree.appendMessage("user", "first-own")
        val rootId = tree.allBranches.first { it.parentId == null }.id
        tree.switchTo(rootId)                       // back on the root
        tree.forkAt(messageIndex = 2)              // second child, now active
        tree.appendMessage("user", "second-own")

        val visible = tree.visibleMessages().map { it.content }
        assertEquals(listOf("m1", "m2", "second-own"), visible)
    }

    @Test
    fun `visibleMessages survives a parent-id cycle in persisted data`() {
        // Corrupt data guard: the up-walk must terminate even if branch
        // parents form a loop.
        val tree = ConversationTree()
        tree.appendMessage("user", "m1")
        val child = tree.forkAt(messageIndex = 1, name = "loop-child")
        tree.appendMessage("user", "child-own")
        // Forge a cycle: root's parent points at the child.
        val root = tree.allBranches.first { it.parentId == null }
        val forged = root.copy(parentId = child.id)
        tree.replaceAll(listOf(forged, child), child.id)

        val visible = tree.visibleMessages().map { it.content }
        // Chain: child (active) → root → (cycle detected, stop).
        // Effective = root.take(child.forkAtMessageIndex) + child's own.
        assertEquals(listOf("m1", "child-own"), visible)
    }
}
