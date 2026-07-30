# CI/CD 设计文档（测试通道 + 正式发行通道）

> 本文件沉淀 GitHub Actions 的构建/发布架构设计。任何后续接手本项目的人（包括换 agent）
> 都应先读此文档，理解"为什么拆成两条流水线、各自何时触发、签名现状如何"，
> **不要凭直觉重新设计一套**，除非需求已变化。
>
> 配套需求约束见 [requirements.md](./requirements.md)（算法侧硬约束，与本文件互不冲突）。

## 1. 背景：为什么要拆

早期只有一个 `build-apk.yml`：

- `on: push branches: [main]`，即**每次推 main 都触发**；
- 同时编 debug + release，并创建一个 `nightly-<run_number>` 的 GitHub Release（`make_latest: true`）。

问题：测试提交和"要发版"被混为一谈——推一次 main 就发一个 Release，版本号是毫无语义的
`nightly-N`，历史 Release 会无限堆积，且没有"先验证再发版"的节奏区分。

因此重构为**双通道**：

| 通道 | 文件 | 触发时机 | 产物 | 是否发版 |
|------|------|----------|------|----------|
| 测试通道 | `ci.yml` | 推 `main`、开 PR、手动 | debug APK（artifact，保留 7 天） | 否 |
| 正式发行 | `release.yml` | 打 `v*` tag / 手动填版本 | release + debug APK，建 GitHub Release | 是 |

旧的 `build-apk.yml` 已删除，避免 push main 同时触发 `ci.yml` + 旧 nightly 造成重复发版。

## 2. 已锁定的设计决策（改之前先确认）

1. **正式发行触发方式 = Git Tag 触发（方案①）**
   `git tag v1.0.0 && git push --tags` → 自动发版。版本即 tag，语义清晰、可追溯。
   （`workflow_dispatch` 作为可选手动入口保留，不依赖 tag 也能发，不影响 tag 主逻辑。）

2. **暂时没有正式签名密钥**
   `app/build.gradle.kts` 中 `release` 构建类型当前**复用 debug keystore** 签名
   （`signingConfig = signingConfigs.getByName("debug")`）。
   所以 release APK 能直接 sideload 安装，但**不能上架**（debug key 公开）。
   因此本方案**不引入任何 GitHub Secrets**，build.gradle 也无需改动。

3. **测试通道范围 = `push` 到 `main` + 所有 PR**（单 main 仓库最稳，不会一推就发版）。

4. **实施时删除旧 `build-apk.yml`**，避免重复发版。

## 3. 测试通道 `ci.yml`

目标：每次提交最快给出"能不能编译 + 一个能装的调试包"，绝不发版。

```yaml
name: CI / Test
on:
  push:
    branches: [ main ]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read                  # 最小权限

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true        # 同一分支重复提交自动取消旧构建

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: android-actions/setup-android@v3
      - run: sdkmanager "platforms;android-34" "build-tools;34.0.0"
      - run: chmod +x gradlew
      - uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*','**/gradle-wrapper.properties') }}
          restore-keys: ${{ runner.os }}-gradle-
      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon
      - name: Upload Debug APK
        uses: actions/upload-artifact@v4
        with:
          name: MCDC-debug-${{ github.sha }}
          path: app/build/outputs/apk/debug/app-debug.apk
          retention-days: 7
```

要点：

- `permissions: contents: read` —— 测试不需要写权限，遵循最小权限原则。
- `concurrency` —— 同一分支连续 push 时取消上一次还在跑的构建，省资源、防堆积。
- artifact 只保留 **7 天**，区别于正式发版的永久 Release。
- 单元测试用例 `./gradlew testDebugUnitTest` 当前未加（项目暂无测试）；需要时直接补上即可，失败即阻断。

## 4. 正式发行 `release.yml`

目标：仅在"要发版"时跑，产出安装包并建 GitHub Release。

