# 业务系统接入指南（MCP 连接器）

> 目标：任何业务系统（OA/HR/ERP/工单…）接入本平台 = 部署一个 MCP server + 技能中心注册 + 授权，核心代码零改动。
> 代码模板：`rag-service` 内置演示服务 `DemoMcpServerConfig.java` + `DemoMcpData.java`（复制改造即可）。

## 五步接入清单

| 步骤 | 做什么 | 参考 |
|---|---|---|
| 1 | 写 MCP server：把业务系统接口包装成工具（查员工/查制度/查流程…），独立部署 | 模板 + 下方开发要求 |
| 2 | 平台注册技能：技能中心 → 新建（toolType=mcp，mcpConfig 填地址） | `demo_mcp` 技能 |
| 3 | 授权：技能中心 → 勾选给目标数字员工 | 公司助手授权 demo_mcp |
| 4 | 测试：对话页/飞书里问业务问题，确认工具被调用 | 工具事件可见 |
| 5 | 审计：audit_event endpoint=chat/feishu 里按用户/员工追溯 | 审计看板 |

## MCP server 开发要求

1. **协议**：MCP over SSE（官方 SDK，Java `io.modelcontextprotocol` 或 Python `mcp`）；地址形如 `http://<host>:<port>/mcp/<code>/sse`
2. **工具命名**：小写下划线动词（`employee_lookup` / `policy_search`），描述写清"什么场景调用"——LLM 靠描述决定何时调用
3. **返回格式**：文本优先（结构化数据拼成可读文本），LLM 直接可用
4. **鉴权**：内网可先信任，正式环境加 Bearer token（mcpConfig 里可带）
5. **软依赖**：连接失败不影响主链路——平台侧有冷却重试 + 工具自动从 LLM 视野消失
6. **数据边界**：只暴露该暴露的字段（如员工：姓名/部门/职位/入职日期，不含薪资）

## mcpConfig 格式（技能中心填写）

```json
{
  "baseUrl": "http://oa-server:8090",
  "sseEndpoint": "/mcp/oa/sse"
}
```

- 可选项：`toolNames` 白名单（逗号分隔，空=全部工具）
- 私有化/换系统：只改 baseUrl，平台零改动

## 一个最小 MCP server 的样子（接 OA 时填这里）

```
工具1: employee_lookup(姓名) → 调 OA 查员工接口 → "张伟 | 研发部 | 高级工程师"
工具2: policy_search(关键词) → 调 OA 制度库接口 → 制度条款文本
工具3: process_status(流程号) → 调 OA 流程接口 → "审批中（当前节点：部门经理）"
```

数据源函数换成真实接口（HTTP 调用 + 鉴权），其余结构照模板不动。
