# MC/DC 真值表生成器

Android 原生应用，基于 Jetpack Compose + MVVM/UDF 架构，用于生成 **MC/DC（Modified Condition/Decision Coverage）** 真值表。

## 功能特性

- **布尔表达式解析**：支持 `&`（与）、`|`（或）、`~`/`!`（非）、`()`（括号），递归下降解析器 + AST 求值
- **MC/DC 算法**：局部连续性优先（Locality-First）+ 多米诺链式（Domino Chaining）拼接
  - **判定列（out）严格 0/1 交替**
  - 每个变量满足独立影响对（严格相邻）
  - 链式重叠最大化、用例数最小化
  - 不可覆盖变量自动标记
- **冻结表头 UI**：横向/纵向滚动时首行（用例序号）和首列（变量名）冻结，`wrapContentWidth/Height(unbounded=true)` 确保全部单元格完整渲染
- **高刷新率自适应**：自动匹配设备原生刷新率（120Hz/90Hz/60Hz）
- **高亮显示**：MC/DC 独立影响对在矩阵中高亮标记

## 技术栈

| 组件 | 版本/说明 |
|------|-----------|
| Kotlin | 1.9.24 |
| Jetpack Compose | BOM 2024.09.00（UI 1.7.0） |
| Compose Compiler | 1.5.14（独立工件） |
| Material 3 | 随 Compose BOM |
| 架构 | MVVM + UDF（单向数据流） |
| minSdk | 26（Android 8.0） |
| targetSdk / compileSdk | 34（Android 14） |
| AGP | 8.5.2 |
| Gradle | 8.9 |

## 项目结构

```
app/src/main/java/com/example/mcdc/
├── MainActivity.kt              # Activity，高刷新率初始化
├── algorithm/
│   ├── ExprParser.kt            # 表达式词法分析 + 递归下降解析器 + AST 节点
│   └── McdcAlgorithm.kt         # MC/DC 核心算法（候选对提取 + 多米诺链式拼接）
├── model/
│   └── McdcResult.kt            # 数据模型（TruthRow, McdcResult）
├── viewmodel/
│   └── McdcViewModel.kt         # MVVM ViewModel（UDF 状态管理 + 协程）
└── ui/
    ├── McdcApp.kt               # Compose UI（输入面板 + 冻结表头矩阵）
    └── theme/                   # Material 3 主题
```

## 算法说明

### MC/DC 独立影响对

对每个变量 V，找到满足以下三个条件的行对 (X, Y)：
1. `X[V] ≠ Y[V]` — 目标变量取值不同
2. 其余变量取值完全相同（Hamming 距离 = 1）— 唯一变量控制
3. `Decision(X) ≠ Decision(Y)` — 判定结果反转

### 多米诺链式拼接

- **严格交替约束**：out 列必须严格 0/1 交替（`0101…` 或 `1010…`）
- **链式重叠**：相邻用例对共享同一行，最大化重叠、压缩总用例数
- **断裂兜底**：链无法延续时，复制一对相邻行重启新链（仍维持交替）
- **强校验**：`require(decision[i] ≠ decision[i+1])` + `require(abs(colA - colB) == 1)`

## 构建方法

```bash
# 环境变量
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 构建 Debug APK
gradle :app:assembleDebug --no-daemon

# 输出
app/build/outputs/apk/debug/app-debug.apk
```

## 使用示例

输入布尔逻辑表达式（如 `~((A & B) | ((~c & ~d & ~e))) & f`），点击「生成」即可得到 MC/DC 真值表，支持横向/纵向滚动查看完整矩阵。
