# Palantir Foundry / AIP 风格本体平台实施计划

> 文档状态：已完成产品与架构讨论，作为新仓库当前实施基线。只有标记为“已确认”的决策进入实施范围；新增变更必须先更新本计划。
>
> 部署约束：单台 Linux 服务器，使用 Docker Compose，不使用 Kubernetes。
>
> 最后修订：2026-07-20。

## 0. 决策记录

| 编号 | 状态 | 决策 | 影响 |
|---|---|---|---|
| D-001 | 已确认 | 采用简化架构方案 A | 新主链路为 Flink → Pulsar → Java Projection Worker → HugeGraph/OpenSearch |
| D-002 | 已确认 | 继续使用 Docker Compose | 架构图中的 Kubernetes 不进入当前实现 |
| D-003 | 已确认 | 不引入 Nessie、Trino、SeaTunnel、Iceberg | 数据集成统一使用 Flink + Pulsar，业务事实进入 HugeGraph，检索投影进入 OpenSearch |
| D-004 | 已确认 | Postgres 仅作为控制面数据库 | 保存元数据、配置、权限、审计、任务和幂等记录，不保存业务对象正文 |
| D-005 | 已确认 | MinIO 保留 | 保存原始文件、导入暂存、Flink checkpoint/savepoint、导出和备份 |
| D-006 | 已确认 | 后端采用 Java 21 + Spring Boot | 数据库变更使用 Flyway，异步运行使用持久任务和事件状态 |
| D-007 | 已确认 | APISIX 使用 standalone 模式 | 静态声明路由，当前不引入 etcd |
| D-008 | 已确认 | 平台功能直接按“数据 / 本体 / 应用 / AIP”分组放在左侧导航 | 分组标题不可点击，不设置 Applications 门户、应用卡片或额外首页 |
| D-009 | 已确认 | Flink 只写平台 Pulsar，由独立 Java Projection Worker 写 HugeGraph/OpenSearch | Projection Worker 负责 schema 校验、幂等 ledger、版本控制、双存储投影、重试和 DLQ；不保留 Flink 直写正式路径 |
| D-010 | 已确认 | Keycloak 默认启用，规格冻结 | 标准开发演示、完整验证和生产 Compose 均启用 `auth`；Keycloak subject/group/attribute 是外部身份来源，APISIX OIDC + Frontend PKCE + Java Resource Server 共同校验；仅单元测试和显式 `dev-insecure` 允许模拟身份 |
| D-011 | 已确认 | `Control Panel` 固定在左侧导航底部且仅管理员可见 | 设置、用户权限和审计不混入业务功能分组 |
| D-012 | 已确认 | MinIO 使用可直接拉取的公共镜像 | 不在本项目源码构建 MinIO；服务仅在 Compose 内网使用，不对公网暴露 |
| D-013 | 已确认 | 前端继续使用当前仓库的视觉风格 | 保留白色侧栏、浅色内容区、蓝色主色、Ant Design 组件、圆角和阴影，不复制 Palantir 深色皮肤 |
| D-014 | 已确认 | 导航和产品文案使用中文，并补齐 Palantir 对应能力 | “分析看板”负责分析展示，“业务应用”负责带对象、Action 和 Function 的操作型页面 |
| D-015 | 已确认 | 数据连接列表采用紧凑表格方案 A | 新建连接使用独立全屏向导；连接状态与同步状态分列；Builder 可新建和编辑，只有 Admin 可删除；被管道引用的连接只能停用 |
| D-016 | 已确认 | 数据集成层由 Flink、平台内部 Pulsar 和投影消费组成；SkyWalking 作为横向可观测平面 | 外部 Pulsar 仅作为用户数据源；采用 SkyWalking OAP/UI + OpenTelemetry Collector，复用 OpenSearch 独立命名空间并退出 Prometheus/Grafana |
| D-017 | 已确认 | 新建连接采用四步全屏向导方案 A | 平台托管加密凭据并兼容预置 `file://` Secret；只发现资产元数据；受限发现允许创建；成功后进入连接详情；消费位置策略留在管道构建 |
| D-018 | 已确认 | 数据连接详情整体采用方案 A，规格冻结 | Tabs 为“概览 / 资产 / 同步任务 / 运行记录 / 设置”；资产抽屉可深链接；消息预览不提交 offset；停用时批任务完成、流任务 savepoint 后停止；仅 Admin 打开 SkyWalking 和永久删除 |
| D-019 | 已确认 | 管道构建整体采用完整 DAG 方案 A，规格冻结 | Flink 是唯一计算引擎；批流共用 Pipeline IR；有界 Flink 预览；不可变发布版本、变更提议、Projection 完成语义、内部凭据 Broker 和分层权限 |
| D-020 | 已确认 | 数据质量采用三层、版本化规则集方案 A，规格冻结 | 接入前、Flink 管道内、投影后分层执行；严重级别与动作分离；流式默认隔离；批/流隔离正文分别进入 MinIO/Pulsar；问题聚合与到期风险接受；v1 不新增 Quality Worker |
| D-021 | 已确认 | 数据血缘采用平台原生、版本化血缘索引方案 A，规格冻结 | Ontology Core 在 Postgres 维护控制面血缘；提供业务/技术双视图与设计/运行/历史/影响四种模式；字段级血缘由 Pipeline IR 确定性生成；服务端权限裁剪和大图按需加载；v1 不新增 Lineage 容器、不做行级血缘 |
| D-022 | 已确认 | 本体管理采用单一本体、规范化版本模型和 Proposal 式发布方案 A，规格冻结 | 稳定资源 ID/物理键；轻量 Interface；每个对象类型一个主 Pipeline；Action mutation batch 统一进入 Pulsar/Projection；Function 为受限只读 DSL；不恢复 Nessie/Global Branching，不执行任意用户代码 |
| D-023 | 已确认 | 对象探索采用服务端 Object Set 方案 A，规格冻结 | 全局搜索 + 单类型探索 + 标准对象视图；动态 Exploration 与静态 List 分离；所有写入走 Action；权限在 OpenSearch/HugeGraph 查询阶段执行；异步安全导出；v1 不做直接 CRUD、Iceberg 时间旅行、地图或任意图查询 |
| D-024 | 已确认 | 分析看板采用版本化、对象驱动、只读分析方案 A，规格冻结 | 多页面全屏编辑器；共享 DataSource、Filter Variable 与 Dashboard Query Plan；精确依赖版本、交叉过滤、下钻和小群体抑制；不执行 Action、不接原始 SQL/数据源、不匿名公开、不新增分析数据库或渲染容器 |
| D-025 | 已确认 | 业务应用采用对象驱动、结构化布局的精简 Workshop 方案 A，规格冻结 | 类型化变量与事件图；Action 是唯一写入通道并强制 Preview/确认；Function 只读；应用权限不授予底层资源权限；不做自由坐标、自定义代码组件、Scenario、应用嵌套或实时多人编辑 |
| D-026 | 已确认 | 自动化采用版本化规则、事件驱动、至少一次执行和强幂等方案 A，规格冻结 | 对象/平台事件/Cron 触发；顺序效果、显式执行主体、冷却/批次/重试/DLQ/循环保护；Action 自动执行以启用版本预授权，高风险进入审批；不宣称 exactly-once，不做任意 DAG/BPMN/Webhook/脚本 |
| D-027 | 已确认 | 审批中心采用 Request—Task—Stage—Decision—Invocation 统一模型方案 A，规格冻结 | 多阶段审批、职责分离、要求修改产生新 revision、批准与执行完成分离、显式补偿 Action；不自动批准、不盲审、不用通用快照回滚 |
| D-028 | 已确认 | AIP 采用供应商中立、版本化 Agent Runtime 方案 A，规格冻结 | 保留独立 Java WebFlux Agent 服务；提供 OpenAI Responses 与 OpenAI-compatible 适配器、类型化 Tool Registry、持久运行/SSE、引用、配额和观测；模型永远不获得 execute 工具，不展示思维链 |
| D-029 | 已确认 | 全局 AIP 助手与对话中心采用临时 AssistSession + 私有持久 Thread 方案 A，规格冻结 | 上下文引用显式可移除；Thread 固定 Agent/Model/Tool 版本并支持消息分支、文档引用和权限变化后的历史遮蔽；页面打开不自动调用模型，v1 不分享 Thread |
| D-030 | 已确认 | 智能体工作室采用不可变发布版本和 Eval 门禁方案 A，规格冻结 | 指令、应用状态、检索、工具和 Model Profile 全部版本化；发布前执行权限重查、提示注入、引用和写操作安全评测；不做 AIP Logic、多智能体、任意 MCP/Web/Shell 或代码执行 |
| D-031 | 已确认 | 控制面板采用任务型二级导航方案 A，规格冻结 | 概览、身份与访问、AIP 管理、平台运维四组页面；保留全局侧栏并在内容区显示二级导航，不做卡片门户或底层组件通用管理台 |
| D-032 | 已确认 | 权限采用“管理职责 + 资源角色 + ABAC/字段策略 + 功能访问”四层方案 A，规格冻结 | `Platform Admin` 是全部管理职责的内置角色；组授权优先；功能访问不代替资源/API 鉴权；提供可解释 Access Checker 和最后管理员防锁死 |
| D-033 | 已确认 | 高风险管理变更复用审批中心和不可变配置版本方案 A，规格冻结 | 权限扩大、Provider/AIP 策略、审计保留、维护和恢复先生成 diff/影响，再经职责分离审批和注册式 Invocation 应用，不允许自批或任意 SQL/Shell |
| D-034 | 已确认 | 备份采用一致性 manifest + 受限 Maintenance Runner，恢复采用审批后的服务器 CLI 方案 A，规格冻结 | Runner 无 Docker Socket/任意命令；同机 MinIO 不宣称灾备；全平台恢复固定 manifest/环境并由一次性 token 驱动 host CLI，不做浏览器一键恢复 |
| D-035 | 已确认 | 正式实现位于独立仓库 `/Users/dijkstra/project/06-shixi/Ontology` | `palantir-like-system` 只作交互与视觉参考；新仓库从现有 HEAD 继续并只包含本计划定义的新实现 |
| D-036 | 已确认 | 实施路线采用 18 个页面级可验证 commit | P00—P17 顺序执行；功能 commit 以一个左侧产品页面为边界，同时交付其 schema、API、后端、前端和测试；不保留第二套细粒度提交路线 |
| D-037 | 已确认 | 新仓库采用 `backend / portal / docker / docs` 顶层目录 | 根 `pom.xml` 只作 Maven 聚合/版本管理；Java 服务和共享库进入 `backend/*`，前端进入 `portal`，Compose/镜像/脚本进入 `docker`，OpenAPI 只在 `docs/openapi` |

## 1. 项目目标

构建一个可通过 Docker Compose 部署的 Palantir Foundry / AIP 风格平台。平台以“数据—本体—应用—AIP”形成完整闭环：

1. 连接 CSV、MySQL、PostgreSQL、Kafka 和外部 Pulsar 等数据源。
2. 使用 Flink 对批数据或流数据执行解析、映射、清洗和标准化。
3. 使用 Pulsar 作为平台数据事件总线，解耦数据接入、对象投影、索引和应用消费。
4. 使用 HugeGraph 保存对象、属性和关系，作为本体实例数据的事实存储。
5. 使用 OpenSearch 保存全文与筛选索引，支持跨对象检索和后续语义检索扩展。
6. 使用 Spring Boot Ontology Core 管理对象类型、字段、关系、Action、Function、权限、审计和统一 Ontology API。
7. 使用 APISIX 为 Business Apps、Agents 和前端提供统一入口、认证、限流和路由。
8. 使用 React + Ant Design 构建任务导向的产品界面，而不是把基础设施能力平铺为菜单。
9. 使用 Apache SkyWalking 统一承载服务拓扑、调用链、组件指标、告警和日志关联。

### 1.1 平台边界

本轮计划实现：

- 通用数据连接、资产发现、预览和 Schema 推断。
- Flink 作业配置、提交、运行状态和日志。
- Pulsar topic、订阅、失败重试和死信状态的可视化管理。
- SkyWalking 服务拓扑、Java 调用链、Flink/Pulsar 指标和告警入口。
- 动态本体建模：对象类型、属性、关系、Action、Function。
- HugeGraph 对象与关系查询。
- OpenSearch 跨对象搜索。
- 通用对象浏览器、看板、工作流审批、Agent、权限和审计。
- 单机 Compose 的健康检查、持久卷、备份和验证。

当前不实现：

- Kubernetes、Helm、多节点自动扩缩容。
- 完整数据湖、Iceberg 时间旅行、Nessie 分支和 Trino 联邦查询。
- 对标 Palantir 全部产品，如完整 Workshop、Contour、Code Repositories。
- 自研分布式图数据库或搜索引擎。
- 多租户物理隔离和跨区域容灾。

### 1.2 通用性约束

- 平台代码不得硬编码“人员、Token 消耗、部门”等演示业务概念。
- 新业务应通过连接数据源、创建管道、建立本体和配置应用完成，不修改平台代码。
- 演示 CSV 只是验收样例，不是产品数据模型。
- 前端字段、表格、详情、动作表单和关系展示尽量由元数据驱动。

## 2. 参考仓库使用边界

`/Users/dijkstra/project/06-shixi/palantir-like-system` 只用于观察白色侧栏、浅色内容区、蓝色主色、Ant Design 组件、圆角、阴影、表格密度和基础交互反馈。新前端可参考其视觉令牌与局部交互，但页面信息架构、路由、状态模型和组件拆分全部以本计划为准。

正式实现不从参考仓库复制业务代码、API、数据库、资源 JSON、权限映射、历史数据或部署文件。`/Users/dijkstra/project/06-shixi/Ontology` 只实现本计划冻结的类型化契约，并从第一版遵守以下边界：Postgres 不保存对象正文；HugeGraph 是对象事实存储；OpenSearch 是可重建检索投影；Flink 只向平台 Pulsar 输出；Projection Worker 负责双存储投影；Keycloak 默认启用；SkyWalking + OTel 是唯一可观测方案。

## 3. 已确认的目标架构

### 3.1 逻辑架构

```text
┌──────────────────────────────────────────────────────────────────────┐
│                              应用层                                 │
│   React Web App   Business Apps   Agent / Copilot   External API    │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ HTTPS / SSE
                            ┌──────▼──────┐
                            │ Apache APISIX│
                            └──────┬──────┘
                                   │ 可信身份、限流、路由
                         ┌─────────┴──────────┐
                         │                    │
                 ┌───────▼────────┐   ┌──────▼───────────────────────┐
                 │ Agent Runtime  │──→│ Provider Adapters            │
                 │ Thread/Tool/   │   │ Responses / OpenAI-compatible│
                 │ Context/Eval   │   └──────────────────────────────┘
                 └───────┬────────┘
                         │ 仅调用类型化、权限感知的 Ontology API
┌────────────────────────▼─────────────────────────────────────────────┐
│                         Ontology Core                                │
│ Modeling / Objects / Relations / Actions / Functions / Policy       │
│ Search API / Pipeline Control / Audit / Application Metadata        │
└───────────────┬────────────────────┬────────────────────┬────────────┘
                │                    │                    │
          control metadata      object graph        search/index
                │                    │                    │
           PostgreSQL          Apache HugeGraph       OpenSearch

┌───────────────┐   ┌───────────────────────────────────────────────┐
│ Data Sources  │   │            Data Integration                  │
│ CSV / MySQL   │──→│ Apache Flink → 平台内部 Apache Pulsar          │
│ PostgreSQL    │   │      │             event bus / retry / DLQ   │
│ Kafka         │   │      └─ checkpoint / savepoint → MinIO       │
│ 外部 Pulsar   │   └───────────────────────────────────────────────┘
└───────────────┘
                                    │
                        Java Projection Worker
                                    ├──────────────────────────────→ HugeGraph
                                    └──────────────────────────────→ OpenSearch

┌────────────────────── Apache SkyWalking 可观测平面 ──────────────────┐
│ Java Agent → SkyWalking OAP ← OpenTelemetry Collector               │
│                         ↑ Flink / Pulsar / APISIX Prometheus metrics │
│ SkyWalking UI ← OAP；遥测数据写入 OpenSearch 的 skywalking 命名空间  │
└──────────────────────────────────────────────────────────────────────┘
```

架构边界：

- Data Integration 是明确的逻辑层，包含 Flink 计算、平台 Pulsar 事件总线和独立 Java Projection Worker。
- 数据源中的 Pulsar 是用户已有的外部消息系统；Data Integration 中的 Pulsar 是本平台内部组件，两者不得在配置、权限或 UI 中混为一谈。
- Agent Runtime 是独立 Java 服务，只通过类型化 Ontology API 访问平台数据；模型供应商不直接连接 HugeGraph、OpenSearch、Postgres、Pulsar 或 MinIO，也不能绕过 Action Preview/确认/审批。
- Thread、Run、Tool、引用和 Eval 事实归平台控制面所有；模型供应商会话 ID 只是可选关联，不是会话真相源。
- SkyWalking 是横跨网关、Java 服务、数据集成和存储的可观测平面，不参与任何业务对象读写，也不设置普通业务一级导航。

### 3.2 物理部署

- 所有服务位于一个用户定义的 Compose bridge network：`ontology-platform`。
- 默认仅 APISIX/Frontend 暴露产品入口；基础组件端口仅为开发和运维 profile 暴露。
- 每个有状态组件使用独立 named volume。
- 不使用容器 IP；服务间仅通过 Compose service name 通信。
- Compose 中使用精确 tag，P01 验证通过后记录镜像 digest。
- 生产部署不使用 `latest`、范围 tag 或未锁定的插件 JAR。
- SkyWalking UI 只通过 APISIX 的管理员路由访问；OAP、OTLP、OpenSearch 和组件指标端口默认不暴露公网。

## 4. 组件职责和数据所有权

### 4.1 Apache Flink

负责：

- CSV/JSON 文件批量读取。
- MySQL/PostgreSQL JDBC 批量读取；CDC 作为后续可选能力。
- Kafka/Pulsar 流式读取。
- 字段映射、类型转换、过滤、派生字段、基础聚合和数据校验。
- 生成统一的 ontology event envelope 并写入 Pulsar。
- checkpoint/savepoint 写入 MinIO。

不负责：

- 保存平台元数据。
- 直接向前端提供业务查询 API。
- 执行用户未经校验的任意代码或 SQL。

### 4.2 Apache Pulsar

负责：

- 作为平台内部接入和投影之间的持久事件总线。
- 承载标准化对象 upsert/delete、relation upsert/delete、index rebuild 等事件。
- 提供 subscription、重试、dead-letter topic 和消费积压指标。

初始 topic 约定：

```text
persistent://platform/ingestion/object-events
persistent://platform/ingestion/relation-events
persistent://platform/commands/mutation-batches
persistent://platform/index/rebuild-events
persistent://platform/system/audit-events
persistent://platform/quality/quarantine
persistent://platform/dlq/projection-events
```

命名空间、保留周期和分区数必须通过 bootstrap 脚本声明，不依赖自动创建。

角色隔离：

- 用户在“数据连接”中配置的 Pulsar 称为“外部 Pulsar 数据源”，使用独立的 Service URL、认证、租户、命名空间、Topic 和 Subscription。
- Compose 中的 Pulsar 称为“平台事件总线”，内部连接配置不作为普通数据源显示，也不允许用户删除。
- 默认拒绝外部连接订阅 `persistent://platform/*`，防止管道把平台输出重新作为输入形成消息环路。
- 管道配置分别保存 `source_connection_id/source_topic` 与 `target_platform_topic`，不得复用一个无语义的 `topic` 字段。

### 4.3 Apache HugeGraph

负责：

- 保存本体对象实例、属性和对象关系。
- 为对象详情、关系展开、邻居查询和路径查询提供数据。
- 对外不暴露原生 Gremlin/Cypher 查询端点；只有 Ontology Core 可以访问。

建议映射：

- 一个本体对象类型对应一个 vertex label。
- 一个本体关系类型对应一个 edge label。
- 对象业务主键映射为稳定的逻辑 ID，并保存 `object_type`、`version`、`updated_at`。
- Schema 变更由 Ontology Core 的 provisioning 流程执行并记录状态。

### 4.4 OpenSearch

负责：

- 跨对象全文搜索、筛选、排序和高亮。
- 保存对象的可检索派生文档，不作为事实真相源。
- 索引文档必须保存 HugeGraph 对象 ID、本体类型、版本和权限过滤字段。
- 在独立 `aip-doc-*` 模板/alias 中保存 AIP 附件 chunk 和引用元数据；其 ACL、生命周期和重建不能与对象索引混用。

约束：

- HugeGraph 写成功、OpenSearch 写失败时进入 retry/DLQ，不回滚图数据。
- 搜索结果打开详情时必须回查 Ontology Core/HugeGraph，不能把索引正文当最终状态。
- 权限过滤由 Ontology Core 重新校验，不能只依赖 OpenSearch query filter。

### 4.5 PostgreSQL

仅保存控制面数据：

- 对象类型、字段、关系和部署记录。
- 数据源、资产、管道、运行记录和质量规则。
- Action、Function、工作流、审批和幂等 ledger。
- 用户映射、角色、权限、ABAC 规则和审计记录。
- 看板、应用、保存查询、通知和系统设置。
- `aip` schema 中的 Agent/Model/Tool 版本、Thread/Message/Run、引用、Eval、用量和安全水印。
- Pulsar 投影 offset/状态、失败原因和补偿记录。

新仓库不得创建 `object_records` 业务正文表；Postgres 从第一版起只保存控制面、运行事实、幂等和审计元数据。

### 4.6 MinIO

bucket 规划：

| Bucket | 用途 |
|---|---|
| `raw` | 原始 CSV/JSON、数据库导出暂存 |
| `flink-checkpoints` | Flink checkpoint |
| `flink-savepoints` | Flink savepoint |
| `quarantine` | 批处理质量隔离正文，独立权限、加密与 TTL |
| `exports` | 用户异步导出，设置过期清理 |
| `aip-attachments` | AIP 私有附件原件和受控大工具 artifact；独立权限、加密与 TTL |
| `audit-archive` | 压缩结构化审计归档和 SHA-256 manifest；独立权限与法律保留 |
| `backups` | Postgres/HugeGraph/OpenSearch 配置与备份清单 |
| `demo` | 可重复初始化的演示数据 |

MinIO 不再承载 Iceberg warehouse、Nessie catalog 或 Trino 表。

### 4.7 Ontology Core

保留 Spring Boot 单体控制面，内部按模块边界组织，当前不拆微服务：

- `modeling`：单一本体 revision、对象/属性/关系/Interface 规范版本、Proposal、兼容性校验和 schema 部署 Saga。
- `objects`：Object Set AST、cursor、Facet、对象详情、关系/来源、保存探索、清单、导出和能力发现；普通写入只接收已授权 Action command。
- `graph`：HugeGraph client、schema adapter、关系查询。
- `search`：OpenSearch client、版本化索引/alias、跨对象搜索、聚合和权限谓词；故障时不回退 Postgres/内存对象扫描。
- `ingestion`：数据源、资产、Flink 作业模板、Pulsar topic/运行状态。
- `projection-contracts`：事件 envelope、Pipeline IR、投影接口和共享校验契约，不在 Ontology Core 进程消费正式投影 Topic。
- `quality`：版本化规则集、共享规则 AST/编译、持久化异步运行、问题、隔离元数据和通知。
- `actions`：类型化参数、声明式规则、提交条件、preview、审批请求集成、mutation batch、显式补偿 Action 和幂等。
- `functions`：版本化、类型化、只读的可信查询函数和受限表达式；不执行任意用户代码。
- `automations`：版本化触发器/条件/顺序效果、执行主体、trigger cursor、运行/effect ledger、幂等、重试、DLQ 和循环保护。
- `approvals`：Request/Revision/Stage/Task/Decision、职责分离、委托、评论附件和类型化领域 invocation。
- `security`：JWT、RBAC、ABAC、字段脱敏。
- `audit`：请求 ID、脱敏审计和保留策略。
- `applications`：分析看板、业务应用和应用侧版本配置。

### 4.8 Apache SkyWalking 与 OpenTelemetry Collector

SkyWalking 作为平台唯一的可观测分析后端：

- `skywalking-oap` 接收 Java Agent 的 trace/metric/log correlation 数据，以及 OpenTelemetry Collector 转发的组件指标。
- `skywalking-ui` 提供服务拓扑、调用链、组件看板和告警查询，通过 APISIX 仅向 Admin 暴露。
- Ontology Core、BFF、Agent 和独立 Projection Worker 使用 SkyWalking Java Agent，无需在业务代码内手写基础 HTTP/JDBC 追踪。
- `otel-collector` 使用 Prometheus receiver 抓取 Flink JobManager/TaskManager、Pulsar 和 APISIX 指标，通过 OTLP gRPC 写入 OAP。
- OAP 复用平台 OpenSearch，但必须配置独立 `SW_NAMESPACE=skywalking`、独立索引模板、保留周期和资源水位告警。
- v1 不新增 BanyanDB；若 SkyWalking 10.4 与目标 OpenSearch 版本的兼容 PoC 失败，暂停 P01 并重新评估兼容 OpenSearch 版本或 BanyanDB，不能静默改用 H2。

可观测边界：

- SkyWalking 不替代管道运行表、审计日志、Pulsar ledger 或业务血缘。
- 不承诺自动提供“源消息到最终对象”的完整事件级 trace；事件信封必须携带 correlation、pipeline run 和 Flink job 标识完成业务关联。
- Prometheus 格式只是 Flink/Pulsar/APISIX 的指标暴露协议，不再部署独立 Prometheus Server 和 Grafana。

### 4.9 Java Projection Worker

Projection Worker 使用 Java 21 + Spring Boot 独立进程运行，订阅平台 Pulsar 的对象、关系、Action mutation batch、索引重建和 DLQ 恢复 Topic：

- 校验 event envelope、本体 schema version、对象/关系类型和字段权限策略。
- 使用 `event_id` 和 `object_type + object_id + object_version` 实现幂等与旧版本拒绝。
- 先写 HugeGraph 事实存储，再写 OpenSearch 派生索引；OpenSearch 失败进入可重试状态，不回滚图数据。
- 更新 projection ledger、批次进度和 pipeline run stage，达到终态后通知 Ontology Core。
- 超过重试阈值进入受限 DLQ；支持按失败批次或事件重放。
- 使用 SkyWalking Java Agent 上报 trace/metric/log correlation，但运行真相仍保存在 Postgres 与 Pulsar 状态中。

Projection Worker 不提供普通公网 API，不保存平台控制面元数据副本，也不允许 Flink 绕过它直接写双存储。共享契约放在独立 Maven module，Ontology Core、Flink Job 和 Worker 依赖同一版本。

### 4.10 Agent Runtime

Agent Runtime 使用 Java 21 + Spring WebFlux 独立进程，负责供应商适配、Agent/Model/Tool 版本解析、上下文组装、持久 Thread/Run、文档检索、SSE、引用、Action Preview 编排、Eval、配额和审计。它只调用 APISIX 内部受信路由或 Ontology Core 的类型化 API，并始终透传当前用户身份。

Agent Runtime 不直接读取业务存储、不持有浏览器身份之外的万能数据身份、不向模型暴露 execute/原生查询/任意网络工具、不保存思维链，也不把供应商 response/conversation ID 当真相源。Provider key 仅由加密 credential ref 在适配器调用时解析；MinIO/OpenSearch/Postgres 分别保存附件原件、检索派生索引和控制面/运行事实。

### 4.11 Maintenance Runner

Maintenance Runner 是 Java 21 独立内部进程，只消费 Postgres 中已注册的备份、验证和索引重建任务。任务输入是类型化 job/manifest ID，不接受命令字符串、任意 URL、SQL 或 Shell；容器不挂载 Docker Socket，只持有完成对应任务所需的最小组件凭据。

Runner 可执行在线一致性备份、artifact/hash 校验和派生索引重建，不能停止/删除其他容器、修改 Compose、删除 volume 或执行全平台离线恢复。完整恢复必须由审批后的服务器 `make restore` 流程完成；Runner 的所有状态、错误、watermark 和产物均回写控制面并进入结构化审计。

## 5. 镜像和版本矩阵

### 5.1 候选锁定版本

| 变量 | 镜像 | 部署角色 | 状态 |
|---|---|---|---|
| `APISIX_IMAGE` | `apache/apisix:3.17.0-debian` | standalone API gateway | 已确认，待 digest |
| `PULSAR_IMAGE` | `apachepulsar/pulsar:4.0.12` | standalone broker/bookie/metadata | 已确认，待兼容验证 |
| `FLINK_IMAGE` | `flink:1.20.5-scala_2.12-java17` | JobManager/TaskManager | 已确认，待连接器验证 |
| `HUGEGRAPH_IMAGE` | `hugegraph/hugegraph:1.7.0` | 单节点 RocksDB graph store | 已确认，待持久卷验证 |
| `OPENSEARCH_IMAGE` | `opensearchproject/opensearch:3.7.0` | 单节点搜索索引 | 已确认，待 Java client 验证 |
| `POSTGRES_IMAGE` | `postgres:17.9-alpine3.23` | 控制面数据库 | 已确认，待拉取验证 |
| `KEYCLOAK_IMAGE` | `quay.io/keycloak/keycloak:26.6.0` | 默认 OIDC 身份服务 | 已确认默认启用，待拉取与 PKCE 验证 |
| `MINIO_IMAGE` | `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` | 公共预构建镜像 | 已确认，manifest 已验证 |
| `MINIO_MC_IMAGE` | `minio/mc:RELEASE.2025-08-13T08-35-41Z` | bucket bootstrap | 候选 |
| `MAVEN_IMAGE` | `maven:3.9.9-eclipse-temurin-21` | Java build stage | 保留 |
| `JAVA_IMAGE` | `eclipse-temurin:21.0.7_6-jre-jammy` | Java runtime | 保留，后续安全复核 |
| `NODE_IMAGE` | `node:22.22.0-alpine3.23` | Frontend build stage | 保留 |
| `NGINX_IMAGE` | `nginx:1.27.0-alpine3.19` | Frontend runtime | 暂保留，后续安全复核 |
| `SKYWALKING_OAP_IMAGE` | `apache/skywalking-oap-server:10.4.0` | 可观测分析后端 | 已选，待 OpenSearch 兼容 PoC 与 digest |
| `SKYWALKING_UI_IMAGE` | `apache/skywalking-ui:10.4.0` | 管理员可观测界面 | 已选，待拉取和 APISIX 路由验证 |
| `SKYWALKING_JAVA_AGENT_VERSION` | `9.6.0` | Java 服务无侵入探针 | 已选，构建时下载并校验 SHA-512 |
| `OTEL_COLLECTOR_IMAGE` | `otel/opentelemetry-collector-contrib:0.153.0` | Flink/Pulsar/APISIX 指标采集 | 已选，待配置兼容验证与 digest |

### 5.2 MinIO 公共镜像约束

MinIO 使用 `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`，不在本项目中维护 MinIO 源码构建。部署约束：

1. 锁定精确 tag，并在 P01 记录目标服务器架构对应的 image digest。
2. MinIO API 和 Console 只允许内网或受控运维网络访问，禁止直接暴露到公网。
3. 必须设置非默认管理员凭据，并通过 Docker secret 或受控环境文件注入。
4. bucket 默认私有；下载和导出使用短期签名 URL。
5. 在兼容性报告中记录该公共镜像早于 MinIO 社区最后安全修订的已知风险。
6. 每次部署前复核是否出现更新的可信公共镜像；升级必须先通过 Flink S3、持久卷和备份恢复测试。

### 5.3 Flink 连接器锁定规则

Flink 基础镜像不包含所有连接器。P01 必须对以下 JAR 建立清单和 SHA-256：

- Pulsar connector。
- Kafka connector。
- JDBC connector。
- PostgreSQL JDBC driver。
- MySQL JDBC driver。
- S3 filesystem plugin。

禁止容器启动时从不固定 URL 临时下载 JAR。连接器应在自定义 Flink 镜像构建时复制并校验哈希。

## 6. Docker Compose 设计

### 6.1 目录

```text
docker/
├── docker-compose.yml
├── versions.env
├── env/.env.example
├── compose/
│   ├── 05-gateway.yml
│   ├── 10-control-store.yml
│   ├── 15-object-storage.yml
│   ├── 20-event-bus.yml
│   ├── 30-compute.yml
│   ├── 40-ontology-storage.yml
│   ├── 50-security.yml
│   ├── 60-platform.yml
│   ├── 70-apps.yml
│   ├── 80-source-demos.yml
│   └── 90-observability.yml
├── images/
│   └── flink/Dockerfile
├── config/
│   ├── apisix/
│   ├── flink/
│   ├── hugegraph/
│   ├── keycloak/
│   ├── opensearch/
│   ├── skywalking/
│   └── otel-collector/
└── scripts/
    ├── bootstrap-pulsar.sh
    ├── bootstrap-storage.sh
    ├── compatibility-check.sh
    ├── healthcheck.sh
    ├── e2e-verify.sh
    └── backup-restore-verify.sh
```

Compose 从 `docker/` 目录按本节结构新建；服务名或路径变化必须同步更新 Makefile、README、验证脚本和文档链接。

### 6.2 Profiles

| Profile | 服务 | 用途 |
|---|---|---|
| `core` | Postgres、MinIO、Pulsar、HugeGraph、OpenSearch、Ontology Core、Projection Worker | 最小平台主链路 |
| `compute` | Flink JobManager、Flink TaskManager | 数据接入与转换 |
| `gateway` | APISIX | 统一入口 |
| `apps` | BFF、Agent、Frontend | 产品界面与智能应用 |
| `maintenance` | Maintenance Runner | 备份、验证和索引重建；生产应启用，开发可按需启动 |
| `auth` | Keycloak | 默认启用的 OIDC 登录、用户组/属性来源和 token 签发 |
| `source-demos` | MySQL、PostgreSQL demo source | 数据源演示 |
| `observability` | SkyWalking OAP、SkyWalking UI、OpenTelemetry Collector | 拓扑、调用链、Flink/Pulsar/APISIX 指标与告警 |
| `devtools` | 可选运维 UI/调试端口 | 仅开发环境，不进入默认部署 |

标准启动方式（Keycloak 默认启用）：

```bash
docker compose --profile core --profile compute --profile gateway --profile apps --profile maintenance --profile auth up -d
```

完整验证再增加 `--profile observability`；`dev-insecure` 仅用于显式的本地后端调试和自动化测试，不能与生产配置共用 secret、域名或部署文档，也不能成为 README 默认启动命令。

安全演示：

```bash
docker compose --profile core --profile compute --profile gateway --profile apps --profile auth up -d
```

完整运维部署：

```bash
docker compose --profile core --profile compute --profile gateway --profile apps --profile auth --profile observability up -d
```

### 6.3 端口规划

| 服务 | 容器端口 | 默认主机端口 | 说明 |
|---|---:|---:|---|
| APISIX HTTP | 9080 | 9080 | 产品统一入口 |
| APISIX HTTPS | 9443 | 9443 | 正式入口 |
| Frontend | 3000 | 3000 | 仅本地开发可直接访问 |
| Ontology Core | 8000 | 8000 | 仅开发 profile 暴露 |
| BFF | 8001 | 8001 | 仅开发 profile 暴露 |
| Agent | 8002 | 8002 | 仅开发 profile 暴露 |
| Flink UI | 8081 | 8081 | 运维访问 |
| Pulsar binary | 6650 | 6650 | producer/consumer |
| Pulsar admin HTTP | 8080 | 8085 | 避免与其他组件冲突 |
| HugeGraph HTTP | 8080 | 8084 | 不对公网暴露 |
| OpenSearch | 9200 | 9200 | 不对公网暴露 |
| MinIO API | 9000 | 9000 | 内部 S3 API |
| MinIO Console | 9001 | 9001 | 运维访问 |
| Postgres | 5432 | 5432 | 仅本地或受控运维访问 |
| Keycloak | 8080 | 8083 | auth profile |
| SkyWalking OAP gRPC | 11800 | 不默认映射 | Java Agent 与 OTLP 内部上报 |
| SkyWalking OAP HTTP | 12800 | 不默认映射 | UI/GraphQL 和健康检查 |
| SkyWalking UI | 8080 | 不直接映射 | 仅通过 APISIX `/observability/*` 访问 |
| OTel Collector OTLP gRPC | 4317 | 不默认映射 | 可选内部 OTLP receiver；Collector 向 OAP 的导出目标为 `skywalking-oap:11800` |

### 6.4 Named volumes

```text
postgres-data
minio-data
pulsar-data
pulsar-conf
flink-checkpoints-cache
hugegraph-data
opensearch-data
keycloak-data
mysql-source-data
postgres-source-data
```

不得把宿主机宽泛目录、用户 HOME 或仓库根目录挂载为数据库数据目录。

### 6.5 健康依赖

```text
Postgres healthy ───────────────→ Ontology Core
MinIO healthy ─────────────────→ Flink checkpoint / file source
Pulsar healthy + bootstrap ────→ Flink jobs / Projection consumer
HugeGraph healthy + schema ────→ Ontology Core / Projection consumer
OpenSearch healthy + templates → Ontology Core / Projection consumer
Postgres + Pulsar + stores ready → Projection Worker
Postgres + MinIO + stores ready ─→ Maintenance Runner（仅 maintenance profile）
Ontology ready ────────────────→ BFF / Agent / APISIX routes
Keycloak ready ────────────────→ APISIX OIDC / Frontend / Java Resource Servers（默认 auth profile）
OpenSearch healthy ────────────→ SkyWalking OAP（仅 observability profile）
SkyWalking OAP ready ──────────→ SkyWalking UI / OTel Collector
```

规则：

- `depends_on` 只表达启动必要条件，不形成整条阻塞链。
- 一次性 bootstrap 服务必须有幂等脚本，成功后正常退出。
- readiness 必须验证真实依赖，不只验证进程端口存在。
- OpenSearch 索引不可用时对象详情仍可通过 HugeGraph 读取，但搜索页面必须明确显示降级状态。

## 7. 标准数据模型与事件契约

### 7.1 Ontology event envelope

```json
{
  "event_id": "uuid",
  "event_type": "object.upsert",
  "schema_version": 1,
  "ontology_revision": 12,
  "occurred_at": "2026-07-19T12:00:00Z",
  "producer": "flink-pipeline/pipeline-id",
  "correlation_id": "stable-business-correlation-id",
  "trace_id": "skywalking-trace-id-if-available",
  "flink_job_id": "flink-job-id",
  "object_type": "Employee",
  "object_id": "E0001",
  "object_version": 3,
  "payload": {
    "name": "example",
    "department": "platform"
  },
  "source": {
    "data_source_id": "source-id",
    "asset_id": "asset-id",
    "pipeline_run_id": "run-id"
  }
}
```

事件要求：

- `event_id` 全局唯一，用于投影幂等。
- `correlation_id` 贯穿源采样、管道运行、平台 Pulsar、投影和对象写入；不能只依赖短生命周期 APM trace。
- `trace_id` 用于跳转 SkyWalking 调用链；Flink 或外部源无法提供时允许为空，但不得用空值覆盖 `correlation_id`。
- `schema_version` 用于兼容演进，不允许无版本 payload。
- `ontology_revision` 锁定解释事件所需的不可变 Projection Contract；Worker 不得用当前最新本体解释历史事件。
- `object_type`、字段名和关系名必须来自已激活的本体元数据。
- 敏感字段是否进入 OpenSearch 由字段元数据控制。
- delete 使用 tombstone 语义，但审计和 lineage 记录保留。
- 消费者按 `object_type + object_id` 保证同一对象的顺序处理。

