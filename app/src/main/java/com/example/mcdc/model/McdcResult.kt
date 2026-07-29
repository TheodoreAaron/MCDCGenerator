package com.example.mcdc.model

/**
 * 一行真值表。
 * @param id    行唯一标识（复制行会分配新 id，保证对象可区分）
 * @param values 变量名 -> 0/1 取值映射
 * @param decision 该行的判定（Decision）结果，0/1
 */
data class TruthRow(
    val id: Int,
    val values: Map<String, Int>,
    val decision: Int
)

/**
 * MC/DC 计算结果（转置矩阵）。
 * @param variables   升序变量名（不含 out）
 * @param orderedCases X 轴：每一列代表一个测试用例（行实例，复制行会重复出现）
 * @param highlight    变量 -> 其 MC/DC 独立影响对对应的两列下标 (colA, colB)，
 *                     二者必严格相邻（abs(colA - colB) == 1）
 * @param uncoverable 不可覆盖（被屏蔽）的变量集合
 */
data class McdcResult(
    val variables: List<String>,
    val orderedCases: List<TruthRow>,
    val highlight: Map<String, Pair<Int, Int>>,
    val uncoverable: Set<String>
)
