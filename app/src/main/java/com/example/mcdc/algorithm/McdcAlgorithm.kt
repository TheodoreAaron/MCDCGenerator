package com.example.mcdc.algorithm

import com.example.mcdc.algorithm.ExprParser.extractVariables
import com.example.mcdc.algorithm.ExprParser.parse
import com.example.mcdc.model.McdcResult
import com.example.mcdc.model.TruthRow
import kotlin.math.abs

/**
 * MC/DC 核心算法：极小用例 + 严格 0/1 交替（相邻约束已放开）。
 *
 * 旧版「局部相邻链式（Locality-First）」强制每个变量的 MC/DC 对必须落在相邻两行，
 * 对 OR / 掩码类结构会反复克隆支点行导致用例膨胀（如 a & (b | c | d | e | f) 出 11 行）。
 *
 * 新版策略（已用 Python 原型验证：本例得 9 行，少于手工 10 行、旧版 11 行）：
 *   ① 极小覆盖（贪心）：优先安置"候选对最少"的变量，挑选"引入新行最少"的候选对，
 *      尽量复用已选行（支点 P 被所有 OR 变量共用，只计一次），把总用例压到最少。
 *   ② 平衡 out 计数：若 |#1 - #0| > 1，复制少数派判定值的已有行（新 id）补足，
 *      使序列可以严格交替（相邻判定必相反）。
 *   ③ 排序：把 #1 / #0 两组交错排列，较多的一组开头（数量相等时按 startFromTrue 定首行判定）。
 *   ④ 高亮：每个变量取其覆盖对（允许非相邻）在最终序列中的下标。
 *
 * 强校验：ordered 判定列严格交替；每个高亮对确为合法的 Hamming-1 / 判定反转对。
 */
object McdcAlgorithm {

    /** 变量数量上限，防止 2^n 爆炸导致 OOM（PRD §7）。 */
    const val MAX_VARIABLES = 10

    fun generate(expression: String, startFromTrue: Boolean): McdcResult {
        val ast = parse(expression)
        val variables = extractVariables(expression)
        if (variables.isEmpty()) throw McdcParseException("未检测到任何变量，请检查表达式")
        if (variables.size > MAX_VARIABLES) {
            throw McdcParseException("变量数量(${variables.size})超过上限 $MAX_VARIABLES，请减少变量")
        }

        // Phase 1: 基础真值表
        val baseRows = generateBaseTable(variables, ast, startFromTrue)
        // Phase 2: 候选对提取（枚举全部数学合法的 MC/DC 候选对）
        val candidates = extractCandidatePairs(variables, baseRows)
        // Phase 3 & 4: 极小覆盖 + 严格交替拼接
        val (orderedCases, highlight, uncoverable) = dominoChain(candidates, startFromTrue)

        return McdcResult(
            variables = variables,
            orderedCases = orderedCases,
            highlight = highlight,
            uncoverable = uncoverable
        )
    }

    // ------------------------------------------------------------------
    // Phase 1: 基础真值表生成
    // ------------------------------------------------------------------
    private fun generateBaseTable(
        variables: List<String>,
        ast: ExprNode,
        startFromTrue: Boolean
    ): List<TruthRow> {
        val n = variables.size
        val total = 1 shl n
        val rows = ArrayList<TruthRow>(total)
        for (idx in 0 until total) {
            val values = LinkedHashMap<String, Int>(n)
            for ((bit, v) in variables.withIndex()) {
                // 变量按字母序映射到高位→低位；第 bit 位取变量 v
                val rawBit = (idx shr (n - 1 - bit)) and 1
                // 起始基准：从 1 开始则翻转所有位，使首行为全 1
                val value = if (startFromTrue) 1 - rawBit else rawBit
                values[v] = value
            }
            // 严格 AST 全节点求值（无短路）
            val decision = ast.eval(values)
            rows.add(TruthRow(id = idx, values = values, decision = decision))
        }
        return rows
    }

    // ------------------------------------------------------------------
    // Phase 2: 候选对提取
    // 对每个变量 V_i，找出所有满足三条件的 (RowX, RowY)：
    //   1) X[V_i] != Y[V_i]              目标反转
    //   2) 其余变量取值完全相同（Hamming=1）  唯一变量控制
    //   3) Decision(X) != Decision(Y)    判定反转
    // 若某变量无候选对 -> 标记为 UNCOVERABLE
    // ------------------------------------------------------------------
    private fun extractCandidatePairs(
        variables: List<String>,
        rows: List<TruthRow>
    ): Map<String, List<Pair<TruthRow, TruthRow>>> {
        val result = LinkedHashMap<String, MutableList<Pair<TruthRow, TruthRow>>>(variables.size)
        for (v in variables) {
            val pairs = mutableListOf<Pair<TruthRow, TruthRow>>()
            for (i in rows.indices) {
                val a = rows[i]
                for (j in i + 1 until rows.size) {
                    val b = rows[j]
                    if (a.values[v] == b.values[v]) continue          // 条件 1 不满足
                    if (a.decision == b.decision) continue            // 条件 3 不满足
                    // 条件 2：除 v 外所有变量取值相同
                    val othersSame = variables.none { other ->
                        other != v && a.values[other] != b.values[other]
                    }
                    if (othersSame) pairs.add(a to b)
                }
            }
            result[v] = pairs
        }
        return result
    }

