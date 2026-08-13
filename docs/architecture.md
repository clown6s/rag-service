# 内网智能体平台架构方案（WorkBuddy 内网版）

> 状态：基线 v1.1（2026-08-13）
> 范围：rag-service（本仓库）独立演进为平台；cb-ai-data-analysis 仅作设计参考，不纳入本方案资产与部署
> 约定：后续架构决策以此文档为坐标，重大变更需更新本文档

## 1. 产品定位与约束

内网私有化的企业 AI 智能体平台（对标腾讯云 WorkBuddy Enterprise 的私有化形态）：
数字员工 + 知识库 + 技能体系 + 管理后台。

**硬约束：**

- **数据不出域**：外部 API（DeepSeek / 硅基流动）逐步被本地模型替代；模型网关、推理均在域内
- **合规审计**：审计日志、权限隔离是上线门槛，不是可选项
- **团队与资产**：Java 为主（本仓库存量），Python 只承担生态决定性差距的能力
- **扩展性**：接模型 / 技能 / 业务系统 / 数字员工 / Python 能力，均不碰核心代码

## 2. 总体分层

```
┌──────────────────────────────────────────────────────────────┐
│ 接入层：Web 工作台 · API · 企微/钉钉/OA 集成 · MCP Server(对外)  │
├──────────────────────────────────────────────────────────────┤
│ 平台层（Java，内网，rag-service 内）                            │
│  ├─ 管理面：组织/授权/审计聚合/用量统计/数字员工生命周期          │
│  ├─ 编排引擎：路由(R1) → 状态机/工作流 → Agent工具循环（三层共存）│
│  ├─ 技能注册中心：skill = SKILL.md + 工具清单，版本化+授权        │
│  ├─ MCP 汇聚：统一 client 接业务系统                            │
│  └─ 会话/记忆：统一 ChatMemory + 长期记忆，跨数字员工共享         │
├──────────────────────────────────────────────────────────────┤
│ 能力层（Java，rag-service 内）                                 │
│  ├─ 知识底座：摄入/混合检索/GraphRAG（现状）                    │
│  ├─ 分析/文档等重型能力（未来以 MCP server 接入，不并入单体）     │
│  └─ 业务系统连接器：OA/邮件/工单（MCP server 各出一份）           │
├──────────────────────────────────────────────────────────────┤
│ AI 原子能力层（Python ai-sidecar，可横向多实例）                 │
│  ├─ 文档解析(/parse)：PyMuPDF 文本 + 扫描件检测（已接入）          │
│  ├─ embedding/rerank（本地 BGE）· 评测(Ragas) · 模型服务(vLLM)   │
│  └─ 未来：DeepDoc OCR/表格/布局 · 语音 · 前沿编排实验             │
├──────────────────────────────────────────────────────────────┤
│ 数据层：Milvus · Neo4j · MinIO · MySQL(业务库,平台化引入) · Redis│
└──────────────────────────────────────────────────────────────┘
```

## 3. 四根可扩展柱子

### 3.1 统一模型网关（模型的扩展点）

目标：model-agnostic。Java/Python 侧永远只对着网关一个 base-url（OpenAI 兼容协议），
所有模型统一注册为网关"渠道"。

**现状**：私有底座（8686）= vLLM 部署 Qwen，已输出 OpenAI 兼容 API；DeepSeek、硅基流动同为
OpenAI 兼容。协议层已统一，网关接入零适配。

**网关职责**：多模型注册、按任务路由（V3 对话 / R1 推理 / 小模型改写）、故障 fallback、配额、调用日志。

**定位**：协议统一后网关是"按需"组件——平台侧审计才是合规硬门槛（见 4.2，必须自建，网关替代不了）。
近期策略：Java 侧直连 vLLM(8686) + DeepSeek 两个 base-url（配置项切换）；模型数 >3 或多租户配额需求出现时再上网关。

**选型（2026-08-13 调研，按需启用）：New-API**（One-API 的活跃 fork）

