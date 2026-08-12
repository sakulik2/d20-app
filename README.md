# d20

d20 是一个面向单人 TRPG 的 Android 应用原型。应用使用 Jetpack Compose 构建界面，以本地规则引擎负责掷骰与战斗裁决，LLM 只根据已经确定的结果续写叙事。项目同时支持虚拟 3D 骰子和线下实体骰：线下模式由玩家填写已经完成所有取高、取低和修正后的最终结果。

## 当前能力

- D&D 5e、CoC 7e 与示例声明式规则包。
- 角色创建、存档、世界观、聊天与本地 Room 持久化。
- 长对话保留最近完整回合，较早历史生成本地提取式摘要，并按相关性召回世界书。
- 武器、法术、法术位、目标选择、多目标原子结算与死亡豁免。
- 先攻、回合资源、持续伤害/治疗效果及战斗恢复。
- CommonMark Markdown 渲染和 Debug 专用 3D 骰子实验室。
- 固定开发者服务器上的规则包与世界观包下载、校验和更新。
- Release 构建可从官方 GitHub Releases 检查并下载新版 APK。

## 项目结构

```text
app/src/main/java/xyz/sakulik/d20/app/
├── data/      Room、LLM 协议、仓库与安全配置
├── domain/    战斗、规则 AST、动作裁决与插件管理
├── engine/    骰子表达式解析和计算
└── ui/        Compose 页面、ViewModel 与 3D 骰子
app/src/main/assets/   内置规则包与世界观包
app/src/test/          JVM 单元测试
app/src/androidTest/   Room、Compose 和设备测试
```

## 在 Android Studio 中运行

1. 使用 JDK 17 打开仓库根目录，等待 Gradle Sync 完成。
2. 在 Build Variants 中选择 `dev`，再选择 Android 8.0（API 26）以上设备或模拟器运行日常开发应用；它使用独立的 `.dev` 包名保存开发存档。
3. JVM 测试可在 `app/src/test` 直接 Run，或从 Gradle 工具窗口运行 `testDebugUnitTest`。
4. 设备测试恢复为 Android Studio 标准 `debugAndroidTest`：可直接 Run `app/src/androidTest`、测试类或单个方法，也可运行 `connectedDebugAndroidTest`。其被测应用使用 `.debug` 包名，不会清除日常 `.dev` 或 Release 数据。

当前数据库版本为 12。修改 Room 实体后，请确认对应 `app/schemas/xyz.sakulik.d20.app.data.local.AppDatabase/<版本>.json` 已更新并纳入版本控制。

### DeepSeek 冒烟验证

在设置中填写 `https://api.deepseek.com`、模型 `deepseek-chat`、自己的 API Key，并将协议保持为“默认”或选择“OpenAI Chat Completions”。新建存档后发送一个无需检定的动作，例如“环顾房间并描述眼前景象”：正常结果应显示一段叙事且不报 JSON 格式错误。随后发送一个有风险的动作，例如“撬开上锁的箱子”：正常结果应打开本地掷骰面板，提交结果后继续叙事。客户端会为 DeepSeek 使用 `response_format={"type":"json_object"}`，拒绝截断或无效事件，并在格式错误时自动重试一次。

聊天原文仍完整保存在 Room 中。发送给模型时，应用按“最近完整回合 + 较早对话本地提取式摘要 + 相关世界书”组装上下文；隐藏的本地判定消息不会进入记忆。近期原文默认保留 16 个完整回合，可在设置中调整为 8–48 回合，其字符预算随轮数同步增长；较早摘要最多约 8,000 字符，世界书最多召回 6 条、约 6,000 字符。当前检索是无需网络和 embedding 的确定性关键词排序，不是向量数据库 RAG。

Debug/Dev 构建会用 Logcat 标签 `LlmTraffic` 分段记录最终协议、去除查询参数与用户信息后的请求 URL、完整 JSON 请求正文和模型返回正文，便于排查兼容端点。日志不读取请求 Header，因此不会记录 `Authorization`、`x-api-key` 或本地 API Key；但提示词、用户输入、角色信息和模型输出仍属于敏感内容，提交日志前必须脱敏。Release 构建不输出这些正文日志。

## 规则包安全边界

最终用户不能新建、编辑、本地导入规则包，也不能修改下载源。规则包只能由开发者内置，或发布到应用固定的 HTTPS 索引后由客户端下载。下载器限制文件大小，在流式写入临时文件的同时计算 SHA-256，以严格的 32 字节摘要和常量时间比较核对索引，再验证 manifest ID、版本和完整规则契约；任一环节失败都不会替换已安装版本。若已登记下载包在后续加载时无法通过完整语义契约，应用会将其隔离为 `.rejected`、撤销登记，并回退同 ID 的内置包。远程包不能执行脚本、Kotlin 类或任意代码。

开发者制作与发布规则包前，请阅读 [RULESET_AUTHORING.md](RULESET_AUTHORING.md)。本地问题清单与代理工作说明属于开发环境文件，不纳入产品仓库。

## 发布应用更新

应用更新入口只存在于 Release 构建的设置页，且仅在用户点击后访问官方仓库。每次发布前同时提高 `app/build.gradle.kts` 中的 `versionCode` 和三段式 `versionName`；GitHub Release 使用对应的 `vMAJOR.MINOR.PATCH`（例如 `v1.1.0`）作为 tag，并附加一个不超过 250 MiB 的 `.apk` asset。Draft、Prerelease、非三段版本号和非 APK 文件不会被客户端接受。下载完成后 Android 系统仍会核对更高的 `versionCode`、APK 签名并要求用户确认安装，因此后续版本必须继续使用与已安装版本相同的签名密钥。

Release 构建启用 R8 代码压缩和资源收缩、禁止明文 HTTP，并保留可用于 mapping 还原的行号。Kotlin Serialization、Room、OkHttp 和 AndroidX Security 依赖各自的消费者规则；应用规则不再整包保留业务类。发布时保存 `app/build/outputs/mapping/release/mapping.txt`，它必须与对应 APK 一起归档。API Key 所在的 `llm_secure_prefs.xml` 已排除在云备份和设备迁移之外。

正式签名从仓库根目录、已被 Git 忽略的 `keystore.properties` 读取。复制 `keystore.properties.example` 并填写 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`；`storeFile` 相对于仓库根目录。未提供该文件时 Release 产物保持未签名，只适合静态检查，不能发布或覆盖安装。签名文件和密码必须离线备份；丢失后无法继续更新已安装应用。

## 开发约定

- Kotlin 使用四空格缩进和官方命名风格。
- 业务状态放在领域层或 ViewModel，不在 Composable 中重复维护。
- 规则变化应补充 JVM 契约测试；Room 或 Compose 行为放入 `androidTest`。
- 不提交 API Key、`local.properties`、IDE 私有状态、APK 或构建产物。

## 已知限制

规则包索引目前使用 HTTPS 与 SHA-256，但尚未实现开发者私钥签名；敌人属性与先攻已改由规则包可信档案提供，友方目标和非 HP 条件效果仍在后续计划中。该项目当前处于积极开发阶段，发布前应在 Android Studio 完整执行 JVM 与设备测试。
