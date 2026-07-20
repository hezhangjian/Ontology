import { ArrowLeftOutlined, BranchesOutlined, DatabaseOutlined, FileTextOutlined, LinkOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Form, Input, message, Radio, Select, Space, Spin, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { ApiProblem, dataConnectionsApi } from '../data-connections/services/dataConnections';
import type { DataSource, DataSourceAsset } from '../data-connections/types';
import { pipelinesApi } from './services/pipelines';
import type { PipelineMode } from './types';

const { Paragraph, Text, Title } = Typography;
const templates = [
  { description: '从受控源开始，自行添加转换与本体输出。', icon: <BranchesOutlined />, label: '空白管道', modes: ['BATCH', 'STREAMING'], value: 'BLANK' },
  { description: '读取 MinIO/S3 CSV 或数据库快照并批量投影。', icon: <FileTextOutlined />, label: '文件 / 数据库批量导入', modes: ['BATCH'], value: 'FILE_BATCH' },
  { description: '稳定 Consumer Group、事件时间、水位线与 checkpoint。', icon: <ThunderboltOutlined />, label: 'Kafka 实时对象管道', modes: ['STREAMING'], value: 'KAFKA_STREAM' },
  { description: 'Failover Subscription 与受控消费位置。', icon: <ThunderboltOutlined />, label: '外部 Pulsar 实时对象管道', modes: ['STREAMING'], value: 'PULSAR_STREAM' },
  { description: '从同一 DAG 生成对象与关系输出。', icon: <LinkOutlined />, label: '对象与关系构建', modes: ['BATCH', 'STREAMING'], value: 'OBJECT_RELATION' },
];

export default function NewPipelinePage({ accessToken, displayName, navigate, userId }: { accessToken: string; displayName: string; navigate: (path: string) => void; userId: string }) {
  const connectionApi = useMemo(() => dataConnectionsApi(accessToken), [accessToken]);
  const api = useMemo(() => pipelinesApi(accessToken), [accessToken]);
  const query = useMemo(() => new URLSearchParams(window.location.search), []);
  const [form] = Form.useForm();
  const [connections, setConnections] = useState<DataSource[]>([]);
  const [assets, setAssets] = useState<DataSourceAsset[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState<string>();
  const mode = Form.useWatch<PipelineMode>('mode', form) ?? 'BATCH';
  const selectedTemplate = Form.useWatch<string>('template', form) ?? 'BLANK';
  const selectedConnection = Form.useWatch<string>('dataSourceId', form);

  useEffect(() => {
    connectionApi.list('size=100').then((page) => {
      const usable = page.items.filter((connection) => connection.status === 'HEALTHY' || connection.status === 'HEALTHY_RESTRICTED');
      setConnections(usable);
      const initial = query.get('connectionId');
      if (initial && usable.some((connection) => connection.id === initial)) form.setFieldValue('dataSourceId', initial);
    }).catch((cause) => setProblem(cause instanceof Error ? cause.message : '连接加载失败')).finally(() => setLoading(false));
  }, [connectionApi, form, query]);
  useEffect(() => {
    if (!selectedConnection) { setAssets([]); return; }
    setAssets([]);
    connectionApi.listAssets(selectedConnection).then((result) => {
      setAssets(result.items.filter((asset) => asset.permissionStatus !== 'DENIED' && asset.status !== 'UNAVAILABLE'));
      const initial = query.get('assetId');
      if (initial && result.items.some((asset) => asset.id === initial)) form.setFieldValue('sourceAssetId', initial);
    }).catch((cause) => setProblem(cause instanceof Error ? cause.message : '资产加载失败'));
  }, [connectionApi, form, query, selectedConnection]);
  useEffect(() => {
    const template = templates.find((item) => item.value === selectedTemplate);
    if (template && !template.modes.includes(mode)) form.setFieldValue('template', mode === 'BATCH' ? 'FILE_BATCH' : 'KAFKA_STREAM');
  }, [form, mode, selectedTemplate]);

  async function submit(values: Record<string, unknown>) {
    setSubmitting(true);
    try {
      const result = await api.create({ ...values, ownerId: userId, ownerName: displayName });
      message.success('管道草稿已创建');
      navigate(`/data/pipelines/${result.data.id}/edit`);
    } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '创建管道失败'); }
    finally { setSubmitting(false); }
  }

  return <div className="new-pipeline-page">
    <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/data/pipelines')} type="text">返回管道列表</Button>
    <div className="new-pipeline-heading"><Title level={2}>新建管道</Title><Paragraph>选择紧凑模板并绑定受控源；复杂映射将在完整 DAG 编辑器中配置。</Paragraph></div>
    {problem && <Alert message={problem} showIcon type="error" />}
    {loading ? <Spin /> : <Form form={form} initialValues={{ mode: 'BATCH', ownerName: displayName, template: 'FILE_BATCH' }} layout="vertical" onFinish={(values) => void submit(values)}>
      <section className="pipeline-create-section"><Title level={4}>1. 选择模板</Title><Form.Item name="template" rules={[{ required: true }]}><Radio.Group className="pipeline-template-grid">{templates.filter((template) => template.modes.includes(mode)).map((template) => <Radio.Button key={template.value} value={template.value}><Card className="pipeline-template-card" size="small"><div className="template-icon">{template.icon}</div><Text strong>{template.label}</Text><Paragraph>{template.description}</Paragraph></Card></Radio.Button>)}</Radio.Group></Form.Item></section>
      <section className="pipeline-create-section"><Title level={4}>2. 基本信息与源</Title><div className="pipeline-create-grid"><Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }, { max: 240 }]}><Input placeholder="例如：员工主数据导入" /></Form.Item><Form.Item label="负责人" name="ownerName"><Input disabled /></Form.Item><Form.Item className="wide-field" label="说明" name="description"><Input.TextArea maxLength={1000} rows={2} /></Form.Item><Form.Item label="模式" name="mode" rules={[{ required: true }]}><Radio.Group buttonStyle="solid"><Radio.Button value="BATCH">批处理</Radio.Button><Radio.Button value="STREAMING">流处理</Radio.Button></Radio.Group></Form.Item><Form.Item label="源连接" name="dataSourceId" rules={[{ required: true, message: '请选择源连接' }]}><Select onChange={() => form.setFieldValue('sourceAssetId', undefined)} options={connections.filter((connection) => mode === 'STREAMING' ? ['KAFKA', 'EXTERNAL_PULSAR'].includes(connection.type) : !['KAFKA', 'EXTERNAL_PULSAR'].includes(connection.type)).map((connection) => ({ label: <Space><DatabaseOutlined />{connection.name}</Space>, value: connection.id }))} placeholder="选择已测试连接" /></Form.Item><Form.Item label="源资产" name="sourceAssetId" rules={[{ required: true, message: '请选择源资产' }]}><Select disabled={!selectedConnection} options={assets.map((asset) => ({ label: `${asset.name} · ${asset.fullPath}`, value: asset.id }))} placeholder="选择已发现资产" /></Form.Item></div></section>
      <div className="pipeline-create-actions"><Button onClick={() => navigate('/data/pipelines')}>取消</Button><Button htmlType="submit" loading={submitting} type="primary">创建并打开编辑器</Button></div>
    </Form>}
  </div>;
}