```yaml
name: Release
on:
  push:
    tags: [ 'v*' ]
  workflow_dispatch:
    inputs:
      version:
        description: 'Release version, e.g. 1.0.0'
        required: true

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: android-actions/setup-android@v3
      - run: sdkmanager "platforms;android-34" "build-tools;34.0.0"
      - run: chmod +x gradlew
      - uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*','**/gradle-wrapper.properties') }}
          restore-keys: ${{ runner.os }}-gradle-
      # 沿用 build.gradle 现有 debug key 签名（暂无正式 key）
      - name: Build Release APK
        run: ./gradlew assembleRelease --no-daemon
      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon
      - name: Resolve version
        id: ver
        run: |
          if [ "${{ github.event_name }}" = "workflow_dispatch" ]; then
            echo "tag=v${{ github.event.inputs.version }}" >> "$GITHUB_OUTPUT"
          else
            echo "tag=${GITHUB_REF_NAME}" >> "$GITHUB_OUTPUT"
          fi
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ steps.ver.outputs.tag }}
          name: MC/DC Generator ${{ steps.ver.outputs.tag }}
          make_latest: true
          files: |
            app/build/outputs/apk/release/app-release.apk
            app/build/outputs/apk/debug/app-debug.apk
          body: |
            🚀 正式发行 ${{ steps.ver.outputs.tag }}
            - 提交: ${{ github.sha }}
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

要点：

- `make_latest: true` —— 新发版本自动成为 Latest，下载入口始终指向最新正式版。
- 版本号解析：tag 触发用 `GITHUB_REF_NAME`（即 `v1.0.0`）；手动触发用输入的 `version`。
- Release Assets 同时含 release + debug 两个 APK，方便不同安装场景。

## 5. 如何切一个正式版本

```bash
# 在已合并到 main 的提交上打 tag 并推送
git tag v1.0.0
git push origin v1.0.0        # 或 git push --tags
```

推送后 `release.yml` 自动运行，构建完成即在 Releases 页生成 `v1.0.0`（Latest）。
日常推 `main` 只会跑 `ci.yml` 出调试包，不会发版。

## 6. 后续升级：引入正式签名（未做，预留接口）

当需要"对外可信分发 / 上架"时，再补正式签名，无需改动通道结构：

1. 在 GitHub **Settings → Secrets** 配置 4 个变量：
   `RELEASE_KEYSTORE_BASE64`（base64 后的 .jks）、
   `RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`、`RELEASE_STORE_PASSWORD`。
2. 把 `build.gradle.kts` 的 release 签名改成"有正式 key 就用、没有就回退 debug"：

   ```kotlin
   signingConfigs {
       create("release") {
           val d = getByName("debug")
           storeFile = d.storeFile; keyAlias = d.keyAlias
           storePassword = d.storePassword; keyPassword = d.keyPassword
           if (System.getenv("RELEASE_KEYSTORE") != null) {
               storeFile = file(System.getenv("RELEASE_KEYSTORE"))
               keyAlias = System.getenv("RELEASE_KEY_ALIAS")
               storePassword = System.getenv("RELEASE_STORE_PASSWORD")
               keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
           }
       }
   }
   buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
   ```
3. 在 `release.yml` 的 build 步骤前加"还原 keystore（`echo $BASE64 | base64 -d > key.jks`）+ 注入 4 个 env"即可。

## 7. 已知约束 / 注意事项

- 本仓库无单元测试，`ci.yml` 当前只验证"能编译 + 出 debug 包"；加测试请见 §3 注释处。
- release APK 现以 debug key 签名，仅在受信任的 sideload 场景使用；上架前必须走 §6。
- 两个 workflow 共用同一套 Gradle 缓存 key，互不影响。
- 若将来要"发版前人工审批"，给 `release.yml` 的 job 加 `environment: production` 并在
  GitHub 设置审批人即可（当前未启用，因为暂无此需求）。
