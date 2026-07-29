package com.example.mcdc.algorithm

/**
 * 表达式解析器：Tokenizer + 递归下降解析器。
 *
 * 支持的语法：
 *  - 变量：大小写英文字母（如 A, b, c），取首字母作为变量名
 *  - 与：&  &&  and
 *  - 或：|  ||  or
 *  - 非：!  ~  not
 *  - 括号：()
 *
 * 优先级：NOT > AND > OR（与布尔代数一致）。
 */
class McdcParseException(message: String) : Exception(message)

object ExprParser {

    private sealed interface Tok
    private data class TVar(val name: String) : Tok
    private object TAnd : Tok
    private object TOr : Tok
    private object TNot : Tok
    private object TLp : Tok
    private object TRp : Tok

    /** 解析表达式字符串为 AST。非法输入抛出 [McdcParseException]。 */
    fun parse(input: String): ExprNode {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) throw McdcParseException("表达式为空")
        val parser = Parser(tokens)
        val node = parser.parseExpr()
        if (parser.hasMore()) throw McdcParseException("存在多余的运算符或符号")
        return node
    }

    /**
     * 从表达式中提取不重复变量名，按字母升序返回。
     * 关键字 and/or/not 被排除，其余字母序列取首字母作为变量。
     */
    fun extractVariables(input: String): List<String> {
        val vars = sortedSetOf<String>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c.isLetter()) {
                val word = input.drop(i).takeWhile { it.isLetter() }
                when (word.lowercase()) {
                    "and", "or", "not" -> i += word.length
                    else -> {
                        vars.add(word[0].toString())
                        i += word.length
                    }
                }
            } else {
                i++
            }
        }
        return vars.toList()
    }

    private fun tokenize(input: String): List<Tok> {
        val toks = mutableListOf<Tok>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c.isLetter() -> {
                    val word = input.drop(i).takeWhile { it.isLetter() }
                    when (word.lowercase()) {
                        "and" -> { toks.add(TAnd); i += 3 }
                        "or"  -> { toks.add(TOr); i += 2 }
                        "not" -> { toks.add(TNot); i += 3 }
                        else  -> { toks.add(TVar(word[0].toString())); i += word.length }
                    }
                }
                c == '&' -> {
                    if (i + 1 < input.length && input[i + 1] == '&') { toks.add(TAnd); i += 2 }
                    else { toks.add(TAnd); i += 1 }
                }
                c == '|' -> {
                    if (i + 1 < input.length && input[i + 1] == '|') { toks.add(TOr); i += 2 }
                    else { toks.add(TOr); i += 1 }
                }
                c == '!' || c == '~' -> { toks.add(TNot); i += 1 }
                c == '(' -> { toks.add(TLp); i += 1 }
                c == ')' -> { toks.add(TRp); i += 1 }
                else -> throw McdcParseException("非法字符: '$c'")
            }
        }
        return toks
    }

    private class Parser(private val toks: List<Tok>) {
        private var pos = 0
        fun hasMore(): Boolean = pos < toks.size
        private fun peek(): Tok? = toks.getOrNull(pos)
        private fun next(): Tok = toks.getOrNull(pos++) ?: throw McdcParseException("表达式意外结束")

        fun parseExpr(): ExprNode {
            var node = parseTerm()
            while (peek() is TOr) {
                next()
                node = OrNode(node, parseTerm())
            }
            return node
        }

        private fun parseTerm(): ExprNode {
            var node = parseFactor()
            while (peek() is TAnd) {
                next()
                node = AndNode(node, parseFactor())
            }
            return node
        }

        private fun parseFactor(): ExprNode = when (peek()) {
            TNot -> {
                next()
                NotNode(parseFactor())
            }
            TLp -> {
                next()
                val node = parseExpr()
                if (peek() !is TRp) throw McdcParseException("括号不匹配，缺少 ')'")
                next() // 消费 ')'
                node
            }
            is TVar -> {
                val v = next() as TVar
                VarNode(v.name)
            }
            else -> throw McdcParseException("语法错误：期望变量名或 '('")
        }
    }
}
