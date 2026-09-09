# 技能体系（Skill）

声明式能力包 + 加载机制：**往技能目录放一个 YAML，就能用**。

## 核心机制

```
skills/*.yaml（classpath 内置） + app.skill.dir（文件系统可扩展）
        │ SkillLoader 启动扫描
        ▼
Skill{ name, description, instruction, tools(建议), examples }
        │
① 能力清单注入 systemPrompt  →  模型知道有哪些技能（name: description）
② LLM 自主触发 useSkill(name) / 前端 /skill名 显式触发
        ▼
UseSkillTool 返回技能指令全文 → 经工具结果通道进上下文 → 模型下一轮按指南行动
```

**零引擎改动**：技能指令通过工具结果（ToolResponseMessage）进入上下文，不触碰
`ToolCallAgent` / `SoraManus` 的 ReAct 循环内核。

## 定义一个技能

在 `src/main/resources/skills/`（内置，随 jar）或 `app.skill.dir`（文件系统，扩展）
放一个 YAML 即可：

```yaml
name: web-researcher          # 唯一标识，useSkill("web-researcher")
description: 联网调研与信息整理   # 注入能力清单，帮模型判断何时用
instruction: |                 # 技能指南（多行），激活后注入上下文
  你正在执行「联网调研」技能。请严格遵循以下工作流：...
tools:                        # 建议工具（软指导，非硬隔离）
  - searchweb
  - webScraping
examples:                     # 可选，触发示例
  - "帮我调研一下XX的最新进展"
```

> 同名技能文件系统目录覆盖 classpath；单个文件解析失败仅告警跳过，不影响启动。

## 关键设计

| 项 | 说明 |
|---|---|
| 触发 | LLM 自主（useSkill 工具）+ 前端 `/skill名` 斜杠命令（显式） |
| 工具关系 | 软指导：skills 的 tools 是建议清单；实际可用性由全局注册 + `app.security.tools.*` 决定 |
| 能力清单 | SoraManus 启动时把 name+description 追加进 systemPrompt |
| 装载时机 | 启动时扫描一次（新增技能需重启）；`SkillLoader.reload()` 已预留，可接热加载 |

## 配置

```yaml
app:
  skill:
    enabled: true
    dir: ./skills    # 可选：文件系统技能目录
```

## 验证

### 自动测试（离线）
```bash
./mvnw test -Dtest="com.sora.sora_agent.skill.*"
```

### 手工验证
1. 起服务后打开 `/manus`，输入 `/skill web-researcher` → 前端把它转为
   「请激活并使用技能「web-researcher」…」→ 模型应调用 `useSkill` 并返回技能指南。
2. 直接提问「帮我调研 Spring AI 最新版本」→ 模型应自主判断匹配该技能并激活。
3. 在 `app.skill.dir` 指向的目录新增一个技能 YAML → 重启后出现在可用技能清单。
4. 前端查看助手回复，应包含「【技能已激活】web-researcher …」及其工作流。

## 边界

- 技能清单随 SoraManus 每次请求注入（agent 无状态）。
- TourApp（旅游助手）不接技能体系；`allTools` 中的 useSkill 工具存在但无技能清单引导，正常不触发。
- 热加载（运行中新增技能）预留 `reload()`，当前需重启。
