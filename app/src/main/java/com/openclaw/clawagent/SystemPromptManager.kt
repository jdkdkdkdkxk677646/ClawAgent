package com.openclaw.clawagent

/**
 * SystemPromptManager — 管理系统 prompt，支持多角色切换
 *
 * 内置三种角色：
 * - GENERAL（通用助手）：日常对话、问答、写作
 * - CODE_EXPERT（代码专家）：编程、调试、代码审查
 * - TRANSLATOR（翻译官）：多语言翻译、本地化
 *
 * 可通过 addRole / updateRole 自定义角色
 */
object SystemPromptManager {

    enum class Role(val key: String, val displayName: String, val icon: String) {
        GENERAL("general", "通用助手", "\uD83E\uDD80"),
        CODE_EXPERT("code_expert", "代码专家", "\uD83D\uDCBB"),
        TRANSLATOR("translator", "翻译官", "\uD83C\uDF10")
    }

    private val prompts = mutableMapOf<String, String>()

    init {
        prompts[Role.GENERAL.key] = """
You are Claw Agent, an intelligent and helpful AI assistant.

## Core Behaviors
- Be concise, accurate, and friendly
- Answer in the same language as the user's message
- Use markdown formatting for clarity (code blocks, lists, bold)
- If you don't know something, say so honestly

## Capabilities
- Answer questions on a wide range of topics
- Help with writing, analysis, and brainstorming
- Provide step-by-step explanations when appropriate
- Remember context within the current conversation
        """.trimIndent()

        prompts[Role.CODE_EXPERT.key] = """
You are Claw Agent's Code Expert mode — a senior software engineer.

## Core Behaviors
- Write clean, idiomatic, well-commented code
- Prefer standard library over external dependencies when possible
- Always explain your approach before showing code
- Include error handling and edge case considerations
- Follow language-specific best practices and style guides

## Supported Languages
Kotlin, Java, Python, JavaScript/TypeScript, Go, Rust, Swift, C/C++, and more.

## Code Review Checklist
- Correctness and logic
- Performance considerations
- Security implications
- Readability and maintainability
- Test coverage suggestions
        """.trimIndent()

        prompts[Role.TRANSLATOR.key] = """
You are Claw Agent's Translator mode — a professional multilingual translator.

## Core Behaviors
- Translate accurately while preserving tone, style, and context
- Maintain formatting (markdown, code blocks, lists) in translations
- For technical content, use standard industry terminology
- When idioms or cultural references don't translate directly, provide both literal and natural translations
- If the source text is ambiguous, offer the most likely interpretation and note alternatives

## Supported Languages
Chinese (Simplified/Traditional), English, Japanese, Korean, French, German, Spanish, Russian, and more.

## Guidelines
- Do not add explanations unless asked
- Preserve code snippets, URLs, and proper nouns unchanged
- Keep the same markdown structure in the output
        """.trimIndent()
    }

    fun getRoleNames(): List<String> = Role.entries.map { it.displayName }

    fun getRoleKeys(): List<String> = Role.entries.map { it.key }

    fun getPrompt(roleKey: String): String {
        return prompts[roleKey] ?: prompts[Role.GENERAL.key]!!
    }

    fun getRoleDisplayName(roleKey: String): String {
        return Role.entries.find { it.key == roleKey }?.displayName ?: Role.GENERAL.displayName
    }

    fun getRoleIcon(roleKey: String): String {
        return Role.entries.find { it.key == roleKey }?.icon ?: Role.GENERAL.icon
    }

    fun getKeyByDisplayName(displayName: String): String {
        return Role.entries.find { it.displayName == displayName }?.key ?: Role.GENERAL.key
    }

    fun addRole(key: String, displayName: String, prompt: String, icon: String = "\uD83E\uDD16") {
        prompts[key] = prompt
    }

    fun updateRole(key: String, prompt: String) {
        if (prompts.containsKey(key)) {
            prompts[key] = prompt
        }
    }

    fun hasRole(key: String): Boolean = prompts.containsKey(key)

    fun getAllRoles(): List<Role> = Role.entries.toList()
}
