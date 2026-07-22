import { ArrowLeftOutlined, DatabaseOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, message, Select, Space, Spin, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { ApiProblem, dataConnectionsApi } from '../data-connections/services/dataConnections';
import type { DataSource, DataSourceAsset } from '../data-connections/types';
import { pipelinesApi } from './services/pipelines';

const { Paragraph, Text, Title } = Typography;

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
  async function submit(values: Record<string, unknown>) {
    setSubmitting(true);
    try {
      const result = await api.create({ ...values, template: 'FILE_BATCH', mode: 'BATCH', ownerId: userId, ownerName: displayName });
      message.success('已创建数据整理任务');
      navigate(`/data/pipelines/${result.data.id}/edit`);
    } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '创建管道失败'); }
    finally { setSubmitting(false); }
  }

  return <div className="new-pipeline-page">
    <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/data/pipelines')} type="text">返回管道列表</Button>
    <div className="new-pipeline-heading"><Title level={2}>创建数据处理管道</Title><Paragraph>选择刚连接的数据和主文件，默认按“数据源 → 选择字段 → 数据集输出”生成可复用 Dataset。</Paragraph></div>
    {problem && <Alert message={problem} showIcon type="error" />}
    {loading ? <Spin /> : <Form form={form} layout="vertical" onFinish={(values) => void submit(values)}>
      <section className="pipeline-create-section"><div className="pipeline-create-grid"><Form.Item label="任务名称" name="name" rules={[{ required: true, message: '请输入名称' }, { max: 240 }]}><Input placeholder="例如：业务数据整理" /></Form.Item><Form.Item className="wide-field" label="说明（可选）" name="description"><Input.TextArea maxLength={1000} rows={2} /></Form.Item><Form.Item label="数据连接" name="dataSourceId" rules={[{ required: true, message: '请选择数据连接' }]}><Select onChange={() => form.setFieldValue('sourceAssetId', undefined)} options={connections.filter((connection) => !['KAFKA', 'EXTERNAL_PULSAR'].includes(connection.type)).map((connection) => ({ label: <Space><DatabaseOutlined />{connection.name}</Space>, value: connection.id }))} placeholder="选择 CSV 或数据库连接" /></Form.Item><Form.Item label="主文件或数据表" name="sourceAssetId" rules={[{ required: true, message: '请选择文件或数据表' }]}><Select disabled={!selectedConnection} options={assets.map((asset) => ({ label: asset.name, value: asset.id }))} placeholder="选择要整理的数据" /></Form.Item></div><Text type="secondary">下一步可选择、重命名、关联、去重和派生字段，标准产物是数据集。</Text></section>
      <div className="pipeline-create-actions"><Button onClick={() => navigate('/data/pipelines')}>取消</Button><Button htmlType="submit" loading={submitting} type="primary">下一步：配置数据集</Button></div>
    </Form>}
  </div>;
}
