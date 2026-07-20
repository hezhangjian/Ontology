# Ontology implementation timeline

> 目标：严格按 `plan.md` 的 P00—P17 顺序交付整个系统。每个 Phase 只对应一个 signed-off Conventional Commit；涉及前端的 Phase 必须在 Codex 内置浏览器中完成真实点击测试。

## 恢复点

- 当前状态：P00 已提交，工作树应保持干净。
- 下一阶段：P01 `feat(platform): add authenticated compose foundation`。
- 继续规则：先确认 P00 提交存在且工作树干净，再只领取 P01；不得提前实现 P02 或页面 Phase。

## Phase 状态

| Phase | 状态 | Commit | 验证摘要 |
|---|---|---|---|
| P00 | 已完成 | `build(platform): establish project baseline`（本记录与 Phase 同一提交） | J/F/O/C + 内置浏览器点击通过 |
| P01 | 待实施 | `feat(platform): add authenticated compose foundation` | `E:platform-foundation` |
| P02 | 待实施 | `feat(storage): add ontology projection foundation` | `E:storage-e2e` |
| P03 | 待实施 | `feat(connections): deliver data connections page` | `E:connections-page` |
| P04 | 待实施 | `feat(pipelines): deliver pipeline builder page` | `E:pipelines-page` |
| P05 | 待实施 | `feat(quality): deliver data quality page` | `E:quality-page` |
| P06 | 待实施 | `feat(lineage): deliver data lineage page` | `E:lineage-page` |
| P07 | 待实施 | `feat(modeling): deliver ontology management page` | `E:modeling-page` |
| P08 | 待实施 | `feat(explorer): deliver object exploration page` | `E:explorer-page` |
| P09 | 待实施 | `feat(dashboards): deliver analytics dashboard page` | `E:dashboards-page` |
| P10 | 待实施 | `feat(applications): deliver business applications page` | `E:applications-page` |
| P11 | 待实施 | `feat(approvals): deliver action and approval center` | `E:approvals-page` |
| P12 | 待实施 | `feat(automations): deliver automation page` | `E:automations-page` |
| P13 | 待实施 | `feat(aip): deliver agent studio` | `E:agent-studio-page` |
| P14 | 待实施 | `feat(aip): deliver conversations and assistant` | `E:conversations-page` |
| P15 | 待实施 | `feat(admin): deliver control panel` | `E:control-panel-page` |
| P16 | 待实施 | `test(platform): harden security and resilience` | `E:security-resilience` |
| P17 | 待实施 | `chore(release): verify production delivery` | `E:all` |

## P00 — 项目基线

- 完成时间：2026-07-20 14:48 CST
- Commit：`build(platform): establish project baseline`
- 范围：
  - 纳入最新 `plan.md` 并建立本 timeline。
  - 建立 Java 21 / Spring Boot 3.5.3 Maven 聚合与 8 个边界模块，固定 Maven Wrapper 3.9.9。
  - 建立 React 18、TypeScript、Vite、Ant Design 与 pnpm 锁文件的中文产品外壳。
  - 建立唯一 OpenAPI 基线、ADR、架构/证据/runbook 文档入口和仓库说明。
  - 建立 Makefile、GitHub Actions、Compose 空服务基线和正式顶层目录。
  - 更新 `AGENTS.md`，删除根 `src/` 业务源码约定并冻结包管理与验证规则。
- 自动验证：
  - `make verify-fast`：通过。
  - Java：9 个 reactor project 全部 `BUILD SUCCESS`，Java/Maven Enforcer 通过。
  - Frontend：lint、typecheck、Vite production build 全部通过且无构建警告。
  - OpenAPI：Redocly recommended lint 通过且无警告。
  - Compose：`docker compose config --quiet` 通过。
  - `git diff --check`：通过。
- 内置浏览器手测：
  - 打开 `http://localhost:3000/data/connections`，确认白色侧栏、浅色内容区、蓝色主色和中文信息架构。
  - 点击“收起导航”与“展开导航”，两种状态均正确。
  - 点击“通知”，确认抽屉打开并可关闭。
  - 点击“AIP 助手”，确认 480px 上下文抽屉打开且输入在 P13 前明确禁用。
  - 点击“用户菜单”，确认个人资料、偏好设置和退出登录项可见。
  - 点击“数据连接”导航，确认 URL 与页面标题保持 `/data/connections` / “数据连接”。
  - 首轮发现 Ant Design `Card bordered` 弃用告警，改为 `variant` 后使用全新页签复测；最终 console warning/error 为 0。
- 边界说明：P00 只交付工程与全局外壳；导航中后续功能保持禁用并明确标注交付 Phase，未引入 P01 基础设施或虚假 API。

## 后续记录模板

每完成一个 Phase，在同一 Phase commit 中追加：完成时间、Commit subject、范围、自动门禁、内置浏览器点击步骤（若涉及前端）、发现并修复的问题、依赖/兼容证据、下一恢复点。
