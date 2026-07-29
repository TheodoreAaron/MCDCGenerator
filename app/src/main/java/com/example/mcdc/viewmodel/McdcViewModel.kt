package com.example.mcdc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 */
data class McdcUiState(
    val expression: String = "A & B | C",
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

    fun onExpressionChange(value: String) {
        _uiState.update { it.copy(expression = value, error = null) }
    }

    fun onStartToggle(value: Boolean) {
        _uiState.update { it.copy(startFromTrue = value) }
    }

    fun clear() {
        _uiState.update { it.copy(expression = "", result = null, error = null) }
    }

    fun generate() {
        val expr = _uiState.value.expression.trim()
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
