 # RAG Service - 企业级智能知识服务平台

 > 一个基于 RAG（检索增强生成）技术的企业级智能知识服务平台，提供数字员工、知识库检索、AI Agent 等核心能力。

 ---

 ## 📋 目录

 - [项目概述](#项目概述)
 - [核心功能](#核心功能)
 - [技术架构](#技术架构)
 - [快速开始](#快速开始)
 - [配置说明](#配置说明)
 - [开发指南](#开发指南)
 - [部署指南](#部署指南)
 - [API 文档](#api-文档)
 - [常见问题](#常见问题)

 ---

 ## 🎯 项目概述

 **RAG Service** 是一个面向企业内部使用的智能知识服务平台，旨在通过先进的 RAG 技术，为企业提供高效的数字员工、智能问答、知识管理等服务。

 ### 产品定位

 - **数据不出域**：所有数据存储在内网，确保企业信息安全
 - **合规审计**：完整的操作日志和权限管理，满足企业合规要求
 - **团队协作**：支持多数字员工、技能组合、工作流编排
 - **可扩展性**：支持接入多种大模型、技能工具、业务系统

 ---

 ## ✨ 核心功能

 ### 1. 知识库管理

 - **多格式文档解析**：支持 PDF、Word、Markdown、Excel、PPT 等多种格式
 - **智能分块与嵌入**：自动将文档切分为语义相关的段落，并生成向量嵌入
 - **混合检索**：结合向量检索和全文检索（BM25），提供更精准的搜索结果
 - **图增强检索（GraphRAG）**：通过知识图谱增强检索效果，支持实体关系查询

 ### 2. 智能问答

 - **多轮对话**：支持上下文记忆的连续对话
 - **答案溯源**：自动标注答案来源，支持查看原始文档片段
 - **事实验证**：对生成的答案进行事实性验证，减少幻觉

 ### 3. AI Agent（数字员工）

 - **意图路由**：根据用户问题自动选择合适的处理策略
 - **状态机**：复杂问题的多步骤解决流程
 - **工具调用**：支持调用外部工具和业务系统接口
 - **记忆管理**：跨会话的长期记忆能力

 ### 4. 管理功能

 - **用户管理**：支持数字员工配置、权限管理
 - **技能管理**：可插拔的技能系统，支持动态加载
 - **审计日志**：完整的操作记录和统计分析
 - **监控告警**：集成飞书机器人，实时监控服务状态

 ### 5. 集成能力

 - **飞书集成**：支持飞书机器人、事件订阅
 - **OA 系统集成**：支持流程审批、工单对接
 - **MCP 协议**：通过 Model Context Protocol 接入外部系统

 ---

 ## 🏗️ 技术架构

 ### 技术栈

 | 层级 | 技术 | 说明 |
 |------|------|------|
 | **后端框架** | Spring Boot 3.4.1 | Java 应用框架 |
 | **编程语言** | Java 21 | 现代 Java 语言 |
 | **AI 框架** | LangChain4j 1.17.2 | LLM 应用开发框架 |
 | **向量数据库** | Milvus 2.5 | 高性能向量存储与检索 |
 | **图数据库** | Neo4j 5 | 知识图谱存储 |
 | **关系数据库** | MySQL 8.4 | 业务数据存储 |
 | **缓存** | Redis | 会话记忆、缓存 |
 | **ORM 框架** | MyBatis-Plus 3.5.12 | 数据持久化 |

 ### 大模型支持

 | 能力 | 开发环境 | 生产环境 |
 |------|----------|----------|
 | 对话/Agent | 千问 DashScope | 本地 vLLM + Qwen |
 | 意图路由 | 千问 DashScope | vLLM + Qwen |
 | 文档摘要 | 千问 Turbo | vLLM + Qwen 小模型 |
 | Embedding | 硅基流动 BGE-M3 | 本地 BGE-M3 |

 ### 项目结构

 ```
 rag-service/
 ├── src/main/java/com/zzp/rag/
 │   ├── agent/          # Agent 相关（配置、工具、路由）
 │   ├── audit/          # 审计日志
 │   ├── auth/           # 认证授权
 │   ├── config/         # 配置类
 │   ├── feishu/         # 飞书集成
 │   ├── graph/          # 知识图谱
 │   ├── ingestion/      # 文档摄取与处理
 │   ├── mcp/            # MCP 协议支持
 │   ├── memory/         # 记忆管理
 │   ├── mysql/          # 数据库访问层
 │   ├── oa/             # OA 系统集成
 │   ├── retrieval/      # 检索引擎
 │   ├── skill/          # 技能管理
 │   ├── util/           # 工具类
 │   ├── web/            # Web 控制器
 │   └── workflow/       # 工作流引擎
 ├── src/main/resources/
 │   ├── application.yml          # 主配置文件
 │   ├── application-dev.yml      # 开发环境配置
 │   ├── application-prod.yml     # 生产环境配置
 │   └── schema-mysql.sql         # MySQL 数据库脚本
 ├── docs/               # 文档目录
 │   ├── architecture.md          # 架构文档
 │   └── biz-mcp-integration.md   # MCP 集成文档
 ├── data/               # 数据目录
 ├── logs/               # 日志目录
 └── deploy/             # 部署配置
 ```

 ---

 ## 🚀 快速开始

 ### 前置要求

 - JDK 21+
 - Maven 3.8+
 - Docker & Docker Compose
 - MySQL 8.4+
 - Redis 6.0+
 - Milvus 2.5+
 - Neo4j 5+（可选，启用 GraphRAG 时需要）

 ### 环境变量配置

 1. 复制环境变量模板：

 ```bash
 cp .env.example .env
 ```

 2. 编辑 `.env` 文件，填写必要的配置：

 ```env
 # LLM API 配置
 DASHSCOPE_API_KEY=your-api-key
 SILICONFLOW_API_KEY=your-api-key

 # 数据库配置
 MYSQL_HOST=127.0.0.1
 MYSQL_USERNAME=root
 MYSQL_ROOT_PASSWORD=your-password

 # Milvus 配置
 MILVUS_HOST=127.0.0.1
 MILVUS_PORT=19530

 # Neo4j 配置（可选）
 NEO4J_PASSWORD=your-password

 # Redis 配置
 REDIS_HOST=127.0.0.1
 REDIS_PORT=6379
 ```

 ### 启动依赖服务

 使用 Docker Compose 启动依赖服务：

 ```bash
 docker-compose up -d
 ```

 这将启动以下服务：
 - Milvus（向量数据库）
 - Neo4j（图数据库）
 - MySQL（业务数据库）
 - Redis（缓存）

 ### 启动应用

 1. 编译项目：

 ```bash
 mvn clean package -DskipTests
 ```

 2. 运行应用：

 ```bash
 java -jar target/rag-service-1.0.0-SNAPSHOT.jar
 ```

 或使用 Maven 直接运行：

 ```bash
 mvn spring-boot:run
 ```

 3. 访问应用：

 - 应用地址：http://localhost:8080
 - 健康检查：http://localhost:8080/actuator/health

 ---

 ## ⚙️ 配置说明

 ### 应用配置

 主配置文件 `application.yml` 包含以下主要配置项：

 ```yaml
 # 服务端口
 server:
   port: 8080

 # Spring 配置
 spring:
   application:
     name: rag-service

 # LLM 配置
 langchain4j:
   open-ai:
     deepseek:
       api-key: ${DASHSCOPE_API_KEY}
       base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
       chat-model:
         model-name: qwen-plus
         temperature: 0.3
         max-tokens: 4096

 # Embedding 配置
 embedding:
   base-url: https://api.siliconflow.cn/v1
   api-key: ${SILICONFLOW_API_KEY}
   model: BAAI/bge-m3
   dimension: 1024

 # Milvus 配置
 milvus:
   host: ${MILVUS_HOST:127.0.0.1}
   port: ${MILVUS_PORT:19530}
   database: rag_service
   collection: documents
   dimension: 1024

 # Neo4j 配置
 neo4j:
   uri: bolt://127.0.0.1:7687
   authentication:
     username: neo4j
     password: ${NEO4J_PASSWORD}

 # GraphRAG 配置
 graph:
   enabled: true
   async: true
   extract-concurrency: 12
   rate-limit-per-second: 20
 ```

 ### 环境变量

 完整的环境变量列表请参考 `.env.example` 文件。

 ---

 ## 💻 开发指南

 ### 代码规范

 - 遵循 Java 编码规范
 - 使用 Lombok 减少样板代码
 - 所有公开 API 添加 Javadoc 注释

 ### 测试

 运行单元测试：

 ```bash
 mvn test
 ```

 运行集成测试：

 ```bash
 mvn verify
 ```

 ### 添加新技能

 1. 在 `skill` 包下创建新的技能类
 2. 使用 `@Tool` 注解标记工具方法
 3. 实现权限控制逻辑
 4. 在数据库中注册技能配置

 ### 接入新业务系统

 1. 创建 MCP Server（推荐使用 Python）
 2. 实现 ToolProvider 接口
 3. 配置权限和访问控制
 4. 在平台中注册 MCP 连接

 ---

 ## 🚢 部署指南

 ### 生产环境配置

 1. 使用 `application-prod.yml` 配置文件
 2. 配置本地 vLLM 模型服务
 3. 启用所有安全配置（RBAC、审计、告警）
 4. 配置监控和日志收集

 ### Docker 部署

 构建镜像：

 ```bash
 docker build -t rag-service:latest .
 ```

 运行容器：

 ```bash
 docker run -d \
   --name rag-service \
   -p 8080:8080 \
   --env-file .env \
   rag-service:latest
 ```

 ### 健康检查

 应用提供以下健康检查端点：

 - `/actuator/health` - 应用健康状态
 - `/actuator/health/db` - 数据库连接状态
 - `/actuator/health/redis` - Redis 连接状态
 - `/actuator/health/milvus` - Milvus 连接状态

 ---

 ## 📚 API 文档

 主要 API 端点：

 ### 知识库管理

 - `POST /api/kb` - 创建知识库
 - `GET /api/kb` - 获取知识库列表
 - `GET /api/kb/{id}` - 获取知识库详情
 - `DELETE /api/kb/{id}` - 删除知识库

 ### 文档管理

 - `POST /api/doc/upload` - 上传文档
 - `GET /api/doc/{id}` - 获取文档信息
 - `DELETE /api/doc/{id}` - 删除文档

 ### 智能问答

 - `POST /api/rag/chat` - 发起对话
 - `POST /api/rag/chat/stream` - 流式对话

 ### Agent 聊天

 - `POST /api/agent/chat` - Agent 对话
 - `POST /api/agent/chat/stream` - Agent 流式对话

 ### 技能管理

 - `POST /api/skill` - 创建技能
 - `GET /api/skill` - 获取技能列表
 - `PUT /api/skill/{id}` - 更新技能
 - `DELETE /api/skill/{id}` - 删除技能

 ### 审计管理

 - `GET /api/audit` - 获取审计日志
 - `GET /api/audit/stats` - 获取统计数据

 ---

 ## ❓ 常见问题

 ### Q: 如何切换不同的 LLM？

 A: 修改 `application.yml` 中的 `langchain4j.open-ai.deepseek.base-url` 和 `api-key` 配置即可。

 ### Q: 如何启用 GraphRAG？

 A: 在配置文件中设置 `graph.enabled: true`，并确保 Neo4j 服务正常运行。

 ### Q: 文档上传失败怎么办？

 A: 检查以下项目：
 1. 文件大小是否超过 20MB 限制
 2. 文件格式是否在支持列表中
 3. Milvus 连接是否正常
 4. 查看日志获取详细错误信息

 ### Q: 如何配置飞书机器人？

 A: 在 `.env` 文件中配置以下变量：
 - `FEISHU_ENABLED=true`
 - `FEISHU_APP_ID`
 - `FEISHU_APP_SECRET`
 - `FEISHU_VERIFICATION_TOKEN`

 ### Q: 如何添加自定义技能？

 A: 参考 `skill` 包中的示例代码，创建新的工具类并使用 `@Tool` 注解标记方法。

 ---

 ## 📄 许可证

 内部使用，未经授权不得外传。

 ## 🤝 贡献

 如有问题或建议，请联系开发团队。

 ## 📞 联系方式

 - 技术支持：[support@example.com]
 - 项目文档：查看 `docs/` 目录下的详细文档

 ---

 **最后更新：2026-08-15**
