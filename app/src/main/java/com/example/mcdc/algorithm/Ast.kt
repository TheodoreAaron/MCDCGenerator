package com.example.mcdc.algorithm

/**
 * 布尔表达式抽象语法树（AST）节点。
 *
 * 关键约束（PRD Phase 1）：求值必须基于 AST 进行**全节点纯数学求值**，
 * **绝对禁用语言自带的短路求值**。因此 AndNode / OrNode 会先**强制求值两侧子节点**，
 * 再用位运算合并结果，确保即使一侧为 0/1，另一侧也一定会被计算。
 */
sealed interface ExprNode {
    /** 在给定变量取值下求值，返回 0（False）或 1（True）。 */
    fun eval(values: Map<String, Int>): Int
}

/** 变量叶子节点。 */
data class VarNode(val name: String) : ExprNode {
    override fun eval(values: Map<String, Int>): Int = values[name] ?: 0
}

/** 逻辑非：1 - child，全节点求值。 */
data class NotNode(val child: ExprNode) : ExprNode {
    override fun eval(values: Map<String, Int>): Int {
        val c = child.eval(values) // 强制求值子节点
        return 1 - c
    }
}

/** 逻辑与：先求两侧，再按位与（0/1 场景下等价于逻辑与）。 */
data class AndNode(val left: ExprNode, val right: ExprNode) : ExprNode {
    override fun eval(values: Map<String, Int>): Int {
        val l = left.eval(values)  // 必须两侧都求值（禁用短路）
        val r = right.eval(values)
        return l and r
    }
}

/** 逻辑或：先求两侧，再按位或。 */
data class OrNode(val left: ExprNode, val right: ExprNode) : ExprNode {
    override fun eval(values: Map<String, Int>): Int {
        val l = left.eval(values)  // 必须两侧都求值（禁用短路）
        val r = right.eval(values)
        return l or r
    }
}
