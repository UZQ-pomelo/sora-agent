# 安全加固 · 验收清单

本次安全加固的验收方式：**纯单元测试 + MockMvc 认证测试 + 手工 curl 验证**。
单元测试全部离线可跑（不依赖 Spring 上下文、不依赖外部服务）。

## 1. 自动测试

```bash
# 需要 JDK 21+ 与 Maven。仓库已带 mvnw（若缺失可改用全局 mvn）
./mvnw test -Dtest="com.sora.sora_agent.security.*" -pl .
```

| 测试 | 验证内容 |
|---|---|
| `PathSafetyTest` | 路径穿越/绝对路径/盘符/前导斜杠/反斜杠穿越被拒，正常文件名放行 |
| `UrlSafetyTest` | http/https 放行；ftp/file 协议、私网段、回环、云元数据(169.254.169.254)、IPv6 回环被拒；重定向复验 |
| `CommandGuardTest` | 空白名单放行；前缀白名单边界（`git` 不匹配 `gitty`）；未授权命令被拒 |
| `RateLimiterTest` | 窗口内放行、超限拒绝、key 独立、窗口过期、clear 重置 |
| `ApiKeyAuthFilterTest` | 缺失/错误 key→401；正确 key→200；限流→429；OPTIONS 放行；未保护路径不拦截；disabled 跳过 |

> 若 `./mvnw` 缺失：`mvn test` 亦可。测试仅依赖 `spring-boot-starter-test`，无需数据库/外部服务。

## 2. 手工验证（起服务后）

配置 `application-local.yml`：`app.security.api-keys` 至少一个 key（记为 `<KEY>`），
`app.security.tools.terminal.enabled: true`（验证终端守卫），其余工具按需开启。

### 2.1 认证
```bash
BASE=http://localhost:8080/api
# 无 key → 401
curl -i "$BASE/ai/models"
# 错误 key → 401
curl -i -H "X-API-Key: wrong" "$BASE/ai/models"
# 正确 key → 200
curl -i -H "X-API-Key: <KEY>" "$BASE/ai/models"
# 静态资源（同源前端入口）无需 key → 200/非401
curl -i -o /dev/null -w "%{http_code}" http://localhost:8080/api/index.html
```

### 2.2 限流（将 per-key-per-minute 临时调成 2）
```bash
for i in 1 2 3; do curl -s -o /dev/null -w "%{http_code}\n" -H "X-API-Key: <KEY>" "$BASE/ai/models"; done
# 期望输出：200 200 429
```

### 2.3 路径穿越（开启 file-read 后，经 agent 对话诱导模型读文件，或直接调用工具方法）
```bash
# 期望：工具返回「路径越界/被拒绝」，不返回任何 host 文件内容
```

### 2.4 SSRF（开启 scrape 后）
```bash
# 期望：抓取内网地址被拒（返回「禁止访问内网/保留地址」）
# 可让模型尝试抓取 http://169.254.169.254/latest/meta-data/ 或 http://127.0.0.1:8080/api/models
```

### 2.5 终端资源守卫（开启 terminal 后）
```bash
# 配置 allow-commands: ["git"] 后，命令 `dir` 被拒（返回白名单提示）
# 超时验证：命令 `ping -t 8.8.8.8` 在 30s 后被强制终止并提示超时
```

### 2.6 图片代理收窄
```bash
# 白名单外的域名 → 403
curl -i -H "X-API-Key: <KEY>" "$BASE/image/proxy?url=https://example.com/x.png"
# file 协议 → 400
curl -i -H "X-API-Key: <KEY>" "$BASE/image/proxy?url=file:///C:/Windows/win.ini"
# 白名单内（真实 DashScope 图片 URL）→ 200 且 Content-Type 为 image/*
```

### 2.7 前端安全
- 助手消息里的链接自动 `target="_blank"` + `rel="noopener noreferrer"`（查看 DOM 确认）。
- 非 localhost/https 部署下点击「复制」仍可用（降级 execCommand）。

## 3. 部署提醒

- `app.security.api-keys` 必配，否则应用拒启（fail-fast 是特性）。
- `app.image.base-url` 改为实际部署域名，否则生图代理链接指向 localhost。
- 生产前端同源部署时，`app.security.cors.allowed-origins` 需加实际来源，或由反向代理统一注入 `X-API-Key`。
- 危险工具保持默认关闭；确需开启时逐个评估风险并配合限流阈值。
- 已知局限：SSRF 校验存在 DNS rebinding 的 TOCTOU 缝隙（见 `UrlSafety` 类注释）；本方案为框架场景的务实水位。
