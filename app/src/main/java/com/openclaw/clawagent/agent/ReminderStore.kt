package com.openclaw.clawagent.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久化提醒记录：存储 key/value → JSON 数组
 *
 * 通过注入 read/write 回调，同一类可在 JVM 测试（内存后端）和设备端使用。
 */
class ReminderStore private constructor(
    private val read: () -> String,
    private val write: (String) -> Unit
) {

    /** 一条提醒记录。 */
    data class Entry(
        val requestCode: Int,
        val triggerAtMillis: Long,
        val message: String
    )

    /** 登记一条新提醒（若已存在相同 requestCode 则替换）。 */
    fun register(requestCode: Int, triggerAtMillis: Long, message: String) {
        synchronized(lock) {
            val list = loadList().toMutableList()
            list.removeAll { it.requestCode == requestCode }
            list.add(Entry(requestCode, triggerAtMillis, message))
            saveList(list)
        }
    }

    /** 删除指定 requestCode 的提醒。 */
    fun remove(requestCode: Int) {
        synchronized(lock) {
            val list = loadList().toMutableList()
            list.removeAll { it.requestCode == requestCode }
            saveList(list)
        }
    }

    /** 返回所有记录的提醒（不限时间）。 */
    fun listAll(): List<Entry> = synchronized(lock) { loadList() }

    /** 返回未过期的提醒（triggerAtMillis > nowMillis）。 */
    fun listPending(nowMillis: Long): List<Entry> =
        synchronized(lock) { loadList().filter { it.triggerAtMillis > nowMillis } }

    /** 返回已过期的提醒（triggerAtMillis <= nowMillis）。 */
    fun listExpired(nowMillis: Long): List<Entry> =
        synchronized(lock) { loadList().filter { it.triggerAtMillis <= nowMillis } }

    // ─── 内部实现 ──────────────────────────────────────────────────────────

    private fun loadList(): List<Entry> {
        return try {
            val text = read()
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val rc = obj.optInt("requestCode", -1)
                val ta = obj.optLong("triggerAt", 0L)
                val msg = obj.optString("message", "")
                if (rc == -1 || ta <= 0L || msg.isEmpty()) null
                else Entry(rc, ta, msg)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveList(list: List<Entry>) {
        val array = JSONArray()
        list.forEach { e ->
            val obj = JSONObject()
            obj.put("requestCode", e.requestCode)
            obj.put("triggerAt", e.triggerAtMillis)
            obj.put("message", e.message)
            array.put(obj)
        }
        write(array.toString())
    }

    companion object {
        private val lock = Any()

        /** 设备端工厂：数据存入 SharedPreferences。 */
        fun forContext(context: android.content.Context): ReminderStore {
            val prefs = context.getSharedPreferences(
                PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            )
            return ReminderStore(
                read = { prefs.getString(KEY_JSON, "[]") ?: "[]" },
                write = { prefs.edit().putString(KEY_JSON, it).apply() }
            )
        }

        /** 测试用工厂：数据存内存，不涉及 Android API。 */
        fun inMemory(initial: String = "[]"): ReminderStore {
            var current = initial
            return ReminderStore(read = { current }, write = { current = it })
        }

        private const val PREFS_NAME = "claw_reminder_store"
        private const val KEY_JSON = "reminders_json"
    }
}
