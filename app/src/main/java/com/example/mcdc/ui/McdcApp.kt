package com.example.mcdc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mcdc.model.McdcResult
import com.example.mcdc.viewmodel.McdcUiState
import com.example.mcdc.viewmodel.McdcViewModel

private val CELL = 48.dp
private val LABEL_W = 96.dp

/**
 * 应用根可组合项。
 *
 * 整体布局（自上而下）：
 *  ┌────────────────────────────┐
 *  │ 输入面板卡（表达式+基准确认）│
 *  ├────────────────────────────┤
 *  │                            │
 *  │    MC/DC 真值表矩阵（卡）   │
 *  │    冻结首行/首列             │
 *  └────────────────────────────┘
 */
@Composable
fun McdcApp(viewModel: McdcViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val clipboard = remember(ctx) {
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    // 通过累加计数器触发 snackbar —— 每次成功复制都让 copyAckTick++ 一次
    var copyAckTick by remember { mutableStateOf(0) }
    val onCopy: () -> Unit = onCopy@{
        val r = state.result ?: return@onCopy
        clipboard.setPrimaryClip(
            ClipData.newPlainText("MC/DC truth table", formatAsTsv(r))
        )
        copyAckTick++
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(copyAckTick) {
        if (copyAckTick > 0) snackbarHostState.showSnackbar("✅ 已复制真值表到剪贴板")
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InputPanel(
                state = state,
                viewModel = viewModel,
                onCopy = onCopy,
                canCopy = state.result != null && !state.isLoading,
            )
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when {
                    state.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                    state.result != null -> MatrixView(result = state.result!!)
                    else -> Text(
                        text = "输入布尔逻辑表达式后点击「生成」",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * 输入面板：表达式输入框 + 快捷插入 + 起始基准切换 + 生成/复制按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputPanel(
    state: McdcUiState,
    viewModel: McdcViewModel,
    onCopy: () -> Unit,
    canCopy: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 表达式输入 ─────────────────────────────
            OutlinedTextField(
                value = state.expression,
                onValueChange = viewModel::onExpressionChange,
                label = { Text("布尔逻辑表达式") },
                placeholder = { Text("例如 A & B | C") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    if (state.expression.isNotEmpty()) {
                        IconButton(onClick = viewModel::clear) {
                            Icon(Icons.Filled.Clear, contentDescription = "清空")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── 快捷符号插入 ────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                QuickChip("AND") { viewModel.onExpressionChange(state.expression + " & ") }
                QuickChip("OR")  { viewModel.onExpressionChange(state.expression + " | ") }
                QuickChip("NOT") { viewModel.onExpressionChange(state.expression + "~") }
                QuickChip("(")   { viewModel.onExpressionChange(state.expression + "(") }
                QuickChip(")")   { viewModel.onExpressionChange(state.expression + ")") }
            }

            // ── 起始基准切换 ────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("起始基准：", style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = !state.startFromTrue,
                    onClick = { viewModel.onStartToggle(false) },
                    label = { Text("从 0 开始") },
                )
                FilterChip(
                    selected = state.startFromTrue,
                    onClick = { viewModel.onStartToggle(true) },
                    label = { Text("从 1 开始") },
                )
            }

            // ── 操作按钮（复制 + 生成） ─────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (canCopy) {
                    Button(
                        onClick = onCopy,
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("复制", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Button(
                    onClick = viewModel::generate,
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "生成",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.height(32.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * MC/DC 转置真值表矩阵视图：单一滚动容器 + 冻结窗格（frozen panes）。
 *
 * 布局结构（自上而下绘制，z 层级：数据 0 < 冻结条 1 < 左上角 2）：
 *  ┌───────────────┬─────────────────────────────┐
 *  │  左上角(固定)  │  顶部用例序号条(横向跟随)     │
 *  ├───────────────┼─────────────────────────────┤
 *  │ 左侧变量名列   │  数据区(唯一双向滚动容器)     │
 *  │  (纵向跟随)    │                              │
 *  └───────────────┴─────────────────────────────┘
 *
 * 性能关键点：仍只保留 **一个** 双向滚动容器（数据区），避免多 scroll 容器嵌套联动
 * 掉帧。冻结条用「固定外壳 Box + 内部内容 `offset{}` 平移」实现：外壳始终钉在边缘
 * 并铺满整条，内部用例序号/变量名随同一对 ScrollState 做图形层平移（placement 阶段，
 * 不触发子项 re-layout），因此滑动仍稳定高帧率。
 *
 * 健壮性：冻结条外壳用实心背景 + clipToBounds，彻底消除旧实现在滚动末端可能出现的
 * 漏缝、以及首行/首列属性被覆盖的问题；左上角 zIndex 最高，永远遮住两条溢出部分。
 */
@Composable
private fun MatrixView(result: McdcResult) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val nCases = result.orderedCases.size

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ── 数据区：唯一的可滚动容器（最底层）────────
            Box(
                modifier = Modifier
                    .padding(top = CELL, start = LABEL_W)
                    .fillMaxSize()
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll),
            ) {
                Column {
                    result.variables.forEach { v ->
                        val pair = result.highlight[v]
                        Row(Modifier.height(CELL)) {
                            result.orderedCases.forEachIndexed { col, row ->
                                val isHi = pair?.let { col == it.first || col == it.second } ?: false
                                DataCell(value = row.values[v] ?: 0, highlight = isHi)
                            }
                        }
                    }
                    Row(Modifier.height(CELL)) {
                        result.orderedCases.forEach { row ->
                            DataCell(value = row.decision, isOut = true)
                        }
                    }
                }
            }

            // ── 顶部用例序号：固定外壳铺满整条，内部序号随 hScroll 平移 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CELL)
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier
                        .offset { IntOffset(-hScroll.value, 0) }
                        // 关键：冻结条外壳是定宽 Box，默认把内部 Row 的最大宽度约束成可视宽度，
                        // 导致只布局屏幕内可见的单元格，向右滑动后后面的序号直接空白。
                        // 必须用 wrapContentWidth(unbounded = true) 让 Row 以「无限宽」测量，
                        // 从而布局全部用例序号；再配合 clipToBounds + offset 只露出可视窗口。
                        // 注意：wrapContentWidth 的 unbounded 默认是 false（不会无限测量），
                        // 这就是之前表头不完整、左侧列却正常（左侧用的是 wrapContentHeight(unbounded=true)）的原因。
                        .wrapContentWidth(align = Alignment.Start, unbounded = true)
                        .padding(start = LABEL_W)
                        .height(CELL),
                ) {
                    for (col in 0 until nCases) {
                        Box(
                            modifier = Modifier
                                .size(CELL)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${col + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // ── 左侧变量名列：固定外壳铺满整列，内部标签随 vScroll 平移 ──
            Box(
                modifier = Modifier
                    .width(LABEL_W)
                    .fillMaxHeight()
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier
                        .offset { IntOffset(0, -vScroll.value) }
                        // 关键：同理，冻结列外壳是定高 Box，默认把内部 Column 的最大高度约束成
                        // 可视高度，导致只布局可见变量名，向下滑动后下面的变量名空白。
                        // 用 wrapContentHeight(unbounded = true) 以「无限高」测量，布局全部变量名，
                        // 配合 align = Top 使其顶部与外壳顶部对齐（offset 负责平移）。
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .padding(top = CELL),
                ) {
                    result.variables.forEach { v ->
                        val uncover = result.uncoverable.contains(v)
                        Box(
                            modifier = Modifier
                                .size(LABEL_W, CELL)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Column {
                                Text(
                                    v,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uncover) MaterialTheme.colorScheme.outline
                                            else MaterialTheme.colorScheme.onSurface,
                                    textDecoration = if (uncover) TextDecoration.LineThrough else null,
                                )
                                if (uncover) {
                                    Text(
                                        "不可覆盖",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(LABEL_W, CELL)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "out",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // ── 左上角：固定，zIndex 最高，遮住两条溢出 ──
            Box(
                modifier = Modifier
                    .size(LABEL_W, CELL)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "条件\\用例",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 数据单元格。
 */
@Composable
private fun DataCell(value: Int, highlight: Boolean = false, isOut: Boolean = false) {
    val bg = when {
        highlight -> MaterialTheme.colorScheme.primaryContainer
        isOut -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val borderWidth = if (highlight) 2.dp else 1.dp
    val borderColor = if (highlight) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(CELL)
            .background(bg)
            .border(borderWidth, borderColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (value == 1) "1" else "0",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 把结果格式化为 TSV（制表符分隔）多行文本——按「用例一行」排列，便于直接粘进
 * Excel/Google Sheets。包含所有覆盖到的变量名 + out 列。
 */
private fun formatAsTsv(r: McdcResult): String {
    val sb = StringBuilder()
    sb.append("Case")
    for (v in r.variables) sb.append('\t').append(v)
    sb.append('\t').append("out").append('\n')
    r.orderedCases.forEachIndexed { idx, row ->
        sb.append(idx + 1)
        for (v in r.variables) sb.append('\t').append(row.values[v] ?: 0)
        sb.append('\t').append(row.decision).append('\n')
    }
    return sb.toString()
}
