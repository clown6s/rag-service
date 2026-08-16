# WorkBuddy 内网版 — 运行手册

> 最后更新：2026-08-15。当前为单机开发/试运行环境（Windows 11 开发机 + 10.211.55.5 基础设施机）。

## 一、系统组成

| 组件 | 位置 | 端口 | 说明 |
|---|---|---|---|
| rag-service | D:\project\rag-service | 8080 | 主后端（Spring Boot 3.4 / Java 21），RAG + Agent + 飞书桥 + 管理 API |
| rag-admin | D:\project\rag-admin | 5174 | 管理后台前端（Vite + React + MUI），11 个功能页 |
| ai-sidecar | D:\project\ai-sidecar | 8081 | Python 原子能力：/parse 解析（含扫描件 OCR）、/rerank、/evaluate、LLM 代理 |
| oa-approval | D:\project\oa-approval | 7000 | 真实 OA 审批系统（Spring Boot），请假/报销全流程 |
| 基础设施 | 10.211.55.5 | - | MySQL 8.4(3306) / Milvus(19530) / Neo4j(7687) / Redis(6379) |

飞书机器人：应用 cli_aaf54f0fcf78dbd1，**长连接模式**（出站 WS 收事件，不需要公网回调地址）。
模型：通义 qwen-plus（主力）/ qwen-turbo（闲聊澄清）/ deepseek-reasoner（意图路由），经 ai-sidecar 代理 + 限流。

## 二、启动步骤（按顺序）

### 1. 基础设施（10.211.55.5，通常已在跑）

MySQL / Milvus / Neo4j / Redis 四件套常驻。Milvus 走 rag-service/docker-compose.yml（etcd+minio+milvus）。
检查：`netstat -ano | findstr "19530 7687 6379"`（在基础设施机上），或直接看 rag-service 启动日志有没有报连接失败。

### 2. OA 审批服务

```bash
cd /d/project/oa-approval
# 已打包则直接跑 jar；改动后用 mvn 重新打包
java -jar target/*.jar          # 端口 7000，账号 admin/123456
```

### 3. ai-sidecar

```bash
cd /d/project/ai-sidecar
# 本机直接跑（依赖已装）：
uvicorn main:app --host 127.0.0.1 --port 8081
# 或 docker compose up -d
```

### 4. 主后端 rag-service

```bash
cd /d/project/rag-service
mvn -q package -DskipTests
java -jar target/rag-service-1.0.0-SNAPSHOT.jar     # 前台跑；后台: (java -jar ... > /tmp/rag-service.log 2>&1 &)
```

配置在 `.env`（启动时注入）：
- 模型 key、飞书 app_id/secret、MYSQL_ROOT_PASSWORD（含特殊字符 `nfg!om,j^...`，**部署前必须改纯字母数字**）
- OA_ENABLED=true / OA_BASE_URL=http://localhost:7000 / OA_USERNAME=admin / OA_PASSWORD=123456
- MILVUS_HOST / MYSQL_HOST / REDIS_HOST = 10.211.55.5

启动成功标志（日志）：`[飞书] 长连接就绪 domain=https://open.feishu.cn`、`[MySQL] 业务库已连接`、`[Neo4j] 连接成功`、`[Milvus] collection 已存在`。

### 5. 管理后台前端

```bash
cd /d/project/rag-admin
npm run dev        # http://localhost:5174
```

登录：**admin / Admin@2026**。
注意：后端每次重启，内存里的登录 token 全部失效 → 浏览器要重新登录。

## 三、日常操作

### 重启后端（改代码后）

```bash
# 按端口找 PID 精确杀（不要 taskkill //IM java.exe，会误杀 IntelliJ）
netstat -ano | grep LISTENING | grep ":8080 "
taskkill //PID <上面查到的PID> //F
mvn -q package -DskipTests && java -jar target/rag-service-1.0.0-SNAPSHOT.jar
```

顺序必须「先杀后编」：Windows 下运行中的 JVM 锁住 jar 文件，
服务活着时 `mvn package` 会报 PluginExecutionException（jar 无法覆盖）。

重启后两件事：重新登录后台拿 token；飞书长连接会自动重连（约 30 秒内）。

### 联调飞书（无需在飞书里真发消息）

飞书事件可以通过 HTTP 模拟，直接 POST 到 `/api/rag/bridge/feishu/event`（verification token 为空时不验签）：

- 文本消息：header.event_type=im.message.receive_v1，event.message 里给 chat_id/message_id/message_type/content
- 卡片回调：header.event_type=card.action.trigger，event.action.value + event.action.form_value
- 模拟用的 message_id 不真实 → 回复会失败（日志 WARN 99992354），但处理链路照常执行，用日志和审计验证

### 常用验证入口

