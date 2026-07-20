import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Checkbox, Form, Input, InputNumber, message, Result, Select, Skeleton, Space, Typography } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { ApiProblem, dataConnectionsApi } from './services/dataConnections';
import type { DataSource, DataSourceType } from './types';

const { Paragraph, Title } = Typography;

interface Props {
  accessToken: string;
  id: string;
  navigate: (path: string) => void;
}

interface EditValues {
  name: string;
  description?: string;
  ownerId: string;
  ownerName: string;
  tags?: string;
  endpoint?: string;
  region?: string;
  bucket?: string;
  prefix?: string;
  pathStyle?: boolean;
  host?: string;
  port?: number;
  database?: string;
  schema?: string;
  tls?: boolean;
  bootstrapServers?: string;
  securityProtocol?: string;
  saslMechanism?: string;
  serviceUrl?: string;
  adminUrl?: string;
  tenant?: string;
  namespace?: string;
  timeoutSeconds?: number;
}

export default function EditConnectionPage({ accessToken, id, navigate }: Props) {
  const api = useMemo(() => dataConnectionsApi(accessToken), [accessToken]);
  const [form] = Form.useForm<EditValues>();
  const [source, setSource] = useState<DataSource>();
  const [problem, setProblem] = useState<string>();
  const [dirty, setDirty] = useState(false);
  const [connectionDirty, setConnectionDirty] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void api.get(id).then((value) => {
      setSource(value);
      form.setFieldsValue({
        ...value.config,
        name: value.name,
        description: value.description,
        ownerId: value.ownerId,
        ownerName: value.ownerName,
        tags: value.tags.join(','),
      } as EditValues);
    }).catch((cause) => setProblem(cause instanceof ApiProblem ? cause.message : '加载失败'));
  }, [api, form, id]);

  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => { if (dirty) event.preventDefault(); };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);

  const leave = () => { if (!dirty || window.confirm('编辑内容尚未保存，确认离开吗？')) navigate(`/data/connections/${id}?tab=settings`); };
  const save = async () => {
    if (!source) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      const updated = await api.update(id, source.version, {
        name: values.name,
        description: values.description,
        ownerId: values.ownerId,
        ownerName: values.ownerName,
        tags: values.tags?.split(',').map((tag) => tag.trim()).filter(Boolean) ?? [],
        config: connectionConfig(source.type, values),
      });
      setSource(updated);
      setDirty(false);
      setConnectionDirty(false);
      message.success(connectionDirty ? '新连接配置测试通过并已保存' : '基本信息已保存');
      navigate(`/data/connections/${id}?tab=settings`);
    } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '保存失败'); }
    finally { setSaving(false); }
  };

  if (problem) return <Result status="error" title={problem} />;
  if (!source) return <Skeleton active />;

  return (
    <div className="edit-page">
      <Button icon={<ArrowLeftOutlined />} onClick={leave} type="link">返回连接详情</Button>
      <Title level={2}>编辑连接配置</Title>
      <Paragraph>基本信息可直接保存；目标、TLS 或访问范围变更会先执行真实连接测试，失败时保留当前有效配置。</Paragraph>
      <Form
        form={form}
        layout="vertical"
        onValuesChange={(changed) => {
          setDirty(true);
          if (Object.keys(changed).some((key) => configKeys.has(key))) setConnectionDirty(true);
        }}
      >
        <Card title="基本信息">
          <div className="form-grid">
            <Form.Item label="连接名称" name="name" rules={[{ required: true, whitespace: true }, { max: 160 }]}><Input /></Form.Item>
            <Form.Item label="负责人 ID" name="ownerId" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item label="负责人名称" name="ownerName" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item label="标签" name="tags"><Input /></Form.Item>
          </div>
          <Form.Item label="说明" name="description"><Input.TextArea maxLength={1000} rows={3} /></Form.Item>
        </Card>
        <Card title="连接配置">
          <Alert message="保存目标配置前会依次验证网络、TLS、认证、元数据和资产发现；测试失败不会覆盖当前配置。" showIcon type="info" />
          <ConnectionFields type={source.type} />
        </Card>
        <Card title="凭据">
          <Alert message={`凭据“${source.credential.name}”只显示脱敏元数据；请在详情设置页完成“填写 → 测试 → 确认替换”的轮换流程。`} type="info" />
        </Card>
        <Card title="资产访问范围"><Paragraph>Bucket/Prefix、Schema 或 Topic 范围与连接配置一起测试并保存；缩小范围前请检查详情页“同步任务”中的引用。</Paragraph></Card>
        <Space className="edit-actions">
          <Button onClick={leave}>取消</Button>
          <Button loading={saving} onClick={() => void save()} type="primary">{connectionDirty ? '测试并保存' : '保存基本信息'}</Button>
        </Space>
      </Form>
    </div>
  );
}