    // ------------------------------------------------------------------
    // Phase 3 & 4: 极小覆盖 + 严格 0/1 交替（相邻约束放开）
    //
    // 目标（高于一切）：在「每个变量都满足 MC/DC」且「out 严格 0/1 交替」的前提下，
    // 使总用例数最少。
    //
    // 算法：
    //   ① 极小覆盖（贪心）
    //      - 选取「候选对最少」的未覆盖变量（most-constrained-first，最难安置的先放）。
    //      - 在该变量的候选对里挑「引入新行最少」的一对，尽量复用已选行
    //        （如支点 P 被所有 OR 变量共用，只算一次），把总用例压到最少。
    //   ② 平衡 out 计数
    //      - 若 |#1 - #0| > 1，复制少数派判定值的已有行（分配新 id）补足，
    //        使序列可严格交替（相邻判定必相反）。这是唯一会增加用例的步骤，且为最少补数。
    //   ③ 排序为严格交替序列：#1 与 #0 两组交错，较多组开头（相等时按 startFromTrue）。
    //   ④ 高亮：每个变量取其覆盖对（允许非相邻）在 ordered 中的下标。
    //
    // 强校验：①ordered 的 decision 严格交替；②每个高亮对确为 Hamming-1 / 判定反转对。
    // ------------------------------------------------------------------
    private fun dominoChain(
        candidates: Map<String, List<Pair<TruthRow, TruthRow>>>,
        startFromTrue: Boolean
    ): Triple<List<TruthRow>, Map<String, Pair<Int, Int>>, Set<String>> {
        val chosen = mutableListOf<TruthRow>()                 // 全部测试用例（含平衡用复制行）
        val byId = LinkedHashMap<Int, TruthRow>()              // id -> 已选行（去重复用）
        val coverPair = LinkedHashMap<String, Pair<TruthRow, TruthRow>>()
        val uncoverable = LinkedHashSet<String>()
        val uncovered = candidates.keys.toMutableSet()
        var cloneCounter = 1_000_000                           // 复制行使用独立 id 空间

        fun addRow(r: TruthRow): TruthRow {
            byId[r.id]?.let { return it }
            byId[r.id] = r
            chosen.add(r)
            return r
        }

        // ── ① 极小覆盖（贪心：most-constrained-first，选引入新行最少的对）──
        while (uncovered.isNotEmpty()) {
            // 选候选对最少的未覆盖变量（越难安置越先放）
            var bestV: String? = null
            var bestSize = Int.MAX_VALUE
            for (v in candidates.keys) {
                if (v !in uncovered) continue
                val sz = candidates[v]?.size ?: 0
                if (sz == 0) continue
                if (sz < bestSize) { bestSize = sz; bestV = v }
            }

            var bestPair: Pair<TruthRow, TruthRow>? = null
            var bestNew = Int.MAX_VALUE
            if (bestV != null) {
                for (p in candidates[bestV]!!) {
                    val newCount = (if (p.first.id in byId) 0 else 1) +
                            (if (p.second.id in byId) 0 else 1)
                    if (newCount < bestNew) { bestNew = newCount; bestPair = p }
                }
            }

            if (bestV == null || bestPair == null) {
                // 剩余变量均无候选对 → 标记不可覆盖后退出
                for (v in uncovered.toList()) {
                    if ((candidates[v]?.size ?: 0) == 0) {
                        uncoverable.add(v); uncovered.remove(v)
                    }
                }
                break
            }
            val (a, b) = bestPair
            addRow(a); addRow(b)
            coverPair[bestV] = a to b
            uncovered.remove(bestV)
        }

        // ── ② 平衡 out 计数，保证可严格交替（|#1 - #0| <= 1）──
        var balanceGuard = 0
        while (abs(chosen.count { it.decision == 1 } - chosen.count { it.decision == 0 }) > 1
            && balanceGuard < 1000
        ) {
            val minority = if (chosen.count { it.decision == 1 } > chosen.count { it.decision == 0 })
                chosen.filter { it.decision == 0 }
            else
                chosen.filter { it.decision == 1 }
            val src = minority.first()
            addRow(src.copy(id = cloneCounter++))   // 复制一行作为额外测试用例（新 id）
            balanceGuard++
        }

        // ── ③ 排序为严格交替序列 ──
        val ones = chosen.filter { it.decision == 1 }.toMutableList()
        val zeros = chosen.filter { it.decision == 0 }.toMutableList()
        val firstIsOne = when {
            ones.size > zeros.size -> true
            zeros.size > ones.size -> false
            else -> startFromTrue
        }
        val (firstGroup, secondGroup) = if (firstIsOne) ones to zeros else zeros to ones
        val ordered = mutableListOf<TruthRow>()
        for (i in secondGroup.indices) {
            ordered.add(firstGroup[i]); ordered.add(secondGroup[i])
        }
        if (firstGroup.size > secondGroup.size) ordered.add(firstGroup.last())

        // ── ④ 高亮：每个变量覆盖对在 ordered 中的下标（允许非相邻）──
        val highlight = LinkedHashMap<String, Pair<Int, Int>>()
        for ((v, p) in coverPair) {
            val i = ordered.indexOfFirst { it.id == p.first.id }
            val j = ordered.indexOfFirst { it.id == p.second.id }
            if (i >= 0 && j >= 0) highlight[v] = i to j
        }

        // ── 强校验 ──
        for (i in 0 until ordered.size - 1) {
            require(ordered[i].decision != ordered[i + 1].decision) {
                "算法内部错误：判定列未严格交替 (位置 $i,${i + 1} 均为 ${ordered[i].decision})"
            }
        }
        for ((v, p) in highlight) {
            val a = ordered[p.first]; val b = ordered[p.second]
            require(a.values[v] != b.values[v]) { "算法内部错误：变量 $v 高亮对取值相同" }
            require(a.decision != b.decision) { "算法内部错误：变量 $v 高亮对判定相同" }
            val othersSame = candidates.keys.none { o -> o != v && a.values[o] != b.values[o] }
            require(othersSame) { "算法内部错误：变量 $v 高亮对非 Hamming-1" }
        }
        return Triple(ordered, highlight, uncoverable)
    }
}
