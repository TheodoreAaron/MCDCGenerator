package com.example.mcdc.data

import android.content.Context
import com.example.mcdc.model.HistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 历史记录本地持久化（文件式 JSON，不依赖 Room / 序列化库）。
 *
 * 设计取舍（见需求文档）：
 * - 记录结构简单（表达式字符串 + 布尔 + 长整型时间戳），手动读写 JSON 即可，
 *   刻意不引入 kotlinx-serialization / Room，避免给构建链增加依赖与配置风险。
 * - 主存储仍是应用私有目录 `context.filesDir/mcdc_history.json`（读写即时、无需权限）。
 * - **额外**把历史同步到共享存储（[HistoryBackup]，用户选定的文件夹，如 Documents/MCDC），
 *   该位置不随应用卸载 / 覆盖安装（签名不一致导致强制重装）而清除，从而彻底解决
 *   “装上新版后旧记录丢失”的问题。加载时若主存储为空，会自动从共享备份恢复。
 * - 按 (expression, startFromTrue) 去重：同一表达式以相同基准重复生成只保留最新一条（置顶）；
 *   最多保留 [MAX_ENTRIES] 条，防止无限增长。
 * - 所有文件 IO 在 [Dispatchers.IO] 上执行，避免阻塞主线程；备份失败静默忽略。
 */
class HistoryStore(context: Context, private val backup: HistoryBackup) {

    private val file = File(context.filesDir, "mcdc_history.json")

    /** 最多保留的条目数。 */
    private val MAX_ENTRIES = 200

    /**
     * 读取全部历史（按文件中顺序返回，调用方约定为「新→旧」）。
     * 优先读主存储；若主存储为空/损坏，则尝试从共享备份恢复并同步回主存储，
     * 这样覆盖安装/重装后历史仍能找回。两者皆空时返回空列表。
     */
    suspend fun load(): List<HistoryRecord> = withContext(Dispatchers.IO) {
        val local = if (file.exists()) runCatching { parse(file.readText()) }.getOrDefault(emptyList())
                    else emptyList()
        if (local.isNotEmpty()) return@withContext local

        val restored = backup.read()
            ?.let { runCatching { parse(it) }.getOrDefault(emptyList()) }
            .orEmpty()
        if (restored.isNotEmpty()) {
            runCatching { file.writeText(serialize(restored)) } // 同步回主存储
            restored
        } else {
            emptyList()
        }
    }

    /**
     * 记录一条生成结果。已存在相同 (expression, startFromTrue) 则更新其时间戳并置顶；
     * 否则新增到最前。返回更新后的完整列表。
     */
    suspend fun record(expression: String, startFromTrue: Boolean): List<HistoryRecord> {
        val now = System.currentTimeMillis()
        val current = load().toMutableList()
        val key = dedupeKey(expression, startFromTrue)
        current.removeAll { dedupeKey(it.expression, it.startFromTrue) == key }
        current.add(0, HistoryRecord(expression, startFromTrue, now))
        val trimmed = current.take(MAX_ENTRIES)
        save(trimmed)
        return trimmed
    }

    /** 删除指定时间戳对应的记录，返回剩余列表。 */
    suspend fun delete(createdAt: Long): List<HistoryRecord> {
        val remaining = load().filter { it.createdAt != createdAt }
        save(remaining)
        return remaining
    }

    /** 清空全部历史，返回空列表。 */
    suspend fun clear(): List<HistoryRecord> {
        save(emptyList())
        return emptyList()
    }

    /** 写入主存储，并（在已配置备份文件夹时）同步到共享存储。 */
    private suspend fun save(records: List<HistoryRecord>) = withContext(Dispatchers.IO) {
        val text = serialize(records)
        runCatching { file.writeText(text) }
        backup.write(text)
    }

    /** 立即把当前主存储内容同步到共享备份（用户首次选定备份文件夹后调用）。 */
    suspend fun backupNow() {
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        backup.write(text)
    }

    private fun dedupeKey(e: String, s: Boolean) = "${if (s) 1 else 0}:$e"

    // ───────────────────────── 手动 JSON（受控格式，避免引入序列化库） ─────────────────────────

    /**
     * 序列化为 JSON 数组：[{"e":"<expr>","s":0|1,"t":<ms>}, ...]
     * 写入时对表达式做最小转义（反斜杠、双引号），读取时还原。
     */
    private fun serialize(list: List<HistoryRecord>): String {
        val sb = StringBuilder()
        sb.append('[')
        list.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"e\":\"").append(escape(r.expression))
                .append("\",\"s\":").append(if (r.startFromTrue) "1" else "0")
                .append(",\"t\":").append(r.createdAt)
                .append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** 逐个对象 `{}` 切分并解析，容忍尾部空白/格式微差。 */
    private fun parse(text: String): List<HistoryRecord> {
        val result = mutableListOf<HistoryRecord>()
        var i = text.indexOf('{')
        while (i >= 0) {
            val end = text.indexOf('}', i)
            if (end < 0) break
            result.add(parseObject(text.substring(i, end + 1)))
            i = text.indexOf('{', end + 1)
        }
        return result
    }

    private fun parseObject(obj: String): HistoryRecord {
        val e = findString(obj, "\"e\"")
        val s = findScalar(obj, "\"s\"")?.toIntOrNull() ?: 0
        val t = findScalar(obj, "\"t\"")?.toLongOrNull() ?: 0L
        return HistoryRecord(unescape(e), s == 1, t)
    }

    /** 提取 `"key":"value"` 中的字符串值（含转义还原）。 */
    private fun findString(obj: String, key: String): String {
        val idx = obj.indexOf(key)
        if (idx < 0) return ""
        val colon = obj.indexOf(':', idx)
        if (colon < 0) return ""
        val q1 = obj.indexOf('"', colon + 1)
        if (q1 < 0) return ""
        val q2 = obj.indexOf('"', q1 + 1)
        if (q2 < 0) return ""
        return unescape(obj.substring(q1 + 1, q2))
    }

    /** 提取 `"key":<number>` 中的标量值（不含引号）。 */
    private fun findScalar(obj: String, key: String): String? {
        val idx = obj.indexOf(key)
        if (idx < 0) return null
        val colon = obj.indexOf(':', idx)
        if (colon < 0) return null
        val rest = obj.substring(colon + 1)
        val comma = rest.indexOf(',')
        val brace = rest.indexOf('}')
        val end = when {
            comma < 0 -> brace
            brace < 0 -> comma
            else -> minOf(comma, brace)
        }
        if (end < 0) return rest.trim()
        return rest.substring(0, end).trim()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\")
}
