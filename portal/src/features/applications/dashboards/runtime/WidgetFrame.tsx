import { Area, Column, Line, Pie } from '@ant-design/charts';
import { Alert, Card, Empty, Space, Spin, Table, Tag, Typography } from 'antd';
import type { DashboardMeasureConfig, DashboardWidget, DashboardWidgetResult } from '../types';

type Bucket = { label: unknown; value: number | null; suppressed: boolean; dimensions?: Record<string, unknown> };

export default function WidgetFrame({ result, widget, onOpenObject }: { result?: DashboardWidgetResult; widget: DashboardWidget; onCrossFilter: (value: unknown) => void; onOpenObject: (typeId: string, objectId: string) => void }) {
  if (!result) return <Card className="dashboard-widget" title={widget.title}><Spin tip="加载组件" /></Card>;
  if (result.status === 'FAILED') return <Card className="dashboard-widget" title={widget.title}><Alert description={result.safeError} message="组件查询失败" showIcon type="error" /><Typography.Text code>{result.correlationId}</Typography.Text></Card>;
  return <Card className="dashboard-widget" extra={<Space><Tag>{result.cacheHit ? '缓存' : '实时'}</Tag>{result.suppressed && <Tag color="gold">含抑制值</Tag>}</Space>} title={widget.title}>
    {renderWidget(widget, result, onOpenObject)}<div className="widget-watermark">水位 {new Date(result.watermark).toLocaleTimeString()} · {result.correlationId.slice(0, 8)}</div>
  </Card>;
}

function renderWidget(widget: DashboardWidget, result: DashboardWidgetResult, onOpenObject: (typeId: string, objectId: string) => void) {
  if (widget.type === 'METRIC') return renderMetric(widget, result.data);
  if (['AREA','BAR','DONUT','LINE','PIE','STACKED_BAR'].includes(widget.type)) return renderChart(widget, result.data);
  if (widget.type === 'PIVOT') return renderPivot(widget, result.data);
  if (widget.type === 'OBJECT_TABLE') {
    const data = result.data as { items?: Array<Record<string, unknown> & { objectId?: string; title?: string; objectTypeId?: string; properties?: Record<string, unknown> }>; visibleCount?: number; columns?: string[] };
    const items = data?.items ?? [];
    if (!items.length) return <Empty description="当前权限和筛选下无数据" />;
    if (data.columns?.length) return <><Typography.Text type="secondary">共 {data.visibleCount ?? 0} 行</Typography.Text><Table columns={data.columns.slice(0, 12).map((field) => ({ title: field, dataIndex: field, ellipsis: true }))} dataSource={items} pagination={false} rowKey={(_, index) => String(index)} scroll={{ x: 'max-content' }} size="small" /></>;
    return <><Typography.Text type="secondary">授权后共 {data.visibleCount ?? 0} 个对象</Typography.Text><Table columns={[{title:'对象',dataIndex:'title',render:(value:string,item) => <button className="link-button" onClick={() => onOpenObject(String(item.objectTypeId),String(item.objectId))}>{value}</button>},{title:'属性',dataIndex:'properties',render:(value:Record<string,unknown>) => Object.entries(value).slice(0,3).map(([key,item]) => `${key}: ${String(item)}`).join(' · ')}]} dataSource={items} pagination={false} rowKey={(item) => String(item.objectId)} size="small" /></>;
  }
  if (widget.type === 'MARKDOWN' || widget.type === 'SECTION') return <Typography.Paragraph>{String((result.data as Record<string, unknown>)?.markdown ?? widget.config.markdown ?? widget.description)}</Typography.Paragraph>;
  return <Empty description="组件暂无可显示结果" />;
}

function renderMetric(widget: DashboardWidget, raw: unknown) {
  const data = raw as { value?: number; values?: Record<string, unknown>; metricFields?: string[] };
  const measures = measureConfigs(widget);
  const values = data.values ?? { [measures[0]?.id ?? 'value']: data.value ?? 0 };
  return <div className="metric-values">{measures.map((measure) => <div className="metric-widget" key={measure.id}><strong>{formatNumber(values[measure.id])}</strong><span>{measure.label}</span></div>)}</div>;
}