### 7.2 投影一致性

HugeGraph 是对象事实存储，OpenSearch 是可重建索引：

1. 消费事件并校验 schema。
2. 使用 `event_id` 查询 projection ledger；已成功则跳过。
3. 写 HugeGraph，记录 graph version。
4. 写 OpenSearch，文档 version 不得低于 graph version。
5. 两者成功后提交 Pulsar ack 并更新 ledger。
6. 可重试错误 negative ack；超过阈值进入 DLQ。
7. OpenSearch 可通过 HugeGraph 全量重建。

投影固定由独立 Java Projection Worker 实现。Flink 正式输出只写平台 Pulsar；P02 验证 Worker 的幂等、失败恢复、DLQ 和双存储一致性。

### 7.3 Ontology mutation batch

Action 和交互式对象写入使用类型化批命令：

```json
{
  "batch_id": "uuid",
  "ontology_revision": 12,
  "action_type_id": "action-id",
  "action_version": 3,
  "preview_token_id": "preview-id",
  "idempotency_key": "caller-provided-key",
  "requested_by": "user-id",
  "occurred_at": "2026-07-20T12:00:00Z",
  "correlation_id": "stable-correlation-id",
  "edits": [
    {
      "operation": "object.update",
      "object_type_id": "object-type-id",
      "object_id": "E0001",
      "expected_version": 3,
      "properties": {"property-id": "approved"}
    }
  ]
}
```

命令写入 `persistent://platform/commands/mutation-batches`。Ontology Core 只在 preview token、审批、权限、提交条件、ETag 和幂等检查通过后生成命令；属性使用稳定 property ID，不信任浏览器提交的物理键。Projection Worker 对小批 edit 使用单个 HugeGraph 事务并记录 operation ledger，再投影 OpenSearch；超过默认 100 edit 的操作必须拆为显式异步批任务，不能伪装成原子小批命令。

## 8. 数据流

### 8.1 CSV 文件

```text
用户创建 S3/MinIO 数据源
  → 测试连接
  → 发现 bucket/prefix/glob 资产
  → 预览与推断 schema
  → 创建本体映射与 Flink pipeline
  → Flink FileSource 读取 MinIO
  → 转换为 ontology event
  → Pulsar
  → Projection
  → HugeGraph + OpenSearch
  → 对象浏览器/搜索/看板/Agent
```

CSV 不提供“浏览器上传即完成建模”的捷径；它与数据库一样是可配置、可复用的数据连接。演示数据可以由 bootstrap 预置到 `demo` bucket。

### 8.2 MySQL/PostgreSQL

v1：

```text
JDBC 连接 → 发现表/字段 → 有界批量读取 → Pulsar → 双存储投影
```

后续 CDC：

```text
Flink CDC → 统一 change event → Pulsar → 双存储投影
```

CDC 连接器版本未通过专项兼容验证前，不在 UI 中宣称实时同步。

### 8.3 Kafka/Pulsar 流

```text
选择 topic + offset/subscription 策略
  → 采样消息并推断 schema
  → 配置事件时间、水位线、错误策略
  → Flink 标准化
  → 平台 Pulsar ontology topics
  → 双存储投影
```

外部 Pulsar 数据源与平台内部 Pulsar 必须在 UI、API、密钥和配置中区分，禁止误删平台内部 namespace/topic，也禁止把 `persistent://platform/*` 配置为默认输入范围。

### 8.4 交互式对象写入

对象创建、编辑、删除和 Action 必须复用统一事件契约。最终写入时需要满足：

- preview token 和有效期校验。
- `Idempotency-Key`。
- 对象版本或 ETag 并发校验。
- 权限与字段校验。
- 审计记录。
- 可观察的投影状态；前端不得假装异步写入已经完成。

按照 D-022，交互式写入固定为命令进入 `persistent://platform/commands/mutation-batches`，由 Projection Worker 统一写 HugeGraph/OpenSearch。Ontology Core 在 preview/审批后生成不可变、类型化 `OntologyMutationBatch`，包含精确 `ontology_revision`、对象 ETag、幂等键、编辑集合和 `correlation_id`；Worker 在单个 HugeGraph 事务中应用小批对象/关系编辑，再更新 OpenSearch 和 operation ledger。API 返回 operation ID，可在受限超时内等待 Projection 完成；超时必须显示“仍在处理”，OpenSearch 尚未完成时显示 `DEGRADED`，不得同步直写 HugeGraph 后再走另一条索引路径。

## 9. API 边界

### 9.1 APISIX 路由

```text
/api/ontology/*   → ontology:8000
/api/bff/*        → bff:8001
/api/agent/*      → agent:8002
/observability/*  → skywalking-ui:8080（仅 Admin）
/                 → frontend:3000
```

新前端服务层统一使用 `/api/ontology/v1/*`；新仓库不提供参考仓库旧 `/v1/*` 兼容入口。

### 9.2 Ontology API 分组

```text
/v1/modeling/*             本体建模
/v1/objects/*              对象详情、能力、关系、活动和来源
/v1/object-sets/*          类型化查询、Facet、沿关系探索、比较和 selection token
/v1/explorations/*         动态保存探索、布局和分享
/v1/object-lists/*         静态对象引用清单
/v1/export-jobs/*          权限感知的异步导出
/v1/bulk-action-jobs/*     分批 Action、部分结果和失败重试
/v1/relations/*            关系查询
/v1/actions/*              Action preview/execute/approval
/v1/functions/*            Function invoke
/v1/search/*               权限感知的 OpenSearch 对象/资源搜索
/v1/data-sources/*         数据连接与资产
/v1/pipelines/*            Flink 管道
/v1/pipeline-runs/*        运行、日志、重试、取消
/v1/event-bus/*            topic/subscription/DLQ 状态
/v1/quality/*              接入校验与对象质量规则
/v1/lineage/*              source→pipeline→object→app 血缘
/v1/dashboards/*           看板、草稿、版本、分享、健康和使用情况
/v1/dashboard-query-plans/* 看板查询计划、组件执行、筛选选项和下钻
/v1/applications/*         业务应用、草稿、版本、权限、健康和使用情况
/v1/application-runtime-plans/* 运行初始化、变量计算、组件批量查询、Action 绑定和导出
/v1/automations/*          自动化草稿、版本、触发器、运行、DLQ、权限和健康
/v1/approval-requests/*    审批请求、任务、阶段、决策、评论、附件和执行
/v1/approval-delegations/* 审批委托
/v1/control/*              控制面概览、搜索、健康和用量
/v1/settings/users/*       用户、组、服务身份和身份映射
/v1/settings/access-*      operation、角色、授权、策略、访问检查和功能访问
/v1/settings/configuration/* 类型化配置、版本、变更请求和维护状态
/v1/observability/*        产品化健康摘要、问题和 SkyWalking deep link
/v1/audit-*                结构化审计、归档、导出和法律保留
/v1/backups/*              备份目标、策略、运行、验证和 Restore Plan
```

原生 HugeGraph、OpenSearch、Pulsar Admin 和 Flink REST API 不直接透传给浏览器，由 Ontology Core 转换为受限的产品 API。

SkyWalking UI 是管理员运维工具，可由 APISIX 代理；普通前端只通过受限的 Ontology/BFF 汇总 API 获取服务状态、告警数量和 trace deep link，不直接调用 OAP GraphQL。

### 9.3 AIP Agent API 分组

以下 API 由独立 Agent 服务通过 `/api/agent/*` 提供；Agent 只通过服务身份 + 当前用户委托身份调用 Ontology Core 的受限 API：

```text
/v1/aip/agents/*           智能体草稿、不可变版本、权限、模型绑定和发布
/v1/aip/threads/*          私有 Thread、分支、消息、附件、导出和反馈
/v1/aip/runs/*             持久运行、SSE、工具步骤、澄清、Action 确认和取消
/v1/aip/contexts/*         签名页面上下文、检索上下文和临时 AssistSession
/v1/aip/evals/*            Eval Suite、Case、Run、结果和发布门禁
```

浏览器不直接调用模型供应商；Ontology Core 不接受模型自报身份，工具调用必须携带 Agent Run/Tool Call 证明并按真实当前用户重新鉴权。

## 10. 代码框架目标

```text
Ontology/
├── pom.xml                          Maven 聚合、依赖版本和插件管理；不放业务源码
├── mvnw / mvnw.cmd / .mvn/          固定 Maven Wrapper 与 Java 21 toolchain
├── backend/
│   ├── platform-contracts/          Event、Mutation、Pipeline IR、通用错误和安全契约
│   ├── ontology-core/               Spring Boot Ontology Core；控制面与 Ontology API
│   │   └── src/main/java/com/hezhangjian/ontology/core/
│   │       ├── modeling/            本体 revision、Proposal 和发布 Saga
│   │       ├── objects/             Object Set、对象、关系、探索和导出
│   │       ├── connections/         数据连接、资产和凭据引用
│   │       ├── pipelines/           Pipeline 控制面、运行与事件总线状态
│   │       ├── quality/             规则、运行、问题、隔离与重放
│   │       ├── lineage/             版本化血缘、影响与历史
│   │       ├── actions/             Preview、确认、Mutation 和幂等
│   │       ├── functions/           类型化只读 DSL
│   │       ├── applications/        看板、业务应用和 Runtime Plan
│   │       ├── automations/         触发、条件、效果、ledger 和 DLQ
│   │       ├── approvals/           Request、Task、Decision 和 Invocation
│   │       ├── administration/      身份投影、权限、配置、审计和备份控制面
│   │       ├── graph/               HugeGraph adapter
│   │       ├── search/              OpenSearch adapter
│   │       ├── security/            JWT、RBAC、ABAC 和字段策略
│   │       └── shared/              仅 Core 内共享；禁止成为杂物包
│   ├── bff/                         Spring WebFlux 页面聚合；不拥有领域事实
│   ├── agent-runtime/               Provider、Run、Tool、Thread、Retrieval、Eval 和 SSE
│   ├── flink-job/                   Pipeline IR 编译后的通用 Flink Job
│   ├── projection-worker/           Pulsar 消费、ledger 和 HugeGraph/OpenSearch 投影
│   ├── maintenance-runner/          类型化备份、验证和索引重建；无 Docker Socket
│   └── test-support/                Testcontainers、fixture、固定时钟/ID 和 E2E client
├── portal/                          React 18、TypeScript、Vite、Ant Design、pnpm
│   ├── src/app/                     Router、Provider、全局外壳和权限路由
│   ├── src/features/                data、ontology、applications、aip、control-panel
│   ├── src/shared/                  API client、基础组件、hooks、类型和视觉 token
│   ├── public/
│   ├── package.json
│   └── pnpm-lock.yaml
├── docker/
│   ├── docker-compose.yml           Compose 入口
│   ├── compose/                     按顺序拆分的服务 include
│   ├── images/                      Flink 等项目自定义镜像
│   ├── config/                      APISIX/Flink/HugeGraph/Keycloak/OpenSearch/SkyWalking
│   ├── env/.env.example             只含占位和说明
│   ├── scripts/                     bootstrap、health、E2E、backup/restore
│   └── versions.env                 镜像 digest 与外部 JAR SHA
├── docs/
│   ├── openapi/openapi.yaml         所有公共 HTTP API 的唯一契约源
│   ├── architecture/                架构、数据流和安全边界
│   ├── adr/                         不可变决策记录
│   ├── runbooks/                    部署、升级、备份、恢复和故障处理
│   └── evidence/                    每阶段机器验证证据，不提交敏感正文
├── examples/demo-data/              可重复生成的无敏感演示数据
├── .github/workflows/
├── AGENTS.md
├── Makefile
├── plan.md                          P00 后成为实现计划唯一工作副本
└── README.md
```

目录与依赖原则：

- 根 `pom.xml` 使用 `packaging=pom`，只聚合 `backend/*` 并统一 Java 21、Spring、插件和依赖版本；根目录不再保留 `src/` 业务源码。
- `platform-contracts` 只能包含稳定 DTO/schema/错误契约，不依赖 Spring Web、数据库 client 或任何可执行服务；服务间禁止直接依赖对方实现模块。
- `ontology-core` 是控制面和业务规则真相源；BFF 只聚合，Agent 只编排工具，Worker 只投影，Runner 只执行注册维护任务。
- 各可执行模块拥有自己的 `Application`、配置、Dockerfile 和测试；共享代码只有被两个以上模块使用且边界稳定时才能进入 contracts/test-support。
- Java 根包固定为 `com.hezhangjian.ontology`；禁止继续引入旧仓库的 `com.ontology.platform` 包。
- `portal` 按 feature vertical slice 拆分，页面不得直接调用供应商、Flink、Pulsar、HugeGraph、OpenSearch、MinIO 或 SkyWalking 原生 API。
- `docker` 是 Compose/容器配置唯一位置，`docs/openapi/openapi.yaml` 是公共 API 唯一契约源；不再创建 `infra/` 或第二份 OpenAPI。
- P00 完成此骨架并同步更新目标仓库 `AGENTS.md`；此后禁止无独立 Phase 的跨模块大搬迁。

## 11. 前端信息架构与逐页功能

### 11.1 视觉系统

前端延续现有代码仓风格，不复制 Palantir 的深色外观：

- 白色侧栏、白色顶栏、浅灰页面背景。
- 主色保持 `#3157d5`，成功、警告、错误继续使用 Ant Design 语义色。
- 左侧导航展开宽度保持约 `238px`，折叠宽度约 `72px`，顶栏高度约 `68px`。
- 保留当前圆角、轻阴影、卡片、表格、抽屉、Modal、Tabs、Tag、Steps 等组件语言。
- 页面内容宽度按工作类型变化：列表页可限制最大宽度，管道、本体图谱和应用构建器使用全宽画布。
- 借鉴 Palantir 的资源工作流、画布、详情侧栏、预览面板和信息密度，不复制其 Logo、图标、配色或商业标识。
- 导航、页面标题、按钮、提示、状态和错误使用中文；必要时在页面副标题中显示英文能力名。

### 11.2 全局外壳与中文导航

```text
┌──────────────────────────────────────────────────────────────┐
│ Logo   当前页面 / 当前资源                     搜索  通知  AI  用户 │
├────────────────┬─────────────────────────────────────────────┤
│ 数据           │                                             │
│   数据连接     │                                             │
│   管道构建     │                                             │
│   数据质量     │              当前页面内容                   │
│   数据血缘     │                                             │
│ 本体           │                                             │
│   本体管理     │                                             │
│   对象探索     │                                             │
│ 应用           │                                             │
│   分析看板     │                                             │
│   业务应用     │                                             │
│   自动化       │                                             │
│   审批中心     │                                             │
│ AIP            │                                             │
│   智能体工作室 │                                             │
│   对话中心     │                                             │
│────────────────│                                             │
│ 控制面板       │ 仅管理员可见                               │
└────────────────┴─────────────────────────────────────────────┘
```

全局规则：

- “数据 / 本体 / 应用 / AIP”是不可点击的导航分组，不设置分组首页。
- 每个功能项直接打开对应页面，不经过 Applications 门户或卡片中转页。
- 不增加 Home、Applications、Files、Spaces、Projects、Recent 等页面。
- 登录默认落点：Builder/Admin 进入“数据连接”，Viewer 进入“对象探索”。
- 顶栏显示当前页面、资源面包屑、全局搜索、通知、AIP 助手和用户菜单。
- 顶栏全局搜索查找数据源、管道、本体类型、Action、Function、看板、业务应用和智能体；业务对象正文搜索在“对象探索”内完成。
- 通知使用右侧抽屉，展示审批、质量告警、管道失败和系统通知。
- AIP 助手使用宽 `480px` 的右侧 Drawer；上下文以可见、可移除的引用标签展示，只传签名资源引用和非敏感状态 ID，不发送整页数据。
- 打开页面或助手不自动调用模型；用户发送消息后才创建临时 AssistSession，24 小时后过期，只有显式“保存为对话”才转为持久 Thread。
- 左侧导航支持折叠；折叠后显示图标和 Tooltip，分组通过分隔线和间距表达。
- 控制面板固定在侧栏底部；拥有任一已注册管理职责的用户可见，进入后只显示获授的二级管理页面。

### 11.3 导航权限

| 角色 | 默认可见功能 |
|---|---|
| Viewer | 对象探索、分析看板、业务应用、审批中心、对话中心 |
| Builder | Viewer 权限 + 数据连接、管道构建、数据质量、数据血缘、本体管理、自动化、智能体工作室 |
| Admin | 全部功能 + 控制面板 |
| 细分管理员职责 | 继承其 Viewer/Builder 产品能力 + 控制面板中获授的身份、权限、AIP、运维、备份或审计页面 |

实际操作仍由资源权限、RBAC、ABAC 和字段权限二次校验，隐藏导航不能代替后端鉴权。

### 11.4 数据连接

页面组成：连接列表、新建连接向导、连接详情。路由使用：

- `/data/connections`：连接列表。
- `/data/connections/new`：新建连接向导。
- `/data/connections/:id`：连接详情。
- `/data/connections/:id/assets/:assetId`：可深链接的资产详情。
- `/data/connections/:id/edit`：编辑连接配置。

功能：

- 数据源列表：名称、类型、状态、负责人、最近同步和更新时间。
- 支持 MinIO/S3 CSV、MySQL、PostgreSQL、Kafka、外部 Pulsar。
- 新建、编辑、停用和删除连接。
- 连接测试和错误诊断。
- 浏览 bucket/prefix、数据库表或 topic 等外部资产。
- Schema 推断、字段类型、可空性和样例值。
- 前 N 行/条数据预览。
- 创建同步配置并跳转到管道构建。
- 查看同步历史、读取量、失败原因和重试入口。
- 凭据只在创建或轮换时提交，查询接口返回脱敏值和 Secret 引用。

页面结构：列表页 + 新建连接向导 + 连接详情 Tabs（概览、资产、同步任务、运行记录、设置）。

#### 11.4.1 数据连接列表（已确认）

页面目标：让 Builder 和 Admin 在一个紧凑资源表格中查找、判断和管理所有外部数据连接；本页不承担资产预览、字段映射或管道配置。

页面布局：

1. 标题区显示“数据连接”、一句用途说明，以及右侧“刷新”“新建连接”按钮。
2. 标题下方使用单行紧凑统计：全部、正常、异常、未测试；不使用大型统计卡片。
3. 筛选工具栏包含名称搜索、连接类型、连接状态、负责人和“重置筛选”。
4. 主体使用表格，不提供卡片视图；筛选、排序和分页状态同步到 URL query。
5. 默认排序为异常优先，其次按最近更新时间倒序。

表格字段：

| 字段 | 展示与行为 |
|---|---|
| 连接名称 | 类型图标、名称和简短说明；点击整行进入详情 |
| 类型 | MinIO/S3 CSV、MySQL、PostgreSQL、Kafka 或外部 Pulsar |
| 连接状态 | 正常、异常、测试中、未测试、已停用；仅表示平台能否访问外部数据源 |
| 资产数量 | 已发现的文件、表或 Topic 数量 |
| 同步状态 | 实时、成功、失败、从未同步；表示引用该连接的最近一次管道运行结果 |
| 最近检查 | 最近一次连接测试时间，支持相对时间和完整时间 Tooltip |
| 负责人 | 用户头像和姓名 |
| 更新时间 | 最后一次配置更新时间 |
| 操作 | 更多菜单，不在每行堆叠多个按钮 |

连接状态和同步状态必须分列，避免把“连接可用但管道运行失败”等情况压缩成一个含糊状态。

行操作：

- 查看详情。
- 测试连接。
- 浏览资产。
- 创建管道：跳转到 `/data/pipelines/new?connectionId={id}` 并预选当前连接。
- 编辑配置。
- 停用连接。
- 删除连接。

约束与权限：

- Builder 可以查看、新建、编辑、测试、浏览资产、创建管道和停用连接。
- Admin 拥有全部能力；只有 Admin 可以永久删除连接。
- Viewer 默认看不到“数据连接”导航和路由。
- 被任何管道引用的连接禁止删除，只允许停用，并在确认框中列出引用数量和查看入口。
- 停用连接不删除历史运行、血缘、审计或已生成对象。
- 删除使用二次确认，界面优先建议“停用连接”。
- 密码、Token、Secret Key 不得出现在列表、详情响应、浏览器日志和审计差异正文中。

异常诊断：

- 点击异常状态打开右侧诊断抽屉，不离开列表上下文。
- 抽屉显示失败阶段、发生时间、用户可读原因、请求编号、建议操作和技术详情折叠区。
- 操作包括“复制诊断信息”“重新测试”“编辑配置”。
- 不直接把底层 HTTP 500、Java 堆栈或连接密码展示给用户。

页面状态：

- 首次空状态：说明支持的五类连接，并提供“新建第一个连接”。
- 筛选空状态：保留当前筛选条件，提供“清除筛选”。
- 加载失败：显示失败阶段、请求编号、“重新加载”，并保留用户的筛选条件。
- 测试中：仅更新对应行状态，不阻塞整个列表。
- 删除或停用成功后刷新当前页；若当前页已无数据，回到上一页。

参考实现边界：可复用 `CsvImportPage.tsx` 的视觉和交互经验，但新仓库直接按本节结构实现，不复制内联新建、Iceberg 入口或混合职责组件。

#### 11.4.2 新建连接向导（已确认）

路由：`/data/connections/new`。页面目标是安全地完成连接类型选择、配置、凭据提交、连通性验证和资源创建；资产正文预览与消费策略不属于本页。

整体布局：

- 使用全屏四步向导：`选择类型 → 连接配置 → 测试与发现 → 确认创建`。
- 顶部显示返回入口、页面标题和 Steps；底部固定“取消 / 上一步 / 下一步”操作栏。
- 步骤切换保留当前内存状态；v1 不把任何向导草稿写入 localStorage 或 sessionStorage。
- 用户取消、浏览器后退或切换路由时，如已修改内容，必须确认是否放弃。

##### 第一步：选择类型

使用紧凑分类列表，不使用应用商店式大卡片：

- 文件与对象存储：MinIO/S3 CSV。
- 关系数据库：MySQL、PostgreSQL。
- 消息系统：Kafka、外部 Pulsar。

“外部 Pulsar”必须带有说明：“连接用户已有的 Pulsar 集群，不是平台内部事件总线”。前端不提供平台 Pulsar 的创建、编辑或删除入口。

##### 第二步：连接配置

公共字段：

| 字段 | 规则 |
|---|---|
| 连接名称 | 必填；去除首尾空格后不区分大小写唯一 |
| 说明 | 选填；用于列表和详情摘要 |
| 负责人 | 必填；默认当前用户，可选择有 Builder/Admin 权限的用户 |
| 标签 | 选填；使用受控长度和数量 |
| 连接超时 | 高级设置；默认 15 秒，后端限制最小和最大值 |

类型字段：

| 类型 | 配置字段 |
|---|---|
| MinIO/S3 CSV | Endpoint、Region、Access Key、Secret Key、可选 Session Token、Path Style、TLS、允许的 Bucket/Prefix |
| MySQL | Host、Port、Database、Username、Password、TLS 模式、允许的 Schema |
| PostgreSQL | Host、Port、Database、Username、Password、TLS 模式、允许的 Schema |
| Kafka | Bootstrap Servers、Security Protocol、SASL Mechanism、Username、Password、CA 证书、可选 Schema Registry |
| 外部 Pulsar | Service URL、Admin URL、Tenant、Namespace、Token 或 TLS 客户端证书、允许的 Topic 范围 |

字段约束：

- 普通用户不能提交任意 JDBC URL；前端使用类型化字段，后端只组装允许的 JDBC 参数。
- TLS 证书支持 PEM 文本或文件上传，证书和私钥归入凭据，不写入普通连接配置。
- “跳过证书验证”默认关闭，生产模式由后端完全禁用。
- Kafka Consumer Group、offset 策略、Pulsar Subscription、初始位置和事件时间配置留在管道构建页面。
- Pulsar Admin URL 用于 Topic 发现；Service URL 用于数据读取，两项测试结果分开显示。

##### 平台托管凭据

向导提供“新建平台凭据”和“使用已有凭据”两种方式：

- 新凭据使用 AES-256-GCM 加密，密文、nonce、算法和 key version 保存在 Postgres；主密钥通过 Docker Secret 注入，不进入数据库、镜像或仓库。
- 数据连接只保存 `secret_ref`；API 永不返回密码、Token、私钥、Access Key/Secret Key 或解密后的凭据。
- 编辑时空凭据表示沿用原值，只有显式填写并确认后才轮换。
- 系统可提供 `file://` Secret provider 支持预置和自动化部署，但不作为普通 Builder 的主要创建方式。
- 审计只记录凭据创建、引用、轮换、撤销的主体和时间，不记录明文或密文正文。
- 删除连接时仅删除无其他引用的托管凭据；共享凭据必须保留并报告引用数量。

建议的托管凭据记录至少包含：`id/provider/ciphertext/nonce/key_version/credential_type/created_by/created_at/rotated_at/revoked_at`。密钥轮换必须支持按 `key_version` 解密旧记录和后台重新加密。

##### 第三步：测试与发现

依次显示以下检查项，单项状态为等待、运行中、通过、警告或失败：

1. 地址解析与网络连接。
2. TLS 握手。
3. 身份认证。
4. 元数据访问。
5. 资产发现。

发现结果只展示元数据：

- MinIO/S3：Bucket、Prefix 和文件数量。
- MySQL/PostgreSQL：Schema、Table/View 和字段数量。
- Kafka/外部 Pulsar：Topic、Partition 和元数据读取状态。

本步骤不读取或展示业务数据样例；数据预览放在连接详情的“资产”页，并再次执行权限检查。

测试规则：

- TLS、认证或目标服务不可达属于阻塞失败，不能进入确认步骤。
- 能建立数据连接但无权枚举全部资产时允许继续，连接状态标记为“正常 · 发现受限”，并说明需要手动输入授权范围。
- S3 无权列举全部 Bucket 时允许验证用户指定 Bucket；Pulsar Broker 可连接但 Admin API 无权限时允许以后手工指定 Topic。
- 测试支持取消、超时和重新运行；错误显示失败阶段、用户可读原因、请求编号和恢复建议。
- Java 堆栈、内部绝对路径、密钥和原始依赖响应不进入前端错误正文。

##### 第四步：确认创建

只读摘要显示连接名称、类型、负责人、目标地址、TLS/认证方式、授权资产范围、测试时间、测试结果和发现数量。所有凭据只显示“已配置”或“使用已有凭据”。

点击“创建连接”后，后端在一个业务事务中：

1. 校验测试结果仍在有效期内，并验证连接配置指纹未变化。
2. 创建或引用加密凭据。
3. 保存连接元数据和 `secret_ref`。
4. 保存最近测试结果与资产发现摘要。
5. 写入脱敏审计记录。
6. 返回连接 ID；任一步失败都清理本次产生的无引用凭据和半成品记录。

创建成功后跳转 `/data/connections/{id}`，显示成功提示和“浏览资产 / 创建管道 / 返回连接列表”；不自动进入管道构建。

##### API 与安全边界

建议 API：

```text
POST /v1/data-sources/test          使用瞬时配置测试，返回分阶段结果、配置指纹和短期 test_token
POST /v1/data-sources               校验 test_token，原子创建凭据与连接
GET  /v1/credentials?usable=true    只返回当前用户可引用的凭据名称、类型和脱敏元数据
POST /v1/data-sources/{id}/rotate-credential
```

- `test_token` 与用户、连接类型、规范化配置指纹和过期时间绑定，不能用于其他连接；默认有效期 15 分钟。
- 测试请求中的凭据仅存在于请求处理内存，不写请求日志、APM span tag、异常正文或临时文件。
- 测试和创建接口按用户与目标地址限流，设置 DNS、连接、读取和总超时。
- 后端阻止 loopback、link-local、云元数据地址和禁止端口；允许 Compose 网络及 Admin 配置的私网范围，并防止 DNS rebinding。
- 前端即时校验不能替代后端校验；连接测试也不能替代创建时的权限、名称唯一性和目标策略检查。

##### 前端代码拆分

```text
pages/data-connections/
├── NewConnectionPage.tsx
├── components/ConnectionWizardShell.tsx
├── steps/ConnectionTypeStep.tsx
├── steps/ConnectionConfigStep.tsx
├── steps/ConnectionTestStep.tsx
├── steps/ConnectionReviewStep.tsx
├── forms/MinioS3ConnectionFields.tsx
├── forms/JdbcConnectionFields.tsx
├── forms/KafkaConnectionFields.tsx
├── forms/PulsarConnectionFields.tsx
├── hooks/useConnectionWizard.ts
├── services/dataConnections.ts
└── types.ts
```

连接类型字段定义尽量由 schema 配置驱动，但密码输入、证书上传和依赖字段仍使用显式组件，避免动态表单绕过类型检查和安全规则。

#### 11.4.3 连接详情公共外壳与状态（已确认）

详情页顶部固定显示返回“数据连接”、类型图标、名称、说明、连接状态、同步状态、负责人和最近检查时间。主要操作为“测试连接”“创建管道”；更多菜单包含编辑、轮换凭据、停用/恢复和删除。

Tabs 固定为：`概览 / 资产 / 同步任务 / 运行记录 / 设置`。“同步任务”表示引用当前连接的管道配置，“运行记录”表示这些管道产生的执行实例，两者不得混用。

连接状态：

| 内部状态 | 中文展示 | 含义 |
|---|---|---|
| `UNTESTED` | 未测试 | 尚无有效测试结果 |
| `TESTING` | 测试中 | 正在执行分阶段测试 |
| `HEALTHY` | 正常 | 网络、TLS、认证和资产发现正常 |
| `HEALTHY_RESTRICTED` | 正常 · 发现受限 | 数据连接可用，但无法枚举全部资产 |
| `ERROR` | 异常 | 网络、TLS、认证或目标服务失败 |
| `DISABLED` | 已停用 | 禁止新发现、预览和管道运行 |

同步状态独立计算：`无任务 / 空闲 / 运行中 / 实时同步 / 部分失败 / 全部失败`。连接正常但管道失败时必须同时显示“连接正常”和“同步失败”，不能合并成一个模糊状态。

“测试连接”只验证连接能力和资产发现，不触发管道、不读取完整业务正文。测试进行时更新标题状态和健康区域，不阻塞用户切换到其他 Tab。

#### 11.4.4 概览页（已确认）

概览使用紧凑两列信息布局，不使用大型统计卡片：

1. 连接信息：类型、非敏感地址、端口、Database/Tenant/Namespace、TLS、认证方式、凭据状态、负责人和创建/修改信息。
2. 健康状态：最近测试时间和网络、TLS、认证、元数据访问、资产发现五阶段结果。
3. 资产摘要：按连接类型显示 Bucket/Prefix/File、Schema/Table/View 或 Topic/Partition 数量。
4. 同步任务摘要：关联管道总数，以及草稿、已发布、运行中、流式和失败数量。
5. 最近运行：最近五次运行的管道、触发方式、开始时间、读/写/拒绝数量、状态和耗时。
6. 最近活动：创建、编辑、测试、凭据轮换、创建管道、停用和恢复等脱敏事件。

凭据只展示“已配置 / 即将过期 / 已撤销”、凭据类型和轮换时间，不展示 Password、Token、Secret Key、私钥、完整证书正文或可逆密文。

概览采用聚合接口 `GET /v1/data-sources/{id}/overview`，一次返回连接摘要、健康结果、资产统计、管道统计、最近五次运行和最近活动。某个聚合分区失败时，其余分区仍可展示，并在失败区域提供单独重试；不能因为运行记录依赖失败而让整个连接详情白屏。

#### 11.4.5 资产页（已确认）

资产页负责发现、查看 Schema、安全预览和查看下游使用情况。顶部提供资产搜索、类型筛选、状态筛选和“刷新资产”。主体为左侧层级树和右侧资产表格：

- MinIO/S3：Bucket → Prefix → File。
- MySQL/PostgreSQL：Database → Schema → Table/View。
- Kafka：Cluster → Topic。
- 外部 Pulsar：Tenant → Namespace → Topic。

资产表格字段：名称与完整路径、资产类型、Schema 状态、字段数量、大小/估算行数/Partition 数、权限状态、最近发现时间和是否被管道使用。

点击资产打开右侧详情抽屉，同时更新为 `/data/connections/{id}/assets/{assetId}`；刷新或分享 URL 可恢复同一资产，关闭抽屉回到连接的资产 Tab。抽屉内部包含：

- 概览：完整路径、类型、规模、更新时间、发现时间和读取权限。
- Schema：字段名、推断类型、原始类型、可空性、敏感标记、主键候选和 Schema 变化状态。
- 数据预览：按权限读取有限样例并脱敏。
- 使用情况：引用该资产的管道、本体类型、看板、业务应用和智能体。

Schema 规则：

- 支持“重新推断 Schema”，保存 schema hash/version，并与上一个版本比较。
- 新增、删除、类型变化和可空性变化分别标记；受影响管道进入健康告警，但不自动改写已发布映射。
- 无权限查看的下游资源只显示“存在受限引用”，不泄露名称或配置。

预览规则：

- 默认最多 50 行，可选择 100 行；响应正文最大 1 MiB，并设置读取与总超时。
- 字段脱敏由本体/资产字段权限执行，前端不能自行决定是否显示原值。
- 不提供从预览页导出完整表或完整 Topic 的入口。
- Kafka 使用临时只读 Consumer，关闭 auto commit，不提交 offset；外部 Pulsar 使用 Reader API，不创建持久 Subscription。
- 消息预览默认读取最近少量消息，结束后立即关闭 Consumer/Reader，不影响业务消费者。

“刷新资产”启动异步 discovery run：

- 已缓存资产在刷新期间仍可浏览，页面显示 task ID、进度和发现数量。
- 新资产标记“新增”，本次未发现但历史存在的资产标记“不可用”，不立即物理删除。
- Schema 变化标记“已变化”，并通知受影响管道负责人。
- 支持 SSE 推送进度；SSE 断开后回退到按 task ID 轮询，不重复启动任务。
- 连接为 `DISABLED` 时禁止刷新和预览；`HEALTHY_RESTRICTED` 时允许手动输入并验证授权资产路径。

#### 11.4.6 同步任务页（已确认）

同步任务页展示引用当前连接的管道，不在连接详情内重复实现管道编辑器。表格字段：管道名称、源资产、批处理/流式、目标对象类型、草稿/已发布/运行中/失败/已停用状态、调度方式、最近运行、下次运行和负责人。

页面操作：

- 新建管道、名称搜索、模式筛选和状态筛选。
- 点击管道进入 `/data/pipelines/{pipelineId}`。
- 已发布批处理管道可“立即运行”；失败状态可打开对应失败运行。
- 启停调度、修改字段映射、修改 Topic/Subscription、发布和版本变更必须进入管道页面完成。
- 从连接级入口新建管道使用 `/data/pipelines/new?connectionId={connectionId}`；从资产入口使用 `/data/pipelines/new?connectionId={connectionId}&assetId={assetId}`。

#### 11.4.7 运行记录页（已确认）

运行记录聚合所有使用当前连接的管道执行实例。筛选条件包括管道、源资产、批处理/流式、状态、触发方式和时间范围。

表格字段：运行 ID、管道名称、源资产、手动/定时/事件/重试触发方式、开始时间、持续时间、读取数量、写入数量、拒绝数量、状态和 Flink Job ID。

点击运行打开详情抽屉，Tabs 为：`运行概要 / 阶段 / 日志 / 指标 / 错误与重试`。阶段按以下链路展示：

```text
源数据读取 → Flink 转换与质量校验 → 写入平台 Pulsar → 对象投影 → HugeGraph/OpenSearch
```

每个阶段展示状态、耗时、输入/输出/失败数量、`correlation_id`、Flink Job ID、平台 Pulsar Topic/Subscription/backlog、投影状态及 HugeGraph/OpenSearch 写入状态。SkyWalking trace ID 只作为 Admin deep link，不取代业务运行状态。

操作规则：

- 运行中批任务可以取消；失败批任务可以重试。
- 流任务的停止、savepoint 和恢复进入管道页面处理，连接详情只展示状态和入口。
- Builder 可查看脱敏日志；Admin 可打开 SkyWalking；Viewer 无数据连接运行权限。
- 日志、指标标签和 SkyWalking span tag 不得包含凭据、完整消息正文或未脱敏敏感字段。
- SSE 实时日志断开时提示重连，不把任务误判为失败；任务最终状态由后端运行记录决定。

#### 11.4.8 设置与编辑（已确认）

设置 Tab 分为基本信息、连接参数、凭据与证书、访问范围与权限、危险操作五个区域。

基本信息可修改名称、说明、负责人和标签，不要求重新测试。连接参数包括 Endpoint/Host/Port、Database/Schema、TLS、Kafka Bootstrap Servers、外部 Pulsar Service/Admin URL、超时和受控高级参数；修改连接目标、TLS、认证或凭据后必须重新测试成功才能保存。

凭据与证书只展示凭据名称、类型、创建/轮换/到期时间和引用数量，支持更换已有凭据、轮换凭据和上传新证书。轮换流程为 `填写新凭据 → 测试 → 确认替换 → 写审计`；测试失败时继续使用旧凭据，不能造成现有连接中断。

访问范围与权限管理允许的 Bucket/Prefix、Schema/Table、Topic，以及连接查看、数据预览和创建管道的主体。缩小范围前必须展示受影响资产和管道；后端再次校验，不能只依赖前端提示。

编辑路由 `/data/connections/{id}/edit` 使用“基本信息 / 连接配置 / 凭据 / 资产访问范围”分区表单，不重复新建四步向导：

- 只修改名称、说明、负责人和标签时直接保存。
- 修改地址、TLS、认证或凭据时主按钮变为“测试并保存”；测试失败不修改当前有效配置。
- 使用 `version`/ETag 乐观锁，冲突时展示双方更新时间并要求重新加载，不静默覆盖。
- 离开未保存页面必须确认。

停用规则：

- Builder 和 Admin 可停用，停用前显示关联管道、活动运行和下游影响并二次确认。
- 停用后阻止新管道运行、资产发现和数据预览。
- 正在运行的批任务默认允许完成；流任务先创建 savepoint，成功后停止。savepoint 失败时停用操作进入“需要处理”，不能假装已安全停止。
- 历史运行、血缘、已生成对象、审计和连接快照保留；恢复连接后必须重新测试。

删除规则：

- 只有 Admin 可以永久删除；连接必须已停用、没有管道引用且没有活动运行。
- 托管凭据被其他资源引用时不删除凭据；无其他引用时随事务清理。
- 删除连接不删除已生成对象、历史血缘、已完成运行摘要或审计；历史记录保留连接 ID 和名称快照。

#### 11.4.9 权限、API、数据模型与代码结构（已确认）

权限矩阵：

| 操作 | Viewer | Builder | Admin |
|---|---:|---:|---:|
| 查看连接与资产 Schema | 否 | 是 | 是 |
| 预览资产数据 | 否 | 按资源权限 | 是 |
| 新建、编辑、测试、创建管道 | 否 | 是 | 是 |
| 查看运行与脱敏日志 | 否 | 是 | 是 |
| 停用、恢复 | 否 | 是 | 是 |
| 管理托管凭据 | 否 | 仅自有或获授权 | 是 |
| 永久删除 | 否 | 否 | 是 |
| 打开 SkyWalking | 否 | 否 | 是 |

主要 API：