| 要点 | 结论 |
|---|---|
| 为什么不是 One-API | New-API 是其后继活跃分支，DB 兼容；数据看板/调用统计明显更强；支持 rerank、embeddings 等渠道（embedding/rerank 流量可一并走网关，Java 侧只改 base-url） |
| 多租户 | 令牌分组、渠道加权、故障自动禁用，适合按部署单位隔离 |
| 协议风险 | AGPL-3.0；内网自用不对外分发，无开源义务 |
| 部署 | Docker 单容器 + SQLite（可换 MySQL），内网无外网依赖；**默认 root/123456 必须立即修改** |
| 短板 | 无原生告警；审计需自建补齐（见 4.2）；SLA 靠运维自己保证 |

**迁移路径**（启用网关后）：注册 vLLM(8686) / DeepSeek / 硅基流动为渠道，rag-service 改 base-url
接入 → ai-sidecar 能力成熟后注册为本地渠道（数据不出域的最后一块）。

### 3.2 技能规范（能力的扩展点）

```
一个技能 = 一个目录
├── SKILL.md      # 描述 + SOP + prompt 模板（纯文本，不绑框架）
└── tools/        # 工具清单（@Tool / MCP / SQL / Python 均可）
```

- **注册中心**：上传、版本化、按数字员工授权（技能可见范围 = 岗位）、热加载
- **现状迁移**：从 rag-service 的 `@Tool` 工具开始收敛到技能包格式；
  参考案例（cb 的 XSkill，14 技能 + 工厂）证明该格式可行，其被注释的 DAG 是"编排做重"的反例
- **扩展收益**：新技能 = 上传技能包 + 授权，核心代码零改动

### 3.3 MCP 连接器（业务系统的扩展点）

- **MCP 为唯一连接器标准**：每个业务系统出一个 MCP server（内部小服务，Java/Python 均可），
  平台侧统一 MCP client 汇聚，统一鉴权、统一审计
- **规则**：新业务能力默认做成 MCP server，**不再新增 Maven 模块**（参考案例 cb 的 31 模块耦合教训）
- **客户端**：LangChain4j MCP client，协议对齐 OpenAI 兼容

### 3.4 编排三层（流程的扩展点）

编排在 Java（判据：工具在哪，编排就在哪）。三层按场景复杂度选择，共享同一套技能/工具注册表：

| 层 | 机制 | 适用场景 | 新增流程代价 |
|---|---|---|---|
| L1 固定流水线 | 现有 `RetrievalPipeline` | 标准问答，高频简单 | 改代码 |
| L2 状态机/工作流图 | 轻量状态机（自研，节点少 ~100 行）；复杂后再评估 spring-ai-alibaba-graph-core 单模块 | 复杂 SOP（多步审批式流程） | 画图/配置 |
| L3 Agent 工具循环 | LangChain4j AiServices | 开放决策（数字员工主形态） | 配工具集 |

- L2 节点可调 L3 工具，L3 agent 可触发 L2 工作流；新数字员工 = 选编排层 + 组合技能 + 绑定知识库
- 需 LangGraph 级特性（checkpoint/中断恢复）时才迁 Python，且接口粒度 = 整任务（粗粒度 HTTP）

## 4. 支撑面

### 4.1 统一会话记忆

- conversationId 维度 ChatMemory（Redis 持久化），替代 rag-service 当前前端传 history 的方案
- 长期记忆：用户偏好、常用上下文
- 跨数字员工共享上下文（对应 WorkBuddy 的"项目"空间）

### 4.2 审计治理（合规上线门槛）

- **全链路 traceId**：Java → 网关 → 模型 / sidecar，各层日志带同一 traceId
- **审计日志**：问答内容、工具调用记录、数据访问记录、Token 用量统计
- **网关短板自建**：New-API 提供基础看板；告警（渠道失败率、配额耗尽、降级事件）自建
- **降级可视化**：Reranker/网关 fallback 发生时必须打 WARN + 指标（当前已有日志，缺指标）
- **安全整改**：`application.yml` 中 Neo4j 密码硬编码（`application.yml:75`）改为 `${NEO4J_PASSWORD}` 并轮换
- **落地进度（2026-08-13）**：已实现 traceId 过滤器（`TraceIdFilter`，X-Trace-Id 透传 + MDC）与审计事件落盘（`AuditService` → `logs/audit/audit-YYYY-MM-DD.jsonl`，异步写入不阻塞链路，事件含 query/来源/检索耗时/rerank 降级标记/回答预览）；审计聚合入库、看板、告警待建（阶段 2，需先引入业务库）
- **Agent 可靠性（2026-08-13）**：rerank 双通道落地（ai-sidecar 主通道 + 硅基流动降级，主通道挂掉自动切换）；Agent 确定性兜底——检索被调用且最高相关度低于 `rag.agent.min-relevance-score`（默认 0.3）时，固定输出"根据现有资料，暂无法回答该问题"，不信任 LLM 输出（提示词约束实测不可靠）

