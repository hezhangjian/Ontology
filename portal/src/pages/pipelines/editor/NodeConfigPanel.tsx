import { Alert, Checkbox, Divider, Input, InputNumber, Select, Space, Switch, Typography } from 'antd';
import type { Pipeline, PipelineNode, RuntimeSettings, ScheduleSettings } from '../types';

const { Text } = Typography;

interface Props {
  pipeline: Pipeline;
  node?: PipelineNode;
  onNodeChange: (node: PipelineNode) => void;
  onRuntimeChange: (runtime: RuntimeSettings) => void;
  onScheduleChange: (schedule: ScheduleSettings) => void;
}

export default function NodeConfigPanel({ pipeline, node, onNodeChange, onRuntimeChange, onScheduleChange }: Props) {
  if (!node) return <aside className="pipeline-config-panel">
    <div className="editor-panel-title">运行与调度</div>
    <ConfigLabel label="并行度"><InputNumber max={4} min={1} onChange={(value) => onRuntimeChange({ ...pipeline.draft!.runtime, parallelism: value ?? 1 })} value={pipeline.draft?.runtime.parallelism} /></ConfigLabel>
    <ConfigLabel label="重试次数"><InputNumber max={10} min={0} onChange={(value) => onRuntimeChange({ ...pipeline.draft!.runtime, restartAttempts: value ?? 0 })} value={pipeline.draft?.runtime.restartAttempts} /></ConfigLabel>
    {pipeline.mode === 'STREAMING' && <>
      <ConfigLabel label="Checkpoint 间隔（毫秒）"><InputNumber min={10000} onChange={(value) => onRuntimeChange({ ...pipeline.draft!.runtime, checkpointIntervalMs: value ?? 60000 })} value={pipeline.draft?.runtime.checkpointIntervalMs} /></ConfigLabel>
      <ConfigLabel label="初始消费位置"><Select onChange={(offsetPolicy) => onRuntimeChange({ ...pipeline.draft!.runtime, offsetPolicy })} options={['EARLIEST', 'LATEST', 'TIMESTAMP', 'SPECIFIC_OFFSETS'].map((value) => ({ label: value, value }))} value={pipeline.draft?.runtime.offsetPolicy} /></ConfigLabel>
      <ConfigLabel label="事件时间字段"><Input onChange={(event) => onRuntimeChange({ ...pipeline.draft!.runtime, eventTimeField: event.target.value })} value={pipeline.draft?.runtime.eventTimeField} /></ConfigLabel>
      <Alert message="流任务默认 stop-with-savepoint；危险消费位置重置需 Admin 确认。" showIcon type="info" />
    </>}
    {pipeline.mode === 'BATCH' && <>
      <Divider />
      <ConfigLabel label="启用调度"><Switch checked={pipeline.draft?.schedule.enabled} onChange={(enabled) => onScheduleChange({ ...pipeline.draft!.schedule, enabled })} /></ConfigLabel>
      {pipeline.draft?.schedule.enabled && <>
        <ConfigLabel label="调度类型"><Select onChange={(type) => onScheduleChange({ ...pipeline.draft!.schedule, type })} options={[{ label: 'Cron', value: 'CRON' }, { label: '指定时间', value: 'AT' }]} value={pipeline.draft.schedule.type} /></ConfigLabel>
        {pipeline.draft.schedule.type === 'CRON' ? <ConfigLabel label="Cron（UTC）"><Input onChange={(event) => onScheduleChange({ ...pipeline.draft!.schedule, cronExpression: event.target.value })} placeholder="0 0 * * * *" value={pipeline.draft.schedule.cronExpression} /></ConfigLabel> : <ConfigLabel label="执行时间"><Input onChange={(event) => onScheduleChange({ ...pipeline.draft!.schedule, runAt: event.target.value })} placeholder="2026-07-21T01:00:00Z" value={pipeline.draft.schedule.runAt} /></ConfigLabel>}
        <ConfigLabel label="并发策略"><Select onChange={(concurrencyPolicy) => onScheduleChange({ ...pipeline.draft!.schedule, concurrencyPolicy })} options={[{ label: '跳过', value: 'SKIP' }, { label: '排队', value: 'QUEUE' }, { label: '取消旧运行', value: 'CANCEL_PREVIOUS' }]} value={pipeline.draft.schedule.concurrencyPolicy} /></ConfigLabel>
      </>}
    </>}
  </aside>;

  const config = node.config;
  const update = (values: Record<string, unknown>) => onNodeChange({ ...node, config: { ...config, ...values } });
  const availableFields = node.inputSchema.map((field) => ({ label: `${field.name} · ${field.type}`, value: field.name }));
  return <aside className="pipeline-config-panel">
    <div className="editor-panel-title">节点配置</div>
    <Space direction="vertical" size={4}><Text strong>{node.name}</Text><Text code>{node.type}</Text></Space>
    <ConfigLabel label="节点名称"><Input onChange={(event) => onNodeChange({ ...node, name: event.target.value })} value={node.name} /></ConfigLabel>
    {node.invalidReasons.length > 0 && <Alert description={node.invalidReasons.join('；')} message="节点配置无效" showIcon type="error" />}
    {node.type === 'SOURCE' && <Alert description={`${pipeline.dataSourceName} / ${pipeline.sourceAssetName}`} message="受控源资产" showIcon type="info" />}
    {node.type === 'SELECT' && <ConfigLabel label="保留字段"><Select mode="multiple" onChange={(values: string[]) => update({ fields: values.map((field) => ({ source: field, target: field })) })} options={availableFields} value={Array.isArray(config.fields) ? (config.fields as Array<{ source: string }>).map((field) => field.source) : []} /></ConfigLabel>}
    {node.type === 'CAST' && <><ConfigLabel label="字段"><Select onChange={(field) => update({ field })} options={availableFields} value={config.field as string} /></ConfigLabel><ConfigLabel label="目标类型"><Select onChange={(type) => update({ casts: { [String(config.field ?? '')]: type } })} options={['STRING', 'BOOLEAN', 'INT', 'LONG', 'DECIMAL', 'DATE', 'TIMESTAMP', 'JSON'].map((value) => ({ label: value, value }))} /></ConfigLabel><ConfigLabel label="失败策略"><Select onChange={(failurePolicy) => update({ failurePolicy })} options={[{ label: '停止', value: 'STOP' }, { label: '置空', value: 'NULL' }, { label: '隔离', value: 'QUARANTINE' }]} value={config.failurePolicy as string ?? 'STOP'} /></ConfigLabel></>}
    {node.type === 'FILTER' && <><ConfigLabel label="字段"><Select onChange={(field) => update({ field })} options={availableFields} value={config.field as string} /></ConfigLabel><ConfigLabel label="条件"><Select onChange={(operator) => update({ operator })} options={['EQUALS', 'NOT_EQUALS', 'CONTAINS', 'GREATER_THAN', 'LESS_THAN', 'IS_NULL', 'IS_NOT_NULL'].map((value) => ({ label: value, value }))} value={config.operator as string ?? 'EQUALS'} /></ConfigLabel><ConfigLabel label="比较值"><Input onChange={(event) => update({ value: event.target.value })} value={String(config.value ?? '')} /></ConfigLabel></>}
    {node.type === 'DERIVE' && <><ConfigLabel label="字段名称"><Input onChange={(event) => update({ name: event.target.value })} value={String(config.name ?? '')} /></ConfigLabel><ConfigLabel label="安全运算"><Select onChange={(operation) => update({ operation })} options={['CONSTANT', 'CONCAT', 'COALESCE'].map((value) => ({ label: value, value }))} value={config.operation as string ?? 'CONSTANT'} /></ConfigLabel><ConfigLabel label="值 / 左字段"><Input onChange={(event) => update({ value: event.target.value, left: event.target.value })} value={String(config.value ?? config.left ?? '')} /></ConfigLabel></>}
    {node.type === 'DEDUPLICATE' && <ConfigLabel label="去重键"><Select mode="multiple" onChange={(keys) => update({ keys })} options={availableFields} value={config.keys as string[] ?? []} /></ConfigLabel>}
    {node.type === 'JOIN' && <><ConfigLabel label="JOIN 类型"><Select onChange={(joinType) => update({ joinType })} options={[{ label: 'INNER', value: 'INNER' }, { label: 'LEFT', value: 'LEFT' }]} value={config.joinType as string ?? 'LEFT'} /></ConfigLabel>{pipeline.mode === 'STREAMING' && <Checkbox checked={Boolean(config.boundedDimension)} onChange={(event) => update({ boundedDimension: event.target.checked })}>辅助输入是有界维表</Checkbox>}</>}
    {node.type === 'WINDOW' && <><ConfigLabel label="窗口类型"><Select onChange={(windowType) => update({ windowType })} options={['TUMBLING', 'SLIDING', 'SESSION'].map((value) => ({ label: value, value }))} value={config.windowType as string ?? 'TUMBLING'} /></ConfigLabel><ConfigLabel label="窗口大小（毫秒）"><InputNumber min={1000} onChange={(windowSizeMs) => update({ windowSizeMs })} value={config.windowSizeMs as number ?? 60000} /></ConfigLabel></>}
    {node.type === 'AGGREGATE' && <><ConfigLabel label="Group By"><Select mode="multiple" onChange={(groupBy) => update({ groupBy })} options={availableFields} value={config.groupBy as string[] ?? []} /></ConfigLabel><ConfigLabel label="指标"><Select onChange={(aggregation) => update({ aggregation })} options={['COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'COUNT_DISTINCT'].map((value) => ({ label: value, value }))} value={config.aggregation as string ?? 'COUNT'} /></ConfigLabel></>}
    {node.type === 'QUALITY' && <><ConfigLabel label="字段"><Select onChange={(field) => update({ field })} options={availableFields} value={config.field as string} /></ConfigLabel><ConfigLabel label="规则"><Select onChange={(rule) => update({ rule })} options={['NOT_NULL', 'TYPE', 'RANGE', 'REGEX', 'UNIQUE', 'REFERENCE'].map((value) => ({ label: value, value }))} value={config.rule as string ?? 'NOT_NULL'} /></ConfigLabel><ConfigLabel label="失败动作"><Select onChange={(failureAction) => update({ failureAction })} options={[{ label: '停止', value: 'STOP' }, { label: '跳过', value: 'SKIP' }, { label: '受限隔离', value: 'QUARANTINE' }]} value={config.failureAction as string ?? 'STOP'} /></ConfigLabel></>}
    {node.type === 'ONTOLOGY_OBJECT' && <><ConfigLabel label="对象类型 ID"><Input onChange={(event) => update({ objectTypeId: event.target.value })} value={String(config.objectTypeId ?? '')} /></ConfigLabel><ConfigLabel label="对象 ID 字段"><Select onChange={(idField) => update({ idField })} options={availableFields} value={config.idField as string} /></ConfigLabel><ConfigLabel label="属性映射"><Input onChange={(event) => update({ mappings: parseMappings(event.target.value) })} placeholder="displayName=name,department=department" value={formatMappings(config.mappings)} /></ConfigLabel></>}
    {node.type === 'ONTOLOGY_RELATION' && <><ConfigLabel label="关系类型 ID"><Input onChange={(event) => update({ relationTypeId: event.target.value })} value={String(config.relationTypeId ?? '')} /></ConfigLabel><ConfigLabel label="源对象类型"><Input onChange={(event) => update({ sourceObjectTypeId: event.target.value })} value={String(config.sourceObjectTypeId ?? '')} /></ConfigLabel><ConfigLabel label="源对象 ID 字段"><Select onChange={(sourceIdField) => update({ sourceIdField })} options={availableFields} value={config.sourceIdField as string} /></ConfigLabel><ConfigLabel label="目标对象类型"><Input onChange={(event) => update({ targetObjectTypeId: event.target.value })} value={String(config.targetObjectTypeId ?? '')} /></ConfigLabel><ConfigLabel label="目标对象 ID 字段"><Select onChange={(targetIdField) => update({ targetIdField })} options={availableFields} value={config.targetIdField as string} /></ConfigLabel></>}
    <Divider />
    <Text type="secondary">输入 Schema：{node.inputSchema.length} 字段 · 输出 Schema：{node.outputSchema.length} 字段</Text>
  </aside>;
}

function ConfigLabel({ children, label }: { children: React.ReactNode; label: string }) {
  return <label className="pipeline-config-field"><span>{label}</span>{children}</label>;
}

function parseMappings(value: string) {
  return Object.fromEntries(value.split(',').map((part) => part.trim()).filter(Boolean).map((part) => {
    const [property, field] = part.split('=').map((item) => item.trim());
    return [property, field];
  }).filter(([property, field]) => property && field));
}

function formatMappings(value: unknown) {
  return value && typeof value === 'object' ? Object.entries(value).map(([property, field]) => `${property}=${String(field)}`).join(',') : '';
}