```text
GET    /v1/data-sources
POST   /v1/data-sources
GET    /v1/data-sources/{id}
GET    /v1/data-sources/{id}/overview
PATCH  /v1/data-sources/{id}
DELETE /v1/data-sources/{id}

POST   /v1/data-sources/test
POST   /v1/data-sources/{id}/test
POST   /v1/data-sources/{id}/disable
POST   /v1/data-sources/{id}/enable
POST   /v1/data-sources/{id}/rotate-credential

GET    /v1/data-sources/{id}/assets
POST   /v1/data-sources/{id}/discover
GET    /v1/data-sources/{id}/assets/{assetId}
POST   /v1/data-sources/{id}/assets/{assetId}/infer-schema
POST   /v1/data-sources/{id}/assets/{assetId}/preview
GET    /v1/data-sources/{id}/assets/{assetId}/usage

GET    /v1/data-sources/{id}/pipelines
GET    /v1/data-sources/{id}/runs
GET    /v1/pipeline-runs/{runId}
GET    /v1/pipeline-runs/{runId}/logs
POST   /v1/pipeline-runs/{runId}/cancel
POST   /v1/pipeline-runs/{runId}/retry
```

资产发现和 Schema 推断返回 task ID；客户端优先用 SSE 接收进度，断开后按 task ID 轮询。所有列表 API 使用一致的分页、排序和过滤协议，并返回可用于前端恢复 URL 状态的规范化参数。

后端至少需要以下表或等价持久模型：

```text
data_sources
connection_secrets
data_source_test_results
data_source_assets
data_source_asset_fields
data_source_discovery_runs
pipelines
pipeline_runs
pipeline_run_stages
```

数据约束：

- `data_sources` 不保存明文凭据；资产使用稳定 `asset_id`，不能只依赖显示名称或路径。
- 消失资产使用状态标记而不是立即物理删除；Schema 用 hash/version 判断变化。
- 运行阶段保存 `correlation_id`、Flink Job ID 和平台 Pulsar 位置信息，用于关联投影和 SkyWalking。
- 连接、资产、运行和凭据表的删除/保留策略必须满足前述历史与审计约束。

前端目标结构：

```text
pages/data-connections/
├── DataConnectionListPage.tsx
├── NewConnectionPage.tsx
├── DataConnectionDetailPage.tsx
├── EditConnectionPage.tsx
├── tabs/
│   ├── ConnectionOverviewTab.tsx
│   ├── ConnectionAssetsTab.tsx
│   ├── ConnectionPipelinesTab.tsx
│   ├── ConnectionRunsTab.tsx
│   └── ConnectionSettingsTab.tsx
├── assets/
│   ├── AssetTree.tsx
│   ├── AssetTable.tsx
│   ├── AssetDetailDrawer.tsx
│   ├── AssetSchemaView.tsx
│   └── AssetPreview.tsx
├── runs/
│   ├── ConnectionRunsTable.tsx
│   └── PipelineRunDrawer.tsx
├── components/
│   ├── ConnectionHeader.tsx
│   ├── ConnectionStatus.tsx
│   ├── ConnectionHealthPanel.tsx
│   └── ConnectionDiagnosticDrawer.tsx
├── hooks/
├── services/
└── types.ts
```

#### 11.4.10 数据连接验收标准（已确认）

1. MinIO/S3 CSV、MySQL、PostgreSQL、Kafka、外部 Pulsar 均可创建、测试、编辑、停用和恢复。
2. 外部 Pulsar 与平台事件总线在 UI、API、配置和权限中完全分离。
3. API、浏览器状态、日志、审计和 SkyWalking span 中不存在明文凭据。
4. 无资产枚举权限时可创建“正常 · 发现受限”连接，并能手工验证授权范围。
5. 资产发现、Schema 推断或预览失败不会破坏连接；刷新期间缓存资产可继续浏览。
6. Kafka/Pulsar 预览不提交 offset、不创建持久消费位置、不影响业务消费者。
7. 敏感字段按用户权限脱敏；无权下游资源不泄露名称。
8. 连接正常但管道失败时正确显示连接状态和同步状态。
9. 停用连接阻止新任务；批任务允许完成；流任务 savepoint 后停止，失败时明确进入待处理状态。
10. 被管道引用、有活动运行或未停用的连接不能永久删除。
11. 凭据轮换测试失败时旧凭据继续有效，共享凭据不会被误删。
12. 创建、编辑、测试、预览、轮换、停用、恢复和删除都有脱敏审计。
13. URL 可恢复列表筛选、详情 Tab 和资产抽屉；SSE 断开不会重复启动任务。
14. 所有错误都提供失败阶段、请求编号、恢复动作和权限安全的技术详情。

### 11.5 管道构建

管道构建使用完整 DAG 编辑器。Flink 是唯一数据计算引擎，前端和 API 不提供 Java/SeaTunnel/Flink 引擎选择；正式输出只进入平台 Pulsar，再由独立 Projection Worker 写 HugeGraph/OpenSearch。

路由：

```text
/data/pipelines
/data/pipelines/new
/data/pipelines/:id
/data/pipelines/:id/edit
/data/pipelines/:id/runs/:runId
/data/pipelines/:id/proposals/:proposalId
```

#### 11.5.1 管道列表（已确认）

列表使用紧凑表格，不使用卡片。标题区提供“刷新”“新建管道”；筛选包含名称、批处理/流式、生命周期、运行状态和负责人，筛选/排序/分页同步到 URL。

表格字段：管道名称和说明、源连接/资产、目标对象/关系类型、模式、生命周期、运行状态、调度方式、最近运行、负责人、更新时间和更多操作。

生命周期与运行健康分开：

| 生命周期 | 含义 |
|---|---|
| 草稿 | 尚未发布 |
| 待审核 | 已提交变更提议 |
| 已发布 | 有生效的不可变版本 |
| 已暂停 | 禁止新运行，流任务已停止 |
| 已归档 | 只保留历史和血缘 |

| 运行状态 | 含义 |
|---|---|
| 从未运行 | 没有运行记录 |
| 健康 | 最近运行和投影成功 |
| 运行中 | 批任务正在执行 |
| 实时运行 | 流任务持续执行 |
| 降级 | Projection 积压或派生索引失败 |
| 失败 | 最近运行失败 |

行操作：打开编辑器、立即运行批任务、启动/停止流任务、查看运行、复制、暂停和归档。只有从未发布且没有运行记录的草稿可永久删除。

#### 11.5.2 新建管道（已确认）

`/data/pipelines/new` 使用全屏紧凑模板列表：空白管道、文件/数据库批量导入、Kafka 实时对象管道、外部 Pulsar 实时对象管道、对象与关系构建。

创建时填写名称、说明、负责人、批处理/流式模式、模板、源连接和源资产。来自数据连接的入口支持 `connectionId`，来自资产的入口同时支持 `assetId`；创建后进入编辑器草稿，不在新建页完成复杂映射。

#### 11.5.3 可视化编辑器（已确认）

编辑器顶部显示返回、名称、版本/草稿状态、保存状态，以及“预览 / 校验 / 提交审核 / 发布”。资源 Tabs 固定为 `编辑 / 变更提议 / 历史`。

主体为左侧节点库、中间 DAG 画布、右侧节点配置和底部 `预览 / Schema / 校验问题 / 运行日志` 面板。支持选择、连线、缩放、适应画布、自动布局、撤销、重做、复制节点、删除节点和快捷键。

草稿自动保存到后端，显示保存中、已保存和版本冲突；Zustand 仅保存当前选择、面板和撤销栈，后端草稿才是可恢复真相源，草稿不写 localStorage。

#### 11.5.4 节点库和配置（已确认）

节点类型：

| 节点 | 主要配置与限制 |
|---|---|
| 数据源 | MinIO/S3 CSV、MySQL、PostgreSQL、Kafka、外部 Pulsar；连接、资产、字段、格式和读取策略 |
| 选择/重命名 | 选择、删除、重命名和排序字段 |
| 类型转换 | String、Boolean、Int/Long、Decimal、Date、Timestamp、JSON；失败策略为停止、置空或隔离 |
| 过滤 | 类型化 AND/OR 条件，不接受任意 SQL |
| 派生字段 | 字符串、数值、日期、条件、空值和常量的安全表达式；不允许任意 JS/Python/Java/UDF |
| 去重 | 批处理键与保留策略；流处理键、事件时间和 state TTL |
| JOIN | 批处理主源 + 最多三个辅助源，INNER/LEFT；流处理只允许有界维表 lookup/broadcast JOIN |
| 窗口 | 流处理的滚动、滑动、会话窗口、允许延迟和水位线 |
| 聚合 | group by、count、sum、avg、min/max、去重计数；流式聚合前必须有窗口 |
| 质量门禁 | 非空、类型、范围、正则、唯一和引用完整性；复杂规则引用数据质量规则集 |
| 本体对象输出 | 对象类型、对象 ID、属性映射、默认值、空值、upsert/delete、版本和敏感字段策略 |
| 本体关系输出 | 关系类型、源/目标对象 ID、关系属性和 upsert/delete |

质量失败策略为发现一条即停止、失败比例超过阈值停止、跳过或写入受限隔离 Topic。日志和普通 UI 不显示隔离记录的完整敏感正文。

管道可有多个输出节点，但必须至少有一个本体对象或关系输出。关系输出引用的源/目标对象类型和 ID 必须在发布时可解析。

#### 11.5.5 画布与 Schema 规则（已确认）

- 画布必须为 DAG，不允许循环；数据源只能作为起点，输出只能作为终点。
- 每个端口带类型化 Schema；连线传播字段，类型不兼容时立即标错。
- 删除或修改上游字段后，下游节点标记失效，不自动猜测替代字段。
- 一个流源只能有一套事件时间和水位线；流管道禁止连接不支持流语义的节点。
- v1 不支持 stream-stream JOIN，只支持流源与有界维表快照 JOIN。
- 未连接节点不进入发布版本；自动布局不得修改业务连线。
- JOIN、聚合和高状态量节点显示预计数据膨胀/状态大小警告。

#### 11.5.6 批处理与流处理（已确认）

批处理支持 MinIO/S3 文件和 MySQL/PostgreSQL 有界快照，触发方式为手动、Cron 或指定时间。并发策略为跳过、排队或取消旧运行。增量批处理可配置水位字段，`last_successful_watermark` 只有在 Projection 完成后才提交，Flink 读取/发布完成不能提前推进。

数据库 CDC 不进入 v1，连接器 PoC 未完成前 UI 不显示“实时数据库同步”。

Kafka 流源配置 Topic、平台生成的稳定 Consumer Group、Earliest/Latest/Timestamp/Specific Offsets、JSON 消息格式、事件时间、水位线和 Partition 发现周期。默认 Group ID：`ontology-{pipelineId}-{publishedVersion}`。

外部 Pulsar 流源配置 Topic、Subscription、Subscription 类型、Earliest/Latest/Timestamp、消息格式、事件时间和水位线；默认使用 Failover，默认名称：`ontology-{pipelineId}-{publishedVersion}`。

重置 Kafka offsets 或 Pulsar Subscription/Message ID 时，流任务必须先停止并创建 savepoint；页面展示可能重复或丢失的数据范围，只有 Admin 或 `pipeline.offset.reset` 权限可确认。

#### 11.5.7 Flink 运行策略（已确认）

- 流任务默认每 60 秒 checkpoint 到 MinIO，设置超时、最小间隔和保留数量；停止默认使用 stop-with-savepoint。
- 批任务默认不周期 checkpoint，大规模批任务可在受控高级设置中启用。
- 默认固定间隔重试三次；认证、Schema 和配置错误不无限重启，瞬时网络故障允许重试。
- 并行度由平台给出建议，Builder 可在单机 Compose 全局上限内修改；发布校验检查 TaskManager slots 和预计状态量。
- Flink JobManager REST 调用改为异步提交和状态同步，不在 HTTP 请求线程中轮询到作业结束。
- 平台只运行构建并校验哈希/签名的 Flink JAR，不允许用户上传任意 JAR。

动态数据源凭据不写 Pipeline IR、Flink program args、Web UI、日志或 SkyWalking tag。提交运行时，Ontology Core 为 run 登记短期工作负载授权；受信 Flink 作业以服务身份和 run ID 调用仅内网可达的凭据 Broker，获得该 run/connection 允许的凭据并仅保存在内存。Broker 校验服务身份、作业签名、run 状态、connection scope 和 TTL。

#### 11.5.8 预览和发布校验（已确认）

选中任意节点可“运行到此节点”。后端将同一 Pipeline IR 截断到选中节点，编译为有界 Flink 预览作业；不维护另一套 Java 转换解释器。预览默认 100 行、最大 1 MiB，执行字段脱敏，不写平台 Pulsar/HugeGraph/OpenSearch，异步运行、可取消并短期缓存。

发布校验分为图结构、Schema/类型、数据源/资产权限、本体输出、批流语义、运行资源/安全六类。问题等级：Error 禁止发布，Warning 确认后允许，Info 为优化建议。

#### 11.5.9 草稿、变更提议、发布和回滚（已确认）

- 修改已发布管道时创建新草稿，不影响当前生效版本；草稿自动保存并使用 ETag 乐观锁。
- 同一管道默认一个活动团队草稿；冲突时要求重新加载或显式合并，不静默覆盖。
- Builder 可提交变更提议，提议展示节点、字段映射、Schema、调度、消费位置、下游影响、验证结果、预览证据、评论和审批记录。
- 管道负责人、具有 `pipeline.publish` 权限的 Builder 或 Admin 可发布普通变更；重置 offset、删除输出字段、修改对象 ID 等高风险变更必须 Admin 审批。

发布过程固定为：锁定草稿 → 完整校验 → 编译 Pipeline IR → 生成不可变 Flink job spec → 保存血缘/影响范围 → 激活版本 → 按选择启动流任务或等待批任务触发 → 写审计。

发布版本不可修改，运行引用精确 `pipeline_version_id`。回滚只激活历史版本用于后续运行；流任务仅在 state/schema 兼容时从历史 savepoint 恢复。回滚不删除新版本已写入对象，也不自动恢复业务数据，界面必须明确提示。

#### 11.5.10 运行状态与故障恢复（已确认）

批处理阶段：

```text
SUBMITTED → COMPILING → QUEUED → STARTING → READING → TRANSFORMING
→ PUBLISHING → PROJECTING → COMPLETED
```

取消使用 `CANCELLING → CANCELLED`，任意阶段可进入 `FAILED`。Flink 完成平台 Pulsar 发布后，批任务必须进入 `PROJECTING`，等待 Projection Worker 的 batch ack、HugeGraph 写入和 OpenSearch 成功或明确降级状态，不能提前显示完成。

流处理状态：`STARTING → RUNNING → DEGRADED → STOPPING → STOPPED`，持续显示输入/输出/失败速率、checkpoint、外部源积压、平台 Pulsar 投影积压和 Projection 失败数。

故障规则：

- 取消批任务调用 Flink cancel，已投影对象不自动删除，运行记录保存取消点和已写入数量。
- 重试创建新 run ID、保存 `retry_of`，默认使用原发布版本，依靠 event ID 和对象版本幂等。
- 停止流任务默认 stop-with-savepoint；失败时保持可诊断状态，不伪装停止成功。
- Projection 失败使管道进入 DEGRADED，按策略重试后进 DLQ，可按失败批次/事件重放，不要求 Flink 重读整个数据源。
- HugeGraph 成功、OpenSearch 失败时不回滚图数据，搜索降级并允许从 HugeGraph 重建索引。
- Admin 可从 run/stage/error 打开 SkyWalking trace；SkyWalking 只用于诊断，Postgres/Pulsar/Flink/Projection 状态才是运行真相源。

#### 11.5.11 Pipeline IR 与 Job Spec（已确认）

前端只保存类型化 DAG，后端校验后编译为版本化 Pipeline IR，不能让前端或用户提交任意 Flink JSON/代码：

```json
{
  "api_version": "ontology.pipeline/v1",
  "pipeline_id": "pipeline-id",
  "version": 3,
  "mode": "STREAM",
  "sources": [{
    "node_id": "source-1",
    "connection_id": "connection-id",
    "asset_id": "topic-id"
  }],
  "graph": { "nodes": [], "edges": [] },
  "outputs": [{
    "type": "ONTOLOGY_OBJECT",
    "object_type_id": "Order"
  }],
  "runtime": {
    "parallelism": 2,
    "checkpoint_interval_ms": 60000,
    "restart_attempts": 3
  }
}
```

IR 不含明文秘密，只包含稳定资源 ID、不可变版本、节点参数和运行策略。编译器生成的 Job Spec、Flink JAR 版本、连接器哈希和 schema version 一并保存，确保历史运行可复现。

#### 11.5.12 API 与数据模型（已确认）

```text
GET    /v1/pipelines
POST   /v1/pipelines
GET    /v1/pipelines/{id}
PATCH  /v1/pipelines/{id}/draft
POST   /v1/pipelines/{id}/duplicate
POST   /v1/pipelines/{id}/validate
POST   /v1/pipelines/{id}/preview

GET    /v1/pipelines/{id}/versions
GET    /v1/pipelines/{id}/versions/{version}
GET    /v1/pipelines/{id}/diff
POST   /v1/pipelines/{id}/proposals
POST   /v1/pipelines/{id}/proposals/{proposalId}/approve
POST   /v1/pipelines/{id}/proposals/{proposalId}/reject
POST   /v1/pipelines/{id}/publish
POST   /v1/pipelines/{id}/rollback

POST   /v1/pipelines/{id}/run
POST   /v1/pipelines/{id}/start
POST   /v1/pipelines/{id}/stop
POST   /v1/pipelines/{id}/savepoint
POST   /v1/pipelines/{id}/pause
POST   /v1/pipelines/{id}/resume
POST   /v1/pipelines/{id}/archive

GET    /v1/pipelines/{id}/runs
GET    /v1/pipeline-runs/{runId}
GET    /v1/pipeline-runs/{runId}/events
GET    /v1/pipeline-runs/{runId}/logs
GET    /v1/pipeline-runs/{runId}/metrics
POST   /v1/pipeline-runs/{runId}/cancel
POST   /v1/pipeline-runs/{runId}/retry
POST   /v1/pipeline-runs/{runId}/replay-dlq

GET    /v1/pipeline-node-types
POST   /v1/pipeline-previews/{previewId}/cancel
```

仅内网服务身份可访问：

```text
POST /internal/v1/workload-credentials/exchange
POST /internal/v1/projection/ack
```

建议数据表或等价模型：

```text
pipelines
pipeline_drafts
pipeline_versions
pipeline_dependencies
pipeline_proposals
pipeline_proposal_comments
pipeline_runs
pipeline_run_stages
pipeline_run_events
pipeline_checkpoints
pipeline_schedules
pipeline_preview_runs
projection_batches
projection_ledger
projection_failures
```

发布版本不可修改，运行始终引用精确版本。DAG 可在版本表保存规范化 JSON，但源、输出和下游依赖必须单独索引供权限、血缘和影响分析查询；所有 run/batch/event 保留 `correlation_id`。

#### 11.5.13 权限（已确认）

| 操作 | Viewer | Builder | Admin |
|---|---:|---:|---:|
| 查看管道 | 否 | 按资源权限 | 是 |
| 创建/编辑草稿、预览、校验 | 否 | 是 | 是 |
| 手动运行批任务、启停流任务 | 否 | 按资源权限 | 是 |
| 提交变更提议 | 否 | 是 | 是 |
| 普通发布 | 否 | 负责人或 `pipeline.publish` | 是 |
| 高风险发布 | 否 | 否 | 是 |
| 重置 offset、DLQ 重放 | 否 | 特殊权限 | 是 |
| 打开 SkyWalking | 否 | 否 | 是 |
| 归档 | 否 | 负责人 | 是 |

导航隐藏不代替 API 鉴权；运行权限同时校验管道、源连接、源资产、目标本体和凭据使用权限。

#### 11.5.14 前端结构与参考边界（已确认）

```text
pages/pipelines/
├── PipelineListPage.tsx
├── NewPipelinePage.tsx
├── PipelineEditorPage.tsx
├── PipelineRunPage.tsx
├── editor/
│   ├── PipelineCanvas.tsx
│   ├── NodeLibrary.tsx
│   ├── NodeConfigPanel.tsx
│   ├── PipelineToolbar.tsx
│   ├── BottomPanel.tsx
│   └── nodes/
├── preview/
├── validation/
├── proposals/
├── history/
├── runs/
├── hooks/
├── services/
└── types.ts
```

新仓库直接实现上述目录、完整 DAG 编辑器、Pipeline IR 通用 Flink Job 和异步运行控制。实现中不提供多引擎选择、Iceberg/Trino 路径、专用 CSV 聚合作业、同步轮询或通用 JSON 管道定义。

#### 11.5.15 管道构建验收标准（已确认）

1. MinIO/S3 CSV、MySQL、PostgreSQL、Kafka、外部 Pulsar 均可作为源节点。
2. 批处理和流处理使用同一 DAG/Pipeline IR，模式特有语义和节点限制明确区分。
3. Flink 是唯一计算引擎，UI/API 不再出现 Java/SeaTunnel 引擎选择。
4. 正式运行路径不存在 Iceberg、Trino 和 SeaTunnel 目标或执行器。
5. 节点 Schema 正确向下游传播。
6. 删除或变更字段后，依赖节点明确失效，不静默改写映射。
7. Preview 与正式运行使用同一 Pipeline IR 和 Flink 运算语义。
8. 发布版本不可修改，所有运行引用精确版本。
9. 编辑草稿和变更提议不会直接影响当前生产版本。
10. Kafka/Pulsar 消费位置可控，危险重置需要停止、savepoint、影响提示和高权限确认。
11. 流任务可 checkpoint、stop-with-savepoint 和从兼容 savepoint 恢复。
12. Job Spec、Flink program args/UI、日志、审计和 SkyWalking 不包含明文凭据。
13. Flink 发布完成后批任务进入 PROJECTING，Projection batch ack 后才进入完成或明确降级。
14. Projection Worker 对重复/乱序事件幂等，支持重试、DLQ 和重放。
15. HugeGraph 成功而 OpenSearch 失败时不回滚图数据，搜索明确降级并可重建。
16. 批任务取消和重试保留已写入影响、原版本和 `retry_of`。
17. 回滚只影响后续运行，不虚假承诺回滚已写业务数据。
18. 发布前展示下游对象、看板、应用和智能体影响。
19. Builder、发布者、Admin、offset 重置和 DLQ 重放权限正确隔离。
20. 管理、提议、发布、运行和恢复操作均有脱敏审计。
21. SSE 断开不改变真实运行状态，重连不重复提交作业。
22. SkyWalking 只用于诊断，不作为运行真相源。
23. 全新 Compose 可完成 CSV/数据库批处理和 Kafka/外部 Pulsar 流处理端到端验证。
24. 全新数据库初始化或 Pipeline 发布失败时事务可恢复，不生成半发布版本或假成功记录。

### 11.6 数据质量

数据质量不再同步扫描 Postgres `object_records`，采用接入前、Flink 管道内和投影后三层执行。规则以不可变版本化规则集发布；失败正文不进入 Postgres、普通日志或 APM。

路由：

```text
/data/quality
/data/quality/rule-sets
/data/quality/rule-sets/new
/data/quality/rule-sets/:id
/data/quality/runs
/data/quality/runs/:runId
/data/quality/issues
/data/quality/issues/:issueId
/data/quality/quarantine
```

内部导航固定为：`概览 / 规则集 / 运行记录 / 质量问题 / 隔离区`。

#### 11.6.1 三层执行边界（已确认）

| 阶段 | 执行时机 | 主要规则 | 执行位置 |
|---|---|---|---|
| 接入前 | 资产刷新、管道发布/运行前、定时检查 | 必需字段、类型、CSV 可解析、Schema 漂移、新鲜度、空资产、数据量变化 | Ontology Core 持久化异步质量任务 |
| 管道内 | Flink 读取和转换期间 | 非空、类型、范围、正则、枚举、唯一、表达式、失败数量/比例 | Pipeline IR 编译后的 Flink operators |
| 投影后 | Projection batch、定时或手动 | 对象 ID、属性、关系源/目标、引用完整性、跨对象、属性唯一、数量异常 | Projection Worker 轻量随写校验 + Ontology Core 异步扫描 |

接入前阻断失败时不提交 Flink 作业。Projection Worker 只执行可随写完成的轻量约束；大量对象扫描使用 Postgres 持久任务队列和受控并发，不占用 HTTP 请求线程。v1 不增加独立 Quality Worker 容器。

#### 11.6.2 质量概览（已确认）

概览顶部提供“立即检查”“新建规则集”，筛选为时间范围、执行阶段、目标类型和负责人，状态同步到 URL。

不展示含义不清的综合质量分，展示规则通过率、受影响资源、失败运行、严重开放问题、隔离记录、通过率趋势、阶段失败分布、最不健康的资产/管道/对象和最近质量事件。

概览聚合接口分区降级：趋势加载失败不能阻止严重问题列表展示，每个区域提供独立重试和请求编号。

#### 11.6.3 规则集列表与生命周期（已确认）

生产资源只绑定规则集版本，不直接绑定可变单条规则。生命周期：草稿、待审核、已发布、已暂停、已归档。Pipeline 发布版本必须引用精确 `quality_rule_set_version_id`，后续规则修改不得改变历史管道和报告。

列表字段：名称、执行阶段、绑定目标、规则数量、当前版本、最高严重级别、生命周期、最近通过率、最近运行、负责人和更新时间。操作包括打开、复制、手动运行、提交审核、暂停和归档。

#### 11.6.4 新建规则集与规则类型（已确认）

`/data/quality/rule-sets/new` 使用四步全屏流程：`选择目标 → 配置规则 → 阈值与动作 → 测试并发布`。

第一步选择接入前/管道内/投影后阶段，以及数据资产、管道节点/输出、本体对象类型或关系类型。一个规则集只绑定一个主要执行阶段，避免同一规则在不同引擎产生不同含义。

规则类型：

- Schema：必需/未知字段、类型、可空性、Schema 版本和字段数量。
- 字段：非空、类型、数值/长度范围、安全正则、枚举、唯一、重复率和空值率。
- 数据集：行数/消息数、相对上次数据量变化、新鲜度、最大更新时间、失败比例和简单分布阈值。
- 本体：对象 ID、属性唯一、关系源/目标存在、引用完整性、关系数量和跨对象字段一致性。
- 受限表达式：类型化字段比较、算术、日期、IN、AND/OR/NOT、条件蕴含和注册的纯函数。

禁止 SQL、JavaScript、Python、任意 Java 类、文件/网络访问和未注册函数。表达式保存为类型化 AST，共享编译器生成 Flink 或 Java 执行逻辑，并通过契约测试保持语义一致。正则使用安全实现或严格复杂度/长度限制，禁止可造成 ReDoS 的无限制 Java Pattern。

常用模板通过单个事务型 Bulk API 创建，不允许前端循环调用单条规则接口。

#### 11.6.5 严重级别、阈值和动作（已确认）

严重级别与执行动作分开：

- 级别：信息、警告、错误、严重。
- 动作：仅记录、发送通知、运行降级、隔离失败记录、阻断批任务、暂停流任务、阻止 Projection。
- 阈值：最大失败数量、最大失败比例、连续失败次数、最小样本量和告警冷却时间。

默认映射：信息仅记录，警告通知并继续，错误隔离并降级，严重在超过阈值后阻断/暂停。流任务默认不因一条坏消息退出，先隔离，达到数量、比例或持续时间阈值后才暂停。

第四步使用受限样本测试规则，展示预计失败数/比例、脱敏样例、绑定影响和昂贵扫描警告。观察/告警规则可由负责人或 `quality.publish` 发布；阻断、暂停、禁止 Projection 等高风险规则必须 Admin 审批。

#### 11.6.6 规则集详情与版本（已确认）

详情 Tabs：`规则 / 绑定 / 运行记录 / 版本历史 / 设置`。规则列表展示类型、目标、严重级别、阈值、动作、最近结果和启用状态；排序只影响显示，不改变并行执行语义。

绑定展示资产、管道及版本、本体对象和关系；修改规则或绑定都创建新版本。版本历史展示规则增删、阈值/动作/字段变化、发布人、审批和生效时间；历史运行永远引用当时版本。

#### 11.6.7 质量运行与报告（已确认）

触发方式：手动、资产刷新、管道发布前、管道运行、Projection batch、定时任务和 DLQ 重放复检。幂等键为 `ruleSetVersion + triggerType + triggerResourceId`，避免一个 Pipeline Run 重复触发。

运行状态：`QUEUED → RUNNING → PASSED / WARN / FAILED / CANCELLED`。列表字段为 Run ID、规则集版本、阶段、目标、触发方式、关联 Pipeline Run、检查/通过/失败/隔离数量、状态、耗时和开始时间。

报告 Tabs：`概览 / 规则结果 / 失败样例 / 日志`。

- 概览：总检查、通过/警告/失败、失败比例、隔离数、严重级别、与上次对比及关联资产/管道/对象/Projection batch。
- 规则结果：状态、字段、检查/失败数量、比例、阈值、动作、耗时和质量问题 ID。
- 失败样例：接入前用资产路径/行指纹，管道内用 source offset/行指纹/Pipeline Run，投影后用对象/关系 ID。
- 日志：只显示阶段、规则、数量和错误原因，不显示完整失败数据或凭据。

失败样例默认最多 100 条并分页，按字段/对象权限脱敏；完整原始消息或整行数据不写 Postgres。允许按权限跳转资产详情、管道运行或对象探索。

#### 11.6.8 质量问题（已确认）

相同规则、目标资源和错误指纹的重复失败聚合为一个问题，避免告警风暴。状态：`开放 → 已确认 → 处理中 → 已解决`，处理中可进入“接受风险”。

问题保存标题、严重级别、规则/资源、首次/最近时间、出现次数、失败比例、负责人、状态和关联管道/对象/运行。操作包括指派、确认、评论、解决、接受风险、打开资源以及创建自动化/审批。

接受风险必须填写原因、到期时间和审批人；到期自动重新开放。已解决或接受风险都不删除历史结果。

#### 11.6.9 隔离区（已确认）

批处理失败正文写入 MinIO `quarantine` bucket，流处理失败正文写入 `persistent://platform/quality/quarantine`。Postgres 只保存 quarantine ID、规则/运行、来源、对象/消息标识、MinIO/Pulsar 引用、脱敏摘要、创建/到期时间和重放状态。

隔离正文使用独立权限、加密和 TTL，默认保留 7 天，由 Admin 配置。页面展示来源、管道、规则、脱敏原因、隔离/到期时间和重放状态，支持查看脱敏样例、延长保留、删除正文和选择新管道版本重放。

重放必须选择已发布版本、预览映射/规则变化、创建新 Pipeline Run、保存 `replay_of`，并重新经过全部质量门禁、平台 Pulsar 和 Projection；禁止隔离区直接写 HugeGraph。只有 Admin 或 `quality.quarantine.replay` 权限可重放。

#### 11.6.10 管道、本体、血缘和自动化联动（已确认）

- 管道“质量门禁”节点可创建基础内联规则或引用已发布规则集版本；发布时校验阶段/节点位置并编译到 Pipeline IR。
- 本体规则随 Pipeline/Projection 契约交给 Projection Worker，规则、字段、管道和本体关系进入血缘。
- 管道运行阶段显示 `读取 → 转换 → 质量门禁 → Pulsar → Projection → 本体质量`。
- 全部通过为正常；警告可完成；隔离未超阈值为降级；阻断为失败；投影后结果按动作决定降级或失败。
- 血缘节点展示绑定规则数、最近通过率、严重问题和 Schema 变化，并可打开规则/报告。
- 对象探索详情显示对象失败规则、最近检查、问题和修复状态；列表可按质量状态筛选并服从对象/字段权限。
- 自动化触发器包括问题创建、严重升级、连续失败、隔离超阈值、问题解决和风险即将到期，可执行通知、审批、Action 或停止管道。

通知发送给规则集、目标资源、关联管道负责人和观察者，严重问题额外通知 Admin。相同规则/资源/原因聚合，使用冷却时间；恶化和恢复重新通知，正文不含失败记录原文。

#### 11.6.11 API 与数据模型（已确认）

```text
GET    /v1/quality/overview

GET    /v1/quality/rule-sets
POST   /v1/quality/rule-sets
GET    /v1/quality/rule-sets/{id}
PATCH  /v1/quality/rule-sets/{id}/draft
POST   /v1/quality/rule-sets/{id}/validate
POST   /v1/quality/rule-sets/{id}/test
POST   /v1/quality/rule-sets/{id}/publish
POST   /v1/quality/rule-sets/{id}/pause
POST   /v1/quality/rule-sets/{id}/archive
GET    /v1/quality/rule-sets/{id}/versions
GET    /v1/quality/rule-sets/{id}/diff

GET    /v1/quality/runs
POST   /v1/quality/runs
GET    /v1/quality/runs/{runId}
POST   /v1/quality/runs/{runId}/cancel
GET    /v1/quality/runs/{runId}/results
GET    /v1/quality/runs/{runId}/samples

GET    /v1/quality/issues
GET    /v1/quality/issues/{issueId}
POST   /v1/quality/issues/{issueId}/assign
POST   /v1/quality/issues/{issueId}/acknowledge
POST   /v1/quality/issues/{issueId}/resolve
POST   /v1/quality/issues/{issueId}/accept-risk
POST   /v1/quality/issues/{issueId}/comments

GET    /v1/quality/quarantine
GET    /v1/quality/quarantine/{id}
POST   /v1/quality/quarantine/replay
DELETE /v1/quality/quarantine/{id}/payload
```

Projection Worker 仅内网调用 `POST /internal/v1/quality/results`。模板批量创建使用事务型 Bulk API，不允许前端逐条循环。

建议数据表：

```text
quality_rule_sets
quality_rule_set_versions
quality_rules
quality_rule_bindings
quality_schedules
quality_runs
quality_run_results
quality_failure_samples
quality_issues
quality_issue_events
quality_quarantine_records
quality_notifications
```

规则集版本不可修改，运行引用精确版本；失败样例和隔离正文分开；Issue 按规则、目标和错误指纹去重；运行保存 `correlation_id`、Pipeline Run ID 和 Projection Batch ID；大量失败对象不得存为一个无限 JSON 数组。

#### 11.6.12 权限（已确认）

| 操作 | Viewer | Builder | Admin |
|---|---:|---:|---:|
| 查看概览、规则和运行 | 否 | 按资源权限 | 是 |
| 查看脱敏失败样例 | 否 | 特殊资源权限 | 是 |
| 创建/编辑草稿 | 否 | 是 | 是 |
| 运行规则集 | 否 | 按资源权限 | 是 |
| 发布普通规则 | 否 | 负责人或 `quality.publish` | 是 |
| 发布阻断规则 | 否 | 否 | 是 |
| 管理问题 | 否 | 按资源权限 | 是 |
| 接受风险 | 否 | 特殊权限并审批 | 是 |
| 查看/重放隔离正文 | 否 | 特殊权限 | 是 |
| 修改保留策略 | 否 | 否 | 是 |

v1 不增加新的全局“数据治理员”角色，使用细粒度权限组合；以后可把权限组合映射为角色。

#### 11.6.13 前端结构与参考边界（已确认）

```text
pages/quality/
├── QualityOverviewPage.tsx
├── QualityRuleSetsPage.tsx
├── NewQualityRuleSetPage.tsx
├── QualityRuleSetDetailPage.tsx
├── QualityRunsPage.tsx
├── QualityRunDetailPage.tsx
├── QualityIssuesPage.tsx
├── QualityIssueDetailPage.tsx
├── QualityQuarantinePage.tsx
├── rules/
├── reports/
├── issues/
├── quarantine/
├── components/
├── hooks/
├── services/
└── types.ts
```

新仓库直接实现版本化规则集、持久异步运行、分页样例、分级通知及 Pipeline Run/Projection/血缘关联；不复制 `QualityPage.tsx` 大 Modal、`QUALITY_RULE/QUALITY_RUN` 通用资源、`object_records` 扫描、同步 `executeQuality()` 或无限 JSON 数组。

#### 11.6.14 数据质量验收标准（已确认）

1. 接入前、管道内、投影后三阶段职责明确。
2. 同一规则不能在不兼容阶段错误执行。
3. 规则集发布版本不可修改。
4. 管道引用精确规则集版本。
5. 非空、类型、范围、正则、枚举、唯一和引用完整性可执行。
6. Schema 漂移、新鲜度和数据量规则可执行。
7. 受限表达式不允许任意代码、SQL 或网络访问。
8. Flink 和 Java 规则执行语义通过共享契约测试。
9. 正则或表达式不能造成无界执行。
10. 规则严重级别与执行动作独立配置。
11. 流任务不会因单条坏消息默认退出。
12. 阈值触发后正确降级、隔离、阻断或暂停。
13. 失败正文不写 Postgres、普通日志或 SkyWalking。
14. 失败样例按字段和对象权限脱敏。
15. 隔离数据具有 TTL、加密和独立权限。
16. 隔离重放创建新 Pipeline Run 并再次通过全部门禁。
17. 运行报告引用规则版本、Pipeline Run 和 Projection Batch。
18. 重复失败聚合为质量问题，不产生告警风暴。
19. 接受风险具有原因、审批和到期时间。
20. 对象探索和血缘正确显示质量状态。
21. 通知发送给负责人和观察者，而非只发 Admin。
22. Builder、高风险发布、隔离访问和 Admin 权限正确隔离。
23. 大规模失败结果分页保存，不使用无限 JSON 数组。
24. 取消、失败、重试和 SSE 断开不会生成假状态。
25. 规则、报告的 Flyway 初始化和版本发布失败时可恢复，不留下半成品。
26. 全新 Compose 可完成接入前、批处理、流处理和投影后质量验证。

### 11.7 数据血缘

路由：

- `/data/lineage`：血缘工作台。
- `/data/lineage/views/:viewId`：已保存视图。
- 支持 `focus`、`direction`、`depth`、`mode`、`asOf`、`runId` 查询参数，以便从数据连接、管道、质量、本体和应用页面深链接进入精确上下文。

数据血缘采用 D-021：由 Ontology Core 的 `lineage` 模块在 Postgres 建立平台原生、版本化控制面血缘索引，不新增 Marquez、OpenLineage Server 或独立 Lineage Worker。HugeGraph 继续只保存业务对象和业务对象关系；OpenSearch 只负责有权限资源的搜索，不是血缘关系事实源；SkyWalking 只负责调用链和运维排障，不替代设计或运行血缘。后续可导出 OpenLineage 兼容事件，但不作为 v1 内部事实模型。

#### 11.7.1 血缘范围与双层视图（已确认）

完整资源链为：

```text
数据连接 → 数据资产 → Pipeline → Pipeline 发布版本 → Pipeline 节点
  → 平台 Pulsar Topic → Projection → 对象类型/关系类型 → 属性
  → 分析看板/业务应用/自动化/Agent
```

辅助资源包括数据质量规则集、Action、Function、调度、注册 API Consumer、Pipeline Run 和 Projection Batch。图分为两种层级：

- **业务视图（默认）**：只展示数据资产 → 管道 → 对象/关系 → 看板/应用/自动化/Agent；自动折叠 Pipeline 版本、内部节点、Pulsar Topic、Projection Batch 和字段节点。
- **技术视图**：Builder/Admin 可展开资产字段 → Pipeline Version → Source/Join/Transform/Quality/Sink 节点 → 输出字段 → Pulsar Topic → Projection → 本体属性 → 应用组件。

内部 Pulsar 属于平台实现，普通用户默认不看到；被折叠节点仍计入路径、影响分析、运行状态和权限判断。

#### 11.7.2 页面结构（已确认）

页面继续使用现有白色侧栏、浅色内容区、蓝色主色和 Ant Design 组件，不采用应用卡片：