## 5. 单体演进与部署形态

- **单体承载，包边界即模块边界**：rag-service 作为唯一代码资产演进为平台，按包组织模块
  （agent / audit / skill / retrieval / graph / ingestion / web），暂不拆微服务
- **业务库引入（平台化的前提）**：技能注册、数字员工配置、审计聚合需要关系型存储 →
  引入 MySQL + MyBatis-Plus（与 LangChain4j 无冲突）；职责与 Milvus（向量）/ Neo4j（图）严格分离
- **重型能力不入单体**：数据分析（NL2SQL/沙箱）、公文处理等未来以 MCP server 接入（3.3）
- **部署**：rag-service 的 docker-compose 承载 Milvus / Neo4j；**ai-sidecar 已独立为单独仓库**（`D:\project\ai-sidecar`，自带 compose）独立部署，rag-service 通过 `http://<sidecar-host>:8081/rerank` 调用（未部署时 rerank 自动降级，软依赖）
- **ai-sidecar 横向扩展**：无状态（权重走共享 volume），多实例可挂简单负载均衡

## 6. 演进路线

| 阶段 | 产出 | 扩展性收益 | 状态 |
|---|---|---|---|
| 1 | rag-service agent 化（R1 路由 + 状态机 + 工具）+ ai-sidecar | 知识数字员工最小闭环 | ✅ 完成（非流式 + SSE 流式，route/tool 事件透传） |
| 2 | 审计聚合/用量 + 业务库引入 + 直连 vLLM/DeepSeek；网关按需引入 | 可审计、可切换 | 🚧 审计底座已落地，聚合/看板待建 |
| 3 | 技能规范落地（注册中心 + 存量 @Tool 收敛）+ MCP 试点接一个业务系统 | 能力可插拔 | 待启动 |
| 4 | 数字员工生命周期（配置→发布→运行→治理）+ 管理后台 | 平台化成型 | 待启动 |
| 5 | 多数字员工协作 + 上下文飞轮（答案回流知识库） | 超级团队 | 待启动 |

## 7. 扩展性验收清单

每一条都要求**不碰核心代码**：

1. 接一个新模型 → 只改网关配置 ✓
2. 加一个新技能 → 上传技能包 + 授权 ✓
3. 接一个新业务系统 → 部署 MCP server + 授权 ✓
4. 新建一个数字员工 → 后台组合技能/知识库/工具 ✓
5. 加一个 Python 能力 → sidecar 加 endpoint + Java 薄客户端 ✓

## 8. 技术选型记录

| 组件 | 选型 | 说明 |
|---|---|---|
| 知识底座 | Java 21 / LangChain4j 1.17.2 / Milvus 2.5 / Neo4j 5 | 现状，混合检索 + GraphRAG |
| 业务库 | MySQL + MyBatis-Plus（平台化阶段引入） | 技能/数字员工配置/审计聚合 |
| 模型网关 | New-API（备选 One-API），按需启用 | OpenAI 兼容，内网自建 |
| 模型服务 | vLLM + Qwen（私有底座 8686） | OpenAI 兼容 API，Java 直连 HTTP |
| AI 边车 | Python 3.11 / FastAPI / PyMuPDF / sentence-transformers / Ragas | ai-sidecar（独立仓库）：/rerank（双通道降级）、/parse（PDF 解析，DeepDoc 视觉任务待部署） |
| 连接器 | MCP（LangChain4j MCP client） | 业务系统接入唯一标准 |
| 会话 | Redis + ChatMemory（待引入） | 跨数字员工共享 |
| 异步 | 自研队列（审计已用）；RocketMQ 按需引入 | 评测、批量任务 |

**设计参考（不纳入本平台资产）**：cb-ai-data-analysis（Spring AI 数据分析平台）——状态图编排、
技能工厂、Python 沙箱三模式值得借鉴；其 31 模块耦合、46 套 yml、被注释的 DAG 是反例。
