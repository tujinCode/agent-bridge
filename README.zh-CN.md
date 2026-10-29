<div align="center">

# agent-bridge

**给已有的 Spring Boot 服务加一个注解，让它成为受治理的 AI Agent 工具——不重写、不换权限体系。**

[![CI](https://github.com/tujinCode/agent-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/tujinCode/agent-bridge/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-17%2B-blue.svg)](https://adoptium.net)

[English](README.md) · [当前状态](#当前状态不粉饰) · [路线图](#路线图) · [贡献指南](CONTRIBUTING.md)

</div>

---

大多数"把系统接给大模型"的方案，都要求你把接口重写成工具：几百个端点、一份必然腐化的第二契约，外加一个拥有全部权限、出事查不到人的服务账号。

`agent-bridge` 反过来：**工具本来就在**。你在已有的方法上加一个注解，JSON Schema 从签名推导（不会漂移），调用者身份透传到已有的 `@PreAuthorize`（权限体系继续有效）。

## 为什么

| 常见做法 | 坏在哪 |
|---|---|
| 每个接口手写一份 tool schema | 两份契约必然漂移；改个参数名，模型开始静默传错值 |
| 在网关做 HTTP → MCP 转换 | 网关不知道"谁在调"，身份在转换点丢失，RBAC 与审计同时失效 |
| 给 Agent 单独开账号 | 审计里全是 `svc_agent_01`，出问题无法追责 |

再加上三个后端工程师都熟的坑：**重试导致重复下单**、**审计链断在三处**、**"查一下"和"删了它"没有区分**。

## 长什么样

```java
@AgentTool(name = "workorder.escalate",
           description = "提升工单优先级。已关闭的工单不可升级。",
           risk = RiskLevel.WRITE,
           scopes = "workorder:write")
public WorkOrder escalate(
        @AgentParam(description = "工单号", example = "WO-2001") String workOrderId,
        @AgentParam(description = "新优先级：1 最高，5 最低")
        @Min(1) @Max(5) int priority,
        ToolInvocationContext context) {   // 注入，模型看不见也伪造不了
    ...
}
```

模型拿到的是从这个签名自动生成的 schema：参数名、类型、必填、取值范围、描述全部来自代码本身。没有 YAML，没有适配类，没有第二份契约。

## 工作原理

```
Agent（Claude Desktop / Cursor / 自研）
   │  MCP over stdio（JSON-RPC 2.0）
   ▼
McpServer  ── 传输适配层，核心与协议无关
   ▼
ToolRegistry ─── 风险闸门 ─── 审计事件 ─── 结果渲染
   ▼
ToolInvoker ─── 参数绑定 ─── 身份注入
   ▼
ToolScanner + JsonSchemaBuilder   注解 → 契约
   ▼
已有的 Spring Boot 服务（一行未改）
```

核心模块**不依赖 Spring**，starter 只是其中一个适配器。

## 快速开始

```bash
git clone https://github.com/tujinCode/agent-bridge.git
cd agent-bridge && mvn install
java -jar agent-bridge-example-workorder/target/agent-bridge-example-workorder-0.1.0-SNAPSHOT.jar
```

在 `claude_desktop_config.json` 中加入：

```json
{
  "mcpServers": {
    "workorders": {
      "command": "java",
      "args": ["-jar", "/绝对路径/agent-bridge-example-workorder-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

重启客户端，然后问：

> C-1001 有哪些工单？把登录不上的那个升到优先级 1。

不想装客户端也能验证：

```bash
./scripts/smoke.sh
# PASS - 5 requests, 5 responses, risk gate enforced, logs kept off stdout
```

## 风险模型

| 等级 | 含义 | 默认行为 |
|---|---|---|
| `READ` | 只读 | 自动执行 |
| `WRITE` | 可回滚的写 | 天花板为 `WRITE` 时执行 |
| `DESTRUCTIVE` | 不可逆：删除、转账、扣款 | 必须显式抬高天花板 |

默认天花板是 `READ`。一个装完就能写生产数据的库，没人敢装。

## 配置项

```yaml
agent-bridge:
  enabled: true
  transport: stdio          # stdio | none
  server-name: workorder-tools
  server-version: 0.1.0
  max-auto-risk: WRITE      # READ | WRITE | DESTRUCTIVE
  include-packages: []      # 可选扫描过滤，空 = 全部 bean
```

## 两个必须知道的坑

1. **编译要带 `-parameters`**，否则参数名退化成 `arg0`，生成的 schema 毫无用处。Spring Boot 父 POM 默认开启。
2. **stdout 是协议通道**。stdio 模式下任何一句 `System.out.println` 都会打乱 JSON-RPC 流，客户端直接断连。日志必须走 stderr——示例里带了 `logback-spring.xml` 做这件事。

## 当前状态（不粉饰）

**能用**：注解声明工具、schema 自动推导、风险闸门、审计事件、stdio 传输、MCP 工具提示。

**还不能用**：

- **没有身份委托**。`ToolInvocationContext` 里带 callerId，但它不是从 token exchange 推出来的。在做到之前，这个 id 只能算"声称"，不能算"已验证"。
- **没有幂等**。重试一次 `tools/call`，业务就执行两次。别拿它接扣款或建单类接口。
- **没有审计落库**。每次调用给你一个 `ToolCallListener` 回调，写到哪是你的选择，不是库的事。
- **没有 HTTP 传输**。只有 stdio，一个客户端一个进程。
- **启动时扫描全部 bean**。大应用请用 `include-packages` 收敛范围。
- **不支持 `oneOf` / `anyOf`**。方法签名表达不了 schema 组合。

## 路线图

按"什么在阻塞生产接入"排序，不按"什么有意思"排序。

- **v0.5.0** —— 通过 Spring Security 做 on-behalf-of 身份委托，让已有的 `@PreAuthorize` 对 Agent 调用生效；`requestId` 去重；审计落库与查询；streamable HTTP。
- **v0.9.0** —— 多租户、工具版本化与弃用、单工具限流、响应脱敏、OpenTelemetry 指标。
- **v1.0.0** —— API 冻结、非 Spring 栈适配、文档站。

上面没有一个是带日期的。测试绿了功能才算落地，上一句成立了版本号才往前走。

## 贡献与许可

请先读 [CONTRIBUTING.md](CONTRIBUTING.md)，只有一条硬规则：*没有说清问题的功能，就是没人要的功能*。

**许可**：[Apache License 2.0](LICENSE)，版权归 Zhang Cong（2026），详见 [NOTICE](NOTICE)。