1. **顶部标题区**：标题、全局资源搜索、视图名称、当前发布状态/历史时点、刷新、保存视图、分享链接、导出 SVG 和更多操作。
2. **左侧资源浏览面板**：可收起；包含资源/字段搜索、最近查看、收藏、已保存视图，以及按数据连接、本体和应用浏览；支持类型、负责人、状态和标签筛选。
3. **中央血缘画布**：按需展示图，支持上下游展开、选择、布局和路径分析。
4. **右侧节点详情面板**：单击节点打开，不离开画布。
5. **底部分析面板**：可展开，Tabs 为“字段血缘 / 构建时间线 / 影响分析 / 质量问题 / 运行记录”。

首次打开不请求全平台完整图，只显示搜索入口、最近资源和已保存视图；选择起点后默认请求上下游各两层。保存视图保存起点、查询、筛选、折叠、布局、模式和历史时点，不复制血缘数据。

#### 11.7.3 图交互与着色（已确认）

画布支持：

- 仅上游、仅下游和双向模式。
- 单击选择、双击打开资源页面、节点左右箭头按需展开一层。
- 展开全部祖先/后代、框选、多选、隐藏、折叠、恢复和按资源类型分组。
- 自动布局、手工拖动、缩放、适应画布、迷你地图、图内查找和最短路径高亮。
- 撤销本次未保存的画布操作；保存个人或共享视图；导出当前权限范围内的 SVG。

着色维度一次只能选择一个：资源类型、最近运行状态、数据质量、数据陈旧、负责人、权限或自定义颜色。节点角标独立表示严重质量问题、Schema 变化、运行失败、停用/归档、受影响和未发布变更；颜色与角标不得表达冲突含义。

#### 11.7.4 节点详情（已确认）

右侧面板按资源能力展示：

- **概览**：名称、类型、状态、负责人、标签、说明、更新时间和发布版本。
- **Schema**：字段、类型、主键、敏感级别和 Schema 版本。
- **上下游**：直接上游、直接下游、路径数量和消费者数量。
- **字段血缘**：源字段、转换逻辑、目标属性和置信状态。
- **运行与健康**：最近运行、数据量、延迟、质量通过率、Pulsar lag 和 Projection 状态。
- **使用位置**：引用该资源的看板、业务应用、自动化和 Agent。
- **变更历史**：资源版本、血缘差异、操作者和审计入口。

面板底部根据资源类型提供“打开数据连接 / 打开管道 / 打开质量报告 / 打开本体 / 打开应用 / 打开 SkyWalking trace”等深链接；普通用户不能看到管理员运维入口。

#### 11.7.5 四种血缘模式（已确认）

1. **当前设计血缘**：默认显示全部资源当前已发布版本；草稿不进入正式图，只在对应节点显示“有未发布变更”。
2. **运行血缘**：选择 Pipeline Run 后显示实际输入资产和 Schema 版本、精确 Pipeline Version、Flink Job、Pulsar batch/topic、Projection Batch 和实际输出对象类型；展示输入/输出量、checkpoint/savepoint、质量结果、Projection 重试/DLQ、`correlation_id` 和允许访问的 SkyWalking trace deep link。
3. **历史血缘**：按历史时点或资源版本查询；支持比较两个时点，以新增、删除、改变标识差异；已删除资源保留审计节点和名称快照。
4. **影响分析**：根据草稿或变更提议生成假设图并和当前发布图比较，不写入正式血缘、不自动修改或重跑下游资源。

运行血缘只记录资源、运行和批次级事实，不为每一行或每个对象实例永久建边。历史视图不实现 Nessie/数据分支，也不宣称业务数据时间旅行。

#### 11.7.6 字段级血缘（已确认）

字段链路固定为：

```text
源资产字段 → Pipeline 节点输入端口 → 转换表达式 → Pipeline 输出字段
  → 本体属性/关系键 → 看板组件或应用字段
```

转换类型包括直接映射、重命名、类型转换、表达式、聚合、Join、Filter、Explode、常量/系统生成和无法解析。Join、表达式与聚合允许多个上游字段指向一个目标字段。每条字段边保存源/目标字段、Pipeline Version、节点 ID、转换类型、规范化表达式、是否影响值、是否影响行数、生成方式和置信状态。

Pipeline IR 编译器必须确定性生成平台管道的字段血缘，不能用 SQL 文本正则或运行样本猜测。v1 不实现单行血缘、对象实例完整溯源、任意外部 SQL 自动解析或从 Agent Prompt 猜测依赖。

#### 11.7.7 应用侧血缘（已确认）

资源发布时自动提取声明式依赖：

- 分析看板：对象类型、属性、关系和 Function。
- 业务应用：对象、关系、Action 和 Function。
- 自动化：触发对象/属性以及执行的 Action/Function。
- Agent：发布版本声明的对象范围、工具、Action 和 Function；不把 Prompt 文本推断为依赖。
- 对话中心：临时查询不进入永久设计血缘，但相关调用保留审计和短期 trace。

外部 Power BI、Tableau 或自建客户端只有注册为 API Consumer 或导入依赖清单后才进入血缘；v1 不宣称自动发现外部消费者。

#### 11.7.8 影响分析（已确认）

可分析删除/重命名字段、类型/主键/Nullable 改变、删除资产、停用连接、修改 Pipeline 节点或表达式、修改质量规则/阻断阈值、删除对象属性/关系、修改 Action/Function 以及改变应用依赖。

| 级别 | 规则 |
|---|---|
| 阻断 | 主键删除、必需输入删除、无迁移的类型不兼容等，禁止直接发布 |
| 高 | 下游应用/自动化可能失效或安全边界改变，需要审批或明确确认 |
| 中 | 输出语义或质量行为改变，需要负责人确认 |
| 低 | 新增可选字段、说明或标签变化等非破坏性改变 |

每个影响项必须给出变化内容、受影响资源、最短传播路径、受影响字段、负责人、当前运行/质量状态和建议动作。用户可创建变更提议、通知负责人或跳转修复；分析本身不自动发布、修改、暂停或重跑任何资源。

#### 11.7.9 陈旧与健康状态（已确认）

- **数据陈旧**：上游产生新的成功数据，下游尚未成功消费。
- **逻辑陈旧**：上游 Pipeline、本体或规则发布新版本，下游仍引用旧版本。
- **投影滞后**：Pulsar 已有成功输出，Projection 尚未完成。
- **未知**：缺少运行证据或当前用户无权读取证据。

健康状态聚合最近运行、数据质量、Projection、DLQ、Schema 漂移、调度和数据连接状态，但详情必须保留各状态来源，不能只显示无法解释的综合红绿灯。构建时间线使用 Gantt 展示 Pipeline Run 和 Projection Batch 的开始时间、持续时间、状态和所属调度；调度编辑跳转到对应管理页面，不在血缘中重复实现。

#### 11.7.10 采集契约与一致性（已确认）

连接、管道、质量、本体和应用模块在发布时向 lineage 模块提交统一贡献：

```json
{
  "producer_type": "PIPELINE_VERSION",
  "producer_id": "pipeline-id",
  "producer_version": 7,
  "published_at": "2026-07-20T10:00:00Z",
  "nodes": [],
  "edges": [],
  "field_edges": [],
  "fingerprint": "sha256",
  "correlation_id": "..."
}
```

规则：

- 仅发布成功版本进入当前设计血缘；同一 `producer_id + producer_version + fingerprint` 幂等。
- 新版本在同一 Postgres 事务中写节点/边并关闭旧边的 `valid_to`；失败不得留下半套血缘。
- Pipeline Run 和 Projection Batch 单独写运行事实，并引用精确资源版本和 `correlation_id`。
- 资源删除使用 tombstone，历史血缘、名称快照、影响分析和审计不删除。
- 采集失败时发布失败，或进入明确的“血缘待修复”状态；不得静默发布成功。
- Admin 可以重建血缘索引和修复孤立引用，但不能手工伪造业务血缘。

#### 11.7.11 API 与数据模型（已确认）

产品 API：

```text
POST   /v1/lineage/query
GET    /v1/lineage/search
GET    /v1/lineage/nodes/:id
POST   /v1/lineage/expand
POST   /v1/lineage/paths
GET    /v1/lineage/fields
GET    /v1/lineage/runs/:runId
GET    /v1/lineage/history
POST   /v1/lineage/compare
POST   /v1/lineage/impact-analyses
GET    /v1/lineage/impact-analyses/:id
GET    /v1/lineage/views
POST   /v1/lineage/views
PUT    /v1/lineage/views/:id
DELETE /v1/lineage/views/:id
GET    /v1/lineage/export
POST   /v1/lineage/admin/reindex
```

浏览器不能直连 Postgres、HugeGraph、Flink、Pulsar、OpenSearch管理 API 或内部贡献写入接口。图查询响应必须包含节点、边、continuation cursor、折叠信息、受限步骤计数、图版本和查询时点，禁止静默截断。

Postgres 新增：

| 表 | 用途 |
|---|---|
| `lineage_resources` | 规范资源引用、类型、版本、状态和名称快照 |
| `lineage_edges` | 版本化资源级边、producer、证据及有效时间 |
| `lineage_field_edges` | 字段级映射、表达式和转换语义 |
| `lineage_contributions` | 发布模块提交记录、fingerprint 和采集状态 |
| `lineage_run_edges` | Pipeline Run、Pulsar batch、Projection Batch 实际关系 |
| `lineage_snapshots` | 可查询历史时点的图版本元数据，不保存整张图 JSON |
| `lineage_saved_views` | 起点、筛选、布局、模式、权限和分享配置 |
| `lineage_impact_analyses` | 假设图、基线版本、状态和创建者 |
| `lineage_impact_items` | 分页影响结果、级别、路径和处理状态 |

核心边类型为 `CONTAINS`、`READS_FROM`、`TRANSFORMS`、`PUBLISHES_TO`、`CONSUMES_FROM`、`PROJECTS_TO`、`MAPS_TO`、`DEFINES`、`USES`、`DISPLAYS`、`TRIGGERS`、`INVOKES` 和 `MONITORS`。禁止把整张图、全部运行事件或无限影响结果保存为单个 JSON。

#### 11.7.12 权限与审计（已确认）

| 角色 | 能力 |
|---|---|
| Viewer | 查看有权资源血缘、保存个人视图、导出当前权限图、打开运行/质量/资源详情 |
| Builder | Viewer + 技术视图、字段转换、共享视图、影响分析、从影响项创建变更提议 |
| Admin | 全局血缘、以用户身份查看、重建索引、采集错误、孤立资源和共享视图管理 |

权限在服务端裁剪：无权资源不返回名称、类型、字段、Schema 或状态；为保持路径可理解，只能按策略显示不含类型和名称的“受限步骤”，字段权限不足时降为资源级关系。搜索、节点详情、路径、历史、影响分析、保存视图、分享链接和 SVG 导出使用同一裁剪器。保存/分享视图不授予底层资源权限。

血缘查询本身只记录安全审计摘要，不记录敏感字段名；保存/分享/删除视图、执行影响分析、以用户身份查看、重建索引和修复孤立引用必须写审计。Admin 的“以用户身份查看”不可绕过目标用户的裁剪规则。

#### 11.7.13 大图性能（已确认）

- 默认上下游各两层；单次最多 500 节点、1,000 条边。
- 超限时服务端按 Pipeline、对象类型或应用分组折叠，或要求缩小范围；不得返回任意前 N 条边。
- 展开操作按需查询并使用 cursor；字段、历史、运行时间线和影响结果懒加载。
- OpenSearch 只搜索有权限的资源，关系遍历使用 Postgres 有限深度递归 CTE。
- 为 `source_id`、`target_id`、资源版本、producer、有效时间和运行 ID 建复合索引。
- AntV G6 使用大图/WebGL 能力，布局移入 Web Worker；API 设置最大深度、超时、游标和导出范围。
- 缓存键必须包含用户/角色权限摘要、图版本、时点和过滤条件；权限变化后缓存立即失效。

新实现不得使用参考代码的 `edges.slice(0, depth * 50)` 或在前端全量加载资源自行拼图；深度、裁剪、分组和 cursor 全部由服务端负责。

#### 11.7.14 前端结构与参考边界（已确认）

```text
portal/src/features/data/lineage/
├── LineagePage.tsx
├── LineageToolbar.tsx
├── ResourceBrowser.tsx
├── LineageCanvas.tsx
├── LineageNode.tsx
├── NodeDetailPanel.tsx
├── FieldLineagePanel.tsx
├── BuildTimelinePanel.tsx
├── ImpactAnalysisPanel.tsx
├── SavedViewsPanel.tsx
├── lineage.types.ts
└── lineage.service.ts
```

使用 AntV G6 和既定视觉风格；图谱布局、筛选和跳转不能硬编码资源类型。字段血缘使用详情面板，运行事件使用底部时间线，节点按资源类型跳转，状态/动作/错误使用中文。血缘从第一版使用规范表，不创建通用 `LINEAGE` JSON 记录。

#### 11.7.15 数据血缘验收标准（已确认）

1. 数据源到应用和 Agent 的完整设计血缘可查询。
2. 默认业务视图不会暴露过多内部技术节点。
3. 技术视图可展开 Pipeline Version、节点、Pulsar 和 Projection。
4. 上游、下游和双向深度查询正确。
5. 多输入 Join 和多输出 Pipeline 血缘正确。
6. 字段重命名、转换、聚合和 Join 血缘正确。
7. 当前、运行和历史血缘严格区分。
8. 未发布草稿不污染当前正式血缘。
9. Pipeline Run 引用精确 Pipeline Version。
10. Projection Batch 和质量运行可关联到同一 `correlation_id`。
11. 历史资源删除后血缘仍可审计。
12. Schema 变化影响分析能定位下游字段、对象和应用。
13. 阻断、高、中、低影响规则正确。
14. 影响分析不会自动修改、暂停或重跑资源。
15. 数据陈旧、逻辑陈旧和投影滞后可以区分。
16. 质量问题和运行失败能在图上叠加显示。
17. 权限不足用户无法通过搜索、图、详情、历史、影响分析或导出推断敏感资源。
18. 分享视图不会提升底层资源权限。
19. 保存视图能恢复起点、筛选、布局、模式和历史时点。
20. 大图按服务端深度和游标加载，不截取任意前 N 条边。
21. 超过 500 节点时自动折叠或明确提示缩小范围。
22. 血缘采集具有版本、事务和幂等保证。
23. 发布失败不会产生半套血缘或假成功状态。
24. Admin 可以重建血缘，并看到孤立资源和采集错误。
25. 血缘运行事实在服务重启后仍保留。
26. SVG 导出、深链接和跨资源跳转正确且遵守权限。
27. 全新 Compose 可完成采集、查询、影响分析和历史比较。
28. Java 测试、前端 lint/build、Flyway、权限和大图边界测试全部通过。

### 11.8 本体管理

本体管理采用 D-022：平台只维护一个共享本体；控制面使用 Postgres 规范化、不可变资源版本和全局递增 `ontology_revision`；发布通过多资源 Proposal、审核和部署 Saga 完成。HugeGraph 保存对象/关系事实，OpenSearch 保存可重建搜索索引，Postgres 不保存对象正文；不恢复 Nessie/Global Branching，不延续 `catalog/schema/table` 或“Managed 物理表”概念。

#### 11.8.1 路由与内部导航（已确认）

```text
/ontology                         本体概览
/ontology/object-types            对象类型
/ontology/object-types/:id        对象类型详情
/ontology/properties              属性目录
/ontology/link-types              关系类型
/ontology/link-types/:id          关系类型详情
/ontology/interfaces              Interface
/ontology/interfaces/:id          Interface 详情
/ontology/actions                 Action
/ontology/actions/:id             Action 详情
/ontology/functions               Function
/ontology/functions/:id           Function 详情
/ontology/proposals               变更提议
/ontology/proposals/:id           变更提议详情
/ontology/health                  健康问题
/ontology/history                 变更历史
/ontology/settings                本体设置
```

“本体管理”仍是左侧主导航中的一个页面入口；上述资源使用页面内部侧栏，不向全局导航增加十多个条目。内部导航依次为概览、对象类型、属性目录、关系类型、Interface、Action、Function、变更提议、健康问题、变更历史和设置。所有资源可通过顶栏或 `Ctrl/Cmd + K` 搜索显示名称、API 名称、属性、描述、负责人和标签；结果必须经过权限裁剪。

#### 11.8.2 本体概览（已确认）

概览采用紧凑工作台而非应用卡片门户：

- 顶部显示当前 `ontology_revision`、上次发布时间、发布健康、未发布 Proposal、严重健康问题、Projection 异常和待审核数量。
- 主体展示最近编辑资源、我的草稿/审核任务、核心对象类型、健康问题、最近发布历史、资源统计表和对象—关系简化模型图。
- 顶部操作为“新建对象类型 / 新建关系类型 / 新建 Action / 新建 Function / 创建变更提议 / 搜索资源”。
- 不提供“创建并部署”或绕过 Proposal 的普通发布按钮。

#### 11.8.3 生命周期与成熟度（已确认）

发布生命周期与业务成熟度是两套独立状态：

```text
生命周期：DRAFT → IN_REVIEW → APPROVED → PUBLISHING → PUBLISHED
异常/终止：REJECTED / FAILED / CLOSED / RETIRED

成熟度：EXPERIMENTAL（试验中）/ ACTIVE（正式）/ DEPRECATED（已弃用）
```

对象类型可额外标记 `promoted/core`（核心对象），表示经过 Resource Owner 审核、优先推荐给应用和 Agent 的稳定模型。成熟度不会自动改变发布状态；已弃用资源仍保持可查询，只有经过影响分析和退役 Proposal 才停止新消费者引用。发布资源不物理删除，统一使用 tombstone 并保留名称快照、血缘和审计。

#### 11.8.4 对象类型列表与详情（已确认）

列表使用紧凑表格，列为显示/API 名称、成熟度、核心标记、数据来源、属性/关系数、当前版本、最近 Projection、质量状态、负责人和更新时间；支持按成熟度、发布状态、核心对象、主 Pipeline、健康、负责人和标签筛选。操作为打开、收藏、创建变更、查看血缘、复制为新类型、标记弃用和退役。

已发布对象类型不能原地编辑；“编辑”创建或加入 Proposal。详情 Tabs：

1. 概览：身份、负责人、发布版本、对象数量、最近更新、主键/标题属性、来源、质量、Projection、血缘和最近变更。
2. 属性：属性表、属性编辑器、兼容性和来源字段。
3. 数据映射：主 Pipeline Sink、Action 写入通道、Projection Contract 和字段级血缘。
4. 关系：两侧关系、基数、来源和健康。
5. Action：可应用 Action、版本和执行健康。
6. 索引与投影：HugeGraph/OpenSearch 部署、事件 schema、lag、失败和重建。
7. 使用位置：看板、业务应用、自动化、Agent、Function 和 API Consumer。
8. 版本：不可变版本、diff、Proposal 和发布记录。
9. 设置：负责人、标签、成熟度、核心标记、弃用/退役和资源权限。

#### 11.8.5 新建对象类型向导（已确认）

使用四步全屏向导：

1. **创建方式**：选择“从 Pipeline 输出创建（推荐）”或“创建空对象类型，由 Action 管理数据”。数据连接/表/文件不能绕过 Pipeline 直接成为正式对象映射。
2. **基本信息**：显示名称、API 名称、描述、图标、颜色、成熟度、负责人、标签和核心对象标记。API 名以字母开头，仅含字母/数字/下划线，首次发布后不可直接改名。
3. **属性与标识**：从 Pipeline 输出 Schema 生成候选属性；选择唯一主键和标题属性；配置类型、必填、搜索/筛选/排序、敏感等级；解决未映射和类型不兼容。空对象类型必须手工定义主键和标题属性。
4. **检查并保存草稿**：检查名称冲突、主键、Schema/类型、搜索策略、敏感字段、Pipeline 所有权和血缘影响；完成后只生成草稿，不部署 HugeGraph/OpenSearch。

#### 11.8.6 属性模型与类型（已确认）

每个属性保存稳定属性 ID、显示名称、API 名称、描述、类型、必填、主键、标题属性、搜索/筛选/排序开关、analyzer、敏感等级、脱敏策略、单位、显示格式、枚举值、成熟度、来源字段和转换路径。

v1 类型：

```text
string / integer / long / decimal / boolean / date / datetime / enum
string[] / integer[] / json
```

限制：

- `json` 是不可透明解析的值，不可作为主键、关系键、排序或聚合字段；在 HugeGraph 中按受限序列化字符串保存，默认不进入 OpenSearch。
- 数组不可作为主键/关系键、不支持嵌套数组且元素不可为 null。
- `decimal` 不可作为主键；主键只允许 `string/integer/long`。
- 大文本、大数组和序列化 JSON 使用可配置大小限制；超限批次失败、流记录隔离。二进制只保存 MinIO 引用。
- 除主键外，“唯一”约束由版本化数据质量规则执行，不伪装成 HugeGraph 强事务唯一约束。

#### 11.8.7 主键、标题属性与 API 稳定性（已确认）

- 每个已发布对象类型恰好一个主键，主键必填且 Pipeline 输出必须唯一。
- 已产生对象后不能原地更改主键属性或类型；需要新对象类型或显式迁移 Proposal。
- 每个对象类型可指定一个标题属性，未指定时回退主键；标题属性不是身份键。
- 首次发布后显示名称、描述、图标、格式可兼容修改，API 名不可直接重命名。
- API 迁移固定为新增属性 → 双写/回填 → 迁移消费者 → 弃用旧属性 → 退役，不能原地覆盖。
- 属性 ID 和底层物理键稳定，不使用显示/API 名称作为 HugeGraph/OpenSearch 物理键。

重复主键在批处理中阻断 Projection Batch；流处理中进入隔离并聚合成质量问题。

#### 11.8.8 数据映射与单一所有权（已确认）

v1 每个已发布对象类型最多有一个主 Pipeline Sink 负责规范对象事实，以及一个 Action mutation 通道负责交互式编辑；不允许多个独立 Pipeline 对同一属性执行无优先级的 last-write-wins。多源融合必须在上游 Pipeline Join/Union 后形成一个规范输出。

映射页展示 Pipeline/版本/Sink 节点 → 输出字段/转换 → 对象属性 → Projection Contract → HugeGraph property → OpenSearch field；每行包含源字段类型、目标属性、主键/必填、搜索策略、质量规则、最近 Projection 和字段血缘。

Pipeline Schema 兼容性：新增可空字段可兼容；新增必填字段要求默认值/回填；扩宽类型按矩阵判断；缩窄类型为高风险或阻断；删除仍被映射的字段和改变主键均阻断；类型相同但语义改变必须由提交者声明并人工审核。

#### 11.8.9 轻量 Interface（已确认）

Interface 用于跨对象类型统一查询和 AIP 工具输入，只定义显示/API 名称、描述、必需属性槽位、槽位类型、成熟度和负责人；没有对象实例或独立存储。对象类型可实现多个 Interface，并将自身属性显式映射到兼容槽位；Interface 查询是实现类型在当前用户权限下的 union。

v1 不支持 Interface 继承、对象类继承、默认实现、Interface 上直接定义关系/Action，或隐式复制属性。Agent 和应用只能看到调用者有权访问的实现类型与字段。

#### 11.8.10 关系类型与向导（已确认）

一个 Link Type 是可从两侧遍历的单一关系资源，不创建重复的反向关系。支持 `1:1`、`1:N`、`N:1`、`N:M` 和自关系。四步向导：

1. **端点与基数**：左右对象类型、基数、自关系。
2. **数据来源**：`1:1/1:N/N:1` 可用外键派生；`N:M` 或带关系属性时使用关系 Pipeline Sink，输出 `link_id/left_object_id/right_object_id/relationship properties`。
3. **两侧语义**：两侧分别配置显示名称、API 名、单复数、描述、图标和默认展示方向。
4. **检查并保存**：验证端点、名称、字段类型、基数、引用完整性、循环关系、Sink Schema 和影响范围，只保存草稿。

HugeGraph 使用一个稳定 edge label，双向遍历同一 edge；关系属性存为 edge properties。引用目标不存在时批处理阻断、流记录隔离并生成引用完整性问题；基数冲突按同样的批/流策略处理。删除对象默认不级联删除关联对象，只清理/tombstone相关 edge。

#### 11.8.11 Action 列表、详情与边界（已确认）

Action 定义允许发生的业务改变；自动化定义何时触发，审批中心处理实例审批。列表列为显示/API 名、目标对象、操作类型、参数数、审批策略、成熟度、使用位置、版本、最近执行和负责人。详情 Tabs 为概览、参数、规则、提交条件、审批、预览与测试、使用位置、版本和设置。

参数支持基础类型、enum、对象引用、对象引用列表和基础数组；配置显示/API 名、必填、默认值、表单可见/只读、当前对象来源、动态选项、校验和敏感等级。

Declarative Action DSL 支持创建/修改/删除（退役）对象、创建/删除关系、条件分支、设置属性以及从参数/当前对象取值；不支持任意脚本、SQL、Webhook、无界循环或未声明类型的动态写入。规则有明确顺序，但编译器必须拒绝同一操作对同一对象属性的冲突写入，不能静默以后规则覆盖。

#### 11.8.12 Action 条件、审批与执行（已确认）

提交条件可引用用户/组/角色、对象当前状态、属性、参数、关系存在性、只读 Function 布尔结果和资源策略。“编辑本体模型”和“执行 Action”权限分离；执行者必须能查看目标对象并满足条件，但不需要 Builder 权限。

审批策略为无需、始终、条件审批；删除对象、批量修改、敏感属性、关键状态、高额阈值和高敏关系变动必须强制审批。固定执行链：

```text
参数 → 权限/条件 → 当前对象版本 → Ontology Edit Plan → Diff
  → 短期 preview token → 用户确认/审批
  → persistent://platform/commands/mutation-batches
  → Projection Worker → HugeGraph transaction → OpenSearch → operation 终态
```

Preview token 默认 10 分钟且可配置；execute 要求 `Idempotency-Key` 和对象 ETag/version；单个 mutation batch 默认最多 100 个对象/关系编辑。API 返回 operation ID，可等待受限时间；超时显示处理中，OpenSearch未完成显示 `DEGRADED`。通知等副作用只在图事务成功后发送。v1 不走“同步直写 HugeGraph、异步补索引”的第二写路径。

#### 11.8.13 Function（已确认）

Function 是版本化、类型化、只读的可信计算资源。v1 支持对象筛选/遍历、关系遍历、聚合、排名、标量计算、布尔校验和表格结果；输入可为基础类型、对象引用/列表或对象查询条件，输出可为基础类型、对象引用/集合、表格或受 JSON Schema 约束的结构。

Function Builder 配置显示/API 名、描述、输入/输出、声明依赖、受限查询 DSL、超时、最大结果、缓存、成熟度和负责人；详情 Tabs 为概览、输入输出、逻辑、测试、配置、使用位置、版本和设置。

Function 必须声明依赖并进入血缘；以调用者权限执行，测试控制台也不能提升权限；消费者发布时绑定精确不可变 Function Version，新版本不自动替换。v1 不执行任意 JavaScript/Python/Java，不暴露 Gremlin/原生 OpenSearch DSL，Function 不能写对象；Function-backed Action 留作后续扩展。

#### 11.8.14 Proposal 与审核（已确认）

一个 Proposal 可同时包含对象、属性、Interface 映射、关系、Action、Function、Pipeline 映射和索引配置；每个资源作为独立 task 一起发布为新 `ontology_revision`。状态为草稿 → 校验中 → 待审核 → 已批准 → 发布中 → 已发布，或变更请求/已拒绝/发布失败/已关闭。

详情 Tabs 为概览、变更内容、影响分析、Preview 状态、审核、评论和发布记录；显示基线 revision、资源/字段 diff、Schema兼容性、下游影响、Preview部署状态、必需审核人和审计。

Proposal 记录基线 revision；Main变化后发布前重新比较。不冲突资源可刷新基线，同一资源/属性并发编辑必须选择保留草稿、接受已发布版本或手工解决；不实现通用 Git rebase。受保护/高风险资源必须由 Resource Owner 或策略指定审核人批准；Admin紧急发布仍需原因、审计且不能跳过类型、主键和权限校验。

#### 11.8.15 发布 Saga 与受限回滚（已确认）

发布流程：

```text
锁定 Proposal → 完整校验 → 血缘影响分析 → 权限/审核检查
  → 生成不可变资源版本和 Projection Contract
  → 部署 HugeGraph additive schema
  → 创建/迁移 OpenSearch index
  → 必要回填和验证
  → 原子切换 active ontology_revision
  → 发布血缘贡献 → 审计
```

Postgres、HugeGraph和OpenSearch不能跨系统原子提交，因此采用持久化部署 Saga：切换前旧 revision 始终服务；任一步失败时 Proposal 为 `FAILED`、旧 revision 保持 active；未激活物理 Schema 标记 orphan candidate，由 Admin 检查清理；正式 API 不能读取部分新 Schema。

回滚选择历史版本后创建新 Proposal，复制兼容元数据并重新校验/发布；不修改不可变历史，不自动恢复对象值。数据回填或对象值恢复是单独迁移任务。

#### 11.8.16 兼容性与弃用策略（已确认）

- **安全**：显示名称/描述/图标/标签；新增可空属性；新增不影响既有数据的关系；新增 Action/Function；扩展枚举。
- **需迁移/重建**：analyzer/searchable；可空改必填；兼容类型扩宽；收紧枚举；关系来源；Action参数；Function输入输出。
- **阻断/高风险**：删除使用中属性；主键；已发布API名；不兼容类型；降低关系基数；删除被消费者使用的Action/Function；降低敏感等级；退役核心对象。

破坏性变化采用新增 → 双写/回填 → 迁移消费者 → 弃用 → 退役。`DEPRECATED`资源继续运行并显示警告，不允许新的默认引用；`RETIRED`前必须无活跃消费者或有经批准的例外。

#### 11.8.17 物理映射与 Projection Contract（已确认）

Postgres 只保存本体控制面。HugeGraph 使用稳定内部名称：对象 vertex label `ot_{shortId}`、属性 key `p_{shortId}`、关系 edge label `lt_{shortId}`、关系属性 `lp_{shortId}`；不依赖显示/API 名。OpenSearch每个对象类型使用版本化索引 `ontology-objects-{objectTypeId}-{mappingVersion}` 和稳定 alias `ontology-objects-{objectTypeId}`；mapping改变时创建新索引、从 HugeGraph重建、校验数量/版本、原子切 alias，再延迟清理旧索引。敏感属性默认不索引。

不可变 Projection Contract 包含 ontology revision、对象/关系/属性 ID、API到物理键映射、类型、主键/标题、必填、搜索、端点/基数、质量规则、敏感策略和兼容事件 schema。Projection Worker 按事件的精确 `ontology_revision/schema_version` 选择契约，不能用当前最新配置解释历史事件。

#### 11.8.18 数据模型（已确认）

规范表至少包括：

```text
ontology_revisions
ontology_resources
ontology_resource_versions
object_types / object_type_versions
properties / property_versions
interfaces / interface_versions / interface_implementations
link_types / link_type_versions
ontology_mappings
action_types / action_type_versions
function_types / function_type_versions
ontology_proposals / ontology_proposal_tasks
ontology_reviews / ontology_comments
ontology_deployments / ontology_health_issues
```

稳定资源表保存身份、所有权和 tombstone；版本表保存不可变配置与 fingerprint；Proposal只引用草稿版本；deployment保存 Saga步骤、重试和外部资源标识。核心本体语义不得继续永久存放在 `ResourceEntity.config JSON`，也不得把整套本体或无限 diff 保存为一个 JSON。

#### 11.8.19 健康问题（已确认）

健康页聚合未绑定主 Pipeline、Schema不兼容、重复主键、必填缺失、引用不存在、HugeGraph部署失败、OpenSearch alias异常、Projection延迟/失败/DLQ、长期无数据、严重质量问题、弃用资源仍被使用、Proposal残留、孤立物理 Schema、Function超时和Action失败。

每个问题包含严重度、资源、首次/最近时间、证据、负责人、影响范围、推荐修复、深链接和接受风险到期时间；重复异常聚合，不产生告警风暴。健康问题与数据质量问题可相互引用，但不复制失败正文或敏感值。

#### 11.8.20 API（已确认）

```text
GET    /v1/modeling/summary
GET    /v1/modeling/search
GET    /v1/modeling/object-types
POST   /v1/modeling/object-types
GET    /v1/modeling/object-types/:id
POST   /v1/modeling/object-types/:id/drafts
GET    /v1/modeling/properties
GET    /v1/modeling/properties/:id
GET    /v1/modeling/link-types
POST   /v1/modeling/link-types
GET    /v1/modeling/link-types/:id
GET    /v1/modeling/interfaces
POST   /v1/modeling/interfaces
GET    /v1/modeling/interfaces/:id
GET    /v1/modeling/actions
POST   /v1/modeling/actions
GET    /v1/modeling/actions/:id
POST   /v1/modeling/actions/:id/preview
GET    /v1/modeling/functions
POST   /v1/modeling/functions
GET    /v1/modeling/functions/:id
POST   /v1/modeling/functions/:id/test
GET    /v1/modeling/proposals
POST   /v1/modeling/proposals
GET    /v1/modeling/proposals/:id
POST   /v1/modeling/proposals/:id/validate
POST   /v1/modeling/proposals/:id/submit
POST   /v1/modeling/proposals/:id/reviews
POST   /v1/modeling/proposals/:id/publish
POST   /v1/modeling/proposals/:id/retry
POST   /v1/modeling/proposals/:id/close
GET    /v1/modeling/health
GET    /v1/modeling/history
GET    /v1/modeling/deployments/:id
```

所有更新使用 ETag/资源版本；校验、发布、重试、回填和索引迁移是持久化异步任务，通过轮询/SSE观察，页面断开不取消。浏览器不能调用内部契约、HugeGraph/OpenSearch管理接口或绕过 Proposal 修改 active revision。

#### 11.8.21 权限与审计（已确认）

| 角色/授权 | 能力 |
|---|---|
| Viewer | 查看有权的已发布元数据、脱敏健康和历史 |
| Builder | 创建/编辑草稿和 Proposal、校验、影响分析、Function测试、请求审核 |
| Resource Owner/Reviewer | 审核受保护资源、批准/拒绝task、负责人/成熟度/弃用策略 |
| Admin | 本体设置/权限、部署重试、索引重建、孤立资源清理和有理由紧急发布 |
| `APPLY_ACTION` | 在对象数据权限和提交条件内执行指定 Action，不授予建模权限 |
| `EXECUTE_FUNCTION` | 在调用者对象/字段权限内执行指定 Function，不授予建模权限 |

读取本体元数据不授予对象正文权限，查看敏感属性定义不授予敏感值权限；Action/Function/Agent/应用不能使用创建者或服务账号权限提升调用者访问。创建/修改/提交/审核/发布/重试/回滚/弃用/退役、Action测试、Function测试和管理员操作写脱敏审计；审计记录资源和字段 ID/diff 摘要，不写敏感样例值。

#### 11.8.22 前端结构与参考边界（已确认）

```text
portal/src/features/ontology/modeling/
├── OntologyOverviewPage.tsx
├── OntologyResourceLayout.tsx
├── ObjectTypeListPage.tsx
├── ObjectTypeDetailPage.tsx
├── ObjectTypeWizard.tsx
├── PropertyCatalogPage.tsx
├── PropertyEditor.tsx
├── LinkTypeListPage.tsx
├── LinkTypeDetailPage.tsx
├── LinkTypeWizard.tsx
├── InterfaceListPage.tsx
├── InterfaceDetailPage.tsx
├── ActionListPage.tsx
├── ActionDetailPage.tsx
├── ActionRuleBuilder.tsx
├── FunctionListPage.tsx
├── FunctionDetailPage.tsx
├── ProposalListPage.tsx
├── ProposalDetailPage.tsx
├── OntologyHealthPage.tsx
├── OntologyHistoryPage.tsx
├── ontology.types.ts
└── ontology.service.ts
```

新仓库直接使用独立页面、向导、规范版本表和“保存草稿 → Proposal → 发布”流程；不实现巨型 Modal/抽屉式建模、物理表建模、Trino/Iceberg 路径或通用 JSON 资源定义。

#### 11.8.23 本体管理验收标准（已确认）

1. 对象、属性、关系、Interface、Action和Function可统一搜索。
2. 创建对象类型只产生草稿，不直接部署。
3. 每个已发布对象类型恰好一个有效主键。
4. 已有对象后不能原地修改主键。
5. API 名称首次发布后不可直接修改。
6. 显示名称和描述可安全变更。
7. 从 Pipeline输出生成属性映射正确。
8. 无数据Pipeline的对象类型可由Action管理。
9. 多个Pipeline不能无策略写同一对象类型。
10. 属性类型、必填、数组和JSON限制正确。
11. 敏感属性默认不进入OpenSearch。
12. Interface属性映射和权限裁剪正确。
13. Interface不产生独立对象实例。
14. 关系支持双向遍历且不重复保存反向edge。
15. 外键关系和关系Sink均可发布。
16. 引用不存在和基数冲突进入正确质量流程。
17. Action参数、规则、提交条件和审批策略可配置。
18. Action必须先Preview再执行。
19. Action执行具有幂等和对象版本检查。
20. Action通过Pulsar mutation batch和Projection统一写入。
21. Function输入输出具有静态类型。
22. Function以调用者权限执行且不能写对象。
23. Function新版本不自动替换消费者绑定。
24. Proposal可以包含多个本体资源。
25. Proposal并发冲突可检测和人工解决。
26. 影响分析正确识别阻断、高、中、低风险。
27. 未批准或校验失败的Proposal不能发布。
28. 发布失败时旧ontology revision继续服务。
29. 发布版本不可变，回滚通过新Proposal完成。
30. HugeGraph内部Schema名称不依赖API名称。
31. OpenSearch重建验证通过后才切换alias。
32. Projection按精确ontology revision和schema version解释事件。
33. 已退役资源仍保留血缘、历史和审计。
34. Viewer、Builder、Reviewer、Admin及Action/Function执行权限隔离正确。
35. 本体 Flyway 初始化、Proposal 发布和索引部署失败时可重试、可恢复且有报告。
36. 全新Compose可完成建模、审核、发布、投影、影响分析和回滚演练。

### 11.9 对象探索

对象探索采用 D-023：它是只读发现、分析和调用已发布能力的 Object Set 工作台，不是绕过 Action 的通用 CRUD 页面。OpenSearch 负责权限感知的搜索、筛选、排序、Facet和聚合，HugeGraph负责权威对象详情、版本和关系遍历；浏览器不得加载全量对象后计算，Postgres不得恢复对象正文读路径。

#### 11.9.1 路由与边界（已确认）

```text
/ontology/explorer                                  首页
/ontology/explorer/search                           全局搜索结果
/ontology/explorer/:objectTypeId                    单类型探索
/ontology/explorer/:objectTypeId/:objectId          标准对象 Full View
/ontology/explorer/explorations/:explorationId      已保存动态探索
/ontology/explorer/lists/:listId                    静态对象清单
```

深链接可携带 `perspective/queryToken/focusObject/relation`；大型 Query AST 和对象值不进入 URL。临时探索使用签名、短期 `queryToken`，保存探索使用稳定 `explorationId`。新仓库只提供本节定义的规范路由，不设置历史路由兼容期。

v1 支持跨类型搜索、单对象类型 Object Set、Interface公共属性只读 union、类型化复合过滤、表格/卡片/快速分析/关系图/比较、动态 Exploration、静态 List、标准对象 Full/Panel View、Action/Function、异步导出和跨应用联动。不实现直接 CRUD、Iceberg/Trino历史快照、任意 SQL/Gremlin/OpenSearch DSL、Regex/前导通配、无限多跳图、地图或完整时序引擎。

