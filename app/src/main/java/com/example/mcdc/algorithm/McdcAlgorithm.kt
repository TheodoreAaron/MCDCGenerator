package com.example.mcdc.algorithm

import com.example.mcdc.algorithm.ExprParser.extractVariables
import com.example.mcdc.algorithm.ExprParser.parse
import com.example.mcdc.model.McdcResult
import com.example.mcdc.model.TruthRow
import kotlin.math.abs

/**
 * MC/DC 核心算法：局部连续性优先（Locality-First）+ 判定严格交替策略。
 *
 * 设计基调（PRD §4）：视觉局部连续性（每个变量的测试对在矩阵中**严格相邻**）是
 * **最高优先级**，高于"绝对最小化用例总数"。在此基础上追加**判定（out）列严格
 * 0/1 交替**的约束——相邻两用例的 decision 必须相反，形成 0101…/1010… 序列。
 *
 * Phase 1/2 生成全部数学合法的 MC/DC 候选对；Phase 3&4 采用**多米诺链式
 * （Domino Chaining）**拼接：沿 `orderedCases` 这条不断增长的链做前向拼接，
 * 凡是存在候选对包含链尾(tail)的未覆盖变量，就「只追加缺失行」，使相邻两块共用
 * 同一行 —— 这是最大化用例重叠、把总列数压到最少的关键（旧贪心策略每变量固定
 * +2 列，链式通常只 +1 列）。链断裂则复制一对相邻行重启独立新链，并始终以
 * `require(abs(colA - colB) == 1)` 强校验高亮列严格相邻，且以
 * `require(decision[i] != decision[i+1])` 强校验判定列严格交替。
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
        // Phase 3 & 4: 多米诺链式拼接（Locality-First + 严格交替，最大化用例重叠）
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
    // Phase 3 & 4: 多米诺链式拼接（Domino Chaining，Locality-First + 严格交替）
    //
    // 核心约束（高于压缩率）：
    //   ★ out（判定）列必须严格 0/1 交替 —— 即 orderedCases 的 decision 序列
    //     必须是 0,1,0,1,… 或 1,0,1,0,… 相邻两位绝不相同。
    //
    //   ★ 每个可覆盖变量 V 必须分配到一对严格相邻的用例 (i, i+1)，
    //     且该对是 V 的合法 MC/DC 独立影响对（V 取 0/1、其余变量相同、判定反转）。
    //     由于 MC/DC 对定义上两行 decision 必不同，天然贡献一个 0→1 或 1→0 的交替。
    //
    //   ★ 在满足以上两点的前提下，链式重叠最大化、复制行最小化，使总用例数最少。
    //
    // 算法（贪心 + 链式，与 Python 原型一致）：
    //   维护 nextDec = 下一行应有的判定值（交替约束）。
    //   - 命中：若未覆盖变量 V 存在候选对 (tail, other) 且 other.decision == nextDec，
    //     则只追加 other（复用 tail），相邻对即归 V。nextDec 翻转。
    //   - 断裂：tail 无法接续时，选未覆盖变量 V 的一对 (lo dec=nextDec, hi dec=1-nextDec)，
    //     复制两行放入（仍相邻、仍交替、仍是 V 的合法对）。nextDec 不变（加了两行后回到原值）。
    //   起始：取候选对最少的变量（most-constrained-first），选其一对 (dec=startDec, 1-startDec)。
    //
    // 强校验：①相邻 highlight 下标差为 1；②ordered 的 decision 严格交替。
    // ------------------------------------------------------------------
    private fun dominoChain(
        candidates: Map<String, List<Pair<TruthRow, TruthRow>>>,
        startFromTrue: Boolean
    ): Triple<List<TruthRow>, Map<String, Pair<Int, Int>>, Set<String>> {
        val ordered = mutableListOf<TruthRow>()
        val highlight = LinkedHashMap<String, Pair<Int, Int>>()
        val uncoverable = LinkedHashSet<String>()
        val uncoveredVars = candidates.keys.toMutableSet()
        var cloneCounter = 1_000_000 // 复制行使用独立的 id 空间

        // 起始判定值：startFromTrue=true 时首行 dec=1，否则 dec=0
        val startDec = if (startFromTrue) 1 else 0

        // 选取「候选对最少」的变量（most-constrained-first）。空列表变量视为不可覆盖。
        fun pickMostConstrained(): String? {
            var best: String? = null
            var bestSize = Int.MAX_VALUE
            for (v in uncoveredVars) {
                val size = candidates[v]?.size ?: 0
                if (size < bestSize) {
                    bestSize = size
                    best = v
                }
            }
            return best
        }

        // 在变量 v 的候选对里找一对 (lo, hi)，满足 lo.decision == needLo 且 hi.decision == needHi。
        // 候选对内部 a/b 顺序未定，故两种方向都尝试。
        fun findPairByDec(
            v: String,
            needLo: Int,
            needHi: Int
        ): Pair<TruthRow, TruthRow>? {
            for (p in candidates[v] ?: emptyList()) {
                val (a, b) = p
                if (a.decision == needLo && b.decision == needHi) return a to b
                if (b.decision == needLo && a.decision == needHi) return b to a
            }
            return null
        }

        // 复制一行（保留取值与判定，分配新 id 以区分对象）
        fun cloneRow(r: TruthRow): TruthRow = r.copy(id = cloneCounter++)

        while (uncoveredVars.isNotEmpty()) {
            if (ordered.isEmpty()) {
                // ---- Step A: 初始起点 ----
                val v = pickMostConstrained() ?: break
                val pairs = candidates[v] ?: emptyList()
                if (pairs.isEmpty()) {
                    uncoverable.add(v); uncoveredVars.remove(v); continue
                }
                // 起始对须满足 (dec=startDec, dec=1-startDec) 以保证交替从 startDec 开始
                val chosen = findPairByDec(v, startDec, 1 - startDec) ?: pairs.first()
                ordered.add(chosen.first); ordered.add(chosen.second)
                highlight[v] = (ordered.size - 2) to (ordered.size - 1)
                uncoveredVars.remove(v)
                continue
            }

            // 交替约束：下一行判定必须与当前链尾相反
            val tail = ordered.last()
            val nextDec = 1 - tail.decision

            // ---- Step B: 前向链尾匹配（命中）----
            // 找未覆盖变量 V，其候选对包含 tail，且另一行 decision == nextDec
            var hitV: String? = null
            var hitOther: TruthRow? = null
            var hitPairCount = Int.MAX_VALUE
            for (v in uncoveredVars) {
                val pairCount = candidates[v]?.size ?: 0
                if (pairCount == 0) continue
                for (p in candidates[v]!!) {
                    val other = when {
                        p.first.id == tail.id -> p.second
                        p.second.id == tail.id -> p.first
                        else -> null
                    }
                    if (other != null && other.decision == nextDec) {
                        // 择优：候选对最少者优先（更难安置的先安置）
                        if (pairCount < hitPairCount) {
                            hitPairCount = pairCount
                            hitV = v
                            hitOther = other
                        }
                        break
                    }
                }
            }

            if (hitV != null && hitOther != null) {
                // 仅追加缺失行，复用链尾保证相邻；天然满足交替
                ordered.add(hitOther)
                highlight[hitV] = (ordered.size - 2) to (ordered.size - 1)
                uncoveredVars.remove(hitV)
                continue
            }

            // ---- Step C: 链断裂（兜底）---- 复制一对相邻行放入，维持交替
            // 交替要求下一行 dec=nextDec，故复制 (lo dec=nextDec, hi dec=1-nextDec) 一对
            var placedV: String? = null
            var placedPair: Pair<TruthRow, TruthRow>? = null
            var placedPairCount = Int.MAX_VALUE
            for (v in uncoveredVars) {
                val pairCount = candidates[v]?.size ?: 0
                if (pairCount == 0) continue
                val pair = findPairByDec(v, nextDec, 1 - nextDec)
                if (pair != null && pairCount < placedPairCount) {
                    placedPairCount = pairCount
                    placedV = v
                    placedPair = pair
                }
            }
            if (placedV != null && placedPair != null) {
                val (lo, hi) = placedPair
                val clo = cloneRow(lo)
                val chi = cloneRow(hi)
                ordered.add(clo); ordered.add(chi)
                highlight[placedV] = (ordered.size - 2) to (ordered.size - 1)
                uncoveredVars.remove(placedV)
                continue
            }

            // 理论上不可达：每个可覆盖变量都有 dec=0 与 dec=1 的候选对，
            // findPairByDec 必能命中其一。若真的没命中，标记剩余不可覆盖并退出。
            for (v in uncoveredVars.toList()) {
                if ((candidates[v]?.size ?: 0) == 0) {
                    uncoverable.add(v); uncoveredVars.remove(v)
                }
            }
            break
        }

        // 强校验 ①：所有 highlight 对下标差必须严格为 1
        for ((v, p) in highlight) {
            require(abs(p.first - p.second) == 1) {
                "算法内部错误：变量 $v 的高亮列不相邻 (${p.first}, ${p.second})"
            }
        }
        // 强校验 ②：ordered 的 decision 必须严格交替
        for (i in 0 until ordered.size - 1) {
            require(ordered[i].decision != ordered[i + 1].decision) {
                "算法内部错误：判定列未严格交替 (位置 $i,${i + 1} 均为 ${ordered[i].decision})"
            }
        }
        return Triple(ordered, highlight, uncoverable)
    }
}
