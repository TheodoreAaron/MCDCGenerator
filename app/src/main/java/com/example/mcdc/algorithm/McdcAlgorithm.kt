package com.example.mcdc.algorithm

import com.example.mcdc.algorithm.ExprParser.extractVariables
import com.example.mcdc.algorithm.ExprParser.parse
import com.example.mcdc.model.McdcResult
import com.example.mcdc.model.TruthRow
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * MC/DC 核心算法。
 *
 * 主算法 [solveMinimal] 用 **BFS 最短路径** 在「(当前用例行, 已覆盖变量集合)」状态空间上求解
 * 最小真值表，并严格满足需求文档的三大约束：
 *   ① 每个变量都存在一对相邻用例（Hamming 距离 = 1、其余变量全同、判定反转）—— 即 MC/DC 独立影响对；
 *   ② out（判定）列严格 0/1 交替；
 *   ③ 在上述约束下用例数最少。
 *
 * 转移规则（图边）：
 *   - 翻转恰好 1 个变量且判定反转 → 覆盖该变量（covers that var），计入已覆盖集合；
 *   - 翻转 2~[MAX_JUMP_H] 个变量且判定反转 → "跳跃行"（jump），不覆盖任何变量，仅用于衔接不同变量的影响对；
 *   两类边都要求相邻两行判定相反（严格交替）。BFS 以用例数为代价求最短路，天然得到最小解。
 *
 * 旧版 [dominoChain]（局部相邻优先 + 多米诺链式）作为兜底保留：当 BFS 因极端情形未找到解时回退使用。
 */
object McdcAlgorithm {

    /** 变量数量上限，防止 2^n 爆炸导致 OOM（PRD §7）。 */
    const val MAX_VARIABLES = 10

    /** 跳跃行允许的最大汉明距离（用于衔接不同变量的影响对，不覆盖变量）。 */
    private const val MAX_JUMP_H = 3

    fun generate(expression: String, startFromTrue: Boolean): McdcResult {
        val ast = parse(expression)
        val variables = extractVariables(expression)
        if (variables.isEmpty()) throw McdcParseException("未检测到任何变量，请检查表达式")
        if (variables.size > MAX_VARIABLES) {
            throw McdcParseException("变量数量(${variables.size})超过上限 $MAX_VARIABLES，请减少变量")
        }

        val baseRows = generateBaseTable(variables, ast, startFromTrue)
        // 主算法：BFS 精确最小解
        val bfs = solveMinimal(baseRows, variables, startFromTrue)
        if (bfs != null) return bfs
        // 兜底：旧贪心链式（极少触发）
        val candidates = extractCandidatePairs(variables, baseRows)
        val (orderedCases, highlight, uncoverable) = dominoChain(candidates, startFromTrue)
        return McdcResult(
            variables = variables,
            orderedCases = orderedCases,
            highlight = highlight,
            uncoverable = uncoverable
        )
    }

    // ------------------------------------------------------------------
    // BFS 精确最小解
    // ------------------------------------------------------------------
    private fun solveMinimal(
        baseRows: List<TruthRow>,
        variables: List<String>,
        startFromTrue: Boolean
    ): McdcResult? {
        val n = variables.size
        val N = baseRows.size
        // 每行编码为位向量（变量按下标映射到位）
        val bit = IntArray(N)
        for (i in baseRows.indices) {
            var bv = 0
            for ((bitIdx, v) in variables.withIndex()) {
                bv = bv or ((baseRows[i].values[v] ?: 0) shl bitIdx)
            }
            bit[i] = bv
        }
        val dec = IntArray(N) { baseRows[it].decision }
        // 位向量 -> 行索引 直接寻址表（O(1) 邻居查找）
        val bvToIndex = IntArray(1 shl n) { -1 }
        for (i in 0 until N) bvToIndex[bit[i]] = i

        // 邻居生成：翻转 1..MAX_JUMP_H 个变量且判定反转的行；返回 (邻居行索引, 覆盖位)
        fun neighbors(i: Int): List<Pair<Int, Int>> {
            val res = mutableListOf<Pair<Int, Int>>()
            for (k in 1..MAX_JUMP_H) {
                if (k == 1) {
                    for (v in 0 until n) {
                        val flipped = 1 shl v
                        val j = bvToIndex[bit[i] xor flipped]
                        if (j < 0 || dec[i] == dec[j]) continue
                        res.add(j to flipped)
                    }
                } else if (k == 2) {
                    for (a in 0 until n) for (b in a + 1 until n) {
                        val flipped = (1 shl a) or (1 shl b)
                        val j = bvToIndex[bit[i] xor flipped]
                        if (j < 0 || dec[i] == dec[j]) continue
                        res.add(j to 0)
                    }
                } else {
                    for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) {
                        val flipped = (1 shl a) or (1 shl b) or (1 shl c)
                        val j = bvToIndex[bit[i] xor flipped]
                        if (j < 0 || dec[i] == dec[j]) continue
                        res.add(j to 0)
                    }
                }
            }
            return res
        }