#### 11.9.2 探索首页（已确认）

首页采用紧凑分区，不做应用卡片商城。顶部搜索所有有权对象、对象类型、已保存探索和清单，支持最近搜索、按类型缩小范围，并展示搜索服务状态/索引时间。主体依次为最近对象、最近探索、收藏对象类型、我的探索、我的清单、核心对象类型、按负责人/标签浏览和最近使用；无可访问对象类型时显示权限/下一步说明。

顶部快捷入口为“搜索 / 最近 / 收藏 / 已保存探索 / 对象清单 / 对象类型目录”，属于页面内部导航，不增加新的全局左侧导航项。

#### 11.9.3 全局搜索（已确认）

结果 Tabs 为全部、对象、对象类型、已保存探索和对象清单。对象按类型分组，每条显示标题属性、对象类型、主键摘要、最多三个突出属性、获权命中字段/脱敏 snippet、更新时间、质量角标以及打开详情/沿关系探索操作。

普通模式支持多词“全部/任意”切换、双引号短语和尾部 `term*`；高级模式支持 `AND/OR/NOT` 和括号。禁止前导 `*term`、`*term*`、Regex 和原生 Lucene query string。输入先解析为平台 Search AST，再编译受限 OpenSearch 查询；snippet、分组数量和排序不得使用无权字段或对象。

#### 11.9.4 单类型探索布局（已确认）

顶部显示对象类型/成熟度、当前可见数量、索引更新时间、探索名称、保存、Action、打开到、导出和 AIP。左侧为可收起的属性/关系/保存条件筛选器，中间显示活动筛选 chips 和透视内容。透视模式为结果表格、卡片、快速分析、关系图和比较；切换不丢失查询、排序和选择。

跨类型搜索只负责发现，进入探索后必须确定单一对象类型。Interface探索只显示公共映射属性并以实现类型作为附加列；v1 Interface结果只读，不执行跨实现批量Action。

#### 11.9.5 Object Set Query AST（已确认）

浏览器以稳定对象/属性/关系 ID 提交类型化 AST，不提交显示名、API名、索引名或物理键：

```json
{
  "object_type_id": "object-type-id",
  "where": {
    "type": "and",
    "children": [
      {"type": "property", "property_id": "status-id", "operator": "in", "value": ["active"]},
      {"type": "property", "property_id": "amount-id", "operator": "gte", "value": 1000}
    ]
  },
  "sort": [{"property_id": "created-at-id", "direction": "desc"}]
}
```

服务端按精确 `ontology_revision` 解析资源、验证类型/操作符/可筛选性、合并安全谓词、生成 query fingerprint，并分别编译 OpenSearch/Graph查询。AST最多50个叶子和3层逻辑嵌套；超限返回可理解错误，不静默删条件。

#### 11.9.6 属性与关系过滤（已确认）

- 文本：包含全部词、任意词、精确、不等于、开头、in、空/非空。
- 数值/日期时间：eq/ne/gt/gte/lt/lte/between/in/空；日期另有最近N天。
- Boolean：是/否/未设置。
- Enum：单选、多选、排除、未设置。
- 数组：包含任一、包含全部、空/非空。
- 逻辑：AND/OR/NOT，最多3层。
- 关系：存在/不存在关系、关联指定对象、关联对象满足属性条件。

最多3个关系过滤条件，每个只允许一跳且目标属性必须filterable并有权限；不允许循环或动态多跳。沿关系探索可从当前集合通过一个已发布 Link Type 生成目标类型的新 Object Set，原探索进入可返回历史。

#### 11.9.7 表格与稳定分页（已确认）

表格默认每页50，可选25/50/100；支持服务端多列排序、列显隐/顺序/宽度/固定/换行、类型格式、行选择、全选当前结果、个人默认布局和Admin对象类型默认布局。固定第一列为标题属性，主键可配置显示。

禁止大 offset，使用 OpenSearch `search_after` 或 HugeGraph cursor；排序末尾自动追加 object ID tiebreaker。游标为签名不透明值，绑定用户、query fingerprint、ontology/policy revision并默认15分钟过期。小于10,000返回精确可见数量，大范围允许 `10,000+`/下界，不做无界精确 count。

#### 11.9.8 卡片、快速分析、时间和地图边界（已确认）

卡片使用本体突出属性，展示标题、类型、3—6个突出属性、状态/质量和主要Action，不使用任意前几个字段。

快速分析最多6个临时组件：数量、Enum/Boolean分布、文本Top值、数值直方图、日期直方图以及 `count/sum/avg/min/max` 单字段分组；点击结果追加过滤。布局可随探索保存，但不支持复杂跨对象公式、自由排版或正式发布，后者属于分析看板。

date/datetime支持日/周/月分桶、范围、数量/数值聚合趋势；不做插值、事件序列和实时流图。D-022未定义地理类型，v1不显示空地图入口，后续新增 `geopoint/geoshape` 再实现。

#### 11.9.9 关系图和对象比较（已确认）

关系图围绕当前集合按需展开一层，支持关系类型、双向遍历、聚焦、隐藏/折叠、边属性、对象Panel和沿关系切换探索。默认最多50个起点、200个对象节点和400条边；超限聚合或要求缩小范围，不截任意前N条边，每次展开重新检查目标对象权限。

比较模式只允许2—5个同类型对象，展示相同/不同/缺失属性、共同/差异关系、质量和更新时间。无权字段不参与差异统计，也不提示其存在。布局可保存，对象ID默认不写公开URL。

#### 11.9.10 动态 Exploration 与静态 List（已确认）

动态 Exploration 保存名称、描述、对象类型、Query AST、排序、列、透视、快速分析、关系图配置、保存时 ontology revision、私有/共享、所有者/编辑者；每次打开查询当前数据，不是数据快照。资源以稳定 ID保存；弃用字段显示警告，退役/不兼容条件标记无效并要求修复，不得静默忽略。

静态 Object List 保存单一对象类型和最多10,000个对象引用、名称、说明、创建者/时间、来源探索和共享配置；不保存对象正文、不自动随查询改变，可手工增删。对象被删除显示不存在，用户失权后隐藏。分享 Exploration/List 不授予底层对象权限。

#### 11.9.11 标准对象视图（已确认）

每个已发布对象类型自动拥有标准 Full View 和 Panel View；Panel在探索右侧预览且保留当前查询，Full为可深链接完整页面。v1不在对象探索中实现自定义页面构建器；业务应用可嵌入Panel并组合工作流。

Header显示对象类型、标题、主键摘要、对象version/ETag、更新时间、质量、收藏、复制链接、主要/更多Action和AIP。Tabs为概览、全部属性、关系、Action、Function、活动、数据来源与血缘。

概览显示突出属性、状态、关键关系、最近活动、质量和推荐Action；属性按本体配置分组并展示格式化值、API名、类型、来源和脱敏状态，JSON折叠/延迟加载；关系按Link Type游标分页，显示方向、基数、可见数量、摘要、边属性、图入口和沿关系探索。

#### 11.9.12 活动、来源、质量与血缘（已确认）

活动展示 Action operation、Projection更新、质量状态、关系变化、Pipeline Run、生产者/操作者、时间、correlation ID和终态。Postgres不长期保存完整对象before/after正文；只保存变化属性ID、脱敏摘要、operation状态和证据引用，Action Preview diff按短期TTL清理。

数据来源与血缘展示主Pipeline/版本、最近Run、Projection Batch、ontology revision、源资产、字段血缘、质量规则/报告、OpenSearch索引状态和有权限的SkyWalking trace。不存在Iceberg snapshot选择器；对象版本/审计不是全量历史数据时间旅行。

#### 11.9.13 单对象与批量 Action（已确认）

对象探索操作按“Action / 打开到 / 导出”分组。单对象只返回当前用户可执行且提交条件可能满足的已发布Action；参数能唯一推断才预填，固定 Preview → Diff → 确认/审批 → Execute并观察operation，不能直接POST/PATCH/DELETE对象。

批量选择显式勾选或“当前全部结果”；后者先创建不可变 Selection Token，绑定用户、类型、query fingerprint、policy revision、冻结对象ID、时间和过期。最多1,000对象：不超过100使用单个D-022 mutation batch，101—1,000创建Bulk Action Job按最多100分批。Bulk Job不保证全局原子，展示成功/失败/跳过/部分完成并可仅重试失败；每批执行前重验权限、条件和ETag。Preview给出精确数量、错误和前100项diff；超过1,000禁用并建议缩小或使用自动化。

#### 11.9.14 Function、打开到和 AIP（已确认）

单对象Function接收对象引用，集合Function接收延迟Object Set/query token，不传整页对象值；以调用者权限执行，长任务持久化异步，结果可为标量、表格、对象引用或结构化数据，不能写对象。

“打开到”支持分析看板（保存探索数据源）、业务应用（exploration/list/object引用）、数据血缘、本体管理（Builder入口）和AIP。AIP只携带对象类型/对象ID、exploration/list/query token、页面和意图；Agent工具服务端重查并授权，不把整页数据拼入Prompt，写操作仍调用Action并确认。

#### 11.9.15 异步导出（已确认）

导出固定为持久化异步任务，写MinIO `exports`；v1支持CSV和复制最多1,000个对象ID。向导选择结果/选中对象、列，显示脱敏/禁止字段、预计数量和限制后提交。

默认最多100,000行且Admin可降低；创建和执行均重验权限，绑定Query AST、ontology/policy revision和用户；失权对象/字段不导出，敏感字段脱敏或禁止。文件默认24小时过期，下载使用短期签名URL，仅所有者/授权者可读。审计保存query fingerprint、列ID、行数、hash和下载者，不保存正文；取消不能留下可下载半文件。

#### 11.9.16 权限和防推断（已确认）

授权顺序为对象类型 → Object Security Policy → 对象实例策略 → Property Security Policy → 脱敏 → Action/Function。v1策略DSL支持用户ID、组/角色、用户属性、对象属性、`eq/ne/in/contains`、AND/OR/NOT、敏感等级和组织/部门；不支持任意脚本、外部HTTP、无界关系或无法编译到两存储的策略。

策略发布必须同时生成 `GraphPredicate` 和 `OpenSearchSecurityFilter`，任一失败则阻断。权限在存储查询阶段执行，不能事后删行；总数/Facet/聚合/关系数量均为授权后结果。无权对象返回404；无权字段不可搜索/筛选/排序/聚合/导出，API以 `redacted_fields` 和通用原因码区分脱敏与真实null；snippet不能来自无权字段。缓存键包含用户、权限摘要、policy和ontology revision；权限变化使缓存、query/selection token失效。导出、Bulk Action、Function、List和AIP重用同一授权器。

#### 11.9.17 OpenSearch、HugeGraph 与降级（已确认）

OpenSearch负责跨类型全文、单类型文本、filterable/sortable属性、Facet/聚合、`search_after`和snippet；文档包含对象类型/ID/version、ontology revision、允许索引属性、安全标签/policy字段和Projection时间。HugeGraph负责权威详情、version/ETag、关系、边属性、局部图、索引结果版本确认和索引重建。

未标记filterable/sortable属性不提供对应条件，不对HugeGraph做无界扫描。OpenSearch异常时对象ID直达详情和已知关系可由HugeGraph继续；全局搜索、Facet、复杂列表和导出明确不可用，不回退Postgres或内存扫描。列表显示索引时间，详情始终读取HugeGraph最新授权版本；两者version不一致以HugeGraph为准并触发修复。

#### 11.9.18 性能和限制（已确认）

- 页面25/50/100，默认50；普通查询默认5秒、最大10秒；cursor默认15分钟。
- AST最多50叶子/3层，最多3个一跳关系条件。
- 单Facet最多100桶、单次最多10个Facet。
- 关系图200节点/400边；比较5对象；List 10,000项。
- Bulk Action 1,000项、每批100；导出默认100,000行。
- 对象关系分页默认25、最大100；JSON/大文本/数组懒加载。
- 前端取消普通请求时后端停止查询/编码；SSE断开不取消导出、Function或Bulk Job。
- 所有限制返回产品错误和恢复建议，不静默截断。

#### 11.9.19 数据模型（已确认）

```text
saved_explorations / saved_exploration_versions
object_lists / object_list_items
explorer_layouts / explorer_favorites / explorer_recent_items
selection_tokens
export_jobs
bulk_action_jobs / bulk_action_items
```

Postgres只保存Query AST、稳定资源/对象引用、布局、权限/版本摘要、任务状态、MinIO引用和脱敏错误；不保存对象属性正文。Selection Token保存冻结引用或安全分页清单；大列表、任务项和错误分页保存，不使用无限JSON数组。

#### 11.9.20 API（已确认）

```text
GET    /v1/explorer/home
POST   /v1/search/objects
POST   /v1/object-sets/query
POST   /v1/object-sets/facets
POST   /v1/object-sets/search-around
POST   /v1/object-sets/compare
POST   /v1/object-sets/selection-tokens
GET    /v1/objects/:objectTypeId/:objectId
GET    /v1/objects/:objectTypeId/:objectId/capabilities
POST   /v1/objects/:objectTypeId/:objectId/links
GET    /v1/objects/:objectTypeId/:objectId/activity
GET    /v1/objects/:objectTypeId/:objectId/provenance
GET    /v1/explorations
POST   /v1/explorations
GET    /v1/explorations/:id
PUT    /v1/explorations/:id
DELETE /v1/explorations/:id
POST   /v1/explorations/:id/share
GET    /v1/object-lists
POST   /v1/object-lists
GET    /v1/object-lists/:id
POST   /v1/object-lists/:id/items
DELETE /v1/object-lists/:id/items
POST   /v1/export-jobs
GET    /v1/export-jobs/:id
POST   /v1/export-jobs/:id/cancel
GET    /v1/export-jobs/:id/download
POST   /v1/bulk-action-jobs
GET    /v1/bulk-action-jobs/:id
POST   /v1/bulk-action-jobs/:id/retry-failed
POST   /v1/bulk-action-jobs/:id/cancel
```

query/selection/download token均签名、不透明、短期；浏览器不能提交物理索引、graph label、权限谓词或任意DSL。长任务通过轮询/SSE观察，断开不取消。

#### 11.9.21 前端结构与实现边界（已确认）

```text
portal/src/features/ontology/explorer/
├── ExplorerHomePage.tsx
├── GlobalObjectSearchPage.tsx
├── ObjectExplorationPage.tsx
├── ExplorerToolbar.tsx
├── FilterBuilder.tsx
├── ActiveFilters.tsx
├── ObjectTableView.tsx
├── ObjectCardView.tsx
├── QuickAnalysisView.tsx
├── RelationGraphView.tsx
├── ObjectCompareView.tsx
├── ObjectPreviewPanel.tsx
├── ObjectDetailPage.tsx
├── ObjectPropertiesTab.tsx
├── ObjectRelationsTab.tsx
├── ObjectCapabilitiesTab.tsx
├── ObjectActivityTab.tsx
├── ObjectProvenanceTab.tsx
├── SaveExplorationDialog.tsx
├── SaveObjectListDialog.tsx
├── ExportDialog.tsx
├── BulkActionDialog.tsx
├── explorer.types.ts
└── explorer.service.ts
```

新仓库按上述结构直接实现全局搜索与对象探索，不实现单文件巨型页面、Iceberg snapshot/Trino time-travel、直接 CRUD Modal、浏览器全量筛选/排序/聚合或通用查询端点。查询使用 Object Set AST，导出使用异步任务，关系使用 HugeGraph cursor，Action 使用 D-022 发布版本与 capability API。敏感字段不能只靠 React 隐藏；对象正文不得落入 Postgres；错误、空状态和任务状态使用中文。

#### 11.9.22 对象探索验收标准（已确认）

1. 全局搜索可发现有权对象、对象类型、探索和清单。
2. 无权对象和属性不会出现在搜索或snippet。
3. 单类型探索使用服务端Query AST。
4. 文本、数值、日期、枚举、Boolean和数组过滤正确。
5. AND/OR/NOT及三层嵌套正确。
6. 一跳关系存在、关联对象和关联属性过滤正确。
7. 超限关系和查询返回明确错误。
8. 表格使用稳定cursor，不使用大offset。
9. 多列排序以对象ID作为tiebreaker。
10. 总数、Facet和聚合在权限过滤后计算。
11. 表格列、顺序、宽度和排序可保存。
12. 卡片使用本体突出属性。
13. 快速分析点击结果可追加过滤器。
14. 时间分桶和聚合正确。
15. v1不出现不可用地图入口。
16. 关系图按需展开且遵守目标对象权限。
17. 对象比较不会泄露无权字段差异。
18. 保存探索重新打开查询当前数据。
19. 弃用/退役属性不会被静默忽略。
20. 对象清单只保存引用且不自动变化。
21. 分享探索/清单不会提升对象权限。
22. 每个对象类型自动具有标准Full和Panel视图。
23. 对象详情属性、关系、能力、活动和来源完整。
24. 活动记录不在Postgres保存完整对象正文。
25. 页面不提供绕过Action的直接CRUD。
26. 单对象Action必须Preview后执行。
27. Bulk Action最多1,000对象并以100项分批。
28. Bulk Job正确显示部分成功和失败重试。
29. Function以调用者权限执行且不能写对象。
30. AIP上下文通过引用重查，不把整页数据写入Prompt。
31. Export Job重新检查对象和字段权限。
32. 导出文件过期、hash、审计和取消正确。
33. 无权对象返回404且不能通过数量、Facet或关系推断。
34. OpenSearch异常时详情/关系可降级，搜索和导出不伪装可用。
35. HugeGraph/OpenSearch版本不一致时以HugeGraph为准。
36. 正式路径中不存在 Iceberg/Trino 或 Postgres 对象扫描。
37. 前端 lint/build、Java、权限、性能和 Flyway 测试通过。
38. 全新Compose可完成搜索、探索、关系、Action、保存和安全导出。

### 11.10 分析看板

分析看板按 D-024 实施：它是面向对象和业务指标的只读分析产品，不是原始数据查询器，也不承担业务写入。发布版本只能引用已发布、调用者有权访问的本体资源；运行时始终以当前用户身份重新查询。

#### 11.10.1 页面路由与产品边界

固定路由：

```text
/apps/dashboards                              看板列表
/apps/dashboards/:dashboardId/view            查看模式
/apps/dashboards/:dashboardId/edit            全屏编辑器
/apps/dashboards/:dashboardId/fullscreen      演示/大屏模式
/apps/dashboards/:dashboardId/versions        版本记录
/apps/dashboards/:dashboardId/versions/:id    固定版本只读预览
```

分析看板只负责组合指标、图表、对象表格、筛选器和说明文本。它不能执行 Action、直接编辑对象、连接原始数据源、提交 SQL/Gremlin/PPL、执行任意 JavaScript/HTML/Vega、自建地图或时间序列引擎，也不提供匿名公网发布。复杂可操作页面放入“业务应用”。v1 复用 Postgres、HugeGraph、OpenSearch 和 Ontology API，不新增分析数据库、OLAP 引擎或服务端截图容器。

#### 11.10.2 看板列表

列表使用紧凑表格而不是应用卡片，列为名称、状态、当前发布版本、页面数、组件数、负责人、可见范围、刷新策略、最近发布、健康状态和收藏。顶部提供关键字、状态、负责人、可见范围、标签、健康状态和“仅看收藏”筛选；支持按最近查看、最近更新、名称和使用量排序。

行操作包括打开、编辑、复制、收藏/取消收藏、权限、版本、归档和恢复。永久删除只允许 Owner 删除“从未发布且没有依赖的空草稿”，已发布看板只能归档，以保留依赖、审计和分享链接。新建时填写名称、说明、负责人和可见范围，然后进入空白编辑器；复制只复制定义和依赖引用，不复制权限、使用记录或查询缓存。

#### 11.10.3 生命周期与状态

状态机为：

```text
DRAFT → VALIDATING → READY → PUBLISHING → PUBLISHED
   │          │                       └→ PUBLISH_FAILED
   └──────────┴→ VALIDATION_FAILED
PUBLISHED → ARCHIVED → PUBLISHED（恢复）
```

发布版本不可变。编辑已发布看板会基于当前版本创建新草稿；验证或发布失败时，线上仍指向旧发布版本。所谓回滚不是移动历史指针，而是以目标历史版本创建新草稿、重新验证后发布一个新版本。草稿、发布版本和运行查询必须使用不同 ID，避免缓存和权限判断混用。

#### 11.10.4 查看模式

页头展示名称、说明、版本、负责人、数据更新时间、查询状态、收藏、分享、导出、刷新和全屏入口。看板含 1—10 个页面，以标签切换；页面可有独立说明和筛选器，首屏先加载当前页面可见区域。

Viewer 可切换页面、调整允许的筛选值、排序和分页、触发交叉过滤、查看数据点说明、打开对象详情/对象探索、手动刷新和按权限导出，但不能改布局、保存公共定义或执行 Action。个人筛选状态只保存在用户偏好中，不改变发布版本；“重置”恢复发布者定义的默认值。

#### 11.10.5 数据源与依赖语义

`DashboardDataSource` 是可复用的命名数据源，一个页面或多个组件通过 ID 引用它，避免各组件重复定义查询。允许四类来源：

1. 对象类型 + 服务端 Object Set Query AST。
2. 精确的已发布 Exploration 版本。
3. 精确的已发布 Object List 版本。
4. 精确的已发布、只读 Function 版本。

禁止引用原始数据连接、数据资产、管道内部表、临时探索、任意查询文本或未发布 Function。发布时固定对象类型稳定 ID、属性稳定 ID、关系稳定 ID、被引用资源版本和本体 revision；被引用资源后续发布新版本不会静默改变看板。对象数据本身不是快照，查询结果随当前对象真相和用户权限变化。每个依赖必须进入 D-021 血缘索引，以支持影响分析、健康告警和“在哪里使用”。

#### 11.10.6 组件库

v1 组件固定为：

- 指标卡：单值、单位、趋势、目标值和同比/环比。
- 折线图、面积图：时间或有序维度，支持有限系列和参考线。
- 柱状图、堆叠柱状图：类别比较和 Top N。
- 饼图、环形图：最多 8 个扇区，其余合并为“其他”，不支持多层饼图。
- 散点图：两个数值轴、颜色分组和可选大小度量。
- 对象表格：属性列、排序、分页、对象面板和安全导出，不提供行内编辑。
- 透视表：行/列维度、聚合值、小计和总计，全部由服务端执行。
- 富文本：受限 Markdown、链接和变量占位，不允许原始 HTML、脚本或外部 iframe。
- 筛选器：下拉、多选、日期范围、数值范围、布尔和对象引用选择。
- 分节标题：标题、说明和分隔，不执行查询。

每个组件均有标题、说明、数据源、空状态、错误状态、加载状态、权限裁剪提示和更新时间；图表颜色使用现有前端设计令牌，并满足浅色/深色背景对比度。

#### 11.10.7 维度、度量与计算指标

服务端支持 `count`、`sum`、`avg`、`min`、`max` 和 `approx_distinct`。维度可来自允许分组的枚举、布尔、字符串、对象引用和时间属性；高基数字符串默认要求 Top N，敏感属性不可分组时前端不显示该能力。

计算指标采用类型化表达式 DSL，只允许已定义度量之间的 `+ - * /`、`round`、`coalesce` 和 `percent`，必须在发布时完成类型、除零、单位和空值校验；不允许任意代码或直接访问未授权属性。时间维度支持日、周、月、季度和年粒度，必须保存时区、周起始日和可选财年起始月；同比/环比需要完整记录比较窗口和缺失值策略。

#### 11.10.8 筛选变量

每个 `DashboardFilterVariable` 保存稳定 ID、名称、数据类型、控件类型、默认值、必填、允许空值、候选值来源、敏感标记和作用域。变量与数据源之间采用显式映射：`filter_variable_id → data_source_id → property_id/operator`；禁止根据同名字段隐式绑定。

作用域为全局、页面和组件三级，窄作用域覆盖宽作用域但必须在界面显示来源。普通筛选可实时自动应用；高代价看板使用“应用筛选”按钮，并可配置“首次应用前不查询”。筛选栏显示已生效 chips、待应用状态、恢复默认、全部清除、撤销和重做。敏感筛选值不得进入 URL、浏览器日志、服务端普通日志、APM tag 或 AIP Prompt，只能以短期服务端状态 ID 传递。

#### 11.10.9 交叉过滤、下钻与导航

图表数据点可以发出类型化选择事件，映射为一个或多个筛选变量；当前交叉过滤以可删除 chip 显示，并支持返回上一步。事件只可影响同看板已声明的目标页面/组件，不能拼接任意 URL 或查询表达式。

下钻支持：打开对象 Panel/Full 视图、打开 D-023 对象探索、打开固定 Object List，以及导航到另一个已声明输入变量的看板。聚合数据点下钻必须由服务端生成短期、签名、单次范围受限的 Object Set/selection token，目标页重新执行权限检查；浏览器不得根据图表标签自行构造对象查询。被抑制的小群体数据不能下钻或导出。

#### 11.10.10 全屏编辑器与布局

编辑器采用全屏三栏：左侧为页面、组件库、数据源、筛选变量和大纲；中间为 24 列栅格画布；右侧为当前组件的数据、样式和交互配置。顶栏提供退出、设备预览、撤销/重做、保存状态、预览、验证和发布。

组件支持拖放、缩放、复制、锁定、对齐、层级移动和删除。布局保存 `x/y/w/h`，不能重叠；删除数据源或筛选变量前必须展示受影响组件。发布版本分别保存 desktop、tablet 和 mobile 布局；未手工配置时按确定性规则生成窄屏单列布局。限额为每个看板最多 10 页、每页 30 个查询组件、总计 100 个组件、10 个筛选变量，容器嵌套最多 2 层。

#### 11.10.11 草稿、自动保存与编辑锁

草稿修改使用 ETag 乐观锁。停止操作 2 秒后自动保存，持续编辑时至少每 30 秒保存一次；页头必须明确显示“已保存 / 保存中 / 保存失败 / 离线未保存”。浏览器本地只缓存非敏感布局补丁，不缓存数据结果和敏感筛选值。

v1 采用单编辑者租约，不做实时协同：打开编辑器获取 15 分钟可续租锁，其他 Editor 只读查看并可请求接管；Owner 可强制接管，必须写入审计。锁丢失或 ETag 冲突时禁止覆盖，用户可重新载入或复制未保存补丁为新草稿。

#### 11.10.12 验证与发布

验证至少检查：名称和页面、布局边界、组件 schema、数据源可达、精确版本依赖、属性/函数类型、筛选映射、计算指标、查询成本、循环交叉过滤、权限、敏感聚合、下钻目标和资源限额。

发布流程为：锁定草稿 → 静态验证 → 依赖/血缘/安全检查 → 生成不可变版本和 `DashboardQueryPlan` → 使用发布者权限做受限样例执行 → 原子切换当前版本 → 写审计和事件。任一步失败都删除未完成产物或标记失败，并继续服务旧版本。

普通团队内看板由 Owner 发布。组织级分享、引用高敏感属性、关闭默认小群体抑制或配置高频刷新，需要平台管理员/数据负责人批准；正常看板发布不创建 D-022 Ontology Proposal，因为它不改变本体 schema。

#### 11.10.13 版本、比较与回滚

版本页显示版本号、状态、发布者、时间、说明、依赖 revision、页面/组件数量、查询计划 hash、审批记录和健康状态。任意两个版本可比较页面、布局、组件、数据源、筛选器、权限和依赖的结构化 diff；历史版本只读预览时仍按当前用户权限和当前对象数据查询，并清晰标注“历史定义，不是历史数据”。

恢复历史版本必须“从此版本创建草稿”，完成当前依赖检查后发布新版本；不能直接把旧版本重新设为线上版本。

#### 11.10.14 分享与角色

看板权限角色为 Viewer、Editor、Owner：Viewer 可查看和允许的导出；Editor 可编辑草稿但不能改权限、归档或强制接管；Owner 可发布、管理权限、复制、归档和恢复。平台 Admin 仅用于治理和故障处理，不自动获得业务数据可见权。

可见范围仅支持私有、指定用户、指定用户组、团队和组织内已认证用户；v1 不支持匿名链接、公开互联网、跨租户分享或任意站点嵌入。分享看板只授予看板定义访问权，绝不授予其底层对象、属性、Function、Exploration 或 Object List 权限。权限编辑器提供“以某用户权限检查”，但结果只能由服务端模拟并受管理员权限控制。

#### 11.10.15 聚合安全与防推断

每次查询按以下顺序执行：看板访问 → 数据源访问 → 对象行级策略 → 属性读取/聚合策略 → 用户筛选 → 聚合 → 小群体抑制 → 结果脱敏。任何组件的总数、空状态、Facet、错误文案或执行时长都不能暴露无权对象存在性。

敏感数据聚合默认最小群体阈值为 5；低于阈值的值显示为“已抑制”，tooltip、下钻、AIP 和导出同样不可见。总计或相邻值可能反推被抑制单元格时执行互补抑制；Top N 的“其他”分组也必须经过相同检查。仅管理员在明确治理策略下可为特定数据域调整阈值并留审计。v1 不宣称提供差分隐私。

#### 11.10.16 Dashboard Query Plan、执行与缓存

发布产物 `DashboardQueryPlan` 包含共享数据源、筛选绑定、组件查询、依赖版本、安全策略引用、估算成本和计划 hash。相同数据源/筛选/聚合的组件必须去重或批量执行；当前页面可见组件优先懒加载，浏览器默认每页最多 6 个并发请求，服务端一次批量最多 20 个组件。单组件失败不得清空整页，其余组件继续展示并给出局部重试。

缓存键必须包含看板版本、Query Plan hash、数据源定义 hash、规范化筛选、用户安全上下文 hash、策略 revision、本体 revision、Function 版本和数据 watermark。普通结果默认缓存 60 秒；声明可缓存的确定性 Function 最长 5 分钟。不同用户、角色、属性策略或脱敏策略之间禁止共享结果缓存；权限变化、依赖退役和策略 revision 变化必须立即使相关缓存失效。

#### 11.10.17 刷新、水位与陈旧状态

刷新策略支持关闭自动刷新、1/5/15/60 分钟和手动刷新；平台管理员可配置全局最短间隔，默认 30 秒。只有页面可见且浏览器在线时才执行自动刷新，切换页面后按需加载，避免后台看板持续压垮查询服务。

每个组件展示查询时间、数据 watermark、缓存命中和状态；页面级展示“全部最新 / 部分陈旧 / 部分失败 / 正在刷新”。一次刷新使用同一逻辑刷新 ID，并尽量锁定一致的 source watermark；无法一致时明确标注各组件水位。权限被撤销时立即清除旧结果并显示无权限，不得继续展示缓存内容。

#### 11.10.18 导出、图片与打印

对象表格和透视表 CSV 导出复用 D-023 异步 `export_job`：服务端重新检查权限、字段、抑制策略和行数限制，文件写入 MinIO，短期签名下载并记录 hash、到期和审计。图表数据导出同样通过异步任务，不允许浏览器从已渲染 tooltip 拼接完整数据。

单页/组件 PNG 由浏览器在当前已授权渲染结果上生成；整份 PDF 使用专用打印 CSS 和浏览器“打印为 PDF”，v1 不新增 headless Chromium/渲染服务。导出前可要求重新认证；水印包含看板、版本、导出者和时间。被抑制或无权内容在 CSV、PNG 和 PDF 中保持一致。

#### 11.10.19 AIP 上下文

AIP 只接收 `dashboard_id`、发布版本、页面 ID、组件 ID、非敏感筛选状态 ID 和用户问题，不把整页结果或敏感筛选值直接写入 Prompt。Agent 使用调用者身份通过 Ontology API 重查，可解释指标、比较组件、生成安全下钻链接和建议草稿变更。

AIP 不能发布看板、执行 Action、访问被抑制聚合、扩大查询范围或绕过对象/属性权限。任何“修改看板”建议只能形成可审查的编辑器草稿补丁，由 Editor 接受并通过正常验证/发布流程。

#### 11.10.20 健康、使用情况与可观测性

健康状态为健康、警告、错误和未知，问题精确指向页面/组件/数据源/属性，包括依赖被归档、版本不可用、属性退役、权限变化、查询超限、Function 失败、索引陈旧和持续刷新失败。列表和编辑器均显示问题数量、影响范围和修复入口。

记录打开、页面切换、组件执行、延迟、缓存命中、导出、发布和错误事件，但不记录对象正文、敏感筛选值或完整结果。所有请求携带 `correlation_id`、dashboard/version/page/widget/query-run ID，并可从管理员页面跳转 SkyWalking trace。使用量只展示达到最小群体阈值的聚合统计。

#### 11.10.21 数据模型

Postgres 控制面至少包含：

```text
dashboards
dashboard_versions
dashboard_drafts
dashboard_pages
dashboard_data_sources
dashboard_widgets
dashboard_filter_variables
dashboard_filter_bindings
dashboard_dependencies
dashboard_permissions
dashboard_favorites
dashboard_edit_locks
dashboard_query_plans
dashboard_query_runs
dashboard_health_issues
```

`dashboards` 只保存身份、当前发布版本、活动草稿和生命周期；版本相关表以 `dashboard_version_id` 隔离。组件配置可使用经过 JSON Schema 校验的 JSONB，但稳定资源 ID、依赖、权限、版本、布局和筛选映射必须规范化，不能只塞进一块任意 `config`。Postgres 不保存对象正文、完整查询结果或敏感筛选值；运行表只保留状态、耗时、计数、水位、hash 和过期元数据。

#### 11.10.22 API 契约

```text
GET/POST        /v1/dashboards
GET/PATCH       /v1/dashboards/{dashboardId}
POST            /v1/dashboards/{dashboardId}/copy
POST            /v1/dashboards/{dashboardId}/archive
POST            /v1/dashboards/{dashboardId}/restore
GET/PUT          /v1/dashboards/{dashboardId}/draft
POST             /v1/dashboards/{dashboardId}/edit-lock
POST             /v1/dashboards/{dashboardId}/edit-lock/renew
DELETE           /v1/dashboards/{dashboardId}/edit-lock
POST             /v1/dashboards/{dashboardId}/validate
POST             /v1/dashboards/{dashboardId}/publish
GET              /v1/dashboards/{dashboardId}/versions
GET              /v1/dashboards/{dashboardId}/versions/{versionId}
GET              /v1/dashboards/{dashboardId}/versions/diff
POST             /v1/dashboards/{dashboardId}/versions/{versionId}/create-draft
GET/PUT           /v1/dashboards/{dashboardId}/permissions
PUT/DELETE        /v1/dashboards/{dashboardId}/favorite
GET              /v1/dashboards/{dashboardId}/health
GET              /v1/dashboards/{dashboardId}/usage
GET              /v1/dashboards/{dashboardId}/query-plan
POST             /v1/dashboard-query-plans/{planId}/execute
POST             /v1/dashboard-query-plans/{planId}/widgets:batch
POST             /v1/dashboard-query-plans/{planId}/filter-options
POST             /v1/dashboard-query-plans/{planId}/drilldown-token
```

草稿写入必须带 ETag；执行 API 必须带页面、组件、规范化筛选状态和刷新 ID。所有列表使用稳定 cursor，所有异步执行返回 job/query-run ID；浏览器不直接调用通用 `/v1/query` 拼装看板请求。

#### 11.10.23 权限与审计事件

创建要求 `dashboard:create`，编辑要求资源 Editor，发布/权限/归档要求 Owner；读取组件数据还必须同时通过每个底层资源和对象属性策略。服务端不能相信前端隐藏按钮，固定版本路由、复制、导出、下钻和 AIP 工具均独立鉴权。

至少审计创建、复制、草稿覆盖冲突、锁获取/接管、验证、发布成功/失败、历史版本建草稿、权限变更、归档/恢复、敏感导出、管理员阈值调整和 AIP 草稿建议采纳。审计只保存资源引用、diff hash、结果和 correlation ID，不保存对象结果或敏感变量值。

#### 11.10.24 前端代码拆分

沿用参考仓库的 React 视觉风格，并按职责拆分页面。目标结构：

```text
portal/src/features/applications/dashboards/
├── pages/
│   ├── DashboardListPage.tsx
│   ├── DashboardViewPage.tsx
│   ├── DashboardEditorPage.tsx
│   └── DashboardVersionsPage.tsx
├── editor/
│   ├── DashboardEditorShell.tsx
│   ├── PageNavigator.tsx
│   ├── WidgetPalette.tsx
│   ├── DashboardCanvas.tsx
│   ├── DataSourcePanel.tsx
│   ├── FilterVariablePanel.tsx
│   └── WidgetInspector.tsx
├── runtime/
│   ├── DashboardRuntime.tsx
│   ├── DashboardFilterBar.tsx
│   ├── WidgetFrame.tsx
│   └── DrilldownController.tsx
├── widgets/
│   ├── MetricWidget.tsx
│   ├── CartesianChartWidget.tsx
│   ├── PieWidget.tsx
│   ├── ScatterWidget.tsx
│   ├── ObjectTableWidget.tsx
│   ├── PivotWidget.tsx
│   └── MarkdownWidget.tsx
├── hooks/
├── services/dashboardApi.ts
├── schemas/
└── types.ts
```

运行态组件只消费已验证的版本 DTO 和查询结果，不解析任意后端表达式；编辑态 schema 与服务端 schema 共享版本号。图表适配器、格式化、颜色、空状态和可访问性逻辑集中复用，避免每种组件自行处理权限和错误。

#### 11.10.25 视觉参考与实现边界

看板保留参考仓库的色彩、间距、字体和组件风格，编辑器使用独立全屏路由，不使用超宽 Drawer。新实现只调用类型化看板 API，筛选器使用规范化 AST，看板定义保存到版本化规范表；发布版本只能归档，不能永久删除。新仓库不导入历史看板、通用资源 JSON 或历史权限映射。

#### 11.10.26 验收标准

1. 看板列表能正确显示草稿、已发布、失败、警告和归档状态。
2. 查看、编辑、全屏、版本和固定版本路由可深链接且刷新不丢状态。
3. 编辑已发布看板不会改变当前 Viewer 所见版本。
4. 可创建、排序、复制和删除 1—10 个页面并保存独立布局。
5. 对象类型 + Object Set AST 数据源查询正确。
6. Exploration 精确版本依赖不随新版本静默变化。
7. Object List 精确版本依赖和静态成员语义正确。
8. Function 精确版本、只读和调用者权限正确。
9. 属性/关系退役时依赖进入健康告警而不是自动改绑。
10. 指标、折线/面积、柱状、饼/环形和散点图均有加载、空、错误和权限状态。
11. 对象表格与透视表完全由服务端分页、排序和聚合。
12. 富文本无法注入 HTML、脚本或 iframe。
13. 聚合和计算指标通过类型、单位、除零和空值校验。
14. 时间粒度、时区、周起始、财年和比较窗口结果正确。
15. 全局、页面和组件筛选作用域及覆盖提示正确。
16. 筛选只通过显式 property 映射生效，不能按名称误绑定。
17. 自动应用、手动应用和首次应用前不查询均按配置执行。
18. 恢复默认、全部清除、撤销和重做不产生隐式保存。
19. 交叉过滤显示来源 chip，可撤销且不会形成事件循环。
20. 聚合下钻使用短期签名 token，篡改、过期和越权均被拒绝。
21. 单对象可打开标准 Panel/Full 视图且不绕过字段权限。
22. 全屏三栏编辑器支持拖放、缩放、对齐、复制、锁定和检查器。
23. desktop/tablet/mobile 布局确定且窄屏不会重叠或丢组件。
24. 自动保存、ETag 冲突、锁续期和 Owner 接管不会覆盖他人修改。
25. 无效依赖、超限查询、循环联动和敏感配置不能发布。
26. 验证或发布失败时旧版本继续服务且故障可定位。
27. 发布版本不可变，回滚只能创建并发布新版本。
28. 分享看板不会授予任何底层对象、属性或 Function 权限。
29. 无权用户不能从总数、空状态、Facet、错误或耗时推断对象。
30. 小群体和互补抑制在图表、tooltip、下钻、AIP 和导出中一致。
31. 缓存键包含用户安全上下文、策略、本体和依赖 revision。
32. 权限/策略变化立即失效缓存并清除浏览器旧结果。
33. 查询计划能去重相同查询并限制客户端/服务端并发。
34. 非当前页面和首屏不可见组件按需懒加载。
35. 单组件失败不清空整页，局部重试和 correlation ID 可用。
36. 手动/自动刷新、水位、缓存命中、部分陈旧和失败状态准确。
37. CSV、PNG 和打印/PDF 遵守权限、抑制、水印、到期和审计。
38. AIP 仅凭引用以用户身份重查，不能发布、写对象或绕过抑制。
39. 健康问题能定位到具体版本、页面、组件、数据源和依赖。
40. 全新数据库可初始化示例看板，初始化或发布失败不产生假版本。
41. 前端 lint/build、Java、API、权限、缓存、性能和 Flyway 测试通过。
42. 全新 Compose 可完成建板、查询、联动、下钻、发布、分享、安全导出和版本恢复。

