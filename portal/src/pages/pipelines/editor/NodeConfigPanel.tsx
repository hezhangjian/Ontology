import { Alert, Checkbox, Divider, Input, InputNumber, Select, Space, Switch, Typography } from 'antd';
import type { ObjectTypeDefinition } from '../../../features/ontology/explorer/explorer.types';
import type { OntologyResource } from '../../../features/ontology/modeling/ontology.types';
import type { DataSource, DataSourceAsset } from '../../data-connections/types';
import type { Pipeline, PipelineNode, RuntimeSettings, ScheduleSettings } from '../types';

const { Text } = Typography;

interface Props {
  pipeline: Pipeline;
  node?: PipelineNode;
  linkTypes: OntologyResource[];
  objectTypes: ObjectTypeDefinition[];
  connections: DataSource[];
  lookupAssets: Record<string, DataSourceAsset[]>;
  onLookupAssetChange: (connectionId: string, assetId: string) => void;
  onLookupConnectionChange: (connectionId: string) => void;
  onNodeChange: (node: PipelineNode) => void;
  onRuntimeChange: (runtime: RuntimeSettings) => void;
  onScheduleChange: (schedule: ScheduleSettings) => void;
}

export default function NodeConfigPanel({ pipeline, node, linkTypes, objectTypes, connections, lookupAssets, onLookupAssetChange, onLookupConnectionChange, onNodeChange, onRuntimeChange, onScheduleChange }: Props) {
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
  const selectedLookupAssets = lookupAssets[String(config.lookupConnectionId ?? '')] ?? [];
  const selectedLookupAsset = selectedLookupAssets.find((asset) => asset.id === config.lookupAssetId);
  const lookupFieldOptions = (selectedLookupAsset?.fields ?? []).map((field) => ({
    label: `${field.name} · ${field.inferredType}`,
    value: field.name,
  }));
  return <aside className="pipeline-config-panel">
    <div className="editor-panel-title">节点配置</div>
    <Space direction="vertical" size={4}><Text strong>{node.name}</Text><Text code>{node.type}</Text></Space>
    <ConfigLabel label="节点名称"><Input onChange={(event) => onNodeChange({ ...node, name: event.target.value })} value={node.name} /></ConfigLabel>
    {node.invalidReasons.length > 0 && <Alert description={node.invalidReasons.join('；')} message="节点配置无效" showIcon type="error" />}
    {node.type === 'SOURCE' && <Alert description={`${pipeline.dataSourceName} / ${pipeline.sourceAssetName}`} message="受控源资产" showIcon type="info" />}
    {node.type === 'SELECT' && <ConfigLabel label="保留字段"><Select mode="multiple" onChange={(values: string[]) => update({ fields: values.map((field) => ({ source: field, target: field })) })} options={availableFields} value={Array.isArray(config.fields) ? (config.fields as Array<{ source: string }>).map((field) => field.source) : []} /></ConfigLabel>}
    {node.type === 'CAST' && <><ConfigLabel label="字段"><Select onChange={(field) => update({ field })} options={availableFields} value={config.field as string} /></ConfigLabel><ConfigLabel label="目标类型"><Select onChange={(type) => update({ casts: { [String(config.field ?? '')]: type } })} options={['STRING', 'BOOLEAN', 'INT', 'LONG', 'DECIMAL', 'DATE', 'TIMESTAMP', 'JSON'].map((value) => ({ label: value, value }))} /></ConfigLabel><ConfigLabel label="失败策略"><Select onChange={(failurePolicy) => update({ failurePolicy })} options={[{ label: '停止', value: 'STOP' }, { label: '置空', value: 'NULL' }, { label: '隔离', value: 'QUARANTINE' }]} value={config.failurePolicy as string ?? 'STOP'} /></ConfigLabel></>}
    {node.type === 'FILTER' && <><ConfigLabel label="字段"><Select onChange={(field) => update({ field })} options={availableFields} value={config.field as string} /></ConfigLabel><ConfigLabel label="条件"><Select onChange={(operator) => update({ operator })} options={['EQUALS', 'NOT_EQUALS', 'CONTAINS', 'GREATER_THAN', 'LESS_THAN', 'IS_NULL', 'IS_NOT_NULL'].map((value) => ({ label: value, value }))} value={config.operator as string ?? 'EQUALS'} /></ConfigLabel><ConfigLabel label="比较值"><Input onChange={(event) => update({ value: event.target.value })} value={String(config.value ?? '')} /></ConfigLabel></>}
    {node.type === 'DERIVE' && <><ConfigLabel label="字段名称"><Input onChange={(event) => update({ name: event.target.value })} value={String(config.name ?? '')} /></ConfigLabel><ConfigLabel label="安全运算"><Select onChange={(operation) => update({ operation })} options={['CONSTANT', 'CONCAT', 'COALESCE'].map((value) => ({ label: value, value }))} value={config.operation as string ?? 'CONSTANT'} /></ConfigLabel>{String(config.operation ?? 'CONSTANT') === 'CONSTANT' ? <ConfigLabel label="常量值"><Input onChange={(event) => update({ value: event.target.value })} value={String(config.value ?? '')} /></ConfigLabel> : <><ConfigLabel label="左字段"><Select onChange={(left) => update({ left })} options={availableFields} value={config.left as string} /></ConfigLabel><ConfigLabel label="右字段"><Select onChange={(right) => update({ right })} options={availableFields} value={config.right as string} /></ConfigLabel>{config.operation === 'CONCAT' && <ConfigLabel label="分隔符"><Input onChange={(event) => update({ separator: event.target.value })} value={String(config.separator ?? '')} /></ConfigLabel>}</>}</>}
    {node.type === 'DEDUPLICATE' && <ConfigLabel label="去重键"><Select mode="multiple" onChange={(keys) => update({ keys })} options={availableFields} value={config.keys as string[] ?? []} /></ConfigLabel>}
    {node.type === 'JOIN' && <>
      <ConfigLabel label="辅助数据连接"><Select onChange={(lookupConnectionId) => {
        update({ lookupAssetId: undefined, lookupConnectionId, rightKey: undefined });
        onLookupConnectionChange(lookupConnectionId);
      }} options={connections.filter((connection) => connection.status.startsWith('HEALTHY'))
        .map((connection) => ({ label: connection.name, value: connection.id }))} placeholder="选择一个连接" value={config.lookupConnectionId as string} /></ConfigLabel>
      <ConfigLabel label="辅助文件或数据表"><Select disabled={!config.lookupConnectionId} onChange={(lookupAssetId) => {
        update({ lookupAssetId, rightKey: undefined });
        onLookupAssetChange(String(config.lookupConnectionId), lookupAssetId);
      }}
        options={selectedLookupAssets.filter((asset) => asset.assetType !== 'BUCKET' && asset.permissionStatus === 'READABLE')
          .map((asset) => ({ label: asset.name, value: asset.id }))} placeholder="选择文件或数据表" value={config.lookupAssetId as string} /></ConfigLabel>
      <ConfigLabel label="主数据关联列"><Select onChange={(leftKey) => update({ leftKey })} options={availableFields} placeholder="选择主数据字段" value={config.leftKey as string} /></ConfigLabel>
      <ConfigLabel label="辅助数据关联列"><Select disabled={!selectedLookupAsset} onChange={(rightKey) => update({ rightKey })} options={lookupFieldOptions} placeholder="选择辅助数据字段" value={config.rightKey as string} /></ConfigLabel>
      <ConfigLabel label="保留哪些记录"><Select onChange={(joinType) => update({ joinType })} options={[
        { label: '保留全部主数据', value: 'LEFT' },
        { label: '只保留匹配数据', value: 'INNER' },
      ]} value={config.joinType as string ?? 'LEFT'} /></ConfigLabel>
      <ConfigLabel label="同名列前缀"><Input onChange={(event) => update({ lookupPrefix: event.target.value })} placeholder="辅助_" value={String(config.lookupPrefix ?? '辅助_')} /></ConfigLabel>
      {pipeline.mode === 'STREAMING' && <Checkbox checked={Boolean(config.boundedDimension)} onChange={(event) => update({ boundedDimension: event.target.checked })}>辅助数据是可完整读取的小表</Checkbox>}
      <Alert message="辅助数据最多 1000 行；更大的表请先过滤，后续可升级为分布式关联。" showIcon type="info" />
    </>}
    {node.type === 'WINDOW' && <><ConfigLabel label="窗口类型"><Select onChange={(windowType) => update({ windowType })} options={['TUMBLING', 'SLIDING', 'SESSION'].map((value) => ({ label: value, value }))} value={config.windowType as string ?? 'TUMBLING'} /></ConfigLabel><ConfigLabel label="窗口大小（毫秒）"><InputNumber min={1000} onChange={(windowSizeMs) => update({ windowSizeMs })} value={config.windowSizeMs as number ?? 60000} /></ConfigLabel></>}
    {node.type === 'AGGREGATE' && <><ConfigLabel label="Group By"><Select mode="multiple" onChange={(groupBy) => update({ groupBy })} options={availableFields} value={config.groupBy as string[] ?? []} /></ConfigLabel><ConfigLabel label="指标"><Select onChange={(aggregation) => update({ aggregation })} options={['COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'COUNT_DISTINCT'].map((value) => ({ label: value, value }))} value={config.aggregation as string ?? 'COUNT'} /></ConfigLabel></>}
    {node.type === 'QUALITY' && <><ConfigLabel label="字段"><Select onChange={(field) => update({ field })} options={availableFields} value={config.field as string} /></ConfigLabel><ConfigLabel label="规则"><Select onChange={(rule) => update({ rule })} options={['NOT_NULL', 'TYPE', 'RANGE', 'REGEX', 'UNIQUE', 'REFERENCE'].map((value) => ({ label: value, value }))} value={config.rule as string ?? 'NOT_NULL'} /></ConfigLabel><ConfigLabel label="失败动作"><Select onChange={(failureAction) => update({ failureAction })} options={[{ label: '停止', value: 'STOP' }, { label: '跳过', value: 'SKIP' }, { label: '受限隔离', value: 'QUARANTINE' }]} value={config.failureAction as string ?? 'STOP'} /></ConfigLabel></>}
    {node.type === 'DATASET_OUTPUT' && <DatasetOutput config={config} fields={node.inputSchema.map((field) => field.name)} update={update} />}
    {node.type === 'ONTOLOGY_OBJECT' && <ObjectMapping config={config} fields={node.inputSchema.map((field) => field.name)} objectTypes={objectTypes} update={update} />}
    {node.type === 'ONTOLOGY_RELATION' && <><ConfigLabel label="生成哪种关系"><Select onChange={(relationTypeId) => update({ relationTypeId })} options={linkTypes.map((item) => ({ label: item.displayName, value: item.id }))} placeholder="选择一个已创建的关系" value={config.relationTypeId as string} /></ConfigLabel><ConfigLabel label="起点本体"><Select onChange={(sourceObjectTypeId) => update({ sourceObjectTypeId })} options={objectTypes.map((item) => ({ label: item.displayName, value: item.id }))} value={config.sourceObjectTypeId as string} /></ConfigLabel><ConfigLabel label="起点唯一标识字段"><Select onChange={(sourceIdField) => update({ sourceIdField })} options={availableFields} value={config.sourceIdField as string} /></ConfigLabel><ConfigLabel label="终点本体"><Select onChange={(targetObjectTypeId) => update({ targetObjectTypeId })} options={objectTypes.map((item) => ({ label: item.displayName, value: item.id }))} value={config.targetObjectTypeId as string} /></ConfigLabel><ConfigLabel label="终点唯一标识字段"><Select onChange={(targetIdField) => update({ targetIdField })} options={availableFields} value={config.targetIdField as string} /></ConfigLabel></>}
    <Divider />
    <Text type="secondary">输入 Schema：{node.inputSchema.length} 字段 · 输出 Schema：{node.outputSchema.length} 字段</Text>
  </aside>;
}

function DatasetOutput({ config, fields, update }: { config: Record<string, unknown>; fields: string[]; update: (values: Record<string, unknown>) => void }) {
  const configured = Array.isArray(config.fieldMappings) ? config.fieldMappings as Array<{source:string;target:string}> : [];
  const explicit = config.fieldSelectionMode === 'EXPLICIT';
  const selectedFields = explicit ? configured.map((item) => item.source).filter((field) => fields.includes(field)) : fields;
  const mappings = selectedFields.map((source) => configured.find((item) => item.source === source) ?? { source, target: source });
  const selectFields = (next: string[]) => update({
    fieldMappings: next.map((source) => configured.find((item) => item.source === source) ?? { source, target: source }),
    fieldSelectionMode: 'EXPLICIT',
  });
  const changeTarget = (source: string, target: string) => update({ fieldMappings: mappings.map((item) => item.source === source ? { ...item, target } : item), fieldSelectionMode: 'EXPLICIT' });
  return <><ConfigLabel label="数据集名称"><Input onChange={(event) => update({ datasetName: event.target.value })} placeholder="例如：员工 Token 明细" value={String(config.datasetName ?? '')} /></ConfigLabel><ConfigLabel label="说明"><Input.TextArea onChange={(event) => update({ description: event.target.value })} rows={2} value={String(config.description ?? '')} /></ConfigLabel><Divider orientation="left">输出字段</Divider><ConfigLabel label="保留字段"><Select mode="multiple" onChange={selectFields} options={fields.map((field) => ({ label: field, value: field }))} placeholder="至少选择一个输出字段" value={selectedFields} /></ConfigLabel><Text type="secondary">只会物化勾选的字段；右侧可修改输出字段名。</Text><div style={{maxHeight:360,overflow:'auto',marginTop:8}}>{mappings.map((mapping)=><div className="pipeline-config-field" key={mapping.source}><span>{mapping.source}</span><Input onChange={(event)=>changeTarget(mapping.source,event.target.value)} placeholder="输出字段名" size="small" value={mapping.target}/></div>)}</div></>;
}

function ConfigLabel({ children, label }: { children: React.ReactNode; label: string }) {
  return <label className="pipeline-config-field"><span>{label}</span>{children}</label>;
}

function ObjectMapping({ config, fields, objectTypes, update }: { config: Record<string, unknown>; fields: string[]; objectTypes: ObjectTypeDefinition[]; update: (values: Record<string, unknown>) => void }) {
  const selected = objectTypes.find((item) => item.id === config.objectTypeId);
  const mappings = config.mappings && typeof config.mappings === 'object' ? config.mappings as Record<string, string> : {};
  const fieldOptions = fields.map((field) => ({ label: field, value: field }));
  const chooseType = (objectTypeId: string) => {
    const objectType = objectTypes.find((item) => item.id === objectTypeId);
    const automaticMappings = Object.fromEntries((objectType?.properties ?? []).filter((property) => !property.primaryKey).map((property) => {
      const matched = fields.find((field) => field.toLowerCase() === property.apiName.toLowerCase()
        || field.toLowerCase() === property.displayName.toLowerCase());
      return matched ? [property.apiName, matched] : undefined;
    }).filter((item): item is [string, string] => Boolean(item)));
    update({ objectTypeId, idField: undefined, idFields: [], mappings: automaticMappings });
  };
  return <>
    <ConfigLabel label="生成哪个本体"><Select onChange={chooseType} options={objectTypes.map((item) => ({ label: item.displayName, value: item.id }))} placeholder="选择一个已创建的本体" value={config.objectTypeId as string} /></ConfigLabel>
    <ConfigLabel label="哪些列共同组成唯一标识"><Select mode="multiple" onChange={(idFields) => update({ idField: undefined, idFields })} options={fieldOptions} placeholder="可选择一列或多列" value={Array.isArray(config.idFields) ? config.idFields as string[] : config.idField ? [String(config.idField)] : []} /></ConfigLabel>
    {selected?.properties.map((property) => <ConfigLabel key={property.id} label={property.displayName}>
      {property.primaryKey
        ? <Input disabled value="由唯一标识自动生成" />
        : <Select allowClear onChange={(field) => update({ mappings: { ...mappings, [property.apiName]: field } })} options={fieldOptions} placeholder="选择对应的源字段" value={mappings[property.apiName]} />}
    </ConfigLabel>)}
  </>;
}
