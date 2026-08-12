# 开发者规则包制作与发布指南

## 1. 能力边界

本文仅供应用开发者与受信任的规则包维护者使用。最终用户不能在客户端新建、编辑、导入或指定来源安装规则包；客户端只读取随应用发布的内置包，或从应用内固定的开发者服务器索引下载经过校验的包。

规则包是一个声明式 JSON 文件，可定义角色初始属性、创卡字段、普通检定策略、战斗先攻/回合资源、LLM 提示词和动态 AST。下载规则包不能执行任意 Kotlin、脚本或类名；`lifePolicy` 与 `localActionHandler` 只接受应用内白名单 `NONE`、`DND_5E`。新系统应优先使用 `NONE` 与通用 AST；需要新的死亡、武器、法术或持续效果算法时，必须先在应用源码中注册受信任实现。

当前创卡页仍对 `dnd_5e`、`coc_7e` 有少量专用购点和随机生成逻辑。其他规则包可可靠使用 `creationSchema` 的线性 `point_buy`、`dice_roll`、`string`、`dropdown`，但不要期待 D&D 阶梯购点或 CoC 专用随机属性自动套用。

## 2. 文件与 ID

内置规则包放在 `app/src/main/assets/rulesets/<id>.json`。文件名、根字段 `id`、剧本 `systemId` 必须完全一致，例如：

```text
app/src/main/assets/rulesets/my_system.json
                         ↕
"id": "my_system"
```

应用会加载内置包以及由受控下载器登记的安装包；不会扫描或接纳用户自行放入沙盒目录的任意 JSON。无效 JSON、引用缺失或 ID 不一致的文件不会出现在新建冒险页面。修改已加载文件后需清除应用进程或调用 `RulesetRegistry.evictCache(id)`。

## 3. 最小模板

```json
{
  "id": "my_system",
  "name": "我的规则",
  "description": "1d20 通用检定示例",
  "version": "1.0.0",
  "checkRules": {
    "targetSource": "EVENT",
    "modifierSource": "EVENT",
    "equipmentBonusAppliesTo": "MODIFIER",
    "equipmentBonusActionIds": ["my_check"],
    "defaultActionId": "my_check",
    "requiredTargetActionIds": ["my_check"],
    "statAliases": { "agility": ["敏捷", "agility"] },
    "targetLabel": "难度"
  },
  "combatRules": {
    "lifePolicy": "NONE",
    "localActionHandler": "NONE",
    "defeatAtZeroHp": false
  },
  "systemPromptInjection": {
    "prompt": "需要检定时返回 game_events 中的 require_roll，action_id 使用 my_check，并提供 expression、threshold、stat_id 和 reason。"
  },
  "characterTemplate": {
    "defaultStats": { "hp": "10", "max_hp": "10", "agility": "0" }
  },
  "uiBlueprint": {
    "dicePanelType": "GENERIC",
    "localizationTermMap": { "agility": "敏捷" }
  },
  "creationSchema": {
    "fields": [
      { "type": "string", "id": "name", "label": "姓名" },
      { "type": "point_buy", "id": "agility", "label": "敏捷", "min": 0, "max": 5 }
    ]
  },
  "mechanicsPipeline": {
    "entryNodeId": "roll",
    "nodes": {
      "roll": {
        "type": "roll", "diceFormula": "1d20",
        "outputVariable": "raw", "nextNodeId": "check"
      },
      "check": {
        "type": "condition",
        "leftOperandSource": "variable:raw", "operator": ">=",
        "rightOperandSource": "intent:dc",
        "trueNodeId": "success", "falseNodeId": "failure"
      },
      "success": {
        "type": "effect", "targetType": "result_state",
        "targetKey": "state", "operation": "set",
        "valueSource": "constant:SUCCESS"
      },
      "failure": {
        "type": "effect", "targetType": "result_state",
        "targetKey": "state", "operation": "set",
        "valueSource": "constant:FAILURE"
      }
    }
  }
}
```

也可复制 `app/src/main/assets/rulesets/example_ruleset.json` 后修改。

## 4. 检定与 AST

`checkRules` 支持：

