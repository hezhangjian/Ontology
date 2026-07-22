import { Alert, Button, Empty, Segmented, Space, Spin, Table, Tag, Typography } from 'antd';
import type { FieldSchema, PreviewRun, ValidationResult } from '../types';

const { Text } = Typography;

interface Props {
  active: 'preview' | 'schema' | 'validation' | 'logs';
  nodeSchema: FieldSchema[];
  onActive: (value: 'preview' | 'schema' | 'validation' | 'logs') => void;
  onCancelPreview: () => void;
  preview?: PreviewRun;
  validation?: ValidationResult;
}

export default function BottomPanel({ active, nodeSchema, onActive, onCancelPreview, preview, validation }: Props) {
  const previewColumns = Array.from(new Set((preview?.rows ?? []).flatMap((row) => Object.keys(row)))).map((key) => ({ dataIndex: key, key, title: key, render: (value: unknown) => format(value) }));
  return <section className="pipeline-bottom-panel">
    <div className="bottom-panel-header"><Segmented onChange={(value) => onActive(value as Props['active'])} options={[{ label: '预览', value: 'preview' }, { label: 'Schema', value: 'schema' }, { label: `校验问题 ${validation?.issues.length ?? 0}`, value: 'validation' }, { label: '运行日志', value: 'logs' }]} size="small" value={active} />
      {active === 'preview' && ['SUBMITTED', 'RUNNING'].includes(preview?.status ?? '') && <Button danger onClick={onCancelPreview} size="small">取消预览</Button>}
    </div>
    <div className="bottom-panel-body">
      {active === 'preview' && (!preview ? <Empty description="选择节点后点击顶部“预览”以运行有界 Flink 作业" image={Empty.PRESENTED_IMAGE_SIMPLE} /> : ['SUBMITTED', 'RUNNING'].includes(preview.status) ? <Space><Spin /><Text>Flink 预览正在运行 · {preview.flinkJobId ?? '等待 Job ID'}</Text></Space> : preview.status === 'FAILED' ? <Alert description={format(preview.diagnostic)} message="预览失败" showIcon type="error" /> : <Table columns={previewColumns} dataSource={preview.rows.map((row, index) => ({ ...row, __key: index }))} pagination={false} rowKey="__key" scroll={{ x: true, y: 180 }} size="small" />)}
      {active === 'schema' && (nodeSchema.length === 0 ? <Empty description="选择带输出 Schema 的节点" image={Empty.PRESENTED_IMAGE_SIMPLE} /> : <Table columns={[{ dataIndex: 'name', title: '字段' }, { dataIndex: 'type', title: '类型' }, { dataIndex: 'nullable', title: '可空', render: (value) => value ? '是' : '否' }, { dataIndex: 'sensitive', title: '敏感', render: (value) => value ? <Tag color="warning">敏感</Tag> : '否' }]} dataSource={nodeSchema} pagination={false} rowKey="name" scroll={{ y: 180 }} size="small" />)}
      {active === 'validation' && (!validation ? <Empty description="保存并运行时会自动检查配置" image={Empty.PRESENTED_IMAGE_SIMPLE} /> : validation.issues.length === 0 ? <Alert message="配置检查通过" showIcon type="success" /> : <div className="validation-list">{validation.issues.map((issue) => <Alert description={<><div>{issue.detail}</div><Text type="secondary">建议：{issue.recoveryAction}</Text></>} key={issue.id} message={<Space><Tag color={issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'blue'}>{issue.severity}</Tag>{issue.title}</Space>} showIcon type={issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'info'} />)}</div>)}
      {active === 'logs' && <Alert description="正式运行日志按 run ID 从控制面事件读取；敏感正文和凭据不会显示。" message="运行真相源：PostgreSQL / Flink / Pulsar / Projection" showIcon type="info" />}
    </div>
  </section>;
}

function format(value: unknown) {
  if (value === undefined || value === null) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
