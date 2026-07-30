# MC/DC 真值表生成器

Android 原生应用，基于 Jetpack Compose + MVVM/UDF 架构，用于生成 **MC/DC（Modified Condition/Decision Coverage）** 真值表。

## 功能特性

- **布尔表达式解析**：支持 `&`（与）、`|`（或）、`~`/`!`（非）、`()`（括号），递归下降解析器 + AST 求值
- **MC/DC 算法**：以 **BFS 最短路径** 求解 **精确最小用例集**，并辅以多米诺链式（Domino Chaining）兜底
  - **判定列（out）严格 0/1 交替**
  - 每个变量满足独立影响对（严格相邻，Hamming 距离 = 1）
  - 在全部约束下用例数严格最小（BFS 最短路径保证）
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
│   └── McdcAlgorithm.kt         # MC/DC 核心算法（BFS 精确最小解 + 多米诺链式兜底）
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

### 主算法：BFS 精确最小解（`solveMinimal`）

核心思想是把「生成最小真值表」建模为最短路径问题，用 BFS 在状态空间上求解：

- **状态**：`(当前用例行 i, 已覆盖变量位掩码 mask)`。`i` 来自完整真值表（共 `2^n` 行），`mask` 记录哪些变量已拥有独立影响对。
- **状态转移（图的边）**：从行 `i` 到行 `j`，要求 `Decision(i) ≠ Decision(j)`（保证 out 严格交替），并翻转 `1..MAX_JUMP_H`（默认 3）个变量：
  - **翻转恰好 1 个变量** → 若该翻转使判定反转，则这一跳「覆盖」该变量（`mask |= 1<<v`），即构成它的独立影响对；
  - **翻转 2~3 个变量** → 作为「跳跃行」衔接不同变量的影响对，**不覆盖**任何变量，只用于把链条接到另一段。
- **起点**：所有 `Decision == startDec`（由 `startFromTrue` 决定首行期望取值）的行，初始 `mask=0`、代价 1。
- **终点**：`mask == target`（全部可覆盖变量集），此时路径上的用例数即代价，BFS 天然给出**用例数最少的路径**。
- **加速**：每行编码为位向量 `bv`，用 `bvToIndex[1<<n]` 直接寻址表实现 O(1) 邻居查找，避免每次扫描全表。
- **回溯**：用 `(前驱行, 前驱掩码)` 二元组记录前驱，沿前驱回溯出用例序列；连续两行 Hamming=1 的对即对应变量的独立影响对，写入 `highlight`。
- **强校验**：组装后 `require` 所有高亮对下标差严格为 1、out 列严格交替，断言失败即抛异常。

### 兜底算法：多米诺链式拼接（`dominoChain`）

当 BFS 因极端情形（理论上极少见）未找到解时回退使用：
- **严格交替约束**：out 列必须严格 0/1 交替（`0101…` 或 `1010…`）。
- **局部相邻优先（Locality-First）**：每步尽量复用链尾行，只在尾部追加缺失行，最大化重叠、压缩用例数。
- **最受限优先（Most-Constrained-First）**：优先安置候选对最少的变量（更难安置的先安置）。
- **断裂兜底**：链无法延续时，复制一对相邻行重启新链（仍维持交替）。
- **强校验**：同主算法，断言高亮对相邻 + out 严格交替。

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

## CI/CD 自动化构建

仓库通过两条 GitHub Actions 流水线构建（设计细节见 [docs/ci-cd.md](docs/ci-cd.md)）：

- **测试通道 `ci.yml`**：推 `main` / 开 PR / 手动触发，编译并产出 debug APK（artifact 保留 7 天），**不发版**。
- **正式发行 `release.yml`**：打 `v*` tag 推送（或手动触发）时，构建 release + debug APK 并自动建 GitHub Release（标记为 Latest）。

本地无 Android 环境时，直接推代码即可在 Actions 拿到构建产物；要发正式版执行：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## 使用示例

输入布尔逻辑表达式（如 `~((A & B) | ((~c & ~d & ~e))) & f`），点击「生成」即可得到 MC/DC 真值表，支持横向/纵向滚动查看完整矩阵。
