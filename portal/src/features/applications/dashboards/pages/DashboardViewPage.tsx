import { EditOutlined, ExpandOutlined, PrinterOutlined, ReloadOutlined, StarFilled, StarOutlined } from '@ant-design/icons';
import { Alert, Button, Skeleton, Space, Tabs, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import DashboardFilterBar from '../runtime/DashboardFilterBar';
import DashboardRuntime from '../runtime/DashboardRuntime';
import { DashboardApi } from '../services/dashboardApi';
import type { DashboardBatchResult, DashboardDefinition, DashboardDetail, DashboardPlan } from '../types';

export default function DashboardViewPage({ accessToken, dashboardId, fullscreen = false, navigate, versionId }: { accessToken: string; dashboardId: string; fullscreen?: boolean; navigate: (path: string) => void; versionId?: string }) {
  const api = useMemo(() => new DashboardApi(accessToken), [accessToken]);
  const [detail, setDetail] = useState<DashboardDetail>();
  const [definition, setDefinition] = useState<DashboardDefinition>();
  const [plan, setPlan] = useState<DashboardPlan>();
  const [pageId, setPageId] = useState('');
  const [filters, setFilters] = useState<Record<string, unknown>>({});
  const [result, setResult] = useState<DashboardBatchResult>();
  const [loading, setLoading] = useState(false);
  const [toast, context] = message.useMessage();
  useEffect(() => { void Promise.all([api.detail(dashboardId), api.plan(dashboardId), versionId ? api.version(dashboardId, versionId) : Promise.resolve(undefined)]).then(([value, queryPlan, historical]) => { const selected = historical?.definition ?? value.currentVersion?.definition; setDetail(value); setPlan(queryPlan); setDefinition(selected); setPageId(selected?.pages.sort((a,b) => a.order-b.order)[0]?.id ?? ''); }).catch((error: Error) => void toast.error(error.message)); }, [api, dashboardId, toast, versionId]);
  const run = useCallback(async (nextFilters: Record<string, unknown>) => { if (!definition || !plan || !pageId) return; const ids = definition.widgets.filter((item) => item.pageId === pageId).map((item) => item.id); if (!ids.length) return; setLoading(true); try { setResult(await api.execute(plan.id, pageId, ids, nextFilters)); } catch (error) { void toast.error((error as Error).message); } finally { setLoading(false); } }, [api, definition, pageId, plan, toast]);
  useEffect(() => { if (definition && plan && pageId && !versionId) void run({}); }, [definition, pageId, plan, run, versionId]);
  if (!detail || !definition) return <Skeleton active />;
  const crossFilter = (value: unknown) => { const first = definition.filters[0]; if (!first) { void toast.info('该组件未映射交叉筛选变量'); return; } const next = { ...filters, [first.id]: value }; setFilters(next); void run(next); };
  return <div className={fullscreen ? 'dashboard-view fullscreen' : 'dashboard-view'}>{context}{versionId && <Alert message="历史定义预览" description="这是历史看板定义，不是历史数据；仍使用当前权限重新查询。" showIcon type="warning" />}<div className="dashboard-view-header"><div><Space><Typography.Title level={fullscreen ? 2 : 3}>{detail.summary.name}</Typography.Title><Tag color="green">v{detail.currentVersion?.version}</Tag><Tag>{result?.status ?? '待查询'}</Tag></Space><Typography.Paragraph type="secondary">{detail.summary.description || '无说明'} · 负责人 {detail.summary.ownerName}</Typography.Paragraph></div><Space><Button aria-label="收藏" icon={detail.summary.favorite ? <StarFilled /> : <StarOutlined />} onClick={() => void api.favorite(dashboardId, !detail.summary.favorite).then(() => api.detail(dashboardId)).then(setDetail)} /><Button icon={<ReloadOutlined spin={loading} />} onClick={() => void run(filters)}>刷新</Button><Button icon={<PrinterOutlined />} onClick={() => window.print()}>打印</Button>{!fullscreen && <Button icon={<ExpandOutlined />} onClick={() => navigate(`/apps/dashboards/${dashboardId}/fullscreen`)}>全屏</Button>}{detail.accessRole !== 'VIEWER' && !versionId && <Button icon={<EditOutlined />} onClick={() => navigate(`/apps/dashboards/${dashboardId}/edit`)}>编辑</Button>}<Button onClick={() => navigate(`/apps/dashboards/${dashboardId}/versions`)}>版本</Button></Space></div>
    <DashboardFilterBar filters={definition.filters} onApply={(values) => { setFilters(values); void run(values); }} values={filters} />
    <Tabs activeKey={pageId} items={definition.pages.sort((a,b) => a.order-b.order).map((page) => ({ key: page.id, label: page.name, children: <DashboardRuntime definition={definition} onCrossFilter={crossFilter} onOpenObject={(typeId, objectId) => navigate(`/ontology/explorer/${typeId}/${encodeURIComponent(objectId)}`)} pageId={page.id} result={page.id === pageId ? result : undefined} /> }))} onChange={(value) => { setPageId(value); setResult(undefined); }} />
  </div>;
}
