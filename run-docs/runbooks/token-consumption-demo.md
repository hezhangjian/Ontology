# 部门与团队 Token 消耗演示手册

## 演示结果

本地环境已经准备好以下可复用资源：

- 数据连接：`Token 消耗演示 CSV`
- 完整事实管道：`Token 消耗事实导入 v2`（6000 行）
- 演示汇总管道：`部门 Token 汇总导入`（25 行，包含隐私门槛占位对象）
- 完整事实本体：`Token 使用记录` / `TokenUsage`
- 演示汇总本体：`部门 Token 汇总` / `TokenDepartmentSummary`
- 已发布看板：`部门与团队 Token 消耗`

原始文件位于 `/Users/dijkstra/project/06-shixi/data`。原文件没有被修改；带唯一 ID 的完整事实 CSV 和部门汇总 CSV 只存放在本地 MinIO 的 `raw/token-demo/` 前缀下。

## 启动

在项目目录执行：

```bash
cd /Users/dijkstra/project/06-shixi/Ontology
make compose-up
docker/scripts/healthcheck.sh
```

如果拉取了代码变更或镜像尚未构建，先执行：

```bash
make compose-build
make compose-up
docker/scripts/healthcheck.sh
```

不要执行会删除 Compose volumes 的命令；演示连接、管道、本体、看板和 MinIO CSV 都保存在这些本地卷中。

## 打开平台

浏览器打开 `http://localhost:9080`，无需登录。左侧按“连接数据 → 整理与映射 → 创建本体 → 制作看板”排列。

常用组件入口：

- Flink：`http://服务器地址:8081`
- Pulsar Admin：`http://服务器地址:8080`
- SkyWalking：`http://服务器地址:8082`
- HugeGraph：`http://服务器地址:8088`
- MinIO Console：`http://服务器地址:9001`
- OpenSearch：`http://服务器地址:9200`
- PostgreSQL：`服务器地址:5432`

## 推荐演示路径

### 1. 展示 CSV 连接

1. 点击左侧“数据连接”。
2. 点击 `Token 消耗演示 CSV`。
3. 点击“资产”页签。
4. 展示以下资产：
   - `demo-employee-leaders.csv`
   - `demo-token-usage.csv`
   - `demo-token-usage-with-id.csv`
   - `demo-token-department-summary.csv`
5. 点击 `demo-token-usage.csv` 可说明原始事实共有 12 个字段；带 ID 文件有 13 个字段；部门汇总文件有 7 个字段。

### 2. 展示管道

1. 点击左侧“管道构建”。
2. 打开 `Token 消耗事实导入 v2`，展示完整 6000 行事实的 SOURCE → SELECT → ONTOLOGY_OBJECT DAG、字段映射和通过的校验/预览。
3. 返回列表并打开 `部门 Token 汇总导入`。
4. 点击“历史”，打开最近一次运行。
5. 展示一次成功运行：读取 5、发布 5、拒绝 0、Projection 100%。第二次增加隐私门槛占位对象的运行会显示 CANCELLED，但 20 条新对象均已成功投影；取消是因为 5 条幂等更新没有计入本次 batch ack。

### 3. 展示本体

1. 点击左侧“本体管理”。
2. 点击“对象类型”。
3. 打开 `Token 使用记录`，展示员工、月份、Agent 类型、五级部门和 Token 数等 13 个属性。
4. 打开 `部门 Token 汇总`，展示五级组织维度、`totalToken` 度量以及已发布 Revision 7。

### 4. 展示最终看板

1. 点击左侧“分析看板”。
2. 点击 `部门与团队 Token 消耗`。
3. 如显示缓存结果，可点击右上角“刷新”。
4. “一级部门 Token 消耗”应显示：
   - 产品研发中心：16,124,098
   - 财务中心：11,228,682
   - 运营中心：10,337,318
   - 市场与销售中心：9,437,020
5. “各组 Token 消耗”应显示：
   - 经营分析组：11,228,682
   - 交付支持组：10,337,318
   - 华东大区：9,437,020
   - 智能助手组：8,536,611
   - 数据平台组：7,587,487

两张图的合计都是 47,127,118，与 `/Users/dijkstra/project/06-shixi/data/demo-token-usage.csv` 的 6000 行全量求和一致。

## 为什么需要汇总管道

完整事实管道已验证能读取并发布 6000 条事件，但当前 Projection Worker 逐条同步写 HugeGraph 和 OpenSearch，本机吞吐不足以支持现场等待。看板还默认执行每组至少 5 个对象的小群体抑制。

因此演示使用同一原始 CSV 生成 5 条准确的组织路径汇总，并为每条路径添加 4 条零值占位对象。这样每个分组满足 5 个对象的隐私门槛，同时 Token 合计保持不变。此方案适合演示；生产方案应实现批量 Projection、按事实权重配置隐私门槛，或提供受治理的物化聚合数据产品。

## 本次发现并修复的系统问题

- DAG 节点初始尺寸缺失导致节点隐藏，MiniMap 又遮挡输出节点。
- React Flow 尺寸事件被误当作图变更，持续重置自动保存定时器。
- 校验、预览和发布之前没有强制保存当前草稿。
- 新本体的 API 名称、物理类型键和物理属性键没有在 Pipeline/Projection/Explorer 之间正确转换。
- Flink 事件把 `ontology_revision` 硬编码为 1，无法写入后续 Revision 的本体。
- CSV 推断为 INTEGER 的字段在 Flink 行数据中仍是字符串，契约校验失败。
- 看板柱状图原来只能按对象数计数，不能按 Token 字段执行分组 SUM。
- 已进入 PROJECTING 的运行无法取消。

仍建议后续改进：Projection 批量写入、幂等事件在重跑 batch 中正确回执、编辑器提供本体/属性选择器而不是要求手填稳定 ID，以及用显式聚合权重替代零值占位对象。