### 11.11 业务应用

业务应用按 D-025 实施：它是面向日常业务处理的对象驱动应用，不是任意低代码平台。所有读取来自 Ontology API，业务逻辑使用只读 Function，所有对象写入使用已发布 Action；布局、变量、事件、依赖和版本均为服务端可验证的类型化定义。

#### 11.11.1 产品边界与路由

业务应用承担订单处理、风险处置、客户管理、设备巡检、任务收件箱、案例审核、对象分配和运营工作台等可操作流程。分析看板负责只读分析，业务应用负责对象上下文、表单、Action、审批和跨页流程；自动化负责无人值守触发，审批中心负责审批实例决策。

固定路由：

```text
/apps/business                                      应用列表
/apps/business/new                                  新建应用
/apps/business/:applicationId/run                   运行模式
/apps/business/:applicationId/edit                  全屏编辑器
/apps/business/:applicationId/preview               草稿预览
/apps/business/:applicationId/versions              版本记录
/apps/business/:applicationId/versions/:versionId   固定版本只读预览
/apps/business/:applicationId/access                访问检查
```

平台左侧导航直接显示“业务应用”，不增加 Applications 大分类。进入应用后，平台导航仍保留；应用自己的页面导航位于内容区顶部或应用内次级左栏。v1 不允许原始数据源、SQL/Gremlin/PPL、任意 HTML/CSS/JavaScript、任意 HTTP 调用或绕过 Action 的直接对象 CRUD。

#### 11.11.2 应用列表

列表使用紧凑表格，不使用应用卡片。列为名称、状态、模板、当前发布版本、页面数、负责人、团队、可见范围、使用人数、健康状态、最近发布、最近打开和收藏。支持按名称/说明、状态、负责人、团队、模板、健康、“仅收藏”和“我可编辑”筛选，按最近打开、最近更新、名称和使用量排序。

行操作为运行、编辑、复制、收藏、版本、访问检查、权限、归档和恢复。已发布应用只能归档；只有从未发布、没有依赖的空草稿可由 Owner 永久删除。复制只复制应用定义和精确依赖引用，不复制权限、运行记录、个人状态、缓存或 Action 实例。

#### 11.11.3 新建流程与模板

新建时填写名称、说明、模板、负责人、团队和默认可见范围，完成后进入全屏编辑器。v1 提供五个模板：空白应用、对象管理台、工作队列、案例审核和运营工作台。

模板仅在创建时复制一份可编辑定义，不形成运行时继承；模板后续更新不会静默改变已创建应用。对象管理台默认“对象表格 + 对象详情 + 活动记录 + Action”；工作队列默认“筛选/指标 + 任务表格 + 处理抽屉”；案例审核默认“对象/证据关系 + 评论 + 审批 Action”；运营工作台默认“指标/趋势 + 异常对象 + 常用操作”。

#### 11.11.4 运行页

运行页顶部展示应用名称、版本、页面导航、当前上下文摘要、刷新、分享、收藏和 AIP 入口。当前页面由页面工具栏、筛选/输入区域、业务内容区域和 Drawer/Modal Overlay 组成。Viewer 可切换页面/Tabs、设置变量、选择对象、打开详情、调用 Function、发起有权 Action、查看审批/投影状态、安全导出并导航到对象探索、分析看板或其他应用。

运行用户不能修改应用定义、保存公共默认值、查看内部查询表达式、访问无权底层资源，不能通过浏览器构造未绑定 Action/Function。复杂路由状态使用服务端签名 token；刷新浏览器后只恢复允许持久化的非敏感状态。

#### 11.11.5 全屏编辑器

编辑器采用三栏：左侧为页面/布局树、组件库、变量、事件、依赖和问题；中间为应用画布；右侧为当前节点的数据、输入/输出、样式、交互、条件可见性和权限要求。顶部为退出、撤销/重做、保存状态、设备预览、运行预览、验证和发布。

支持拖放、复制/剪切/粘贴、页面和区域排序、锁定区域、desktop/tablet/mobile 预览、变量依赖图、事件链、发布阻断问题和用户访问模拟。复制组件时必须选择“复用原变量”或“同时复制输入变量”，避免隐式共享状态。编辑和运行采用不同组件边界，运行态不加载编辑器 schema 操作能力。

#### 11.11.6 结构化布局

布局模型固定为：

```text
Application
└── Page
    ├── Header Region
    ├── Section
    │   ├── Row
    │   │   └── Column
    │   │       └── Widget
    │   └── Tabs
    │       └── Tab → Section
    └── Overlay
        ├── Drawer
        └── Modal
```

支持单栏、左右分栏、主内容 + 侧栏、上下分区、收件箱、主从详情、Tabs、可折叠区域、Drawer 和 Modal。使用预设比例和断点生成响应式布局，不使用自由坐标、任意绝对定位、组件覆盖或自定义 CSS。Boolean 变量可控制页面选择、区域显示/折叠、Tab 和 Overlay，但显示条件不是权限边界。

限额为每个应用最多 20 页、每页 30 个组件、应用总计 200 个组件、100 个变量、10 个 Overlay、布局嵌套 4 层、单事件链 10 步。

#### 11.11.7 对象与数据组件

v1 提供对象表格、紧凑对象列表、对象详情、属性面板、一跳关系列表、活动时间线、2—5 个对象比较和对象搜索/选择器。对象表格支持服务端查询、筛选、排序、稳定 cursor、单选/多选、列配置、对象 Panel/Full 视图和 D-023 安全导出，不允许行内直接编辑。

对象详情复用 D-023 标准视图；关系列表只执行已声明关系的一跳查询；活动时间线只保存/读取事件引用和安全摘要，不把完整对象正文复制到 Postgres。组件必须具有加载、空、权限裁剪、局部失败、陈旧和恢复状态。

#### 11.11.8 输入、操作与展示组件

输入组件包括文本、数字、日期/时间、单选/多选、开关、对象选择器、Object Set 筛选器和文件选择；它们只更新变量，不直接写对象。文件先写 MinIO 临时私有区，变量只保存短期文件引用，只有已声明 Action 可消费。

操作组件包括按钮组、Action 表单、审批状态、状态提示和当前对象快捷操作栏。Action 表单完全根据服务端 Action 参数 schema 和能力响应生成，应用只配置变量映射、显示顺序和允许的说明，不能重新定义写入规则。

展示组件包括指标卡、折线图、柱状图、饼/环形图、对象聚合表、受限 Markdown、图片、分节标题、Tabs、分隔线和空状态。指标/图表复用 D-024 渲染库、聚合安全和格式化能力，但业务应用不嵌入完整看板定义。

#### 11.11.9 数据来源与依赖

允许数据来源为对象类型 + Object Set Query AST、单对象引用、一跳关系、精确版本的 Exploration/Object List/只读 Function、已发布 Action 绑定，以及当前用户可见的 Action/审批运行状态。禁止原始连接、管道内部表、任意查询文本、未发布资源和浏览器直连存储。

发布版本固定对象/属性/关系稳定 ID、本体 revision、Exploration/List/Function/Action 精确版本和导航目标版本策略；被引用资源的新版本不静默改变应用。对象数据仍读取当前真相而不是历史快照。所有依赖贡献到 D-021 血缘索引，支持影响分析、健康和“在哪里使用”。

#### 11.11.10 类型化变量

变量类型为 `boolean`、`string`、`number`、`date`、`timestamp`、`enum`、基础类型数组、`object_ref<T>`、`object_set<T>`、`action_result<T>` 和 `approval_ref`。来源可为静态默认值、应用接口输入、签名路由输入、输入组件、组件输出、对象属性、Object Set 查询、Function 输出、受限转换表达式和 Action 结果。

每个变量保存稳定 ID、名称、类型、默认值、可空性、敏感标记、是否可路由、是否保存个人状态、重算策略、上游依赖和使用位置。`object_set<T>` 在浏览器只保存服务端查询/selection token 和选择状态，不物化大对象数组。受限转换只支持类型化比较、布尔逻辑、空值、数组包含和字符串模板，不执行任意代码。

#### 11.11.11 变量计算、状态和依赖图

变量优先级为运行时事件值 → 当前会话值 → 签名路由输入 → 用户个人状态 → 发布默认值。重算策略为自动、手动、首次可见时和事件触发；不可见页面、未选 Tab 和关闭 Overlay 中的变量/查询惰性计算。输入变化默认 300ms 防抖，上游变化取消未完成的旧请求。

编辑器提供 `输入组件 → 变量 → Object Set → 表格 → 当前对象 → Function → Action` 依赖图，可搜索、展开上下游和定位组件。发布拒绝变量类型不兼容、对象泛型不匹配、循环依赖、无稳定 ID 和未使用敏感变量。

#### 11.11.12 组件输入与输出端口

所有组件使用显式类型端口。例如对象表格输入 `object_set<Order>`、列定义和页大小，输出 `active_object: object_ref<Order>`、`selected_objects: object_set<Order>` 和 `selected_count: number`；Action 表单输入 Action binding、目标对象和参数默认值，输出 preview、submission、projected objects 和状态。

编辑器只能连接兼容类型，不能将 `object_ref<Customer>` 接入需要 `object_ref<Order>` 的端口。输出变量具有单一所有者；多个写入者必须通过显式“合并/选择”变量转换，禁止最后写入者隐式获胜。

#### 11.11.13 事件系统

触发器包括按钮点击、表格选择、输入变化、Tab 切换、页面进入、Overlay 关闭、Function 完成/失败、Action 开始/已提交/待审批/已投影/失败和导出完成。

本地效果包括设置/重置变量、切换页面/Tab、展开/折叠区域、打开/关闭 Overlay、通知和聚焦组件。服务端效果包括重算 Function、刷新 Object Set、Action Preview、提交已确认 Action和安全导出。导航效果包括打开对象、对象探索、分析看板、其他业务应用、审批中心或管理员允许域名的 HTTPS 链接。

事件步骤按顺序执行，每步显式指定是否等待上一步结果；发布时检测直接/间接循环和超过 10 步的链路。`ACTION_SUBMITTED`、`PENDING_APPROVAL` 和 `PROJECTED` 是不同事件，只有 `PROJECTED` 才可默认刷新受影响对象；不能用提交成功制造对象已更新的假象。事件不能调用任意 URL/API、脚本或拼装查询语言。

#### 11.11.14 Action 绑定与写入闭环

应用只绑定本体中精确版本的已发布 Action，保存目标对象变量、参数到变量映射、固定参数、用户可编辑参数、显示/禁用条件、批量策略和生命周期事件。固定/隐藏参数必须由 Action schema 明确允许，服务端忽略浏览器伪造绑定。

运行流程固定为：应用权限 → Action 权限 → 对象/属性/版本检查 → 服务端表单能力 → 用户输入 → Preview → 对象/字段 diff → 用户确认 → 可选审批 → mutation batch → Pulsar → Projection → `PROJECTED/DEGRADED/FAILED` → 定向刷新。Preview token 绑定用户、应用版本、Action binding、对象版本、参数 hash 和到期时间，不能跨应用/用户重放。

等待审批显示请求和审批中心链接，不能显示完成。批量 Action 复用 D-023 selection token，最多 1,000 对象、每批 100，展示预计影响和部分结果，可只重试失败项；浏览器不能用任意对象 ID 数组替代 selection token。

#### 11.11.15 Function 绑定

Function 用于派生值、Object Set、输入校验、候选值、按钮可用性和只读摘要。应用绑定精确发布版本和类型化输入/输出，以运行用户权限通过 D-022 受限 DSL 执行；必须只读、可取消、有超时且不能访问无权对象。

Function 默认按依赖惰性计算，失败只影响下游变量和组件。确定性 Function 可按用户安全上下文和输入 hash 短时缓存；应用不能在浏览器执行 Function，也不能把 Function 包装成写操作。

#### 11.11.16 条件显示与禁用

页面、区域、组件、按钮、必填状态、Tab 选择和 Overlay 可绑定 Boolean 变量。条件表达式仅支持等于/不等于、大小比较、空/非空、`and/or/not`、枚举包含和用户应用内 capability；必须有默认分支和类型检查。

条件可见性只用于体验。服务端仍独立执行应用、对象、属性、Action 和 Function 鉴权；被隐藏组件不能通过 Runtime API、缓存、错误、计数或事件输出旁路访问。

#### 11.11.17 应用接口、深链接与导航

已发布应用可声明类型化 `ApplicationInterface` 输入，例如 `order: object_ref<Order>`、`mode: enum<view,review>`；导航到另一个应用时显式映射变量。v1 不支持嵌套运行另一个业务应用或向父应用输出实时变量，输出只用于关闭当前 Overlay/返回已声明来源页面时的受限结果。

对象探索和分析看板通过稳定资源 ID、精确/当前发布版本策略和签名状态 token 打开。敏感值不进入 URL；复杂状态由服务端生成默认 15 分钟有效、绑定目标应用和用户的 `application_state_token`，目标页重新鉴权。外部链接只允许控制面板配置的 HTTPS 域名并显示离站提示。

#### 11.11.18 与分析看板、对象探索和审批的边界

业务应用可导航到带签名筛选状态的分析看板，并复用指标/图表运行组件，但不能嵌入完整看板、编辑看板或读取其他用户缓存结果。对象搜索/集合/详情复用 D-023，业务应用只配置上下文和事件，不另建查询语言。

需要审批的 Action 在应用发起，在审批中心决策；应用只显示审批状态和链接，不能定义审批策略或代替审批人决定。自动化可以调用同一已发布 Action/Function，但不读取浏览器会话变量。

#### 11.11.19 AIP 上下文

AIP 只接收 application/version/page/widget ID、当前对象引用、Object Set/selection token、非敏感状态 ID、允许的 Action/Function 引用和用户问题。Agent 可解释对象、总结选择集合、推荐下一步、调用只读 Function、生成导航和填写 Action 表单草稿。

AIP 不能自动确认 Action、通过审批、发布应用、读取敏感变量或绕过权限；Action 草稿仍进入 Preview → 用户确认 → Execute。整页对象正文、Action 参数和敏感输入不得直接写入 Prompt。

#### 11.11.20 生命周期、草稿与编辑锁

状态为 `DRAFT → VALIDATING → READY → PUBLISHING → PUBLISHED`，异常为 `VALIDATION_FAILED/PUBLISH_FAILED`，另有 `ARCHIVED`。发布版本不可变；编辑已发布版本创建新草稿；发布失败继续运行旧版本；恢复历史版本必须创建草稿、按当前依赖重新验证并发布新版本。

草稿写入使用 ETag。停止操作 2 秒自动保存，持续编辑至少每 30 秒保存；页头显示保存中、已保存、失败和离线未保存。v1 使用 15 分钟可续租的单编辑者锁，不做实时协作；其他 Editor 只读，Owner 可审计式强制接管。冲突时不能覆盖，可重载或把本地非敏感补丁复制为新草稿。

#### 11.11.21 验证与发布

验证布局、组件 schema、端口类型、变量/事件循环、Action/Function 版本、导航目标、条件表达式、对象/属性依赖、权限要求、查询成本、资源限额、敏感变量路由、外部 URL 和响应式布局。

发布流程为锁定草稿 → 静态验证 → 依赖/血缘/权限检查 → 生成不可变版本与 `ApplicationRuntimePlan` → 以发布者权限做受限初始化/Action Preview 模拟 → 原子切换当前版本 → 审计和事件。任一步失败均继续服务旧版本。组织级分享、高敏感依赖或高风险 Action 绑定需要数据负责人/管理员批准，但不创建 Ontology Proposal，因为应用发布不改变本体 schema。

#### 11.11.22 版本、比较与恢复

版本页显示版本、发布者、说明、页面/组件/变量/事件数量、依赖 revision、Runtime Plan hash、权限范围、审批和健康。任意版本可比较页面、布局、组件、变量、事件、Action/Function 绑定、接口、权限和依赖 diff。

历史版本预览仍使用当前对象数据和当前用户权限，并标注“历史应用定义，不是历史数据”。从历史版本恢复必须创建并发布新版本，不能直接移动线上指针。

#### 11.11.23 权限、访问检查与分享

角色为 Viewer、Editor、Owner：Viewer 运行；Editor 编辑草稿、预览和验证；Owner 发布、权限、归档和强制接管。Admin 负责治理但不自动获得业务对象权限。应用 Viewer 不授予对象、属性、关系、Action、Function、Exploration/List、看板或审批权限。

依赖标记为必需或可选：必需依赖缺权时阻止启动并给出不泄露数据的缺失项；可选依赖缺权时隐藏/禁用相关组件并保持布局可理解。访问检查可模拟用户/组能否打开、缺少哪些必需资源、哪些组件/Action 不可用和哪些属性被裁剪，只有相应管理员可检查他人。

分享仅支持私有、指定用户/组、团队和组织内认证用户，不支持匿名、公网、跨租户或任意 iframe。个人状态只能保存最后页面、展开区域、非敏感筛选、列宽和分页大小，不能保存 Action 输入、对象正文、Preview token、审批凭据、文件引用或敏感变量。

#### 11.11.24 Application Runtime Plan 与执行

发布产物包含页面/布局树、组件、变量依赖图、事件图、查询计划、Action/Function 绑定、权限要求、显示条件、应用接口、精确依赖版本和 plan hash。初始化顺序为应用鉴权 → 用户 capability matrix → 合并默认/个人/签名状态 → 计算当前可见变量 → 批量查询可见组件。

浏览器最多 6 个并发请求，服务端每批最多 20 个组件；相同 Object Set 查询去重，不可见页面不查询，单组件失败不清空整页。Action、审批、Projection 等后台状态不因页面关闭而取消。缓存键包含应用版本、Runtime Plan hash、变量、依赖版本、用户安全上下文、策略和本体 revision，不同权限上下文禁止共享结果。

#### 11.11.25 导出、运行状态与刷新

对象导出复用 D-023 异步 export job，服务端重新检查对象/属性权限、选择 token、脱敏、限制和审计，文件进入 MinIO 短期私有下载。浏览器不能从表格已加载页拼接完整导出。Action 表单、敏感输入和审批材料不进入通用导出。

手动刷新只重算当前页面可见依赖；事件可定向刷新变量/Object Set。运行状态区分加载、缓存、陈旧、Function 失败、Action 已提交、待审批、等待投影、DEGRADED 和权限撤销。权限撤销必须清空旧结果；Projection 未完成时展示 pending，不做乐观对象写入。

#### 11.11.26 健康、使用情况与可观测性

健康问题定位到应用版本、页面、布局节点、组件、变量、事件步骤、Action/Function、属性或导航目标，覆盖依赖归档、属性退役、版本不兼容、Function 超时、查询持续失败、事件循环、路由不兼容、权限大面积缺失、无 Owner 和 Projection 长期 DEGRADED。

记录应用打开、页面切换、查询、Action Preview/提交、审批跳转、导出、发布、锁接管和错误；不记录对象正文、敏感变量或完整 Action 参数。请求携带 correlation/application/version/page/widget/variable/event-step/run ID，管理员可跳转 SkyWalking。使用量只展示达到治理阈值的聚合统计。

#### 11.11.27 数据模型与 API

Postgres 控制面至少包含：

```text
business_applications
business_application_versions
business_application_drafts
application_pages
application_layout_nodes
application_widgets
application_variables
application_variable_dependencies
application_widget_bindings
application_event_handlers
application_event_steps
application_action_bindings
application_function_bindings
application_dependencies
application_interfaces
application_permissions
application_favorites
application_edit_locks
application_runtime_plans
application_runtime_runs
application_health_issues
application_personal_states
```

组件特有样式可使用经过版本化 JSON Schema 校验的 JSONB；页面、布局节点、稳定组件 ID、变量、依赖图、事件、Action/Function 绑定、接口、权限和版本必须规范化。Postgres 不保存对象正文、完整 Object Set 结果、敏感变量或 Action 表单正文。

```text
GET/POST        /v1/applications
GET/PATCH       /v1/applications/{applicationId}
POST            /v1/applications/{applicationId}/copy
POST            /v1/applications/{applicationId}/archive
POST            /v1/applications/{applicationId}/restore
GET/PUT         /v1/applications/{applicationId}/draft
POST            /v1/applications/{applicationId}/edit-lock
POST            /v1/applications/{applicationId}/edit-lock/renew
DELETE          /v1/applications/{applicationId}/edit-lock
POST            /v1/applications/{applicationId}/validate
POST            /v1/applications/{applicationId}/publish
GET             /v1/applications/{applicationId}/versions
GET             /v1/applications/{applicationId}/versions/{versionId}
GET             /v1/applications/{applicationId}/versions/diff
POST            /v1/applications/{applicationId}/versions/{versionId}/create-draft
GET/PUT         /v1/applications/{applicationId}/permissions
POST            /v1/applications/{applicationId}/check-access
PUT/DELETE      /v1/applications/{applicationId}/favorite
GET             /v1/applications/{applicationId}/health
GET             /v1/applications/{applicationId}/usage
GET             /v1/applications/{applicationId}/runtime-plan
POST            /v1/application-runtime-plans/{planId}/initialize
POST            /v1/application-runtime-plans/{planId}/widgets:batch
POST            /v1/application-runtime-plans/{planId}/variables:compute
POST            /v1/application-runtime-plans/{planId}/refresh
POST            /v1/application-runtime-plans/{planId}/state-token
POST            /v1/application-runtime-plans/{planId}/action-bindings/{bindingId}/preview
POST            /v1/application-runtime-plans/{planId}/action-bindings/{bindingId}/execute
POST            /v1/application-runtime-plans/{planId}/exports
```

应用 Action API 只能调用 Runtime Plan 已声明的 binding，内部仍进入统一 ActionService；execute 必须携带有效 preview token。草稿写入带 ETag，异步操作返回 run/job ID，浏览器不能通过通用代理调用任意 Action、Function 或查询。

#### 11.11.28 前端结构与实现边界

目标结构：

```text
portal/src/features/applications/business-applications/
├── pages/
│   ├── ApplicationListPage.tsx
│   ├── ApplicationRuntimePage.tsx
│   ├── ApplicationEditorPage.tsx
│   ├── ApplicationPreviewPage.tsx
│   ├── ApplicationVersionsPage.tsx
│   └── ApplicationAccessPage.tsx
├── editor/
│   ├── ApplicationEditorShell.tsx
│   ├── LayoutTree.tsx
│   ├── WidgetPalette.tsx
│   ├── ApplicationCanvas.tsx
│   ├── VariablePanel.tsx
│   ├── VariableGraph.tsx
│   ├── EventEditor.tsx
│   └── WidgetInspector.tsx
├── runtime/
│   ├── ApplicationRuntime.tsx
│   ├── RuntimePage.tsx
│   ├── RuntimeSection.tsx
│   ├── OverlayController.tsx
│   ├── EventDispatcher.tsx
│   └── ActionController.tsx
├── widgets/{object,input,action,display,layout}/
├── services/applicationApi.ts
├── hooks/
├── schemas/
└── types.ts
```

沿用既定前端视觉令牌。对象表格/视图、图表、Action 表单和导出必须从对象探索、分析看板和审批中心抽成共享领域组件。业务应用使用独立、版本化的规范表；审批只由审批中心承载，定时动作只由自动化承载，二者不能写入应用定义。新仓库不导入通用 `APPLICATION` 资源 JSON 或历史应用数据。

#### 11.11.29 明确不做的范围与验收标准

v1 不实现自定义代码 Widget、插件市场、任意外部 API/iframe、完整 Workshop、Scenario/对象分支沙箱、实时多人协作、离线/原生移动应用、循环布局、应用嵌套应用、应用内定义 Action/Function、表格直接 CRUD、匿名公开或浏览器侧安全规则。

验收标准：

1. 应用列表正确显示草稿、已发布、失败、警告和归档。
2. 运行、编辑、预览、版本和访问检查路由可深链接。
3. 编辑草稿不影响当前发布运行版本。
4. 模板创建后不随模板新版本静默变化。
5. 结构化布局在 desktop/tablet/mobile 下不重叠。
6. 对象表格完全服务端分页、排序和权限裁剪。
7. 对象详情和关系遵守对象、字段和关系权限。
8. 输入组件只修改变量，不能直接写对象。
9. 组件端口类型错误和对象泛型不匹配阻止发布。
10. 变量循环和事件循环阻止发布。
11. 不可见页面、Tab 和 Overlay 不执行查询。
12. Object Set 在浏览器只保存引用而非完整对象集合。
13. Function 以当前用户权限执行且不能写入。
14. Function 失败只影响依赖变量和组件。
15. Action 表单根据服务端 schema 和 capability 生成。
16. Action 必须 Preview 后才能提交。
17. Preview 展示准确的对象和字段 diff。
18. Action 无权、对象旧版本、参数和 binding 篡改均被拒绝。
19. 等待审批不能显示为已完成。
20. Projection 未完成不能触发对象已更新的假成功。
21. 批量 Action 使用 selection token 且不超过 1,000 个对象。
22. 批量部分成功能够只重试失败项。
23. 条件隐藏不能绕过服务端鉴权。
24. 应用 Viewer 权限不会授予对象、属性、Function 或 Action 权限。
25. 必需依赖缺权时阻止运行并给出安全修复信息。
26. 可选依赖缺权时不泄露资源内容或对象数量。
27. 签名路由状态过期、篡改、跨用户或越权均被拒绝。
28. 敏感变量不进入 URL、日志、APM、个人状态或 AIP Prompt。
29. 外部 URL 只能使用管理员允许的 HTTPS 域名。
30. Action 提交、审批、投影成功/退化/失败事件顺序正确。
31. 发布失败时旧版本继续运行。
32. 发布版本不可变，恢复历史定义产生新版本。
33. 自动保存、ETag、租约和强制接管不会覆盖他人修改。
34. Runtime Plan 查询去重、惰性加载和并发限制有效。
35. 用户安全上下文不同不能共享查询缓存。
36. 单组件失败不会清空整页应用。
37. 对象导出重新检查权限、到期和审计。
38. AIP 不能自动确认 Action、审批或绕过权限。
39. 健康问题能定位到页面、组件、变量、事件或依赖。
40. 应用定义不包含审批实例、Cron 调度或自动化运行状态。
41. 前端 lint/build、Java、API、权限、性能和 Flyway 测试通过。
42. 全新 Compose 可完成建应用、对象查询、变量联动、Action、审批、发布和版本恢复。

### 11.12 自动化

自动化按 D-026 实施：以版本化规则持续/定时评估平台事件，并通过强幂等、至少一次的效果执行实现可靠业务自动化。它不是通用工作流引擎，不能提交任意脚本、HTTP 请求、Pulsar Topic 或底层查询语言。

#### 11.12.1 架构与产品边界

`automation` 是 Ontology Core 内部模块，Postgres 保存定义、版本、trigger cursor、成员 ledger、运行/effect/idempotency ledger、租约和 DLQ；Spring Scheduler 负责 Cron/到期扫描，Postgres transactional outbox 保证控制面状态与 Pulsar 消息协调，后台效果使用独立有界执行器，不占满 HTTP 线程。v1 不新增 Airflow、Temporal、Camunda 或 Automation 容器。

内部 Topic 固定为：

```text
persistent://platform/events/ontology-changes
persistent://platform/events/pipeline-events
persistent://platform/events/quality-events
persistent://platform/events/approval-events
persistent://platform/commands/automation-effects
persistent://platform/commands/mutation-batches
persistent://platform/dlq/automation-effects
```

Projection Worker 在 HugeGraph 事务提交后发布对象变更事件；事件只作为重评信号，Evaluator 必须以自动化执行主体重新查询当前真相和权限。OpenSearch DEGRADED 时不得基于陈旧索引触发不可逆效果，应延迟/重试或使用可证明等价的 HugeGraph 查询。

#### 11.12.2 路由与首页

```text
/apps/automations
/apps/automations/new
/apps/automations/:automationId
/apps/automations/:automationId/edit
/apps/automations/:automationId/runs
/apps/automations/:automationId/runs/:runId
/apps/automations/:automationId/versions
/apps/automations/:automationId/access
```

首页展示总数、启用、静默、暂停、自动暂停、24 小时失败、待处理 DLQ 和即将到期。主体用紧凑表格，列为名称、状态、触发器、版本、执行身份、负责人、最近触发、成功率、最近错误、下次调度和健康；按状态、触发器/效果、负责人、执行身份、对象类型、是否含 Action/审批和健康筛选。

行操作为打开、编辑、复制、测试、静默/取消、暂停/恢复、历史、权限和归档。已启用/历史版本不可删除，归档保留运行、血缘和审计。

#### 11.12.3 创建向导、详情和版本

五步全屏向导为基本信息 → 触发器 → 条件 → 效果 → 安全与测试。基本信息包括名称、说明、负责人、运行操作员、执行身份和到期；完成只创建草稿，不直接启用。

详情 Tabs 为概览、定义、运行历史、依赖与血缘、版本和设置。版本不可变；编辑启用版本创建草稿，发布/启用失败继续运行旧版本。切换时旧版本已领取的 run 用旧定义完成，新事件进入新版本，trigger cursor 原子切换；历史恢复必须创建新版本。

#### 11.12.4 生命周期

状态为 `DRAFT → VALIDATING → READY → ENABLED`，运行控制状态另有 `MUTED/PAUSED/EXPIRED/AUTO_PAUSED/ARCHIVED`。ENABLED 评估并执行；MUTED 仍评估和记录“本应触发”但不执行效果；PAUSED/EXPIRED/AUTO_PAUSED 不评估新事件。

暂停取消尚未开始的内部步骤，但不能假装撤回已提交 Action、管道、审批或已发送通知；页面必须列出已经发生和仍在运行的效果。恢复前检查积压和补跑策略，禁止无上限追赶。

#### 11.12.5 对象触发器

支持对象进入/离开 Object Set、集合内修改、指定属性修改、对象新建/删除、定时遍历集合以及聚合/Function 条件 false→true 和 true→false。对象来源为对象类型 + Object Set AST、精确 Exploration/List 版本或返回 `object_set<T>` 的精确 Function 版本。

每个版本维护 `automation_version_id + object_type_id + object_id + previous/current membership + object_version + evaluated_at` 成员 ledger，以区分进入、离开和留在集合内修改。事件保存 changed property stable IDs；删除事件只保留最小对象引用和版本，不复制正文。

#### 11.12.6 平台事件与定时触发器

平台事件支持管道成功/失败/DEGRADED、质量运行失败、问题新建/升级/恢复、审批完成/拒绝/过期和 Action Projection 完成/失败。绑定资源稳定 ID、发布版本策略、状态和 event schema 版本，用户不能输入 Topic 名称。

定时支持固定间隔、Cron、工作日、日/周/月和一次性，保存时区、DST、起止、下次时间和 misfire 策略。最短间隔默认 1 分钟；错过策略为 SKIP、RUN_ONCE 或最多默认 3 次的 CATCH_UP_LIMITED，禁止无限补跑。

#### 11.12.7 类型化条件

DSL 只允许 `and/or/not`、等于/比较、空值、包含、changed/changed_from/changed_to、entered_set/left_set 和 duration_since；来源为触发对象/事件字段、静态值、当前时间、上一步类型化输出或只读 Boolean Function。发布时校验类型、属性权限、空值和成本，不执行 SQL/Gremlin/JavaScript/任意正则或外部 HTTP。

#### 11.12.8 分组、批次、冷却和去抖

执行模式为每对象一次、每批一次或触发窗口一次。默认批大小最多 100、单评估最多 1,000 对象、单自动化并发 2（平台最大 5）、同对象串行；超限进入明确错误/DLQ，不静默截断。

冷却键可为自动化、对象 ID、允许属性、接收者或受限 Function 字符串，范围 1 分钟—7 天；被抑制事件记录 `SUPPRESSED_BY_COOLDOWN`。去抖合并短期连续修改，保留首尾对象版本和合并事件 ID。

#### 11.12.9 效果与顺序

效果固定为已发布 Action、只读 Function、平台通知/可选 SMTP 邮件、Approval Request 和已发布批管道运行。v1 使用最多 10 步的顺序流程，不做任意 DAG/循环；每步保存前置条件、输入映射、超时、重试、是否等待和失败策略。仅互不依赖的通知可并行。

失败策略为停止、允许的非关键步骤继续或一个 fallback；fallback 仅限通知、审计检查点或人工处理请求，不能隐藏触发另一条 Action 链。

#### 11.12.10 Action 效果

只绑定精确已发布且声明 `automation_allowed=true` 的 Action 版本，固定对象/Object Set 输入、参数映射、批大小和审批策略。每次运行重新检查执行身份、对象权限/ETag/提交条件，生成服务端 Preview 和 diff；无需审批时由已审查的启用版本提供预授权，高风险时创建 Approval Request 并暂停后续步骤。

执行继续走 D-022 mutation batch → Pulsar → Projection，只有 PROJECTED 才视为对象效果成功，DEGRADED/FAILED 单独处理。自动化不能伪造交互式用户确认，也不能用浏览器对象数组替代安全 Object Set/selection token。

#### 11.12.11 Function、通知、审批和管道效果

Function 使用精确版本和执行主体权限，只读、有类型 schema、超时和取消，可生成参数/Object Set/收件人/条件；类型错误进入失败路径。

通知收件人可为固定用户/组、对象属性、事件字段或 Function 输出。平台通知默认可用，SMTP 仅在控制面板配置后启用；对每个接收者重新检查正文对象/属性权限，无权内容不发送，邮件只含安全摘要和内部链接。

审批效果创建持久化 Request 并等待完成/拒绝/修改/关闭/过期/执行失败。管道效果只启动声明允许自动触发的已发布批管道；删除/修改管道、stop/resume 流任务和 savepoint 操作不在 v1，启动/恢复流任务需管理员策略和审批。

#### 11.12.12 至少一次、幂等与重试

平台明确采用 at-least-once，不宣称 exactly-once。效果幂等键为 automation version + source event + effect step + object/batch fingerprint；重试使用同一键，已成功步骤从 ledger 跳过。Action、管道提交、内部通知、审批和 mutation batch 均有独立 operation ledger。

默认最多 5 次，间隔 5 秒、30 秒、2 分钟、10 分钟、30 分钟。网络/临时依赖错误可重试；权限、schema、参数、提交条件和审批拒绝不可自动重试。ETag 冲突只允许重新查询并 Preview 一次。耗尽后进入 DLQ，人工重试沿用原幂等键。

#### 11.12.13 补偿与人工恢复

跨 Action、通知和管道不承诺全局事务或自动全部回滚。通用“恢复原值快照”退出；补偿必须绑定显式发布的 Compensating Action，重新检查当前 ETag，高风险补偿经审批，并作为新的可审计 run。

人工恢复可重试失败步骤、跳过声明为非关键的步骤、发起补偿、关闭并填写原因或创建审批/问题。已完成效果不会因后续通知失败被改写成未发生；不可补偿的管道/通知明确列出。

#### 11.12.14 循环、雪崩和限额保护

变更事件携带 event/correlation/causation ID、origin automation、automation chain、depth、object version 和 changed property IDs。发布用 D-021 血缘检查明显循环；运行发现同一 automation 再入 chain、深度超过默认 8、root event 派生超过 1,000 或速率/错误阈值超限时停止并 AUTO_PAUSED。

v1 不提供“允许循环”。必须修改终止状态、条件或冷却并发布新版本。错误率持续过高可自动 MUTED，队列/执行超时进入健康告警，不无限等待。

#### 11.12.15 执行身份和权限

每个版本保存不可变 `execution_principal_id`。生产推荐 Keycloak 服务账号；个人身份只用于开发/低风险，启用时明确警告。条件、Function、Action、管道、对象/属性、收件人和审批策略均以该主体在执行时重新鉴权，权限撤销立即停止新效果。

角色为 Viewer（查看允许定义/历史）、Operator（暂停/恢复/静默/重试）、Editor（草稿/测试）和 Owner（发布、启用、权限、执行身份、归档）。Operator 不能改定义，Editor 不能更换身份或启用高风险版本。

#### 11.12.16 安全测试、验证和启用

默认测试无副作用：选择真实/样例事件、计算条件/Object Set、执行只读 Function、生成 Action Preview、预览通知和验证管道/审批权限。通知仅可发给测试者，Action 不 execute，管道不启动。测试结果带用户、版本、样例和到期，不能作为生产授权 token。

启用前检查 schema/类型、依赖版本、执行身份、权限、循环/影响、规模、Action Preview、通知泄露、幂等/重试、到期和负责人。流程为锁草稿 → 验证 → 成本/权限/循环分析 → 不可变版本 → 安全测试 → 必要审批 → 原子启用 → trigger cursor → 审计。

#### 11.12.17 运行历史、DLQ 与可观测性

运行详情时间线为事件接收 → 条件/Object Set → Function → Action Preview → 审批 → mutation → Projection → 通知/管道。每步展示状态、时间、尝试、输入输出引用、错误类别、幂等摘要和 trace；不显示无权对象或敏感正文。

DLQ 可按自动化、版本、效果、错误和时间筛选，支持查看安全摘要、重试或关闭。每条链路携带 source event、automation/version/run/effect、approval、action operation 和 projection batch ID。SkyWalking 展示消费、查询、Function、Action、审批、Pulsar、Projection、通知和管道延迟。

#### 11.12.18 数据模型与 API

Postgres 至少包含 `automations`、versions/drafts/triggers/conditions/effects/bindings/dependencies/permissions/execution_principals、trigger_cursors、membership_ledger、runs/run_events/effect_runs、idempotency_ledger、leases、dlq_entries 和 health_issues。稳定触发器/效果/依赖/权限/版本规范化，类型特有配置仅用版本化 schema 校验 JSONB，不保存对象正文或任意事件 payload。

