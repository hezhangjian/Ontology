import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Form, Input, InputNumber, Select, Space, Switch, Typography } from 'antd';
import { useState } from 'react';
import type { ObjectTypeDefinition } from '../../../ontology/explorer/explorer.types';
import type { Dataset } from '../../../../pages/datasets/types';
import type { DashboardAggregation, DashboardDataSource, DashboardMeasureConfig, DashboardWidget, DashboardWidgetFilterConfig } from '../types';

const aggregations: Array<{ value: DashboardAggregation; label: string }> = [
  { value: 'count', label: '计数' },
  { value: 'sum', label: '求和' },
  { value: 'avg', label: '平均值' },
  { value: 'min', label: '最小值' },
  { value: 'max', label: '最大值' },
  { value: 'approx_distinct', label: '去重计数' },
  { value: 'sum_per_distinct', label: '求和 ÷ 去重计数（人均）' },
];

const timeGrains = [
  { value: 'NONE', label: '原始值' }, { value: 'DAY', label: '按日' }, { value: 'WEEK', label: '按周' },
  { value: 'MONTH', label: '按月' }, { value: 'QUARTER', label: '按季度' }, { value: 'YEAR', label: '按年' },
];

export default function WidgetInspector({ dataSources, datasets, loadFieldValues, objectTypes, onChange, onDelete, widget }: { dataSources: DashboardDataSource[]; datasets: Dataset[]; loadFieldValues: (datasetId: string, field: string) => Promise<string[]>; objectTypes: ObjectTypeDefinition[]; onChange: (widget: DashboardWidget) => void; onDelete: () => void; widget?: DashboardWidget }) {
  const [filterOptions, setFilterOptions] = useState<Record<string, string[]>>({});
  const [loadingOptions, setLoadingOptions] = useState<Record<string, boolean>>({});
  if (!widget) return <div className="inspector-empty">点选画布中的图表进行设置</div>;
  const source = dataSources.find((item) => item.id === widget.dataSourceId);
  const properties = objectTypes.find((item) => item.id === source?.objectTypeId)?.properties ?? [];
  const datasetFields = datasets.find((item) => item.id === source?.datasetId)?.fields ?? [];
  const propertyOptions = source?.kind === 'DATASET'
    ? datasetFields.map((item) => ({ value: item.name, label: `${item.name} · ${item.type}` }))
    : properties.filter((item) => !item.sensitive).map((item) => ({ value: item.id, label: item.displayName }));
  const chart = ['AREA', 'BAR', 'DONUT', 'LINE', 'PIE', 'PIVOT', 'STACKED_BAR'].includes(widget.type);
  const measures = normalizeMeasures(widget);
  const filters = normalizeFilters(widget);
  const updateConfig = (patch: Record<string, unknown>) => onChange({ ...widget, config: { ...widget.config, ...patch } });
  const updateMeasures = (next: DashboardMeasureConfig[]) => updateConfig({ measures: next });
  const updateMeasure = (id: string, patch: Partial<DashboardMeasureConfig>) => updateMeasures(measures.map((item) => item.id === id ? { ...item, ...patch } : item));
  const updateFilters = (next: DashboardWidgetFilterConfig[]) => updateConfig({ filters: next });
  const updateFilter = (id: string, patch: Partial<DashboardWidgetFilterConfig>) => updateFilters(filters.map((item) => item.id === id ? { ...item, ...patch } : item));
  const ensureFilterOptions = async (field?: string) => {
    const datasetId = source?.datasetId;
    if (!field || !datasetId) return;
    const key = `${datasetId}:${field}`;
    if (filterOptions[key] || loadingOptions[key]) return;
    setLoadingOptions((current) => ({ ...current, [key]: true }));
    try {
      const values = await loadFieldValues(datasetId, field);
      setFilterOptions((current) => ({ ...current, [key]: values }));
    } catch {
      setFilterOptions((current) => ({ ...current, [key]: [] }));
    } finally {
      setLoadingOptions((current) => ({ ...current, [key]: false }));
    }
  };

  return <Form layout="vertical">
    <div className="editor-section-title">图表设置</div>
    <Form.Item label="标题"><Input onChange={(event) => onChange({ ...widget, title: event.target.value })} value={widget.title} /></Form.Item>
    {!['MARKDOWN', 'SECTION'].includes(widget.type) && <Form.Item label="数据"><Select onChange={(value) => onChange({ ...widget, dataSourceId: value, config: defaultConfig(widget.type) })} options={dataSources.map((item) => ({ value: item.id, label: item.name }))} value={widget.dataSourceId} /></Form.Item>}

    {chart && <>
      <div className="editor-section-title">坐标轴与系列</div>
      <Form.Item label="横轴（X）"><Select allowClear onChange={(value) => updateConfig({ xField: value })} options={propertyOptions} placeholder="选择日期、部门、组长等字段" showSearch value={widget.config.xField as string | undefined ?? legacyDimension(widget, 0)} /></Form.Item>
      <Form.Item label="横轴时间粒度" tooltip="非日期字段保持“原始值”即可"><Select onChange={(value) => updateConfig({ xTimeGrain: value })} options={timeGrains} value={String(widget.config.xTimeGrain ?? 'NONE')} /></Form.Item>
      {!['PIE', 'DONUT'].includes(widget.type) && <><Form.Item label="系列（颜色）"><Select allowClear onChange={(value) => updateConfig({ seriesField: value })} options={propertyOptions} placeholder="例如组长、组员、月份" showSearch value={widget.config.seriesField as string | undefined ?? legacyDimension(widget, 1)} /></Form.Item>{Boolean(widget.config.seriesField ?? legacyDimension(widget, 1)) && <Form.Item label="系列时间粒度"><Select onChange={(value) => updateConfig({ seriesTimeGrain: value })} options={timeGrains} value={String(widget.config.seriesTimeGrain ?? 'NONE')} /></Form.Item>}</>}
      {widget.type === 'PIVOT' && <Form.Item label="第三级分组"><Select allowClear onChange={(value) => updateConfig({ groupField: value })} options={propertyOptions} showSearch value={widget.config.groupField as string | undefined ?? legacyDimension(widget, 2)} /></Form.Item>}
    </>}

    {!['MARKDOWN', 'SECTION', 'OBJECT_TABLE'].includes(widget.type) && <>
      <div className="editor-section-title"><span>纵轴（Y）</span>{chart && source?.kind === 'DATASET' && measures.length < 4 && <Button icon={<PlusOutlined />} onClick={() => updateMeasures([...measures, newMeasure(measures.length)])} size="small">添加纵轴</Button>}</div>
      {source?.kind !== 'DATASET' && measures.length > 1 && <Alert message="业务对象图表当前执行第一个纵轴；Dataset 支持同图多纵轴" showIcon type="info" />}
      {measures.map((measure, index) => <Card className="dashboard-config-card" key={measure.id} size="small" title={`纵轴 ${index + 1}`} extra={measures.length > 1 && <Button aria-label="删除纵轴" danger icon={<DeleteOutlined />} onClick={() => updateMeasures(measures.filter((item) => item.id !== measure.id))} size="small" type="text" />}>
        <Form.Item label="纵轴名称"><Input onChange={(event) => updateMeasure(measure.id, { label: event.target.value })} value={measure.label} /></Form.Item>
        <Form.Item label="聚合方式"><Select onChange={(value) => updateMeasure(measure.id, { aggregation: value })} options={aggregations} value={measure.aggregation} /></Form.Item>
        {measure.aggregation !== 'count' && <Form.Item label="纵轴字段"><Select onChange={(value) => updateMeasure(measure.id, { field: value })} options={propertyOptions} placeholder="选择数值字段" showSearch value={measure.field} /></Form.Item>}
        {measure.aggregation === 'sum_per_distinct' && <Form.Item label="分母去重字段"><Select onChange={(value) => updateMeasure(measure.id, { divisorField: value })} options={propertyOptions} placeholder="例如员工 ID" showSearch value={measure.divisorField} /></Form.Item>}
      </Card>)}
    </>}

    {source?.kind === 'DATASET' && !['MARKDOWN', 'SECTION'].includes(widget.type) && <>
      <div className="editor-section-title"><span>图表内筛选</span><Button icon={<PlusOutlined />} onClick={() => updateFilters([...filters, { id: crypto.randomUUID(), operator: 'EQUALS', values: [] }])} size="small">添加</Button></div>
      {filters.map((filter) => <Card className="dashboard-config-card" key={filter.id} size="small" extra={<Button aria-label="删除筛选" danger icon={<DeleteOutlined />} onClick={() => updateFilters(filters.filter((item) => item.id !== filter.id))} size="small" type="text" />}>
        <Form.Item label="字段"><Select onChange={(value) => updateFilter(filter.id, { field: value, values: [] })} options={propertyOptions} showSearch value={filter.field} /></Form.Item>
        <Form.Item label="条件"><Select onChange={(value) => updateFilter(filter.id, { comparisonField: undefined, operator: value, values: [] })} options={[{ value: 'EQUALS', label: '等于固定值' }, { value: 'FIELD_EQUALS', label: '等于另一字段' }, { value: 'IN', label: '属于任一值' }, { value: 'NOT_IN', label: '不属于' }]} value={filter.operator} /></Form.Item>
        {filter.operator === 'FIELD_EQUALS'
          ? <Form.Item label="比较字段"><Select allowClear onChange={(value) => updateFilter(filter.id, { comparisonField: value })} options={propertyOptions.filter((option) => option.value !== filter.field)} placeholder="例如：组长名字" showSearch value={filter.comparisonField} /></Form.Item>
          : <Form.Item label="值"><Select allowClear loading={Boolean(loadingOptions[`${source.datasetId}:${filter.field}`])} mode={filter.operator === 'EQUALS' ? undefined : 'multiple'} onChange={(value) => updateFilter(filter.id, { values: Array.isArray(value) ? value : value ? [value] : [] })} onOpenChange={(open) => { if (open) void ensureFilterOptions(filter.field); }} optionFilterProp="label" options={(filterOptions[`${source.datasetId}:${filter.field}`] ?? []).map((value) => ({ label: value, value }))} placeholder={filter.field ? '选择字段值' : '请先选择字段'} showSearch value={filter.operator === 'EQUALS' ? filter.values[0] : filter.values} /></Form.Item>}
      </Card>)}
    </>}

    {['MARKDOWN', 'SECTION'].includes(widget.type) && <Form.Item label="文字"><Input.TextArea onChange={(event) => updateConfig({ markdown: event.target.value })} rows={5} value={String(widget.config.markdown ?? '')} /></Form.Item>}
    <div className="editor-section-title">展示与交互</div>
    <Form.Item label="宽度"><InputNumber max={24} min={1} onChange={(value) => onChange({ ...widget, layout: { ...widget.layout, desktop: { ...widget.layout.desktop, w: value ?? 12 } } })} value={widget.layout.desktop.w} /></Form.Item>
    <Form.Item label="点击后查看明细"><Switch checked={Boolean(widget.interaction.drilldown)} onChange={(value) => onChange({ ...widget, interaction: { ...widget.interaction, drilldown: value } })} /></Form.Item>
    <Space><Button danger onClick={onDelete}>删除图表</Button></Space>
    <Typography.Paragraph className="dashboard-config-hint" type="secondary">字段直接来自当前数据，无需预先配置指标。日期字段可在横轴上按日、周、月、季度或年自动分组。</Typography.Paragraph>
  </Form>;
}