| 验证什么 | 怎么做 |
|---|---|
| 后端健康 | 任意 API 返回 JSON 即活（无专门 /health） |
| 知识库检索 | `GET /api/rag/admin/debug/search?keyword=xx&limit=10`（BM25 粗查，需 admin token） |
| 审计留痕 | 后台「审计看板」页，或 logs/audit/audit-YYYY-MM-DD.jsonl 文件（实时） |
| 飞书链路 | 群里直接对话；日志 `/tmp/rag-service.log`（或前台窗口） |
| 控制台中文乱码 | Git Bash 是 GBK：中文测试一律用 UTF-8 文件 + PYTHONIOENCODING=utf-8，不要 inline 中文 |

## 四、功能地图（当前已完成）

**核心对话（飞书 + Web 对话测试页）**
- RAG 知识问答：混合检索（dense+BM25+rerank）+ 知识图谱 GraphRAG + 五层防编造（法名白名单/确定性预检索/术语核对/事实校验层/图谱收紧）
- 意图路由 + 主动追问澄清 + 会话记忆（Redis）+ 数字员工人设/知识范围 + 当前日期锚点（防年份编造）
- MCP 技能：内置演示 + 真实 OA（请假提交全链路：查员工→提交→回执单号）
- **请假 SOP 四步**：说「帮我请假，张三明天一天年假」→ 查员工→LLM 提取参数→确定性规则校验（姓名/时间/天数/事由）→提交 OA→回执单号；校验失败确定性拦截
- **经营数据图表**：问「最近7天经营数据」「王强最近7天报销」→ OA 查数 → 飞书图表卡片（VChart 折线/柱状图 + 数据表 + 来源注记）；回复「存入表格」写入飞书多维表格
- **图片 OCR**：飞书发图片 → 智谱 glm-4v-flash 识别文字 → 会话上下文问答/入库
- **统一身份 + 按人控权**：通讯录同步（open_id→角色 员工/总监/总经理，职位自动判定+人工覆盖）→ 桥接按角色绑定不同数字员工
- 员工目录确定性直查（入职时间/人数统计）
- SOP 工作流编排（L2 状态机）+ 答案审核流（高风险转人工）+ 部门级隔离

**文档能力**
- 一句话直达：说「写一个关于五一放假的通知」→ 直接生成 → 云文档直出（组织内可读 + 发起人可编辑）；节假日类自动整份注入官方放假安排（知识库《2026年节假日安排通知》）
- 云文档编辑闭环：打开云文档 → 群里回复修改要求 → 机器人读回云文档最新内容（含手工编辑）→ AI 改 → 写回同一份 → 回复「定稿」出最终版
- 表单卡片：说「帮我写文档」（无标题）→ 弹 input/select 表单（卡片 JSON 2.0 form 容器）
- 文档工作台 DocStudio（/studio）：弹窗表单 → 分栏编辑器（markdown 编辑 + 实时预览）→ AI 修改指令 → 导出云文档/docx/pdf/md/txt
- 对话向导（文字多轮：收集→确认→预览→定稿）；文档中心（生成/审核/修改/优化/历史）
- 飞书文件转发：转发文件 → 解析 → 会话内问答（30min）→ 「入库」进知识库 /「清除文件」

**运营闭环（后台 11 页）**
- 仪表盘（问答统计 + 模型用量成本）、审计看板（导出 Excel/CSV）、知识库健康度、评测中心、SOP 流程、答案审核、数字员工、技能管理、告警中心、知识库管理、文档中心 + 工作台
- 上下文飞轮：人工采纳答案回流知识库

## 五、已知注意点

1. `.env` 里 MySQL 密码含特殊字符——部署服务器前改纯字母数字（IDE 字面解析与 bash 行为不一致的根源）
2. 云文档「组织内可读」接口是 PATCH（POST 会 404）；编辑权限需单独加成员（perm=full_access）——均已封装在 FeishuClient
3. 飞书卡片回调 3 秒超时会重投：慢操作必须异步 + 按 messageId 去重（表单生成已处理）
4. 表单卡片是卡片 JSON 2.0（form 容器 + form_action_type=submit）；多行输入是 input+input_type=multiline_text（textarea 会被拒 11310）
5. 生成后编辑跳转的文档存档在内存（重启失效），上限 100 份——正式部署后换 MySQL/对象存储
6. 工作台（rag-admin）登录目前只有 admin 一个账号，普通员工用工作台需要统一身份打通（部署阶段做）

## 六、下一步（未完成）

1. **部署到服务器**：docker compose 就绪；部署前改 .env 密码、配好域名
2. 工作台统一身份（员工账号体系，飞书扫码/员工号登录）
3. 历史云文档编辑权限批量补齐（此前生成的文档只读）
4. 图片消息 OCR（sidecar 目前只做扫描 PDF 的 OCR）
