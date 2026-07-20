import { Alert, Card, Empty, Progress, Space, Spin, Table, Tag, Typography } from 'antd';
import type { DashboardWidget, DashboardWidgetResult } from '../types';

export default function WidgetFrame({ result, widget, onCrossFilter, onOpenObject }: { result?: DashboardWidgetResult; widget: DashboardWidget; onCrossFilter: (value: unknown) => void; onOpenObject: (typeId: string, objectId: string) => void }) {
  if (!result) return <Card className="dashboard-widget" title={widget.title}><Spin tip="加载组件" /></Card>;
  if (result.status === 'FAILED') return <Card className="dashboard-widget" title={widget.title}><Alert description={result.safeError} message="组件查询失败" showIcon type="error" /><Typography.Text code>{result.correlationId}</Typography.Text></Card>;
  return <Card className="dashboard-widget" extra={<Space><Tag>{result.cacheHit ? '缓存' : '实时'}</Tag>{result.suppressed && <Tag color="gold">含抑制值</Tag>}</Space>} title={widget.title}>
    {renderWidget(widget, result, onCrossFilter, onOpenObject)}<div className="widget-watermark">水位 {new Date(result.watermark).toLocaleTimeString()} · {result.correlationId.slice(0, 8)}</div>
  </Card>;
}

function renderWidget(widget: DashboardWidget, result: DashboardWidgetResult, onCrossFilter: (value: unknown) => void, onOpenObject: (typeId: string, objectId: string) => void) {
  if (widget.type === 'METRIC') { const data = result.data as { value?: number; aggregation?: string }; return <div className="metric-widget"><strong>{Number(data?.value ?? 0).toLocaleString()}</strong><span>{String(widget.config.unit ?? data?.aggregation ?? '')}</span></div>; }
  if (['BAR','STACKED_BAR','LINE','AREA','PIE','DONUT','PIVOT'].includes(widget.type)) { const data = result.data as { buckets?: Array<{ label: unknown; value: number | null; suppressed: boolean }> }; const buckets = data?.buckets ?? []; if (!buckets.length) return <Empty description="当前权限和筛选下无数据" />; const max = Math.max(1, ...buckets.map((item) => item.value ?? 0)); return <div className={`chart-widget chart-${widget.type.toLowerCase()}`}>{buckets.map((item) => <button disabled={item.suppressed} key={String(item.label)} onClick={() => onCrossFilter(item.label)}><span>{String(item.label)}</span><Progress percent={item.value == null ? 0 : Math.round(item.value / max * 100)} showInfo={false} status={item.suppressed ? 'exception' : 'normal'} /><strong>{item.suppressed ? '已抑制' : item.value}</strong></button>)}</div>; }
  if (widget.type === 'OBJECT_TABLE') { const data = result.data as { items?: Array<{ objectId: string; title: string; objectTypeId: string; properties: Record<string, unknown> }>; visibleCount?: number }; const items = data?.items ?? []; return <><Typography.Text type="secondary">授权后共 {data.visibleCount ?? 0} 个对象</Typography.Text><Table columns={[{title:'对象',dataIndex:'title',render:(value:string,item) => <button className="link-button" onClick={() => onOpenObject(item.objectTypeId,item.objectId)}>{value}</button>},{title:'属性',dataIndex:'properties',render:(value:Record<string,unknown>) => Object.entries(value).slice(0,3).map(([key,item]) => `${key}: ${String(item)}`).join(' · ')}]} dataSource={items} pagination={false} rowKey="objectId" size="small" /></>; }
  if (widget.type === 'MARKDOWN' || widget.type === 'SECTION') return <Typography.Paragraph>{String((result.data as Record<string, unknown>)?.markdown ?? widget.config.markdown ?? widget.description)}</Typography.Paragraph>;
  return <Empty description="组件暂无可显示结果" />;
}