function ConnectionFields({ type }: { type: DataSourceType }) {
  return <div className="form-grid connection-edit-fields">
    {type === 'S3_CSV' && <>
      <Form.Item label="Endpoint" name="endpoint" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item label="Region" name="region"><Input /></Form.Item>
      <Form.Item label="允许的 Bucket" name="bucket"><Input /></Form.Item>
      <Form.Item label="允许的 Prefix" name="prefix"><Input /></Form.Item>
      <Form.Item name="pathStyle" valuePropName="checked"><Checkbox>使用 Path Style</Checkbox></Form.Item>
    </>}
    {(type === 'MYSQL' || type === 'POSTGRESQL') && <>
      <Form.Item label="Host" name="host" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item label="Port" name="port" rules={[{ required: true }]}><InputNumber min={1} max={65535} /></Form.Item>
      <Form.Item label="Database" name="database" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item label="Schema" name="schema"><Input /></Form.Item>
      <Form.Item name="tls" valuePropName="checked"><Checkbox>启用 TLS</Checkbox></Form.Item>
    </>}
    {type === 'KAFKA' && <>
      <Form.Item label="Bootstrap Servers" name="bootstrapServers" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item label="Security Protocol" name="securityProtocol"><Select options={['PLAINTEXT', 'SASL_PLAINTEXT', 'SASL_SSL', 'SSL'].map((value) => ({ label: value, value }))} /></Form.Item>
      <Form.Item label="SASL Mechanism" name="saslMechanism"><Input /></Form.Item>
    </>}
    {type === 'EXTERNAL_PULSAR' && <>
      <Form.Item label="Service URL" name="serviceUrl" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item label="Admin URL" name="adminUrl" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item label="Tenant" name="tenant"><Input /></Form.Item>
      <Form.Item label="Namespace" name="namespace"><Input /></Form.Item>
    </>}
    <Form.Item label="连接超时（秒）" name="timeoutSeconds"><InputNumber min={3} max={60} /></Form.Item>
  </div>;
}

function connectionConfig(type: DataSourceType, values: EditValues): Record<string, unknown> {
  const common = { timeoutSeconds: values.timeoutSeconds ?? 15 };
  if (type === 'S3_CSV') return { ...common, endpoint: values.endpoint, region: values.region, bucket: values.bucket, prefix: values.prefix ?? '', pathStyle: values.pathStyle ?? true };
  if (type === 'MYSQL' || type === 'POSTGRESQL') return { ...common, host: values.host, port: values.port, database: values.database, schema: values.schema ?? '', tls: values.tls ?? false };
  if (type === 'KAFKA') return { ...common, bootstrapServers: values.bootstrapServers, securityProtocol: values.securityProtocol, saslMechanism: values.saslMechanism ?? '' };
  return { ...common, serviceUrl: values.serviceUrl, adminUrl: values.adminUrl, tenant: values.tenant, namespace: values.namespace };
}

const configKeys = new Set([
  'adminUrl', 'bootstrapServers', 'bucket', 'database', 'endpoint', 'host', 'namespace', 'pathStyle', 'port', 'prefix', 'region',
  'saslMechanism', 'schema', 'securityProtocol', 'serviceUrl', 'tenant', 'timeoutSeconds', 'tls',
]);