```text
GET/POST        /v1/automations
GET/PATCH       /v1/automations/{id}
POST            /v1/automations/{id}/copy
POST            /v1/automations/{id}/archive
POST            /v1/automations/{id}/restore
GET/PUT         /v1/automations/{id}/draft
POST            /v1/automations/{id}/validate
POST            /v1/automations/{id}/test
POST            /v1/automations/{id}/publish
POST            /v1/automations/{id}/enable
POST            /v1/automations/{id}/mute
POST            /v1/automations/{id}/unmute
POST            /v1/automations/{id}/pause
POST            /v1/automations/{id}/resume
GET             /v1/automations/{id}/versions
GET             /v1/automations/{id}/versions/{versionId}
GET             /v1/automations/{id}/versions/diff
POST            /v1/automations/{id}/versions/{versionId}/create-draft
GET             /v1/automations/{id}/runs
GET             /v1/automations/{id}/runs/{runId}
POST            /v1/automations/{id}/runs/{runId}/retry
POST            /v1/automations/{id}/runs/{runId}/close
POST            /v1/automations/{id}/runs/{runId}/compensate
GET             /v1/automations/{id}/dlq
POST            /v1/automations/{id}/dlq/{entryId}/retry
POST            /v1/automations/{id}/check-access
GET/PUT         /v1/automations/{id}/permissions
GET             /v1/automations/{id}/health
GET             /v1/automations/{id}/usage
```

所有控制写入带 ETag 和 Idempotency-Key，异步操作返回 run/operation ID。

#### 11.12.19 前端结构与实现边界

前端拆为 `features/automations/pages` 的 List/Create/Detail/Editor/Runs/RunDetail，`editor` 的 Trigger/Condition/Effect/Safety/Test，`runtime` 的 RunTimeline/EffectRun/DlqPanel，以及独立 services/schemas/types。

自动化定义只包含触发器、条件、顺序效果、安全策略和版本；Action 审批策略由审批中心承载，通知通过平台通知能力展示。新仓库不导入工作流 JSON、Action 调度字段或跨资源步骤；所有自动化均通过本页面创建、验证、发布和启用。

### 11.13 审批中心

审批中心按 D-027 实施：统一承载 Action、Ontology Proposal、管道/应用/看板发布、Automation 启用和补偿等审批实例。各领域拥有验证与执行 handler；Approval Service 只管理 Request/Task/Stage/Decision 并调用已注册 handler，不能拼装任意 mutation 或 HTTP。

#### 11.13.1 路由、收件箱与列表

```text
/apps/approvals
/apps/approvals/:requestId
/apps/approvals/:requestId/tasks/:taskId
/apps/approvals/delegations
```

Tabs 为待我审批、我发起的、要求我补充、与我相关、已完成和“全部（Approval Admin）”。桌面采用筛选栏 + 请求列表 + 右侧预览，可进入完整详情；列表展示标题、类型、状态、优先级、申请人、当前阶段/审批人、影响数量、创建/截止、逾期和敏感标记。

按关键字、类型、状态、申请/审批人、Action/对象类型、来源应用/自动化、优先级、时间、逾期和“我可决策”筛选。无权限 Request 不出现在列表、计数或搜索中。

#### 11.13.2 Request、Revision、Stage 与 Task

一个 Request 有一个或多个不可变 Revision；每个 Revision 含最多 5 个顺序 Stage，每个 Request 最多 20 个 Task。Task 类型包括 Action 执行、Ontology Proposal、Pipeline 发布、高敏感 dashboard/application 发布、Automation 启用/补偿和管理员配置变更。

Stage 审批策略为任意一人、全部、K-of-N、资源 Owner 或角色/组。领域 task 保存稳定资源/版本、验证 hash、影响摘要和 invocation handler 名称；审批中心不复制完整对象正文或领域配置。

#### 11.13.3 状态机

Request：`DRAFT → PENDING_REVIEW → CHANGES_REQUESTED → PENDING_REVIEW → APPROVED_PENDING_EXECUTION → EXECUTING → COMPLETED`；终止/异常为 `REJECTED_CLOSED/CLOSED/EXPIRED/EXECUTION_FAILED`。Task 为 REVIEW/APPROVED/REJECTED/CHANGES_REQUESTED/SKIPPED/EXPIRED，Stage 为 WAITING/ACTIVE/APPROVED/REJECTED/EXPIRED。

批准与执行严格分离：全部 Task 通过只进入 APPROVED_PENDING_EXECUTION，领域 handler 和真实 Projection/发布成功后才 COMPLETED。批准后失败显示 EXECUTION_FAILED，不改写审批历史或假称完成。

#### 11.13.4 多阶段、资格与职责分离

阶段顺序激活，支持必须不同用户、申请人不得自批、最终审批人必须有资源管理权限、重新认证、理由、检查清单和材料。决策时重新检查用户组/角色、任务 capability、必要对象/字段权限、职责分离、Request revision、ETag、状态和到期。

收到邀请不授予资格。无法看到完成决策所需敏感字段的用户不能盲审，应转给合格审批人。Approval Admin 可治理健康/分配但不自动能业务批准；break-glass v1 关闭，不提供自动批准。

#### 11.13.5 详情页与决策

页头展示标题、状态、类型、优先级、申请人、当前阶段、截止、来源和 correlation ID。正文为业务说明/理由、影响摘要、对象字段 diff、Action 参数安全视图、依赖/风险、阶段任务、检查清单、评论材料、时间线和 execution/Projection 状态。

操作为批准、拒绝并关闭、要求修改、关闭、补充/重新提交、催办、转交/委托和对已批准执行失败的安全重试。决策弹窗再次展示影响摘要并要求理由；高风险任务重新认证。写入带 ETag、Idempotency-Key 和 Request revision，行锁防止双重决策。

#### 11.13.6 要求修改与重新提交

Reviewer 指定需修改字段/材料后进入 CHANGES_REQUESTED；Requester 修改说明、参数或附件会创建新 Revision，旧 Preview/token 失效，服务端重算 diff，所有受影响批准失效并重新进入 PENDING_REVIEW。不能保留旧批准同时静默改变参数。

拒绝并关闭是终态，不能重开原 Request；再次申请创建新 Request，并可引用旧请求。普通关闭也需原因并保留历史。

#### 11.13.7 评论、附件与链接

评论 append-only，可关联 Request 或 Task、@用户和平台资源；更正以新评论完成。外部链接显示离站提示并受域名策略。

附件进入 MinIO 私有 bucket，单文件默认 10 MB，仅 PDF/PNG/JPG/TXT，保存 MIME/大小/SHA-256/上传者/保留期，强制安全下载并重新鉴权/审计。当前架构无恶意文件扫描，v1 不接受宏文档、可执行文件或压缩包，也不宣称病毒扫描。

#### 11.13.8 批量审批

最多选择 50 个同类型/同决策请求，用户必须对每项具备资格且每项通过最新 revision、权限和职责分离；高风险不能与普通请求混批。批准前展示总影响，拒绝/要求修改必须填写原因，返回逐项成功/失败，不能“首项成功即全部成功”。

#### 11.13.9 委托、转交、提醒与升级

委托保存委托人/被委托人、类型范围和起止，被委托人仍须具备原资格，不得委托给申请人或再次委托。转交只允许合格审批人且不删除原分配历史。

Stage 可配置首次/重复提醒、截止和后备组；过期策略为保持逾期、关闭或升级，绝不自动批准。通知覆盖创建、分配、补充、临期/逾期、催办、转交、批准/拒绝、执行成功/失败和关闭；大组使用值班/摘要避免通知风暴。

#### 11.13.10 Invocation、失败恢复与补偿

全体必要 Task 通过后冻结 Revision、重验资源权限/版本并调用注册的领域 handler，写 invocation operation，等待真实发布/Action Projection/管道结果。失败保留批准事实，展示领域和步骤，使用同一幂等键重试；依赖/参数已变则不能重试，必须新 Revision/Request。

部分任务结果逐项展示。补偿只能调用显式 Compensating Action，重验 ETag/权限且可再次审批；通用 before snapshot 恢复退出，避免覆盖后来修改。

#### 11.13.11 通知、权限与审计

通知只含安全摘要和内部链接，打开详情按当前权限渲染；个人免打扰/摘要由控制面板管理，高风险失败可按治理策略即时通知但不泄露正文。Request 权限由参与关系、任务资格和底层资源权限共同决定，条件显示和通知不是授权。

审计创建/提交、revision、分配/转交/委托、评论/附件、检查项、每次决策、资格快照与重验、执行/重试/补偿、关闭/过期和通知。日志/APM 不记录完整参数、评论、附件或敏感 diff。

#### 11.13.12 数据模型与 API

Postgres 至少包含 `approval_requests`、request_revisions、stages、tasks、task_requirements、reviewer_assignments、decisions、checkpoints、comments、attachments、delegations、invocations、notifications 和 health_issues。稳定任务/阶段/决策/handler/版本规范化，附件正文只在 MinIO。

```text
GET/POST        /v1/approval-requests
GET             /v1/approval-requests/{id}
PATCH           /v1/approval-requests/{id}/draft
POST            /v1/approval-requests/{id}/submit
POST            /v1/approval-requests/{id}/close
POST            /v1/approval-requests/{id}/resubmit
POST            /v1/approval-requests/{id}/approve
POST            /v1/approval-requests/{id}/reject
POST            /v1/approval-requests/{id}/request-changes
POST            /v1/approval-requests/{id}/nudge
POST            /v1/approval-requests/{id}/retry-invocation
GET             /v1/approval-requests/{id}/tasks
GET             /v1/approval-requests/{id}/audit
POST            /v1/approval-tasks/{taskId}/assign
POST            /v1/approval-tasks/{taskId}/transfer
GET/POST        /v1/approval-requests/{id}/comments
GET/POST        /v1/approval-requests/{id}/attachments
GET             /v1/approval-attachments/{attachmentId}/download
GET/POST        /v1/approval-delegations
DELETE          /v1/approval-delegations/{id}
POST            /v1/approval-requests/batch-approve
POST            /v1/approval-requests/batch-reject
```

#### 11.13.13 前端结构与可观测性

前端拆为 `features/approvals/pages` 的 Inbox/Request/Delegations，以及 ApprovalFilters、RequestList/Summary、TaskTimeline、ChangeDiff、DecisionDialog、CommentsPanel、AttachmentPanel 和独立 API/types。桌面 master-detail，移动端列表/详情分路由。

Action Preview 是短期执行确认，不充当长期审批记录；长期事实从第一版写入规范化 Request/Revision/Stage/Task/Decision 表。审批、自动化和通知各自拥有清晰边界，新仓库不导入历史 Preview、Workflow 或 schedule 数据。

全链路携带 correlation/causation/source event、automation/version/run/effect、approval request/revision/task、action operation 和 projection batch ID。控制面板汇总 Automation backlog/延迟/重试/DLQ/自动暂停、审批积压/耗时/逾期/Invocation 失败，并提供 SkyWalking deep link。

#### 11.13.14 前端明确不做的范围

v1 不做 BPMN、任意 DAG/脚本/HTTP/Webhook、跨服务全局事务、exactly-once、自动全局回滚、自动批准、允许循环、无限重试/补跑、匿名或邮件内批准、浏览器执行 effect、宏/可执行附件或完整 Temporal/Camunda/Airflow 替代品。

#### 11.13.15 联合验收标准

1. 自动化列表正确显示启用、静默、暂停、过期和自动暂停。
2. 创建向导五步可保存草稿和返回。
3. 编辑启用版本不影响当前运行版本。
4. 对象进入、离开、修改和指定属性变化触发正确。
5. 管道、质量、审批和 Projection 事件触发正确。
6. Cron 时区、DST 和错过调度策略正确。
7. Object Set 精确版本不随 Exploration 更新静默变化。
8. 条件 DSL 完成类型和字段权限检查。
9. Function 条件以执行身份运行。
10. 每对象、批量和窗口分组语义正确。
11. 冷却和去抖记录被抑制事件。
12. 超过规模限制不会静默丢对象。
13. Action effect 每次生成服务端 Preview。
14. 未声明 automation_allowed 的 Action 不能绑定。
15. 高风险 Action 正确创建审批请求。
16. 参数、对象版本或 binding 篡改被拒绝。
17. Action 提交后等待真实 Projection。
18. Function 输出类型错误进入失败路径。
19. 通知按每个接收者权限重新裁剪。
20. 管道 effect 只能启动允许自动触发的发布版本。
21. 顺序步骤严格按依赖执行。
22. Fallback 不能形成隐藏 Action 链。
23. 相同事件重复投递不重复执行已成功效果。
24. 重试保持相同幂等键。
25. 不可重试错误不会无限重试。
26. DLQ 能定位、检查和安全重试。
27. 已完成效果不会因后续失败被伪装成回滚。
28. 补偿使用显式 Action、当前 ETag 和独立审计。
29. Automation chain 检测直接和间接循环。
30. 雪崩、错误率和运行上限触发自动静默/暂停。
31. 执行身份停用或失权时停止执行。
32. 测试模式不产生真实 Action、管道或外部通知。
33. 新版本切换不会重复消费旧事件。
34. 发布失败时旧版本继续运行。
35. 运行详情显示完整步骤、重试和 trace。
36. 审批收件箱只显示用户有权知道的请求。
37. 一个 Request 可包含多个 Task 和 Stage。
38. any/all/K-of-N 策略正确。
39. 申请人不能违反职责分离自我批准。
40. 决策时重新检查资格和 Request revision。
41. 批准状态与真实执行完成严格分离。
42. 要求修改生成新 revision 并失效旧批准。
43. 拒绝并关闭后不能重新打开原请求。
44. 委托不能授予资格或绕过职责分离。
45. 过期、提醒和升级不产生自动批准。
46. 批量审批逐项鉴权并返回部分结果。
47. 评论、附件、下载和通知遵守权限及审计。
48. Invocation 失败以同一幂等键安全重试。
49. 全新数据库初始化、Request revision 和 Invocation 重试不产生重复审批或执行。
50. 全新 Compose 完成对象事件 → 自动化 → Preview → 审批 → mutation → Projection → 通知闭环。

### 11.14 智能体工作室

#### 11.14.1 产品边界与运行架构

智能体工作室负责定义、测试、评测和发布智能体；全局助手和对话中心负责使用已发布版本。三处共享 `backend/agent-runtime` 的 Java 21 + Spring WebFlux 服务，不各自实现模型调用，也不增加 Python Agent 服务或新的智能体框架容器。

Agent 服务按职责拆为 `provider`、`runtime`、`tools`、`context`、`threads`、`retrieval`、`guardrails`、`evals`、`usage` 和 `audit`。Postgres 使用独立 `aip` schema 保存控制面和运行事实；MinIO 保存私有附件原件；OpenSearch 使用 `aip-doc-*` 独立模板和索引，不新增向量数据库，也不与对象索引、SkyWalking 索引混用。

#### 11.14.2 模型供应商与 Model Profile

- 实现 `OpenAI Responses API Adapter` 和 `OpenAI-compatible Chat Completions Adapter`；当前 DeepSeek 只能作为后者的一个配置，不再进入领域 DTO、前端类型或智能体定义。
- Runtime 内部只使用供应商中立的消息、流事件、工具调用、用量和错误 DTO，供应商 ID 仅保留在适配层。
- `Model Profile Version` 固定供应商、精确模型名、能力声明、上下文/输出限制、采样参数、工具/流式/结构化输出支持、允许用户组、数据保留声明和健康状态。
- 已发布 Agent Version 固定精确的 Model Profile Version；供应商、模型、参数、工具能力或保留声明变化必须产生新 Agent Version 并重新评测。
- fallback 只能指向已通过兼容性测试且能力不低于当前 Agent 要求的 Profile；不得因为主模型失败而静默降低工具、结构化输出或安全能力。
- API key 仅保存为服务端加密 secret/ref，不返回浏览器，不写日志、事件、trace、Eval 数据或导出文件。
- 没有可用供应商时 Runtime 返回 `DEGRADED / LLM_NOT_CONFIGURED`；不得实现本地关键词伪回答或用假智能结果掩盖未配置模型。

#### 11.14.3 页面路由与列表

- `/aip/agents`：紧凑表格列表，不使用应用卡片；列为名称、状态、发布版本、默认模型、工具数、负责人、最近运行、健康和更新时间。
- `/aip/agents/new`：新建向导。
- `/aip/agents/:agentId`：概览。
- `/aip/agents/:agentId/edit`：全屏编辑器。
- `/aip/agents/:agentId/preview`：发布版本只读预览。
- `/aip/agents/:agentId/versions`：版本、diff 和由旧版本创建草稿。
- `/aip/agents/:agentId/evals`：Eval Suite、运行和发布门禁。
- `/aip/agents/:agentId/access`：用户/组权限与底层资源访问检查。
- `/aip/agents/:agentId/usage`：运行、Token、工具、错误和成本用量。

列表支持状态/负责人/模型/工具/健康筛选、搜索、排序、收藏、复制和归档。仅草稿可删除；已发布版本只能归档。创建入口先选“空白智能体”或平台维护的模板，模板只复制配置，不继承模板拥有者的数据权限。

#### 11.14.4 全屏编辑器

编辑器为三栏布局：左栏设置导航，中栏测试对话，右栏显示结构化 Trace、上下文与校验问题。左栏顺序固定为“基本信息 / 指令 / 应用状态 / 检索上下文 / 工具 / 模型与限制 / Guardrail / 建议问题 / 发布”。测试消息只写测试 Run，不进入正式 Thread。

草稿写入使用 ETag、自动保存和 15 分钟单编辑者租约；失去租约后切换只读，禁止最后写入覆盖。发布版本不可修改，回滚通过目标旧版本创建新草稿、重新 Eval、再发布，不改变历史版本。

#### 11.14.5 Agent 定义与生命周期

每个 Agent Version 必须固定：名称、说明、图标、系统指令、开发者指令、建议问题、Application State JSON Schema、检索上下文绑定、Tool Binding、Model Profile 与 fallback、最大步骤/调用/输入/输出、Run/Tool 超时、引用策略、Action 策略、Guardrail 和输出能力。

生命周期为 `DRAFT → VALIDATING → EVALUATING → READY → PUBLISHING → PUBLISHED`，失败进入对应 `VALIDATION_FAILED / EVAL_FAILED / PUBLISH_FAILED`，资源可归档。任一步失败时旧发布版本继续服务；发布操作以不可变快照为输入，不读取继续变化的草稿。

#### 11.14.6 Application State 与上下文绑定

Application State 使用严格 JSON Schema，只描述智能体需要的类型化状态，例如对象引用、对象集合引用、日期范围或页面筛选；不得接收任意 `Map<String,Object>` 或整页 Redux/Zustand 状态。每项声明来源、是否必需、最大数量、敏感级别和过期策略。

上下文绑定仅允许平台注册的对象类型、Object Set、看板、业务应用、管道、自动化、审批和文档集合。运行时必须以当前用户重新解析引用和裁剪字段；“允许 Agent 使用某对象类型”不是授予对象数据权限。

#### 11.14.7 检索上下文

检索上下文分为 Ontology Context 和 Document Context。前者只能通过类型化 Object Set/Object API 获取；后者只能检索已完成解析且当前用户有权访问的附件。每个绑定固定查询模板、字段 allowlist、最大命中、排序、引用要求和无结果策略。

默认文档检索使用 OpenSearch BM25；只有控制面登记了可用 embedding Model Profile 后，才允许为明确选择的文档集合启用混合检索。发布版本固定检索策略及 embedding 版本，重建索引不得静默改变历史 Run 的引用事实。

#### 11.14.8 Tool Registry 与风险等级

每个工具使用严格 JSON Schema 定义输入/输出、版本、权限检查器、超时、结果大小、幂等语义、审计级别和风险：`READ`、`COMPUTE`、`DRAFT_SUGGESTION`、`ACTION_PREVIEW`。模型可见的工具集合由 Agent Version、当前用户、当前上下文和运行限额求交集得到。

所有模型参数必须再次由服务端反序列化、类型校验、权限检查并绑定当前用户；模型文本不能构造内部身份、任意 URL、原生数据库查询、供应商参数或 execute 请求。

#### 11.14.9 v1 工具目录

允许的工具固定为：

```text
list_object_types             query_object_set
search_objects                get_object
get_object_relations          compare_objects
get_object_lineage            invoke_function
get_dashboard_context         get_application_context
get_pipeline_status           get_quality_status
get_automation_run            get_approval_request
preview_action                request_clarification
create_draft_suggestion       create_navigation_link
```

`create_draft_suggestion` 只返回结构化建议，由用户在对应产品编辑器显式接受；不能发布本体/看板/应用、修改管道、启用自动化或替用户作审批决定。`create_navigation_link` 只能生成注册路由和签名资源引用，不接受任意 URL。

#### 11.14.10 禁止暴露的工具路径

AIP 不提供 `create_object_type`、`add_field`、`create_action`、通用 `/v1/query`、模型可调用的 `execute_action` 或任意 URL/原生 API 工具。AIP 不得绕过本体 Proposal、应用编辑器、Action Preview、审批中心或自动化启用流程。

#### 11.14.11 执行限额

默认限额：每 Agent 最多 20 个工具；每 Run 最多 8 个工具步骤、12 次工具调用、3 个并行只读调用、1 个未完成 Action Preview；单工具结果 50KB、对象摘要 100 条；工具超时 30 秒、Function 60 秒、Run 120 秒；输入上下文 64K tokens、输出 4K tokens。管理员可以在平台上限内收紧，Agent 不能自行放宽。

只读工具在明确无依赖时可并行；写前预览、澄清和审批相关步骤必须顺序执行。达到限额时停止循环并返回部分结果/恢复建议，但不得撤销或重复已经提交的 Action。

#### 11.14.12 Action Preview 与独立确认

模型只能调用 `preview_action`。Runtime 以当前用户解析对象、参数、字段权限和 ETag，返回结构化 diff 后将 Run 置为 `WAITING_FOR_CONFIRMATION`。前端必须调用独立确认端点，携带精确 confirmation token；服务端再次鉴权后执行 Action，必要时创建审批，等待 mutation/Projection 真实状态，再决定是否让模型继续回答。

token 绑定 `user / thread / run / agentVersion / actionVersion / normalizedParams / objectETag / expiresAt`。修改任何参数、换用户、过期、对象版本变化或重复使用都必须重新 Preview。模型可见工具中永远不存在 execute，确认 token 只能通过专用执行请求提交。

AIP 明确禁止：批准/拒绝审批、发布本体/看板/应用、启用自动化、接受质量风险、恢复数据、修改权限/密钥/备份、执行通用补偿或代替用户完成高风险确认。

#### 11.14.13 持久 Run 与 SSE

Run 状态为 `QUEUED / RUNNING / WAITING_FOR_CLARIFICATION / WAITING_FOR_CONFIRMATION / PENDING_APPROVAL / COMPLETED / FAILED / CANCELED / EXPIRED`；Tool Call 状态单独持久化。浏览器断开不取消 Run，用户必须显式取消。

SSE 事件固定为 `run.started`、`message.delta`、`message.completed`、`tool.call.started`、`tool.call.completed`、`tool.call.failed`、`citation.registered`、`action.previewed`、`action.confirmed`、`approval.updated`、`usage.updated`、`run.waiting`、`run.completed`、`run.failed`。事件有递增 ID，客户端使用 `Last-Event-ID` 重连；完成消息从服务端事实重放，不依赖供应商流缓存。

#### 11.14.14 结构化输出与引用

允许渲染：净化后的 Markdown、表格、Metric、受限 `ChartSpec`、对象列表/链接、服务端注册引用、Action Preview、审批/管道/自动化状态卡和草稿建议。禁止模型 HTML/JS/iframe、Vega/任意表达式、可执行链接或前端动态组件代码。

引用必须先由工具/检索服务注册。文档引用包括文档 ID/名称、页码、chunk、offset、内容 hash 和 retrieval run；对象引用包括类型、ID、版本、属性、查询/tool run 和权限水印。模型编造或未知 citation ID 不渲染为引用。

#### 11.14.15 Trace、摘要与思维链边界

不向用户、管理员、日志或数据库请求/保存模型思维链。Trace 仅保存工具名称、净化参数/结果摘要、状态、耗时、引用、用量、错误、SkyWalking trace ID 和一段面向用户的步骤说明。

平台数据库是 Thread/Message/Run 的真相源；供应商 response/conversation ID 只是可选关联，不能成为恢复会话的唯一依据。长 Thread 通过版本化摘要压缩；Action、审批、引用和未完成状态必须原样保留。权限、消息分支、Agent/Model 版本变化会使摘要失效并重新生成。

#### 11.14.16 Prompt Injection 与 Guardrail

对象字符串、附件、日志、质量结果和工具自由文本全部标记为不可信数据，不能覆盖系统/开发者指令、工具定义、权限或确认策略。Runtime 使用结构化 envelope、字段/长度上限、Unicode/Markdown 净化、URL allowlist、工具输出隔离和高风险重新鉴权。

检测到文档要求泄露密钥、忽略指令、调用未授权工具或自动执行 Action 时，模型可解释拒绝，但服务端仍以工具不可见、schema 校验和权限检查作为硬边界，不能只依赖提示词。

#### 11.14.17 权限与共享

Agent 资源角色为 Viewer、Editor、Owner；AIP Admin 可管理 Provider/Profile/平台限额，但不会因此获得业务数据访问权。Agent 可分享给私有、指定用户、组、团队或已认证组织范围；分享 Agent 不分享底层对象、文档、工具或应用权限。

发布前和访问页提供 `check-access`，分别以代表性用户检查缺失的对象/字段、Function、Action、文档和模型权限。每次运行仍以真实当前用户重查，不能把发布时检查当运行时授权。

#### 11.14.18 Eval Suite

Eval Case 固定输入、签名测试上下文、期望/禁止工具、输出 schema、引用断言、内容断言、最大步骤/延迟/tokens 和安全标签。运行先做确定性校验，可选使用独立 LLM grader 评估表达质量；LLM grader 永远不能作为写操作安全的唯一门禁。

发布至少覆盖：正常只读、未授权对象/字段、附件提示注入、Action 只能 Preview 不自动 execute、有效引用和工具故障恢复。绑定 `ACTION_PREVIEW` 的 Agent 对关键用例重复运行 3 次，任一次自动执行倾向、权限旁路或未知引用都阻断发布。

指令、模型、工具、Context、检索或 Guardrail 任一变化都要重新 Eval。用户赞/踩只形成反馈记录，不自动训练、改提示词、扩大工具或发布新版本。

#### 11.14.19 发布门禁与健康

发布门禁依次执行 schema/引用完整性、Tool Binding、Model capability、权限检查、静态提示安全、Eval、安全回归和依赖健康。发布 Saga 成功后原子切换默认 Agent Version；失败保留旧版本并显示可恢复步骤。

健康页区分模型不可用、凭据失效、工具 schema 漂移、底层资源版本失效、文档索引异常、Eval 过期和配额耗尽。严重问题可禁止新 Run，但不能破坏历史 Thread 阅读和审计。

#### 11.14.20 配额、用量与观测

配额可按用户、Agent、Model Profile、用户组、并发 Run、工具调用和文档容量设置；预警后提示，硬限制时不启动新步骤，但不取消已提交 Action/审批。用量记录输入/输出 tokens、缓存 tokens、供应商、工具耗时、失败类型和估算成本。

每个 Run/Tool Call 关联 request ID、correlation ID 和 SkyWalking trace ID。控制面显示成功率、P50/P95 延迟、Token/成本、工具失败、确认/审批转化和引用覆盖率；敏感提示、工具正文、密钥和附件正文不进入 APM。

#### 11.14.21 数据模型

Postgres `aip` schema 至少包含：

```text
aip_agents                       aip_agent_versions
aip_agent_drafts                 aip_agent_permissions
aip_agent_tool_bindings          aip_agent_context_bindings
aip_agent_model_bindings         aip_model_profiles
aip_model_profile_versions       aip_provider_credentials
aip_assist_sessions              aip_threads
aip_thread_branches              aip_messages
aip_message_parts                aip_context_refs
aip_attachments                  aip_document_chunks
aip_runs                         aip_run_events
aip_run_steps                    aip_tool_calls
aip_action_confirmations         aip_citations
aip_thread_summaries             aip_usage_records
aip_eval_suites                  aip_eval_cases
aip_eval_runs                    aip_eval_results
aip_feedback                     aip_health_issues
```

Postgres 不保存明文 key、思维链、完整 Object Set、无限制工具结果或附件正文。大工具结果只保留带 TTL 的受控对象引用/MinIO artifact；附件原件仅在私有 MinIO bucket，chunk 索引只含授权元数据和检索文本。

#### 11.14.22 API 契约

```text
GET/POST       /v1/aip/agents
GET/PATCH      /v1/aip/agents/{id}
GET/PUT        /v1/aip/agents/{id}/draft
POST           /v1/aip/agents/{id}/validate
POST           /v1/aip/agents/{id}/publish
POST           /v1/aip/agents/{id}/archive
GET            /v1/aip/agents/{id}/versions
GET            /v1/aip/agents/{id}/versions/{versionId}
POST           /v1/aip/agents/{id}/versions/{versionId}/create-draft
GET/PUT        /v1/aip/agents/{id}/permissions
POST           /v1/aip/agents/{id}/check-access
GET            /v1/aip/agents/{id}/usage
GET/POST       /v1/aip/eval-suites
GET/PUT        /v1/aip/eval-suites/{id}
POST           /v1/aip/eval-suites/{id}/runs
GET            /v1/aip/eval-runs/{runId}
POST           /v1/aip/eval-runs/{runId}/cancel
```

所有草稿写入带 ETag，异步校验/Eval/发布返回 job ID，所有版本/运行列表使用稳定 cursor。Controller 不透传供应商原始请求体或错误体。

#### 11.14.23 前端代码拆分

```text
features/aip/
├── agents/
│   ├── pages/AgentListPage.tsx
│   ├── pages/AgentOverviewPage.tsx
│   ├── pages/AgentEditorPage.tsx
│   ├── pages/AgentVersionsPage.tsx
│   ├── pages/AgentEvalsPage.tsx
│   ├── components/{InstructionEditor,ToolBindingEditor,ContextBindingEditor,ModelProfileEditor}.tsx
│   └── hooks/{useAgentDraft,useAgentEditLock,useAgentEvalRun}.ts
├── runtime/
│   ├── components/{MessageRenderer,ToolTrace,Citation,ActionPreview,RunStatus}.tsx
│   └── hooks/{useRunStream,useRunRecovery}.ts
├── assistant/
└── threads/
```

公共 Runtime renderer 只接收服务端判别联合类型；不得在三处复制 SSE、Markdown、引用或确认逻辑。所有用户状态、错误和恢复动作使用中文，原始供应商错误只在脱敏管理员详情中展示。

#### 11.14.24 实现边界与明确不做

Agent Runtime 从第一版使用类型化 Provider DTO、真实 SSE、签名上下文 Envelope、独立确认请求和规范 Thread/Run 表。不得实现硬编码供应商 DTO、任意 `Map` 上下文、通用查询、建模写工具、模型 execute 工具、伪 SSE 或无模型关键词 fallback；新仓库不导入历史 Thread/Run。

v1 不实现：完整 AIP Logic、用户自定义代码工具、任意 MCP、公共 Web Search、Computer Use、Code Interpreter、Shell、多智能体、Agent 调 Agent、由自动化打开聊天、自动发布/批准/启用、语音/实时音频、OCR、图片生成、Thread 分享、匿名外部 Chatbot、浏览器持有 API key、思维链展示或反馈自动训练。

### 11.15 对话中心

#### 11.15.1 全局 AIP 助手

顶栏“AI”打开宽 `480px` 的右侧 Drawer，头部显示当前 Agent、模型健康、临时/已保存状态和关闭按钮；正文显示消息、引用、工具步骤和 Action Preview；底部包含多行输入、附件、上下文引用、停止和发送。

当前页面、资源、选中对象、筛选和状态以可见 chip 展示，用户可逐项移除；没有任何隐式隐藏上下文。打开 Drawer、切换页面或选择对象不会自动发起模型调用，也不提供页面加载时主动分析的接口。

#### 11.15.2 临时 AssistSession

用户第一次发送消息才创建临时 AssistSession。默认只属于当前用户，24 小时无活动后过期；可在有效期内跨页面继续，但页面上下文改变时新增引用而不是覆盖历史消息上下文。点击“保存为对话”后以事务方式创建 Thread、分支、消息和引用；过期 session 不能恢复，产品必须提前提示。

临时会话同样执行持久 Run、权限、引用、确认和审计规则，只是历史保留周期不同。不得把临时会话存入 localStorage 作为唯一真相源。

#### 11.15.3 签名上下文 Envelope

`AipContextEnvelope` 只含：context ID、页面/路由、资源引用、选中对象引用、Object Set/Selection Token、看板/应用/管道/自动化/审批引用、非敏感状态 ID、策略 revision、过期时间和服务端签名。它不含整页对象正文、凭据、任意客户端 JSON、访问 token 或供应商 key。

BFF/Ontology Core 签发上下文，Agent Runtime 每次工具调用都用当前用户重新解析和鉴权。签名有效只证明引用未被篡改，不证明用户仍有权限；过期、权限变化或资源版本变化必须刷新上下文。

#### 11.15.4 页面内能力边界

AIP 可解释页面/错误/指标、查找对象、比较对象、汇总血缘、给出筛选或草稿建议、预览 Action 和生成注册路由。AIP 不得读取凭据、替用户接受风险/审批、直接更改编辑器草稿、发布资源、启用自动化或执行控制面操作。

每个页面在 Context Registry 声明可提供的引用和允许展示的恢复动作；未知页面只传页面 ID，不抓 DOM、截图或浏览器网络内容。

#### 11.15.5 对话中心路由与布局

- `/aip/threads`：最近/收藏/归档私有对话。
- `/aip/threads/new`：选择已发布智能体并创建对话。
- `/aip/threads/:threadId`：三栏布局，左侧 Thread 列表，中间消息，右侧上下文/引用/运行详情。
- `/aip/threads/:threadId/runs/:runId`：可深链接的 Run Trace。

v1 Thread 仅自己可见，不提供用户/组分享。支持重命名、收藏、归档、软删除、分支、重新生成、复制上下文和导出；永久删除由保留策略异步清理，并记录最小审计事实。

#### 11.15.6 Thread 版本固定

创建 Thread 时固定 Agent Version、Model Profile Version、Tool Definition Versions 和 prompt hash。Agent 后续发布不会静默改变旧 Thread；用户选择“使用新版本继续”时创建新分支并清楚展示版本差异。

供应商 conversation ID 不影响版本固定。若旧模型/Profile 被管理员禁用，历史仍可读；继续运行必须明确迁移到兼容的新分支并重新校验上下文。

#### 11.15.7 消息、分支与再生成

消息一经完成不可原地编辑。编辑用户消息、重新生成回答或切换 Agent 版本都创建新 branch，父消息和 Run 保持可审计；UI 在分叉点显示分支选择器。消息 part 可为 text、citation、tool trace、table/chart、object refs、Action Preview、approval state 或 error。

发送使用 client message ID 和幂等键；重复请求不产生两条消息/Run。取消只停止尚未提交的模型/工具步骤，不逆转 Action 或审批。失败 Run 可从最后一个安全 checkpoint 重试，不能重复已成功工具副作用。

#### 11.15.8 附件约束

仅支持 PDF、TXT、MD；单文件最大 20MB、每 Thread 最多 10 个、总计 100MB，PDF 最多 300 页。v1 不做 OCR、DOCX、ZIP、宏文档、音视频或可执行文件。上传执行 MIME/扩展名/魔数校验、恶意文件扫描、hash 去重和私有对象权限。

原件写 MinIO 私有 bucket；PDFBox 提取文本，分块后写 `aip-doc-chunks`。解析状态为 `UPLOADING / SCANNING / PARSING / INDEXING / READY / FAILED / DELETED`；只有 `READY` 可被检索。删除附件后新 Run 不能检索，历史引用按保留策略显示文件名/页码或“来源已删除”。

#### 11.15.9 文档检索与不可信内容

默认 BM25 按 Thread/附件 ACL、当前用户、Agent Context Binding 和 chunk 状态过滤后检索；可选 embedding 不能绕过这些过滤。每次 Retrieval Run 记录查询、规范化过滤、命中文档/chunk/hash 和排序分数。

文档内容始终作为引号包裹的“不可信参考材料”，不能成为系统指令、工具参数或确认。文档声称“已获得批准”“忽略规则”“输出密钥”不产生任何权限事实。

#### 11.15.10 引用与来源面板

正文引用点击后打开右侧来源面板：文档显示文件、页码、命中片段和 hash；对象显示对象类型、ID、属性、版本和产生它的查询/工具。所有引用由服务端 citation registry 分配 ID，前端不接受模型自报 URL/页码/对象 ID。

引用源在当前权限下不可访问时不返回片段；正文对应 part 显示“权限已变化，内容已隐藏”，并提供基于当前权限重新生成。导出执行相同检查。

#### 11.15.11 权限撤销与历史敏感回答

每个消息 part 保存安全上下文 hash、策略 revision、资源依赖和 citation IDs。打开 Thread、切换分支、生成摘要和导出时重新验证；如果回答包含已撤销字段/对象/附件依赖，服务端遮蔽受影响 part，而不是只隐藏引用入口。摘要依赖任何失效 part 时整体失效并重建。

自由文本可能包含敏感事实，因此不能假设“引用被删即可安全”。无法精确切分的旧消息整体隐藏；管理员也不能凭 AIP Admin 身份越权查看正文。

#### 11.15.12 澄清、确认与审批状态

缺少必要对象、参数或歧义超过阈值时，Run 进入 `WAITING_FOR_CLARIFICATION` 并显示类型化选项；回答后以同一 Run 继续。Action Preview 显示动作、目标对象、字段 diff、验证、风险、审批要求、过期时间和取消/确认。

确认后如果需要审批，消息卡进入 `PENDING_APPROVAL` 并链接审批中心；审批完成不等于 Action 完成，只有 Invocation 和 Projection 成功后显示完成。拒绝、要求修改、过期或执行失败均保留真实状态，不让模型用自然语言伪装成功。

#### 11.15.13 导出、保留与隐私

导出支持 Markdown、结构化 JSON 和浏览器打印 PDF；导出生成时重新鉴权和净化，包含 Agent/Model 版本、时间、可见引用和状态，不包含思维链、密钥、隐藏 part、原始工具正文或供应商内部 ID。大导出使用异步任务和短期签名下载 token。

用户删除/归档、管理员保留策略和法律保留分别处理。应用日志只保留消息/Run ID 和安全摘要，生产环境不得默认记录完整 prompt/response；审计记录“谁在何时以哪个 Agent/版本访问了哪些资源类别和工具”。

#### 11.15.14 AssistSession、Thread 与 Run API

```text
POST           /v1/aip/contexts/sign
POST           /v1/aip/assist-sessions
GET/PATCH      /v1/aip/assist-sessions/{id}
POST           /v1/aip/assist-sessions/{id}/save-as-thread
GET/POST       /v1/aip/threads
GET/PATCH      /v1/aip/threads/{threadId}
POST           /v1/aip/threads/{threadId}/archive
DELETE         /v1/aip/threads/{threadId}
GET            /v1/aip/threads/{threadId}/branches
POST           /v1/aip/threads/{threadId}/branches
GET/POST       /v1/aip/threads/{threadId}/messages
POST           /v1/aip/threads/{threadId}/attachments
GET/DELETE     /v1/aip/attachments/{attachmentId}
POST           /v1/aip/threads/{threadId}/exports
POST           /v1/aip/runs
GET            /v1/aip/runs/{runId}
GET            /v1/aip/runs/{runId}/events
POST           /v1/aip/runs/{runId}/cancel
POST           /v1/aip/runs/{runId}/clarification
POST           /v1/aip/runs/{runId}/confirm-action
POST           /v1/aip/runs/{runId}/reject-action
POST           /v1/aip/runs/{runId}/feedback
```