- `targetSource`: `EVENT` 或 `STAT_VALUE`。
- `modifierSource`: `EVENT`、`ABILITY_MODIFIER`、`NONE`。
- `equipmentBonusAppliesTo`: `MODIFIER`、`TARGET`、`NONE`。
- `equipmentBonusActionIds`: 允许使用通用装备整数加值的动作；建议显式填写。

AST 节点类型为 `roll`、`switch`、`condition`、`math`、`effect`、`rest`、`consume_resource`、`death_save`、`targeted_attack`。数值来源使用 `constant:<值>`、`variable:<变量>`、`stat:<属性键>`、`intent:<参数>`。数学运算符为 `+ - * /`；条件支持数值 `>= > <= < == !=`，字符串只支持 `== !=`。流水线必须以明确的 `ResultState` 结束：`SUCCESS`、`FAILURE`、`CRITICAL_SUCCESS`、`CRITICAL_FAILURE`、`REGULAR_SUCCESS`、`HARD_SUCCESS` 或 `EXTREME_SUCCESS`。

一个 `DiceSubmission` 只能被一个掷骰节点消费。需要两阶段或多次掷骰的自定义系统，目前应拆成多个 `require_roll` 动作，或新增受信任本地处理器。

## 5. 战斗与多目标

`combatRules` 可声明 `initiative`、`turnResources`、`turnResourceLabels`、`actionCosts`、`actionTimings`、`primaryActionResource`、`lifePolicy`、`localActionHandler` 和 `defeatAtZeroHp`。`actionTimings` 只接受 `ANY`、`PARTICIPANT_TURN`。

D&D 本地武器/法术档案支持以下目标模式：

- `targeting=SINGLE`：默认单体。
- `targeting=MULTIPLE`：玩家多选，可配 `max_targets=3`。
- `targeting=ALL_ENEMIES`：当前全部存活敌人。
- `targeting=SELF`：当前仅用于治疗法术。

多目标动作逐目标完成命中或豁免骰，最后一次性写入全部目标 HP；法术位和行动资源只扣一次。任一目标在提交前失效，整个批量写回取消。此能力属于内置 `DND_5E` 处理器，不是通用 AST 的自动能力。

受信任的内置法术档案还可使用 `effect_name`、`effect_timing`、`effect_operation`、`effect_amount`、`effect_duration`、`effect_stack_policy` 和 `effect_stack_key` 声明固定持续效果。时机仅接受 `TURN_START/TURN_END`，操作仅接受 `DAMAGE/HEAL`，堆叠策略为 `REPLACE/REFRESH/STACK`。这些字段由应用本地白名单处理器解释；远程包不能携带脚本或任意执行代码。

## 6. 远程发布

远程索引是 `RemotePluginIndex`：

```json
{
  "plugins": [{
    "id": "my_system",
    "type": "RULESET",
    "name": "我的规则",
    "description": "说明",
    "version": "1.0.0",
    "downloadUrl": "https://example.com/my_system.json",
    "sha256": "文件 SHA-256（64 位十六进制）"
  }]
}
```

索引与包地址必须使用 HTTPS，版本必须使用 `MAJOR.MINOR.PATCH`。客户端下载后会限制大小、核对 SHA-256、解析完整 manifest、检查 ID 和版本，再以可恢复方式替换并登记为受控安装。下载文件保存在应用私有目录并覆盖同 ID 的内置版本；客户端不提供本地导入入口。

SHA-256 只能证明内容与索引一致，不能在索引源本身被篡改时证明发布者身份。生产发布仍应为索引增加开发者私钥签名，并在客户端内置公钥验证；完成签名校验前，不要将固定索引交给不受控制的第三方托管。

## 7. 验证清单

1. 用 JSON 工具确认语法，并确保文件名等于 `id`。
2. 调用 `RulesetProvider.parseManifestDetailed(json)`；逐项处理返回的 `RuleError`。
3. 为 AST 添加 JVM 测试，至少覆盖成功、失败、缺属性、缺参数和非法骰式。
4. 为内置包在 `RulesetAssetContractTest` 中直接加载 asset。
5. 在 Android Studio 的 Debug 构建中新建冒险，检查规则包可见、创卡可保存、普通检定采用同一骰点。
6. 若使用战斗，验证恢复存档后的先攻、回合资源与目标状态。

当前解析器会忽略未知 JSON 字段，因此字段拼写错误可能不会直接报错；必须使用契约测试确认关键配置实际生效。
