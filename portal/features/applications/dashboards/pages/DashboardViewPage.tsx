import { ArrowLeftOutlined, CompressOutlined, EditOutlined, ExpandOutlined, PrinterOutlined, ReloadOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Skeleton, message } from '@/shared/components/feedback';
import { Space } from '@/shared/components/layout';
import { Tabs } from '@/shared/components/navigation';
import { Typography } from '@/shared/components/typography';
import { useCallback, useEffect, useMemo, useState } from 'react';
import DashboardFilterBar from '../runtime/DashboardFilterBar';
import DashboardRuntime from '../runtime/DashboardRuntime';
import { DashboardApi } from '../services/dashboardApi';
import type { DashboardBatchResult, DashboardDefinition, DashboardDetail, DashboardPlan } from '../types';

export default function DashboardViewPage({  dashboardId, fullscreen = false, navigate }: { dashboardId: string; fullscreen?: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => new DashboardApi(), []);
  const [detail, setDetail] = useState<DashboardDetail>();
  const [definition, setDefinition] = useState<DashboardDefinition>();
  const [plan, setPlan] = useState<DashboardPlan>();
  const [pageId, setPageId] = useState('');
  const [filters, setFilters] = useState<Record<string, unknown>>({});
  const [result, setResult] = useState<DashboardBatchResult>();
  const [loading, setLoading] = useState(false);
  const [toast, context] = message.useMessage();
  useEffect(() => { void Promise.all([api.detail(dashboardId), api.plan(dashboardId)]).then(([value, queryPlan]) => { const selected = value.currentDefinition; setDetail(value); setPlan(queryPlan); setDefinition(selected); setPageId(selected?.pages.sort((a,b) => a.order-b.order)[0]?.id ?? ''); }).catch((error: Error) => void toast.error(error.message)); }, [api, dashboardId, toast]);
  const run = useCallback(async (nextFilters: Record<string, unknown>) => { if (!definition || !plan || !pageId) return; const ids = definition.widgets.filter((item) => item.pageId === pageId).map((item) => item.id); if (!ids.length) return; setLoading(true); try { setResult(await api.execute(plan.id, pageId, ids, nextFilters)); } catch (error) { void toast.error((error as Error).message); } finally { setLoading(false); } }, [api, definition, pageId, plan, toast]);
  useEffect(() => { if (definition && plan && pageId) void run({}); }, [definition, pageId, plan, run]);
  if (!detail || !definition) return <Skeleton active />;
  const crossFilter = (value: unknown) => { const first = definition.filters[0]; if (!first) { void toast.info('该组件未映射交叉筛选变量'); return; } const next = { ...filters, [first.id]: value }; setFilters(next); void run(next); };
  return <div className={fullscreen ? 'dashboard-view fullscreen' : 'dashboard-view'}>{context}<div className="dashboard-view-header"><div><Space><Typography.Title level={fullscreen ? 2 : 3}>{detail.summary.name}</Typography.Title><Tag color={result?.status === 'SUCCEEDED' ? 'green' : 'default'}>{result?.status === 'SUCCEEDED' ? '数据已更新' : '正在查询'}</Tag></Space><Typography.Paragraph type="secondary">{detail.summary.description || '实时查看本体数据'}</Typography.Paragraph></div><Space>{!fullscreen && <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/apps/dashboards')}>返回列表</Button>}<Button icon={<ReloadOutlined spin={loading} />} onClick={() => void run(filters)}>刷新</Button><Button icon={<PrinterOutlined />} onClick={() => window.print()}>打印</Button>{fullscreen ? <Button icon={<CompressOutlined />} onClick={() => navigate(`/apps/dashboards/${dashboardId}/view`)}>退出全屏</Button> : <Button icon={<ExpandOutlined />} onClick={() => navigate(`/apps/dashboards/${dashboardId}/fullscreen`)}>全屏</Button>}{detail.accessRole !== 'VIEWER' && <Button icon={<EditOutlined />} onClick={() => navigate(`/apps/dashboards/${dashboardId}/edit`)}>编辑</Button>}</Space></div>
    <DashboardFilterBar filters={definition.filters} onApply={(values) => { setFilters(values); void run(values); }} values={filters} />
    <Tabs activeKey={pageId} items={definition.pages.sort((a,b) => a.order-b.order).map((page) => ({ key: page.id, label: page.name, children: <DashboardRuntime definition={definition} onCrossFilter={crossFilter} onOpenObject={(typeId, objectId) => navigate(`/ontology/explorer/${typeId}/${encodeURIComponent(objectId)}`)} pageId={page.id} result={page.id === pageId ? result : undefined} /> }))} onChange={(value) => { setPageId(value); setResult(undefined); }} />
  </div>;
}