function defaultConfig(type: string) {
  if (type === 'MARKDOWN') return { markdown: '输入说明文本' };
  if (type === 'OBJECT_TABLE') return {};
  return { measures: [newMeasure(0)], xTimeGrain: 'NONE' };
}

function newMeasure(index: number): DashboardMeasureConfig {
  return { id: crypto.randomUUID(), label: index === 0 ? '指标值' : `指标 ${index + 1}`, aggregation: 'count' };
}

function normalizeMeasures(widget: DashboardWidget): DashboardMeasureConfig[] {
  if (Array.isArray(widget.config.measures) && widget.config.measures.length) return widget.config.measures as DashboardMeasureConfig[];
  const aggregation = String(widget.config.aggregation ?? 'count') as DashboardAggregation;
  return [{ id: 'value', label: '指标值', aggregation, field: String(widget.config.measurePropertyId ?? widget.config.propertyId ?? '') || undefined, divisorField: String(widget.config.divisorPropertyId ?? '') || undefined }];
}

function normalizeFilters(widget: DashboardWidget): DashboardWidgetFilterConfig[] {
  return Array.isArray(widget.config.filters) ? widget.config.filters as DashboardWidgetFilterConfig[] : [];
}

function legacyDimension(widget: DashboardWidget, index: number) {
  const values = Array.isArray(widget.config.dimensionPropertyIds) ? widget.config.dimensionPropertyIds as string[] : widget.config.dimensionPropertyId ? [String(widget.config.dimensionPropertyId)] : [];
  return values[index];
}