        // 可覆盖变量集合
        var coverable = 0
        for (i in 0 until N) for ((_, cm) in neighbors(i)) coverable = coverable or cm
        val target = coverable
        val FULL = 1 shl n
        val INF = Int.MAX_VALUE
        val size = N * FULL
        val dist = IntArray(size) { INF }
        val parent = IntArray(size) { -1 }       // 前驱行索引，-2 表示起点
        val prevmask = IntArray(size) { -1 }    // 前驱掩码，-1 表示起点
        val startDec = if (startFromTrue) 1 else 0
        val queue: ArrayDeque<Int> = ArrayDeque()
        for (i in 0 until N) {
            if (dec[i] == startDec) {
                val key = i * FULL
                if (dist[key] > 1) {
                    dist[key] = 1
                    parent[key] = -2
                    prevmask[key] = -1
                    queue.add(key)
                }
            }
        }
        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            val i = state / FULL
            val mask = state % FULL
            val d = dist[state]
            for ((j, cm) in neighbors(i)) {
                val nm = mask or cm
                val nd = d + 1
                val nk = j * FULL + nm
                if (nd < dist[nk]) {
                    dist[nk] = nd
                    parent[nk] = i
                    prevmask[nk] = mask
                    queue.add(nk)
                }
            }
        }
        // 选取到达 target 的最短终态
        var best = -1
        var bestd = INF
        for (i in 0 until N) {
            val d = dist[i * FULL + target]
            if (d < bestd) { bestd = d; best = i }
        }
        if (bestd == INF) return null

        // 沿 (前驱行, 前驱掩码) 回溯
        val path = mutableListOf<Int>()
        var ci = best
        var cmask = target
        while (true) {
            path.add(ci)
            val p = parent[ci * FULL + cmask]
            if (p == -2) break
            val pm = prevmask[ci * FULL + cmask]
            ci = p
            cmask = pm
        }
        path.reverse()

        // 组装有序用例
        val ordered = path.map { baseRows[it] }
        // 高亮：每条 Hamming=1 的相邻对即某变量的独立影响对
        val highlight = LinkedHashMap<String, Pair<Int, Int>>()
        for (k in 0 until path.size - 1) {
            val xor = bit[path[k]] xor bit[path[k + 1]]
            if (Integer.bitCount(xor) == 1) {
                val v = Integer.numberOfTrailingZeros(xor)
                val name = variables[v]
                if (!highlight.containsKey(name)) highlight[name] = k to (k + 1)
            }
        }
        // 不可覆盖变量
        val uncoverable = (0 until n)
            .filter { (coverable and (1 shl it)) == 0 }
            .map { variables[it] }
            .toSet()

        // 强校验：① 高亮对严格相邻 ② out 严格交替
        for ((_, p) in highlight) {
            require(abs(p.first - p.second) == 1) {
                "算法内部错误：变量 $variables 的高亮列不相邻 (${p.first}, ${p.second})"
            }
        }
        for (i in 0 until ordered.size - 1) {
            require(ordered[i].decision != ordered[i + 1].decision) {
                "算法内部错误：判定列未严格交替 (位置 $i,${i + 1} 均为 ${ordered[i].decision})"
            }
        }
        return McdcResult(
            variables = variables,
            orderedCases = ordered,
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
    // 兜底算法：多米诺链式拼接（Locality-First + 严格交替）
    // 仅当 BFS 主算法未找到解时回退使用。
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
