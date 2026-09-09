package com.openclaw.clawagent.conversation

/**
 * The whole conversation tree. Owned by [MainActivity] (or eventually a
 * ViewModel). Always has a root branch and exactly one active branch.
 *
 * The "effective" message list shown to the user is computed by walking from
 * the active branch's fork point back to the root, then appending the
 * active branch's own messages. This way switching branches instantly shows
 * the inherited context without duplicating stored data.
 *
 * Semantics of [ConversationBranch.forkAtMessageIndex]: it is the number of
 * leading messages taken from the parent's effective message list when this
 * branch is created. So a forkAtMessageIndex of 1 means "inherit the first
 * message from the parent, then add my own".
 */
class ConversationTree {
    private val branches = LinkedHashMap<String, ConversationBranch>()

    /** The branch currently displayed to the user. Always non-null. */
    var activeBranchId: String = ""
        private set

    init {
        val main = ConversationBranch(name = DEFAULT_BRANCH_NAME)
        branches[main.id] = main
        activeBranchId = main.id
    }

    val activeBranch: ConversationBranch
        get() = branches.getValue(activeBranchId)

    val allBranches: List<ConversationBranch>
        get() = branches.values.toList()

    /**
     * Effective message list for the active branch: the inherited prefix
     * (parent chain up to the fork point) followed by this branch's own
     * messages. Read-only view.
     */
    fun visibleMessages(): List<BranchMessage> {
        val chain = collectAncestorChain(activeBranchId)
        if (chain.isEmpty()) return activeBranch.messages.toList()
        // First entry is the root branch; later entries are successively
        // closer ancestors. The last entry is the active branch itself,
        // contributing zero of its own messages to the inherited prefix
        // (we only inherit `forkAtMessageIndex` of the *parent* of the
        // active branch; the active branch's own messages are appended at
        // the end).
        val out = ArrayList<BranchMessage>()
        for ((branch, count) in chain) {
            val n = count.coerceAtMost(branch.messages.size)
            for (i in 0 until n) out.add(branch.messages[i])
        }
        out.addAll(activeBranch.messages)
        return out
    }

    /** Append a message to the active branch. */
    fun appendMessage(role: String, content: String): BranchMessage {
        val msg = BranchMessage(role, content)
        activeBranch.messages.add(msg)
        return msg
    }

    /** Update the content of the last message in the active branch. */
    fun updateLastMessage(content: String) {
        val msgs = activeBranch.messages
        if (msgs.isEmpty()) return
        msgs[msgs.size - 1] = msgs.last().copy(content = content)
    }

    /**
     * Fork the conversation at [messageIndex] (relative to the active
     * branch's effective message list). Creates a new branch whose name
     * defaults to "Branch N" and switches to it. Returns the new branch.
     */
    fun forkAt(messageIndex: Int, name: String? = null): ConversationBranch {
        val effective = visibleMessages()
        val cut = messageIndex.coerceIn(0, effective.size)
        val newName = name ?: defaultBranchName()
        val newBranch = ConversationBranch(
            name = newName,
            parentId = activeBranchId,
            forkAtMessageIndex = cut,
        )
        branches[newBranch.id] = newBranch
        activeBranchId = newBranch.id
        return newBranch
    }

    /** Switch the active branch. */
    fun switchTo(branchId: String) {
        require(branches.containsKey(branchId)) { "Unknown branch: $branchId" }
        activeBranchId = branchId
    }

    /**
     * Delete a branch. The root branch cannot be deleted. Children of the
     * deleted branch are reparented to its parent; if the active branch
     * is the one being deleted, we fall back to the root.
     */
    fun deleteBranch(branchId: String) {
        val target = branches[branchId] ?: return
        if (target.parentId == null) return
        val grandparent = target.parentId
        // Each child of the deleted branch inherits the deleted branch's
        // own forkAtMessageIndex plus its own, because what used to be
        // "N messages of the deleted branch" is now "those same messages
        // visible from the deleted branch's view of the grandparent".
        branches.values
            .filter { it.parentId == branchId }
            .forEach {
                branches[it.id] = it.copy(
                    parentId = grandparent,
                    forkAtMessageIndex = target.forkAtMessageIndex + it.forkAtMessageIndex,
                )
            }
        branches.remove(branchId)
        if (activeBranchId == branchId) {
            activeBranchId = branches.values.first { it.parentId == null }.id
        }
    }

    /** Rename a branch. Empty or whitespace-only names are ignored. */
    fun renameBranch(branchId: String, newName: String) {
        val target = branches[branchId] ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        branches[branchId] = target.copy(name = trimmed)
    }

    /** Clear the active branch's own messages (inherited prefix kept). */
    fun clearActiveBranch() {
        activeBranch.messages.clear()
    }

    /**
     * Walks from [startId] up to the root, returning the chain as a list
     * of (branch, howManyOfBranchCountTowardInheritedPrefix) pairs. The
     * last entry is always the active branch with a contribution of 0
     * (its own messages are appended at the end, not inherited from).
     */
    private fun collectAncestorChain(startId: String): List<Pair<ConversationBranch, Int>> {
        val chain = ArrayList<Pair<ConversationBranch, Int>>()
        var current: ConversationBranch? = branches[startId] ?: return chain
        // The active branch itself contributes 0 to the inherited prefix.
        chain.add(current!! to 0)
        while (current?.parentId != null) {
            val parent = branches[current!!.parentId] ?: break
            // The parent contributes `parent.forkAtMessageIndex` to the
            // active branch's inherited prefix (i.e. the parent's own
            // fork point, because that ancestor is itself a child of an
            // older branch).
            chain.add(0, parent to parent.forkAtMessageIndex)
            current = parent
        }
        // The oldest ancestor (root) contributes its full message count
        // because there's nothing older to fork from.
        if (chain.isNotEmpty()) {
            val (rootBranch, _) = chain.first()
            chain[0] = rootBranch to rootBranch.messages.size
        }
        return chain
    }

    private fun defaultBranchName(): String {
        var n = branches.size + 1
        while (branches.values.any { it.name == "Branch $n" }) n++
        return "Branch $n"
    }

    /**
     * Replace the entire tree from persistence. Used when loading from disk.
     */
    fun replaceAll(
        newBranches: List<ConversationBranch>,
        newActiveId: String,
    ) {
        branches.clear()
        newBranches.forEach { branches[it.id] = it }
        activeBranchId = if (branches.containsKey(newActiveId)) {
            newActiveId
        } else {
            branches.values.first { it.parentId == null }.id
        }
    }

    companion object {
        const val DEFAULT_BRANCH_NAME = "main"
    }
}
