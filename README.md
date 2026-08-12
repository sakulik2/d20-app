# d20

d20 是一个面向单人 TRPG 的 Android 应用原型。应用使用 Jetpack Compose 构建界面，以本地规则引擎负责掷骰与战斗裁决，LLM 只根据已经确定的结果续写叙事。项目同时支持虚拟 3D 骰子和线下实体骰：线下模式由玩家填写已经完成所有取高、取低和修正后的最终结果。

## 当前能力

- D&D 5e、CoC 7e 与示例声明式规则包。
- 角色创建、存档、世界观、聊天与本地 Room 持久化。
- 武器、法术、法术位、目标选择、多目标原子结算与死亡豁免。
- 先攻、回合资源、持续伤害/治疗效果及战斗恢复。
- CommonMark Markdown 渲染和 Debug 专用 3D 骰子实验室。
- 固定开发者服务器上的规则包与世界观包下载、校验和更新。

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
2. 选择 `app` 配置及 Android 8.0（API 26）以上设备或模拟器。
3. 点击 Run 运行应用。
4. 在测试目录或 Gradle 工具窗口运行 `testDebugUnitTest`；连接设备后运行 `connectedDebugAndroidTest`。

当前数据库版本为 11。首次编译后请确认 Room 生成 `app/schemas/xyz.sakulik.d20.app.data.local.AppDatabase/11.json`，并将其纳入版本控制。

## 规则包安全边界

最终用户不能新建、编辑、本地导入规则包，也不能修改下载源。规则包只能由开发者内置，或发布到应用固定的 HTTPS 索引后由客户端下载。下载器限制文件大小，在流式写入临时文件的同时计算 SHA-256，以严格的 32 字节摘要和常量时间比较核对索引，再验证 manifest ID、版本和完整规则契约；任一环节失败都不会替换已安装版本。若已登记下载包在后续加载时无法通过完整语义契约，应用会将其隔离为 `.rejected`、撤销登记，并回退同 ID 的内置包。远程包不能执行脚本、Kotlin 类或任意代码。

开发者制作与发布规则包前，请阅读 [RULESET_AUTHORING.md](RULESET_AUTHORING.md)。本地问题清单与代理工作说明属于开发环境文件，不纳入产品仓库。

## 开发约定

- Kotlin 使用四空格缩进和官方命名风格。
- 业务状态放在领域层或 ViewModel，不在 Composable 中重复维护。
- 规则变化应补充 JVM 契约测试；Room 或 Compose 行为放入 `androidTest`。
- 不提交 API Key、`local.properties`、IDE 私有状态、APK 或构建产物。

## 已知限制

规则包索引目前使用 HTTPS 与 SHA-256，但尚未实现开发者私钥签名；敌人属性与先攻已改由规则包可信档案提供，友方目标和非 HP 条件效果仍在后续计划中。该项目当前处于积极开发阶段，发布前应在 Android Studio 完整执行 JVM 与设备测试。