function renderChart(widget: DashboardWidget, raw: unknown) {
  const type = widget.type;
  const query = raw as { buckets?: Bucket[]; rows?: Record<string, unknown>[]; dimensionFields?: string[]; metricFields?: string[] };
  if (query.rows?.length && query.metricFields?.length) {
    const measures = measureConfigs(widget);
    const labels = new Map(measures.map((item) => [item.id, item.label]));
    const data = query.rows.flatMap((row) => query.metricFields!.map((metric) => {
      const seriesParts = [row.series, row.group, query.metricFields!.length > 1 ? labels.get(metric) ?? metric : undefined].filter((item) => item != null && String(item) !== '');
      return { label: String(row.x ?? ''), series: seriesParts.join(' · '), value: Number(row[metric] ?? 0), metric };
    }));
    if (!data.length) return <Empty description="当前筛选下无数据" />;
    if (type === 'PIE' || type === 'DONUT') return <Pie data={data.filter((item) => item.metric === query.metricFields![0])} angleField="value" colorField="label" innerRadius={type === 'DONUT' ? 0.55 : 0} />;
    const common = { data, xField: 'label', yField: 'value' };
    if (type === 'LINE') return <Line {...common} colorField={data.some((item) => item.series) ? 'series' : undefined} point />;
    if (type === 'AREA') return <Area {...common} colorField={data.some((item) => item.series) ? 'series' : undefined} />;
    return <Column {...common} colorField={data.some((item) => item.series) ? 'series' : undefined} group={type === 'BAR' && data.some((item) => item.series)} stack={type === 'STACKED_BAR'} />;
  }
  const buckets = ((raw as { buckets?: Bucket[] })?.buckets ?? []).filter((item) => !item.suppressed && item.value != null);
  if (!buckets.length) return <Empty description="当前权限和筛选下无数据" />;
  const data = buckets.map((item) => {
    const values = Object.values(item.dimensions ?? {});
    return { label: String(values[0] ?? item.label), series: String(values[1] ?? ''), value: Number(item.value), rawLabel: item.label };
  });
  const common = { data, xField: 'label', yField: 'value' };
  if (type === 'LINE') return <Line {...common} colorField={data.some((item) => item.series) ? 'series' : undefined} point />;
  if (type === 'AREA') return <Area {...common} colorField={data.some((item) => item.series) ? 'series' : undefined} />;
  if (type === 'PIE' || type === 'DONUT') return <Pie data={data} angleField="value" colorField="label" innerRadius={type === 'DONUT' ? 0.55 : 0} />;
  return <Column {...common} colorField={data.some((item) => item.series) ? 'series' : 'label'} group={type === 'BAR' && data.some((item) => item.series)} stack={type === 'STACKED_BAR'} />;
}

function renderPivot(widget: DashboardWidget, raw: unknown) {
  const data = raw as { rows?: Record<string, unknown>[]; dimensionFields?: string[]; metricFields?: string[] };
  const rows = data?.rows ?? [];
  const dimensions = data?.dimensionFields ?? [];
  const labels = new Map(measureConfigs(widget).map((item) => [item.id, item.label]));
  const metrics = data?.metricFields ?? ['value'];
  if (!rows.length) return <Empty description="当前权限和筛选下无数据" />;
  return <Table bordered columns={[...dimensions.map((field) => ({ title: dimensionLabel(field), dataIndex: field })), ...metrics.map((field) => ({ title: labels.get(field) ?? field, dataIndex: field, render: (value: unknown) => formatNumber(value) }))]} dataSource={rows} pagination={false} rowKey={(_, index) => String(index)} scroll={{ x: 'max-content' }} size="small" />;
}

function measureConfigs(widget: DashboardWidget): DashboardMeasureConfig[] {
  if (Array.isArray(widget.config.measures) && widget.config.measures.length) return widget.config.measures as DashboardMeasureConfig[];
  return [{ id: 'value', label: String(widget.config.unit ?? '指标值'), aggregation: String(widget.config.aggregation ?? 'count') as DashboardMeasureConfig['aggregation'] }];
}

function dimensionLabel(field: string) { return field === 'x' ? '横轴' : field === 'series' ? '系列' : field === 'group' ? '分组' : field; }
function formatNumber(value: unknown) { return Number(value ?? 0).toLocaleString(undefined, { maximumFractionDigits: 2 }); }
