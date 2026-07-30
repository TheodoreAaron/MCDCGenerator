package com.example.mcdc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.mcdc.algorithm.McdcAlgorithm
import com.example.mcdc.algorithm.McdcParseException
import com.example.mcdc.model.McdcResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MC/DC 界面状态（单向数据流 UDF）。
 *
 * expression 使用 TextFieldValue 而非裸 String：
 * 这样可以在状态中保留光标的真实位置（selection），
 * 从而支持「在光标处插入符号」而非只能拼接到文本末尾。
 */
data class McdcUiState(
    val expression: TextFieldValue = TextFieldValue("A & B | C"),
    val startFromTrue: Boolean = false,
    val isLoading: Boolean = false,
    val result: McdcResult? = null,
    val error: String? = null
)

/**
 * MVVM 的 ViewModel。
 * 重度矩阵计算放置在 viewModelScope.launch(Dispatchers.Default) 协程中，
 * 期间暴露 isLoading 供 UI 展示 CircularProgressIndicator；异常（语法/计算）
 * 捕获后通过 error 字段驱动 Snackbar，保证应用不崩溃（PRD §6/§7）。
 */
class McdcViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(McdcUiState())
    val uiState: StateFlow<McdcUiState> = _uiState.asStateFlow()

    /**
     * 用户直接在 TextField 中输入时回调。
     * 接收完整的 TextFieldValue（含最新光标 selection），原样保存，
     * 以保证光标位置始终与界面一致，供 insertAtCursor 使用。
     */
    fun onExpressionChange(value: TextFieldValue) {
        _uiState.update { it.copy(expression = value, error = null) }
    }

    /**
     * 在光标处插入逻辑符号/括号（修复「只能拼到末尾」的 bug）。
     *
     * 行为：
     * 1. 读取当前 selection。若用户选中了一段文本（start != end），则整段被符号替换；
     *    若只是折叠光标（start == end），则在光标位置插入。两种情况都用
     *    [TextRange.min, TextRange.max] 作为替换区间，逻辑统一。
     * 2. 用 replaceRange 在 [start, end) 处放入符号，得到新文本。
     * 3. 把光标移动到插入文本之后（start + symbol.length），
     *    这样无论光标在表达式中间还是末尾，后续输入都连贯。
     * 4. 清掉上一次的错误提示，让界面即时回到可编辑态。
     */
    fun insertAtCursor(symbol: String) {
        _uiState.update { state ->
            val current = state.expression
            val sel = current.selection
            val start = sel.min
            val end = sel.max
            val text = current.text
            // 防御：区间越界保护（理论上 TextFieldValue 不会越界，这里兜底）
            val safeStart = start.coerceIn(0, text.length)
            val safeEnd = end.coerceIn(safeStart, text.length)
            val newText = text.replaceRange(safeStart, safeEnd, symbol)
            val newCursor = safeStart + symbol.length
            val newValue = current.copy(
                text = newText,
                selection = TextRange(newCursor)
            )
            state.copy(expression = newValue, error = null)
        }
    }

    fun onStartToggle(value: Boolean) {
        _uiState.update { it.copy(startFromTrue = value) }
    }

    fun clear() {
        _uiState.update { it.copy(expression = TextFieldValue(""), result = null, error = null) }
    }

    fun generate() {
        val expr = _uiState.value.expression.text.trim()
        if (expr.isEmpty()) {
            _uiState.update { it.copy(error = "请输入布尔逻辑表达式") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = McdcAlgorithm.generate(expr, _uiState.value.startFromTrue)
                _uiState.update { it.copy(isLoading = false, result = result, error = null) }
            } catch (e: McdcParseException) {
                _uiState.update { it.copy(isLoading = false, error = e.message, result = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "计算失败：${e.message}", result = null)
                }
            }
        }
    }
}
