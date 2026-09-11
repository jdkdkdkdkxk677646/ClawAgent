package com.openclaw.clawagent.conversation

import java.util.UUID

/**
 * A single branch in a conversation tree.
 *
 * Branches let the user fork the conversation at any past message and try a
 * different direction, without losing the original thread. Each branch holds
 * its own ordered list of messages, plus enough metadata to reconstruct
 * where it came from.
 */
data class ConversationBranch(
    /** Stable id used in persistence and as a Map key. */
    val id: String = UUID.randomUUID().toString(),
    /** Human-readable name shown in the chip and branch list. */
    val name: String,
    /** Branch we forked from. null = this is the root branch. */
    val parentId: String? = null,
    /**
     * Index in [parentId]'s message list at which we forked. The new branch
     * inherits messages 0..forkAtIndex-1 from its parent, then adds its own.
     * 0 means "fresh branch with no inherited history".
     */
    val forkAtMessageIndex: Int = 0,
    /** Own messages appended after the fork point. */
    val messages: MutableList<BranchMessage> = mutableListOf(),
    /** Epoch millis when the branch was created. */
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A single message inside a branch. Mirrors ChatMessage but kept as its own
 * type so the branch model has no Android dependencies and can be unit-tested
 * on the JVM with no Robolectric.
 */
data class BranchMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)