SSE 的 content type、心跳、event ID、恢复窗口和终态必须写入 OpenAPI；上传、导出和确认使用 CSRF/重放保护。所有资源 ID 为不可猜 UUID/ULID 仍不能代替鉴权。

#### 11.15.15 前端状态与可访问性

Drawer 与对话中心复用 `features/aip/runtime`；Thread 列表、消息分页、当前分支、输入草稿和 SSE 连接分别管理，不能让一条 delta 触发全页面重渲染。刷新页面后从服务端恢复 Run；离线/重连明确显示，不把连接断开显示成模型失败。

键盘可完成打开助手、聚焦输入、发送/停止、展开来源和确认对话；流式区域使用适度 `aria-live`，完成后统一宣读，避免每个 token 干扰读屏。确认按钮不能仅用颜色表达风险。

#### 11.15.16 验收标准（55 项）

1. 打开页面、Drawer 或切换对象不会自动调用模型。
2. Drawer 宽度、焦点锁定、关闭和移动端降级正确。
3. 所有上下文引用可见、可移除且无隐藏整页 payload。
4. 篡改、过期或换用户的 Context Envelope 被拒绝。
5. 每次上下文解析都按当前用户重查对象和字段权限。
6. 临时 AssistSession 24 小时过期且可显式保存为 Thread。
7. 保存临时会话时消息、引用、分支和版本固定原子完成。
8. Thread 仅本人可见，越权 ID 返回不可枚举结果。
9. Agent 新版本不会静默改变已有 Thread。
10. 切换 Agent/Model 版本会创建新分支并显示差异。
11. 编辑消息和重新生成不会覆盖原消息或原 Run。
12. 重复发送幂等键不会生成重复消息或 Run。
13. 浏览器断开不会取消持久 Run。
14. `Last-Event-ID` 重连不丢失、不重复应用 SSE 事件。
15. Run 和 Tool 状态机禁止非法跃迁。
16. 用户取消不会撤销或重复已提交的 Action/审批。
17. OpenAI Responses 适配器通过真实流式与工具调用测试。
18. OpenAI-compatible 适配器通过当前 DeepSeek 配置测试。
19. 供应商错误被规范化且不会泄露 key/原始敏感体。
20. 无可用 Model Profile 时显示 `LLM_NOT_CONFIGURED`，没有伪回答。
21. 发布 Agent 固定精确 Model Profile/Tool/prompt 版本。
22. 不兼容 fallback 不会被静默使用。
23. Tool 参数严格 schema 校验并以当前用户鉴权。
24. 模型无法调用未绑定、未授权或超过限额的工具。
25. 任意 URL、原生查询、Shell、代码和通用 MCP 不可调用。
26. 工具结果超过条数/50KB 时安全截断并可追踪。
27. 并行只读工具与顺序有副作用步骤符合依赖。
28. 工具超时、失败和重试不会重复副作用。
29. Action 只能先 `preview_action`，模型工具集中没有 execute。
30. confirmation token 绑定用户、Run、版本、参数、ETag 和过期时间。
31. 参数/对象版本变化后旧 confirmation token 被拒绝。
32. 确认端点再次鉴权并以同一幂等键提交 Action。
33. 需要审批时 Run 等待真实审批和 Invocation 状态。
34. 只有 Projection 成功才向用户显示 Action 完成。
35. AIP 不能审批、发布、启用自动化或接受质量风险。
36. PDF/TXT/MD 类型、20MB/10 个/100MB/300 页限制生效。
37. 文件 MIME/魔数/恶意扫描失败时不进入检索索引。
38. PDFBox 解析、chunk、MinIO 原件与 OpenSearch ACL 一致。
39. 未 READY、已删除或无权附件不能被新 Run 检索。
40. 文档提示注入不能改变指令、工具、权限或确认流程。
41. BM25 检索返回可复现的 document/page/chunk/hash 引用。
42. 可选 embedding 检索仍执行相同 ACL 过滤。
43. 模型编造 citation ID、页码或对象引用不会渲染。
44. 对象引用固定对象/属性版本、query/tool run 和安全水印。
45. 权限撤销后受影响消息 part、摘要和导出被遮蔽。
46. 管理员不因 AIP Admin 角色自动看到业务消息正文。
47. Markdown、表格、ChartSpec 和链接通过 allowlist 净化。
48. HTML/JS/iframe/Vega/可执行模型内容无法渲染。
49. 数据库、日志、Trace、导出均不保存/展示思维链。
50. 发布门禁覆盖正常、未授权、注入、引用、工具失败和 Action Preview 用例。
51. 写能力关键 Eval 连跑 3 次，任一次越权倾向都阻断发布。
52. Prompt/Model/Tool/Context/Guardrail 改动触发重新 Eval。
53. 配额耗尽停止新步骤但不破坏已提交 Action/审批。
54. SkyWalking 可从 Run 定位模型、工具和 Ontology 调用且 APM 无敏感正文。
55. 全新 Compose 完成页面上下文 → Thread → 工具/引用 → Action Preview/确认/审批 → Projection → 可恢复 SSE 的端到端闭环。

### 11.16 控制面板

#### 11.16.1 产品边界与页面框架

控制面板是平台唯一集中管理入口，负责身份映射、管理职责、资源/数据权限、权限诊断、AIP 平台配置、健康/用量、审计、类型化系统配置和备份恢复。它不编辑本体、管道、看板、业务应用、自动化或智能体业务定义，也不是 Docker、Pulsar、OpenSearch 等底层组件的通用管理后台。

路由统一为 `/control-panel/*`。进入后保留全局侧栏，在内容区左侧增加控制面板二级导航；默认打开“平台概览”，不设置卡片门户。二级导航只显示当前用户获授的管理工作流，并提供控制面板内部搜索，可搜索用户、用户组、角色、operation、配置、审计事件、备份任务和健康问题。

```text
概览
身份与访问
  用户
  用户组
  服务身份
  角色与权限
  访问检查
  功能访问
AIP 管理
  Provider
  模型配置
  配额与策略
平台运维
  可观测性
  审计日志
  系统配置
  备份恢复
```

控制面板不得直接编辑数据库、Topic、索引或 Compose YAML，不执行 Shell、容器重启、volume 删除或任意组件 API，不显示任何明文密码/Token/API key，也不提供任意 key/value 配置编辑器。

#### 11.16.2 管理职责、资源角色与最终鉴权

管理职责固定为：

| 管理职责 | 能力 |
|---|---|
| 平台管理员 | 全部控制面能力及管理其他管理员 |
| 身份管理员 | 用户、用户组、身份映射和服务身份 |
| 权限管理员 | 角色、资源授权、数据策略和访问检查 |
| AIP 管理员 | Provider、Model Profile、AIP 配额和保留策略 |
| 运维管理员 | 健康、告警、索引重建和维护状态 |
| 备份管理员 | 备份目标/策略/运行和恢复计划 |
| 审计查看者 | 只读审计查询和受控导出 |

`Platform Admin` 是拥有全部管理职责的内置高权限角色，日常授权优先使用细粒度管理职责。`Viewer/Builder` 是产品体验角色，不获得控制面板权限。具体资源使用 Viewer/Editor/Owner，运行型资源增加 Operator，审批使用 Requester/Approver/Workflow Owner，Agent 使用 Viewer/Editor/Owner。

最终访问结果为 `已认证身份 ∩ 功能访问 ∩ 管理职责或资源 operation ∩ RBAC/ABAC ∩ 字段策略 ∩ 当前资源状态/版本`；显式 Deny 优先于 Allow。隐藏导航不能代替 API 鉴权。AIP 管理员不因此读取对象、附件或 Thread 正文，运维管理员不因此读取业务对象，审计查看者不能修改系统配置。

#### 11.16.3 平台概览

路由：`/control-panel/overview`。页面围绕“管理员现在需要处理什么”，而非服务卡片集合。

顶部状态条显示平台总体状态、活跃用户、失败管道、Projection 积压、开放质量问题、待审批配置变更、AIP 失败/配额预警、最近成功备份和磁盘/MinIO 水位。主体依次为：

1. 需要处理的问题：严重度、类型、资源、开始时间、影响范围、负责人、状态和建议动作。
2. 关键链路：APISIX → Ontology → Pulsar → Projection → HugeGraph/OpenSearch，以及 Agent → Provider/Ontology API。
3. 近期变更：权限、Model Profile、系统配置、资源发布、备份和恢复计划。
4. 资源趋势：Flink 运行时间、Pulsar backlog、存储、索引、图数据、AIP tokens 和并发 Run。
5. 安全摘要：登录失败、权限扩大、服务身份异常、密钥到期和大规模导出。

仅显示真实采集值；无法采集的指标显示“不可用/未配置”及原因，不生成假健康状态。v1 不实现项目计费、Usage Account、货币预算或成本分摊。

#### 11.16.4 用户管理

路由为 `/control-panel/identity/users` 和 `/control-panel/identity/users/:userId`。列表显示显示名称、用户名、不可变外部 subject ID、身份源、状态、用户组、产品角色、管理职责、最近登录/活动和同步状态。详情 Tabs 为概览、用户组、管理职责、属性、资源授权摘要、活跃会话和审计活动。

启用 `auth` profile 时，Keycloak 是登录、密码、MFA 和外部身份的真相源；平台只保存 subject 映射、平台角色、资源授权和审计。外部同步属性只读，平台自定义属性分开保存。停用用户立即拒绝新请求并使平台 Session/短期授权失效，不在平台重置密码或读取 Keycloak 凭据。

未启用 `auth` profile 时，仅允许开发模式 bootstrap 管理员/测试用户，页面持续显示“非生产身份模式”；Header 模拟身份不得视为生产能力。“新建用户”改为“预登记身份”，首次通过 Keycloak 登录后以不可变 subject 完成绑定。v1 不做邮件邀请或平台密码账户。

存在审计、审批、Action、自动化执行或 AIP Run 事实的用户不能永久删除，只能停用并脱敏非必要资料。系统禁止停用最后一名平台管理员，禁止当前操作者在没有另一名管理员的情况下移除自己的最后管理能力。

#### 11.16.5 用户组

路由为 `/control-panel/identity/groups` 和 `/control-panel/identity/groups/:groupId`。用户组分为外部组、规则组和内部组：外部组来自 Keycloak/身份源且成员只读；规则组由受限属性规则计算；内部组由平台人工维护，用于临时团队和例外授权。

组详情展示类型、负责人、说明、成员/嵌套组、规则、管理职责、资源角色、功能访问、影响分析和成员变化。规则只允许 `attribute equals/includes`、`provider group includes` 与 AND/OR，不执行任意脚本或不受限正则。发布前必须对选定用户模拟并显示新增/移除成员；规则只在明确同步/登录时生效，页面显示最后计算时间。

授权默认授予组；直接用户授权作为显式例外，在访问检查和定期复核中标记。组循环、来源冲突、超过成员上限或将移除最后管理员时禁止发布。

#### 11.16.6 服务身份

路由为 `/control-panel/identity/service-identities` 和 `/control-panel/identity/service-identities/:id`。服务身份用于自动化执行主体、受信外部 API Client 和平台内部工作负载，字段包括名称、类型、负责人、用途、精确 operation、凭据状态/到期、最近使用、来源服务、轮换策略和允许调用方范围。

密钥只在创建/轮换时显示一次，数据库只保存 hash 或加密 credential reference；每个身份必须有负责人和复核/到期日期。普通外部 Client 不能获得万能平台管理员。Projection/Flink/Agent 等内部身份不能从 UI 导出 key。停用前展示受影响自动化、管道和 Client，轮换、扩大、停用和撤销均审计。

#### 11.16.7 Operation Registry、角色与授权

路由为 `/control-panel/access/roles`、`/control-panel/access/roles/:roleId`、`/control-panel/access/policies` 和 `/control-panel/access/field-policies`。退出当前无限扩展的“资源 × 角色 × read/write/execute”大表格，改为作用域/资源类型、角色、operation 和授权对象的分步视图。

operation 由代码和 Flyway migration 注册，管理员不能创建后端不识别的字符串。初始 operation 至少包括：

```text
data-connection:view/edit/manage-credential
pipeline:view/operate/edit
ontology:view/propose/publish
object:read/execute-action/export
dashboard:view/edit/publish
application:view/edit/publish
automation:view/operate/edit/enable
approval:view/request/decide/administer
aip:view-agent/edit-agent/manage-provider
audit:view/export
backup:view/create/manage-policy
restore:prepare
```

角色是同一上下文内的 operation 集合；Resource Grant 把用户/组以某角色绑定到具体资源或资源类型。修改角色前展示受影响用户、组、资源和运行主体；被引用角色只能归档或通过新版本替换，不能原地删除历史语义。

#### 11.16.8 ABAC 与字段策略

ABAC 使用受限 AST，只允许 subject 属性、group、resource 属性、object property，以及 equals/in/contains/范围/日期和 AND/OR/NOT。规则必须通过类型、字段存在性、环路、复杂度和双后端编译校验；不能同时编译为 HugeGraph/OpenSearch 安全谓词时禁止发布，不回退前端过滤。

字段策略单独配置可见、脱敏、仅聚合或完全禁止，并可按角色/组/属性生效。脱敏仅使用平台注册模板：全部隐藏、保留末四位、范围化、哈希化；不运行任意脚本。字段策略必须同时覆盖对象详情、搜索、Facet、关系、导出、看板、业务应用和 AIP 引用。

#### 11.16.9 Access Checker

路由：`/control-panel/access/check`。管理员选择用户/服务身份、页面/资源/对象引用、目标 operation 和可选字段，服务端以指定安全上下文运行只读模拟。

结果逐层解释身份、功能访问、直接/组授权、角色 operation、默认授权、Deny、ABAC 条件、字段脱敏、策略版本和最终结果，并显示缺失权限及负责的管理员类型。模拟不产生临时授权、不调用写工具、不显示目标主体本来无权读取的业务值；结果本身按敏感管理数据审计和保留。

#### 11.16.10 功能访问

路由：`/control-panel/access/applications`。按“数据 / 本体 / 应用 / AIP / 管理”展示功能名称、生命周期、默认允许角色/组、例外 Allow/Deny、受影响用户数、发布版本和最近变更，支持批量编辑、diff 和影响预览。

无功能访问时隐藏导航且直接 URL 返回 403，但后端仍执行资源和数据权限。系统禁止移除全部平台管理员的控制面板入口或最后一名功能访问管理员。配置以 Draft/Version 发布，从旧版本创建新草稿回退；扩大访问、管理入口变化或大范围用户变化进入审批中心。

#### 11.16.11 AIP Provider Credential

路由：`/control-panel/aip/providers`。列表显示 Provider 类型、Base URL、凭据引用、健康、能力、最近测试、引用它的 Model Profiles、到期和轮换时间；支持创建、测试、轮换、停用、影响分析和删除未引用配置。

secret 使用环境 master key 做 envelope encryption；master key 通过 Docker secret/服务器文件提供，不进入数据库、Git 或备份包。浏览器提交后不再返回明文。自定义 Base URL 只允许 HTTPS 和管理员 allowlist，拒绝 localhost、link-local、云 metadata、Compose 内网业务服务和重定向绕过，防止 SSRF。

#### 11.16.12 Model Profile

路由：`/control-panel/aip/models`。Model Profile 固定 Provider Credential、精确模型、能力、上下文/输出上限、stream/tool/structured output/embedding 支持、默认参数、允许用户组、数据保留声明、fallback 和健康测试。

Profile Version 不可变；修改生成新版本，不改变已发布 Agent。fallback 必须经过能力兼容测试。停用前显示受影响 Agent/Thread；已停用版本历史仍可读，但不能启动新 Run。

#### 11.16.13 AIP 配额与平台策略

路由：`/control-panel/aip/policies`。管理用户、组、Agent、Profile 的 Token/调用量、并发 Run、工具调用、附件容量、临时会话/Thread/导出保留、Provider 数据保留声明、全局 Tool allowlist、prompt/response 日志策略、Eval 最低门禁、内容安全和引用要求。

页面显示当前用量、趋势、预计触顶时间和受影响 Agent。管理员只能在平台硬上限内配置，不能关闭运行时权限重查、模型无 execute 工具、Action 独立确认、引用注册或思维链禁存等强制安全边界。

#### 11.16.14 可观测性

路由为 `/control-panel/observability` 和 `/control-panel/observability/issues/:issueId`。汇总 APISIX、Ontology、BFF、Agent、Projection Worker、Flink、Pulsar、HugeGraph、OpenSearch、Postgres、MinIO、SkyWalking OAP/UI、OTel Collector 和 Provider 健康。

页面显示活跃问题、服务依赖摘要、P50/P95/P99、错误率、Flink 作业/checkpoint/savepoint、Pulsar backlog/DLQ、Projection 延迟、存储水位、AIP Provider/Run/Tool 错误及最近部署/配置变化。健康问题状态为 `OPEN → ACKNOWLEDGED → SILENCED → RESOLVED`；静默必须有原因、负责人和到期时间，信号仍异常时不能手工伪装为解决。

提供“打开 SkyWalking”和精确 trace deep link，不 iframe 嵌入完整 UI。页面不能重启容器、执行 Shell、删除 volume 或透传组件管理 API。

#### 11.16.15 结构化审计

路由为 `/control-panel/audit`、`/control-panel/audit/:eventId` 和 `/control-panel/audit/exports`。审计使用稳定 `audit.v1` schema：

```text
event_id / occurred_at / category / action
actor_type / actor_id / session_id / service
resource_type / resource_id
request_id / correlation_id / trace_id
source_ip / client / outcome / reason_code
policy_revision / change_request_id
entities[] / sanitized_diff
```

类别至少包括 authentication、permissionChange、configurationChange、dataRead、dataCreate、dataUpdate、dataDelete、dataExport、actionExecution、approvalDecision、automationExecution、aipRun、secretOperation 和 backupRestore。查询支持类别、动作、用户、组、服务、资源、结果、时间和 request/trace ID；详情关联审批、AIP 安全 Trace 和 SkyWalking。

导出采用异步 JSONL/CSV 任务并产生二次审计。普通读取按查询/工具粒度记录资源类别和数量，不逐行保存业务正文。日志禁止保存 key、密码、附件正文、完整 prompt/response、完整工具结果或敏感对象正文。

#### 11.16.16 审计保留、防篡改与法律保留

Postgres 默认保存 180 天可查询事件；每日生成压缩 JSONL 和 SHA-256 manifest 写入独立私有 `audit-archive` bucket，默认归档 365 天。归档不可经 UI 修改；法律保留按用户、类别、资源或时间范围阻止清理。审计查看者不因角色获得业务资源正文权限。

归档验证记录 hash、行数、首末时间、schema 版本和导出主体；重复事件按 log entry ID 去重但保留原 event ID 关联。v1 提供受控拉取/导出 API，不实现任意 SIEM Webhook 推送。

#### 11.16.17 类型化系统配置

路由为 `/control-panel/settings` 和 `/control-panel/settings/changes/:changeId`。配置项由代码注册 key、中文说明、类型/单位、默认值、范围、敏感级别、是否需重启、风险、负责模块和当前来源；不允许创建任意 key。

可在线修改网关用户限流、Preview/Selection/Download/Confirmation token TTL、数据/任务保留、通知合并/频率/渠道、质量/管道/Projection/AIP 告警阈值、注册功能开关、导出上限和维护公告。服务 URL/端口、Docker volume、JVM、组件凭据、APISIX route、Compose profile 和主加密密钥只读显示来源，不在 UI 修改。

配置流程为 `DRAFT → VALIDATING → READY → PENDING_APPROVAL（高风险）→ APPLYING → APPLIED/FAILED`。每次显示 old/new diff、影响、是否重启、风险和恢复方法，使用 ETag 和幂等键；回退是由旧版本创建新 Change Request，不修改历史。

#### 11.16.18 高风险管理变更

控制面板复用 D-027 审批中心，不实现第二套工作流。授予管理职责、扩大对象/字段访问、修改脱敏、大范围功能访问、新增/修改 Provider Credential、放宽 AIP 保留/工具策略、缩短审计保留、修改备份目标、创建恢复计划和进入维护/只读模式必须提交 Request。

高风险默认职责分离，申请人不能自批。批准后仅由注册式 Configuration/Permission/Backup Invocation 应用，不能执行任意 SQL、Shell、URL 或 JSON Patch；批准事实与实际应用完成分离。低风险说明/告警订阅可直接应用但仍审计。

#### 11.16.19 备份页面与灾备边界

路由为 `/control-panel/backups`、`/control-panel/backups/policies`、`/control-panel/backups/runs/:runId` 和 `/control-panel/backups/restore-plans/:planId`。页面明确区分本地恢复点与外部 S3-compatible 备份目标；备份只在同一服务器/MinIO volume 时显示 `LOCAL_ONLY / NOT_DISASTER_PROTECTED`，不得宣称灾难恢复。

备份清单包含 Postgres 控制面/运行事实、HugeGraph 对象事实、MinIO 原始/隔离/AIP 附件和必要 artifact、APISIX standalone 配置、bootstrap/镜像 digest/schema/version manifest、Pulsar/Flink watermark 和 Projection ledger 位置，以及 OpenSearch 模板/alias。OpenSearch 对象与 AIP chunk 索引可重建，快照可选但不是唯一来源；Pulsar 是事件传输，不作为长期业务事实备份。

#### 11.16.20 一致性备份与 Maintenance Runner

完整备份按 `预检查 → WRITE_DRAINING → 拒绝新 mutation/publish → 等待 Projection 到 watermark → 备份 Postgres/HugeGraph/MinIO/配置 → 写 manifest/hash/version/ledger watermark → 完整性验证 → NORMAL` 执行。读取可继续；超时必须失败并恢复写入，不能生成“成功但不一致”的 manifest。

新增轻量 `maintenance-runner` Compose 服务，仅消费平台注册的备份、验证和索引重建任务；不挂载 Docker Socket、不接收命令字符串、不执行任意 Shell/URL，使用最小权限组件凭据并回写 job/manifest。它不停止/删除其他容器，也不负责全平台离线恢复。

默认建议每日控制面一致性备份、每周完整备份、每月自动验证、每季度人工异机恢复演练；生产必须配置外部目标。RPO/RTO 只显示演练测量结果，不写未经验证的承诺值。

#### 11.16.21 Restore Plan 与服务器恢复

全平台恢复会中断控制面，不能做网页“一键恢复”。控制面先创建 Restore Plan，校验 manifest/hash、版本/digest、目标环境、范围和覆盖影响，完成职责分离审批后生成短期一次性 token，绑定 plan、manifest、目标环境、操作者和过期时间。

运维人员在服务器执行 `make restore RESTORE_PLAN=<id> RESTORE_TOKEN=<token>`；host 脚本复核 token，进入维护模式、停止相关 Compose 服务、恢复 Postgres/HugeGraph/MinIO/配置、重启、重建 OpenSearch/AIP 文档索引、对账 Projection ledger/Pulsar watermark、运行完整性检查并回报。换人、换环境、过期、重复或篡改 manifest 均拒绝。控制面板不获得 Docker Socket 或 Shell 能力。

#### 11.16.22 数据模型

```text
platform_users                  identity_mappings
platform_groups                 group_memberships
group_rules                     service_identities
service_credentials             admin_workflow_assignments
permission_operations           role_sets
platform_roles                  role_operations
resource_grants                 access_policies
field_policies                  access_check_runs
application_access_drafts       application_access_versions
configuration_definitions       configuration_drafts
configuration_versions          configuration_change_requests
maintenance_states              health_issues
health_issue_events             alert_rules
usage_records                   audit_events
audit_event_entities            audit_archives
audit_export_jobs               legal_holds
backup_targets                  backup_policies
backup_runs                     backup_artifacts
backup_manifests                backup_verifications
restore_plans                   restore_events
```

AIP Provider/Profile/Quota 继续使用已确认的 `aip_*` 表，不复制到 settings JSON。以上表不保存用户密码、明文 service/provider key、主加密 key、任意 Compose 配置、完整日志正文或备份正文。

#### 11.16.23 API 契约

```text
/v1/control/overview                 /v1/control/search
/v1/control/health/*                 /v1/control/usage/*
/v1/settings/users/*                 /v1/settings/groups/*
/v1/settings/group-rules/*           /v1/settings/service-identities/*
/v1/settings/operations/*            /v1/settings/roles/*
/v1/settings/grants/*                /v1/settings/access-policies/*
/v1/settings/field-policies/*        /v1/settings/access-checks/*
/v1/settings/application-access/*
/v1/aip/admin/providers/*            /v1/aip/admin/model-profiles/*
/v1/aip/admin/quotas/*               /v1/aip/admin/policies/*
/v1/observability/overview           /v1/observability/issues/*
/v1/observability/traces/*
/v1/audit-logs/*                     /v1/audit-exports/*
/v1/audit-archives/*                 /v1/legal-holds/*
/v1/settings/configuration/*         /v1/settings/change-requests/*
/v1/settings/maintenance-state
/v1/backups/targets/*                /v1/backups/policies/*
/v1/backups/runs/*                   /v1/backups/verifications/*
/v1/restore-plans/*
```

列表使用稳定 cursor，修改使用 ETag 和幂等键。高风险 API 返回 Change Request/Invocation ID，不直接返回假成功。AIP admin API 由 Agent 服务拥有，其他管理 API 由 Ontology Core 拥有；BFF 只聚合页面数据，不成为权限真相源。

#### 11.16.24 前端结构与实现边界

```text
features/control-panel/
├── layout/{ControlPanelLayout,ControlPanelNav,ControlPanelSearch}.tsx
├── overview/
├── identity/{users,groups,service-identities}/
├── access/{roles,policies,access-check,application-access}/
├── aip-admin/{providers,models,quotas}/
├── observability/
├── audit/
├── configuration/
├── backups/
└── shared/{ChangeDiff,ImpactSummary,SensitiveValueInput,AdminPermissionGuard}.tsx
```

控制面板按上述领域直接实现，不复制单体设置/审计页面、`admin/writer/reader` 粗粒度角色、简单 page resource、`read/write/execute` 权限或任意 JSON 配置。高风险变更统一使用草稿、diff、校验和审批；配置使用类型化 definition/version。

#### 11.16.25 明确不做

v1 不实现多 Organization/多租户/跨组织 Guest、原生 AD/LDAP/SAML、平台密码/MFA、Palantir Markings/Enrollment/Spaces/Usage Account、货币计费、任意权限脚本、任意配置 key、浏览器 Shell/Docker Socket/容器重启/volume 删除、Kubernetes/自动扩缩容、网页一键全平台恢复、未经验证的跨存储 PITR、审计正文全文收集或任意 SIEM Webhook。

控制面板不取代 Keycloak、SkyWalking、Docker Compose 或服务器运维工具。

#### 11.16.26 验收标准（50 项）

1. Viewer/Builder 看不到且不能直接访问控制面板。
2. 各管理职责只能看到和调用对应管理能力。
3. AIP 管理员无法读取对象、附件或 Thread 正文。
4. 运维管理员无法读取业务数据或修改权限。
5. 审计查看者无法修改配置。
6. Keycloak 映射使用不可变 subject，不依赖可变 username。
7. 外部组只读，规则组和内部组来源清楚。
8. 规则组发布前正确显示成员变化。
9. 停用用户立即失去新请求能力。
10. 最后一名平台管理员不能被停用或锁出控制面板。
11. service credential 只显示一次并可轮换、撤销。
12. 每个权限 operation 都来自注册表。
13. 角色变更展示受影响用户和资源。
14. Deny 优先于 Allow。
15. ABAC 同时正确约束 HugeGraph 和 OpenSearch。
16. 字段策略不能被搜索、Facet、导出、AIP 或引用旁路。
17. Access Checker 解释直接、组、角色、ABAC 和字段决策。
18. Access Checker 不泄露无权资源正文。
19. 功能访问隐藏导航且直接 URL 返回 403。
20. 功能访问不能替代资源/API 权限。
21. 无法删除所有管理员的控制面板入口。
22. 权限扩大创建审批 Request。
23. 高风险申请人不能自批。
24. 审批后仅注册 Invocation 能应用变更。
25. Provider key 不返回前端、日志、审计或 APM。
26. Provider Base URL 能阻止 SSRF。
27. Model Profile 变化不改变已发布 Agent。
28. AIP 配额不能关闭强制安全边界。
29. 平台概览只显示真实采集数据。
30. SkyWalking 链接只允许有权管理员打开。
31. 控制面板不能执行 Shell、Docker 命令或任意组件 API。
32. 健康信号仍异常时不能伪装为已解决。
33. 静默告警必须有原因和到期时间。
34. 审计可回答谁、何时、做了什么、涉及什么及结果。
35. 审计事件不保存凭据或敏感正文。
36. 审计导出产生二次审计。
37. 审计归档 hash/manifest 可验证。
38. 法律保留阻止对应归档清理。
39. 系统配置必须来自类型化注册表。
40. 环境和基础设施配置在 UI 只读。
41. 配置修改使用 ETag、版本和 diff。
42. 高风险配置失败时旧版本继续生效。
43. 本地备份不能显示为灾难恢复。
44. 一致性备份能 drain Projection 并记录 watermark。
45. 备份失败恢复正常写入且不产生成功 manifest。
46. Maintenance Runner 无 Docker Socket 和任意命令能力。
47. Restore Plan 固定 manifest、目标环境和版本。
48. restore token 换人、换环境、过期或重复使用均被拒绝。
49. 恢复后能重建 OpenSearch 并对账 Projection ledger。
50. 全新 Compose 完成身份同步 → 组授权 → 访问检查 → 高风险权限审批 → 审计 → 备份验证闭环。

### 11.17 页面讨论状态

全局外壳及数据、本体、应用、AIP、控制面板的全部页面已经按 D-008、D-010—D-037 确认并冻结，逐页产品讨论完成。正式实现转入 `/Users/dijkstra/project/06-shixi/Ontology`，只按 12.2 的 P00—P17 顺序实施。

## 12. 实施路线

### 12.1 新仓库、参考仓库与 Commit 契约

正式实现仓库固定为 `/Users/dijkstra/project/06-shixi/Ontology`；本仓库 `/Users/dijkstra/project/06-shixi/palantir-like-system` 仅作交互与视觉参考，不在两个仓库同时实现功能，也不复制代码、数据、API、`.git`、构建产物或 secret。`Ontology` 从当前 HEAD 继续实施，不 rebase/squash 已存在的初始化提交。

P00—P17 每项对应一次 commit。执行规则：

1. 一次只实施一个 `Pxx`，完成表中全部交付范围、门禁和文档后再提交。
2. 页面 commit 使用完整 vertical slice：同一 commit 内交付 Flyway/schema、OpenAPI、后端、前端页面、权限、审计和测试，不能提交只有空页面或假 API 的半成品。
3. 页面边界按左侧导航功能计算；列表、向导、详情、编辑器和运行视图属于同一产品页面，不因内部路由拆 commit。
4. 公共能力只能在最早需要它的 commit 中实现并由后续页面复用，禁止提前提交没有调用方的抽象框架。
5. HTTP 契约先更新 `docs/openapi/openapi.yaml`；数据库变化带 Flyway 和前向修复说明。前端只使用 pnpm，注释使用英文。
6. 每次提交前运行 `make verify-fast` 和表中专项 E2E；commit 必须使用 Conventional Commits 与 `git commit -s`，仓库保持可编译、可启动、可回滚。
7. 若一个页面确实无法在单次 review 中审查，必须先与用户讨论是否拆 commit；不能自行增加第二套实施路线。

门禁缩写：`J`=`./mvnw test`，`F`=`pnpm --dir portal lint && pnpm --dir portal typecheck && pnpm --dir portal build`，`O`=OpenAPI lint/兼容检查，`C`=`docker compose config` + 相关 profile 健康检查，`E:<name>`=对应专项 E2E。

### 12.2 十八个可提交 Phase

| Phase | 建议 commit | 页面/平台交付边界 | 门禁 |
|---|---|---|---|
| P00 | `build(platform): establish project baseline` | 将最新 `plan.md` 放入新仓库；完成 ADR、Maven 多模块、portal 外壳、OpenAPI、Makefile、CI、Java 21/pnpm 规范和目录清理 | JFOC |
| P01 | `feat(platform): add authenticated compose foundation` | 锁定镜像；完成 Postgres、MinIO、Pulsar、HugeGraph、OpenSearch、Flink、APISIX、Keycloak、SkyWalking、profiles、bootstrap、健康检查和默认 PKCE 登录 | JFCO E:platform-foundation |
| P02 | `feat(storage): add ontology projection foundation` | 完成控制面 Flyway、事件/Mutation 契约、HugeGraph/OpenSearch clients、Projection Worker、幂等 ledger、retry/DLQ、索引重建和存储 E2E；Postgres 从第一版不创建对象正文表 | JOC E:storage-e2e |
| P03 | `feat(connections): deliver data connections page` | “数据连接”完整 vertical slice：加密凭据、连接测试、资产发现、Schema 预览、紧凑列表、四步向导、详情 Tabs、权限、审计和 API 测试 | JFO E:connections-page |
| P04 | `feat(pipelines): deliver pipeline builder page` | “管道构建”完整 vertical slice：IR/DAG、Flink sources/transforms、预览、版本/发布、运行/SSE、event bus 状态、全屏画布、权限和 E2E | JFCO E:pipelines-page |
| P05 | `feat(quality): deliver data quality page` | “数据质量”完整 vertical slice：版本化规则 AST、接入前/管道内/投影后三阶段、问题、隔离、重放、工作区、权限和 E2E | JFO E:quality-page |
| P06 | `feat(lineage): deliver data lineage page` | “数据血缘”完整 vertical slice：贡献模型、设计/运行/历史/影响查询、业务/技术双视图、按需加载、权限裁剪和 E2E | JFO E:lineage-page |
| P07 | `feat(modeling): deliver ontology management page` | “本体管理”完整 vertical slice：规范资源、对象/关系/Interface、Action/Function、Proposal、发布 Saga、健康/历史、全套编辑页面和 E2E | JFO E:modeling-page |
| P08 | `feat(explorer): deliver object exploration page` | “对象探索”完整 vertical slice：Object Set、搜索/Facet、关系/比较、能力/来源、Selection/Bulk、导出、Exploration/List、Full/Panel 视图和 E2E | JFO E:explorer-page |
| P09 | `feat(dashboards): deliver analytics dashboard page` | “分析看板”完整 vertical slice：版本、DataSource/Filter、Query Plan、安全聚合、列表/查看器/全屏编辑器、组件、发布/分享/导出和 E2E | JFO E:dashboards-page |
| P10 | `feat(applications): deliver business applications page` | “业务应用”完整 vertical slice：版本、类型化变量/依赖、Runtime Plan、Action binding、导航/导出、列表/运行页/编辑器、权限和 E2E | JFO E:applications-page |
| P11 | `feat(approvals): deliver action and approval center` | Action Preview/Mutation 公共能力及“审批中心”完整 vertical slice：Request/Revision/Stage/Task/Decision、Invocation、协作、收件箱和 E2E | JFO E:approvals-page |
| P12 | `feat(automations): deliver automation page` | “自动化”完整 vertical slice：版本化定义、对象/平台/Cron 触发、顺序效果、执行主体、幂等、重试/DLQ/循环保护、工作区和 E2E | JFO E:automations-page |
| P13 | `feat(aip): deliver agent studio` | AIP 公共 Runtime 与“智能体工作室”：Provider/Model、Thread/Run/SSE、Context/Tool/Action、附件/检索/引用、Guardrail、Agent 版本/Eval/用量、编辑器和 E2E | JFO E:agent-studio-page |
| P14 | `feat(aip): deliver conversations and assistant` | “对话中心”及全局 AIP Drawer：临时会话、私有 Thread、消息分支、附件/引用、Run Trace、断线恢复、安全导出、权限撤销遮蔽和 E2E | JFO E:conversations-page |
| P15 | `feat(admin): deliver control panel` | “控制面板”完整 vertical slice：身份/组/服务身份、角色/ABAC/字段策略、Access Checker、功能访问、AIP 管理、观测、结构化审计、配置、备份/恢复和 E2E | JFCO E:control-panel-page |
| P16 | `test(platform): harden security and resilience` | 完成跨页面授权矩阵、secret/SSRF/token 重放、数据/Action/应用/自动化/AIP/管理闭环、依赖故障和遥测隔离；不新增产品功能 | JFCO E:security-resilience |
| P17 | `chore(release): verify production delivery` | 锁定镜像和制品，生成 SBOM/安全报告，完成运维手册、全新 Linux 部署、备份恢复演练、最终 `make verify` 报告和发布候选 | JFCO E:all |

P03—P15 的 commit message 以用户可见页面为中心；其 schema、API、后端、前端和测试是页面交付的一部分，不另拆 commit。P00—P02 是页面开发前必需的平台基础，P16—P17 是跨模块交付门禁，因此总计固定为 18 个 commit。

## 13. 新仓库明确不引入的能力

| 不引入项 | 新仓库规则 | 采用能力 |
|---|---|---|
| Nessie / Iceberg | 不添加服务、依赖、catalog、snapshot 或 time-travel API/文案 | HugeGraph 对象版本 + 审计历史；不宣称等价时间旅行 |
| Trino | 不添加 client、catalog 或原始 SQL 查询路径 | HugeGraph/OpenSearch 类型化查询 API |
| SeaTunnel | 不添加服务、connector 或作业配置 | Flink connector + Pipeline IR |
| Airflow | 不添加编排服务或相关承诺 | Spring scheduler + Flink 作业控制 |
| Great Expectations | 不添加 Python 质量运行时 | Java 质量规则 + Flink/Projection 校验 |
| Prometheus Server / Grafana | 不部署独立服务、数据卷或 dashboard provisioning | OTel Collector + SkyWalking OAP/UI |
| Postgres object JSON | 从第一版起不创建对象正文表或双写路径 | HugeGraph 事实存储 + OpenSearch 派生索引 |

UI、README、API、测试和演示中均不得宣称 Iceberg 时间旅行、Nessie 分支、Trino 联邦查询或其他未实现能力。

## 14. 质量和交付门禁

12.2 中每个可提交 Phase 必须满足：

- 代码、Compose、配置和文档同步。
- Java 21 test、前端 lint/build 通过。
- 新增 API 有成功、权限拒绝、无效参数和依赖失败测试。
- 新增有状态服务有持久化和重启恢复验证。
- 可观测改动必须验证 trace/metric/alarm 三条链路，并证明遥测索引保留策略不会影响业务索引。
- Flyway、发布、索引重建和配置变更有前向修复或可重复执行策略。
- 不提交真实密码、Token、数据库凭据或 LLM key。
- 不用假数据响应掩盖基础设施未接通。
- 页面不能把底层 HTTP 500 原样作为用户唯一提示，必须给出资源、阶段和恢复动作。

## 15. 实施入口

D-001—D-037 已全部确认，产品范围、页面、架构、默认 Keycloak 认证、新仓库目录和提交级实施方式均已冻结。后续不再把“大模块讨论”作为开发入口，而是严格执行 12.2 的 commit-sized Phase。

下一步是新仓库 `/Users/dijkstra/project/06-shixi/Ontology` 的 P00：复制最新 `plan.md`，建立 ADR、Maven/portal/OpenAPI/Makefile/CI 基线和正式目录。P00 完成全部门禁后提交 `build(platform): establish project baseline`，再进入 P01；不得一次领取多个 Phase 或把后续页面提前混入当前 commit。

实现中若发现阶段仍过大、依赖顺序冲突或安全缺陷，先回到本计划拆分/修正对应 Phase，再写代码。除明确冲突、安全修复或已验证的组件兼容问题外，不重新打开 D-001—D-037。
