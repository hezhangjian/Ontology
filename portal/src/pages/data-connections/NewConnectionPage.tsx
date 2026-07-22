import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  message,
  Radio,
  Select,
  Space,
  Spin,
  Steps,
  Tag,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  FolderOpenOutlined,
  LeftOutlined,
  MessageOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { ApiProblem, dataConnectionsApi } from './services/dataConnections';
import type { ConnectionTestResult, CredentialInput, CredentialSummary, DataSourceType } from './types';

const { Paragraph, Text, Title } = Typography;

type ConnectionKind = DataSourceType | 'LOCAL_CSV';

const sourceTypes: { value: ConnectionKind; title: string; category: string; detail: string; icon: React.ReactNode }[] = [
  { value: 'LOCAL_CSV', title: '本地 CSV 文件', category: '文件导入', detail: '选择一个或多个 CSV，平台自动上传并识别字段', icon: <FolderOpenOutlined /> },
  { value: 'S3_CSV', title: 'MinIO/S3 CSV', category: '文件与对象存储', detail: '发现 Bucket、Prefix 与 CSV 文件', icon: <CloudServerOutlined /> },
  { value: 'MYSQL', title: 'MySQL', category: '关系数据库', detail: '发现 Schema、Table 与 View', icon: <DatabaseOutlined /> },
  { value: 'POSTGRESQL', title: 'PostgreSQL', category: '关系数据库', detail: '发现 Schema、Table 与 View', icon: <DatabaseOutlined /> },
  { value: 'KAFKA', title: 'Kafka', category: '消息系统', detail: '发现 Topic 与 Partition 元数据', icon: <MessageOutlined /> },
  { value: 'EXTERNAL_PULSAR', title: '外部 Pulsar', category: '消息系统', detail: '连接用户已有集群，不是平台内部事件总线', icon: <MessageOutlined /> },
];

interface Props {
  accessToken: string;
  displayName: string;
  isAdmin: boolean;
  navigate: (path: string) => void;
  userId: string;
}

interface FormValues {
  name: string;
  description?: string;
  tags?: string;
  endpoint?: string;
  bucket?: string;
  prefix?: string;
  region?: string;
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
  credentialMode: 'MANAGED' | 'EXISTING' | 'FILE';
  credentialName?: string;
  existingSecretRef?: string;
  accessKey?: string;
  secretKey?: string;
  username?: string;
  password?: string;
  token?: string;
  accessKeyRef?: string;
  secretKeyRef?: string;
}

export default function NewConnectionPage({ accessToken, displayName, isAdmin, navigate, userId }: Props) {
  const api = useMemo(() => dataConnectionsApi(accessToken), [accessToken]);
  const [form] = Form.useForm<FormValues>();
  const [step, setStep] = useState(0);
  const [type, setType] = useState<ConnectionKind>();
  const [testResult, setTestResult] = useState<ConnectionTestResult>();
  const [testing, setTesting] = useState(false);
  const [creating, setCreating] = useState(false);
  const [credentials, setCredentials] = useState<CredentialSummary[]>([]);
  const [dirty, setDirty] = useState(false);
  const [localFiles, setLocalFiles] = useState<File[]>([]);
  const fileInput = useRef<HTMLInputElement>(null);
  const credentialMode = Form.useWatch('credentialMode', form) ?? 'MANAGED';

  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty) event.preventDefault(); };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty]);

  useEffect(() => { void api.listCredentials().then(setCredentials).catch(() => setCredentials([])); }, [api]);

  const chooseType = (selected: ConnectionKind) => {
    setType(selected);
    setDirty(true);
    setTestResult(undefined);
    setLocalFiles([]);
    const defaults: Partial<FormValues> = { credentialMode: 'MANAGED', timeoutSeconds: 15 };
    if (selected === 'S3_CSV') Object.assign(defaults, { endpoint: 'http://minio:9000', region: 'us-east-1', bucket: 'raw', pathStyle: true });
    if (selected === 'MYSQL') Object.assign(defaults, { host: '', port: 3306, tls: false });
    if (selected === 'POSTGRESQL') Object.assign(defaults, { host: '', port: 5432, tls: false });
    if (selected === 'KAFKA') Object.assign(defaults, { bootstrapServers: '', securityProtocol: 'PLAINTEXT' });
    if (selected === 'EXTERNAL_PULSAR') Object.assign(defaults, { serviceUrl: '', adminUrl: '', tenant: '', namespace: '' });
    form.setFieldsValue(defaults);
  };

  function config(values: FormValues): Record<string, unknown> {
    const common = { timeoutSeconds: values.timeoutSeconds ?? 15 };
    if (type === 'LOCAL_CSV') return {};
    if (type === 'S3_CSV') return { ...common, endpoint: values.endpoint, region: values.region, bucket: values.bucket, prefix: values.prefix ?? '', pathStyle: values.pathStyle ?? true };
    if (type === 'MYSQL' || type === 'POSTGRESQL') return { ...common, host: values.host, port: values.port, database: values.database, schema: values.schema ?? '', tls: values.tls ?? false };
    if (type === 'KAFKA') return { ...common, bootstrapServers: values.bootstrapServers, securityProtocol: values.securityProtocol, saslMechanism: values.saslMechanism ?? '' };
    return { ...common, serviceUrl: values.serviceUrl, adminUrl: values.adminUrl, tenant: values.tenant, namespace: values.namespace };
  }

  function credential(values: FormValues): CredentialInput {
    if (values.credentialMode === 'EXISTING') return { mode: 'EXISTING', existingSecretRef: values.existingSecretRef };
    if (values.credentialMode === 'FILE') return {
      mode: 'FILE', name: values.credentialName,
      fileRefs: type === 'S3_CSV'
        ? { accessKey: `file://${values.accessKeyRef}`, secretKey: `file://${values.secretKeyRef}` }
        : { username: `file://${values.accessKeyRef}`, password: `file://${values.secretKeyRef}` },
    };
    const valuesMap: Record<string, string> = {};
    if (type === 'S3_CSV') { valuesMap.accessKey = values.accessKey ?? ''; valuesMap.secretKey = values.secretKey ?? ''; }
    if (type === 'MYSQL' || type === 'POSTGRESQL' || type === 'KAFKA') { valuesMap.username = values.username ?? ''; valuesMap.password = values.password ?? ''; }
    if (type === 'EXTERNAL_PULSAR') valuesMap.token = values.token ?? '';
    return { mode: 'MANAGED', name: values.credentialName, values: valuesMap };
  }

  async function next() {
    if (step === 0) { if (!type) return void message.warning('请先选择连接类型'); setStep(1); return; }
    if (step === 1) {
      try {
        await form.validateFields(['name']);
        if (type === 'LOCAL_CSV' && localFiles.length === 0) return void message.warning('请先选择 CSV 文件');
        setTestResult(undefined);
        setStep(2);
      } catch { return; }
      return;
    }
    if (type === 'LOCAL_CSV') return;
    if (step === 2) { if (!testResult?.testToken) return void message.warning('请先完成连接测试'); setStep(3); }
  }

  async function runTest() {
    if (!type || type === 'LOCAL_CSV') return;
    const values = form.getFieldsValue(true);
    setTesting(true);
    setTestResult(undefined);
    try {
      const result = await api.test({ type, config: config(values), credential: credential(values) });
      setTestResult(result);
      if (result.status === 'ERROR') message.error('连接测试失败'); else message.success('连接测试完成');
    } catch (cause) {
      message.error(cause instanceof ApiProblem ? `${cause.message}（请求编号 ${cause.requestId ?? '-'}）` : '连接测试失败');
    } finally { setTesting(false); }
  }

  async function create() {
    if (!type || type === 'LOCAL_CSV' || !testResult?.testToken) return;
    const values = form.getFieldsValue(true);
    setCreating(true);
    try {
      const source = await api.create({
        name: values.name.trim(), description: values.description, type, ownerId: userId, ownerName: displayName,
        tags: values.tags ? values.tags.split(',').map((tag: string) => tag.trim()).filter(Boolean) : [], config: config(values),
        credential: credential(values), testToken: testResult.testToken,
      });
      setDirty(false);
      message.success('数据连接已创建');
      navigate(`/data/connections/${source.id}?created=true`);
    } catch (cause) {
      message.error(cause instanceof ApiProblem ? cause.message : '创建连接失败');
    } finally { setCreating(false); }
  }

  async function importLocalCsv() {
    if (type !== 'LOCAL_CSV' || localFiles.length === 0) return;
    const values = form.getFieldsValue(true);
    setCreating(true);
    try {
      const source = await api.importLocalCsv(
        values.name.trim(),
        values.description,
        values.tags ? values.tags.split(',').map((tag: string) => tag.trim()).filter(Boolean) : [],
        localFiles,
      );
      setDirty(false);
      message.success(`已导入并识别 ${source.assetCount} 个 CSV 文件`);
      navigate(`/data/connections/${source.id}?created=true`);
    } catch (cause) {
      message.error(cause instanceof ApiProblem ? cause.message : '导入 CSV 文件失败');
    } finally { setCreating(false); }
  }

  const cancel = () => { if (!dirty || window.confirm('当前向导内容尚未保存，确认放弃吗？')) navigate('/data/connections'); };
  const values = form.getFieldsValue(true);

  return (
    <div className="wizard-page">
      <div className="wizard-heading"><Button icon={<LeftOutlined />} onClick={cancel} type="text">返回数据连接</Button><Title level={2}>新建数据连接</Title><Steps current={step} items={(type === 'LOCAL_CSV' ? ['选择类型', '选择文件', '导入并识别'] : ['选择类型', '连接配置', '测试与发现', '确认创建']).map((title) => ({ title }))} size="small" /></div>
      <Form<FormValues> form={form} initialValues={{ credentialMode: 'MANAGED', timeoutSeconds: 15 }} layout="vertical" onValuesChange={() => { setDirty(true); setTestResult(undefined); }}>
        <div className="wizard-body">
          {step === 0 && <div className="source-type-list">{sourceTypes.map((source) => <button className={`source-type-row ${type === source.value ? 'selected' : ''}`} key={source.value} onClick={() => chooseType(source.value)} type="button"><span className="type-row-icon">{source.icon}</span><span><Text type="secondary">{source.category}</Text><strong>{source.title}</strong><small>{source.detail}</small></span>{type === source.value && <CheckCircleOutlined />}</button>)}</div>}
          {step === 1 && type === 'LOCAL_CSV' && <Card className="wizard-card" title="选择本地 CSV 文件">
            <Form.Item label="连接名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入一个方便识别的名称' }, { max: 160 }]}><Input placeholder="例如：2026 年 7 月 Token 消耗" /></Form.Item>
            <Form.Item label="说明" name="description"><Input.TextArea maxLength={1000} rows={2} /></Form.Item>
            <Form.Item label="标签" name="tags" extra="多个标签使用英文逗号分隔"><Input placeholder="例如：Token, 月度" /></Form.Item>
            <input accept=".csv,text/csv" multiple ref={fileInput} style={{ display: 'none' }} type="file" onChange={(event) => {
              const files = Array.from(event.currentTarget.files ?? []).filter((file) => file.name.toLowerCase().endsWith('.csv'));
              setLocalFiles(files);
              setDirty(true);
            }} />
            <Alert action={<Button icon={<FolderOpenOutlined />} onClick={() => fileInput.current?.click()} type="primary">选择 CSV</Button>} description={localFiles.length === 0 ? '可以一次选择多个 CSV 文件，平台会自动上传并识别字段。' : `已选择 ${localFiles.length} 个 CSV 文件：${localFiles.slice(0, 3).map((file) => file.name).join('、')}${localFiles.length > 3 ? '…' : ''}`} message={localFiles.length === 0 ? '还没有选择文件' : '文件已选择'} showIcon type={localFiles.length === 0 ? 'info' : 'success'} />
          </Card>}
          {step === 1 && type && type !== 'LOCAL_CSV' && <Card className="wizard-card" title={`${sourceTypes.find((item) => item.value === type)?.title} 配置`}>
            <div className="form-grid"><Form.Item label="连接名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入连接名称' }, { max: 160 }]}><Input placeholder="例如：生产订单数据" /></Form.Item><Form.Item label="负责人"><Input disabled value={displayName} /></Form.Item></div>
            <Form.Item label="说明" name="description"><Input.TextArea maxLength={1000} rows={2} /></Form.Item><Form.Item label="标签" name="tags" extra="多个标签使用英文逗号分隔"><Input /></Form.Item>
            <Divider orientation="left">连接参数</Divider><ConnectionFields type={type} />
            <Divider orientation="left">凭据与证书</Divider><Form.Item label="凭据来源" name="credentialMode"><Radio.Group options={[{ label: '新建平台凭据', value: 'MANAGED' }, { label: '使用已有凭据', value: 'EXISTING' }, ...(isAdmin ? [{ label: '预置 Docker Secret', value: 'FILE' }] : [])]} /></Form.Item>
            <CredentialFields credentials={credentials} mode={credentialMode} type={type} />
            <Alert icon={<SafetyCertificateOutlined />} message="凭据只在请求内存中使用，保存时采用 AES-256-GCM；查询和审计不会返回明文。" showIcon type="info" />
          </Card>}
          {step === 2 && type === 'LOCAL_CSV' && <Card className="wizard-card" title="导入并识别"><Alert description={`导入后会自动创建一个可用于管道的文件数据源，并识别 ${localFiles.length} 个 CSV 文件的字段。MinIO 地址、Bucket 和凭据均由平台处理。`} message="准备好了" showIcon type="success" /></Card>}
          {step === 2 && type !== 'LOCAL_CSV' && <div className="test-step"><div className="test-intro"><Title level={3}>测试与发现</Title><Paragraph>依次验证网络、TLS、认证、元数据和资产发现。本步骤只读取元数据，不读取业务正文。</Paragraph><Button loading={testing} onClick={() => void runTest()} size="large" type="primary">{testResult ? '重新测试' : '开始测试'}</Button></div>{testing && <Spin size="large" />}{testResult && <Card className="test-results"><Alert message={testResult.status === 'HEALTHY_RESTRICTED' ? '连接正常，但资产发现受限，可继续创建后手工指定范围' : testResult.status === 'ERROR' ? '连接测试失败，请修改配置后重试' : `连接正常，发现 ${testResult.assetCount} 个资产`} showIcon type={testResult.status === 'ERROR' ? 'error' : testResult.status === 'HEALTHY_RESTRICTED' ? 'warning' : 'success'} />{testResult.stages.map((stage) => <div className="test-stage" key={stage.stage}><Tag color={stage.status === 'PASSED' ? 'success' : stage.status === 'WARNING' ? 'warning' : stage.status === 'FAILED' ? 'error' : 'default'}>{stage.status}</Tag><strong>{stageName(stage.stage)}</strong><Text type="secondary">{stage.message}</Text></div>)}</Card>}</div>}
          {step === 3 && type && testResult && <Card className="wizard-card" title="确认创建"><Alert message="测试结果仍在 15 分钟有效期内" description={`请求编号：${testResult.requestId}`} showIcon type="success" /><Descriptions bordered column={2} items={[{ key: 'name', label: '连接名称', children: values.name }, { key: 'type', label: '连接类型', children: sourceTypes.find((item) => item.value === type)?.title }, { key: 'owner', label: '负责人', children: displayName }, { key: 'target', label: '目标地址', children: String(values.endpoint ?? values.host ?? values.bootstrapServers ?? values.serviceUrl) }, { key: 'credential', label: '凭据', children: values.credentialMode === 'EXISTING' ? '使用已有凭据' : values.credentialMode === 'FILE' ? '使用预置 Secret' : '已配置（加密保存）' }, { key: 'result', label: '测试结果', children: <Tag color="success">{testResult.status}</Tag> }, { key: 'assets', label: '发现资产', children: testResult.assetCount }, { key: 'expires', label: '有效期', children: new Date(testResult.expiresAt).toLocaleString() }]} /></Card>}
        </div>
        <div className="wizard-footer"><Button onClick={cancel}>取消</Button><Space>{step > 0 && <Button onClick={() => setStep((current) => current - 1)}>上一步</Button>}{type === 'LOCAL_CSV' && step === 2 ? <Button loading={creating} onClick={() => void importLocalCsv()} type="primary">导入并识别</Button> : step < 3 && <Button disabled={step === 2 && !testResult?.testToken} onClick={() => void next()} type="primary">下一步</Button>}{type !== 'LOCAL_CSV' && step === 3 && <Button loading={creating} onClick={() => void create()} type="primary">创建连接</Button>}</Space></div>
      </Form>
    </div>
  );
}

function ConnectionFields({ type }: { type: DataSourceType }) {
  if (type === 'S3_CSV') return <><div className="form-grid"><Form.Item label="Endpoint" name="endpoint" rules={[{ required: true }]}><Input placeholder="https://s3.example.com" /></Form.Item><Form.Item label="Region" name="region"><Input /></Form.Item><Form.Item label="允许的 Bucket" name="bucket"><Input /></Form.Item><Form.Item label="允许的 Prefix" name="prefix"><Input /></Form.Item></div><Form.Item name="pathStyle" valuePropName="checked"><Checkbox>使用 Path Style</Checkbox></Form.Item><Form.Item label="连接超时（秒）" name="timeoutSeconds"><InputNumber max={60} min={2} /></Form.Item></>;
  if (type === 'MYSQL' || type === 'POSTGRESQL') return <><div className="form-grid"><Form.Item label="Host" name="host" rules={[{ required: true }]}><Input /></Form.Item><Form.Item label="Port" name="port" rules={[{ required: true }]}><InputNumber max={65535} min={1} /></Form.Item><Form.Item label="Database" name="database" rules={[{ required: true }]}><Input /></Form.Item><Form.Item label="允许的 Schema" name="schema"><Input /></Form.Item></div><Space><Form.Item name="tls" valuePropName="checked"><Checkbox>启用 TLS</Checkbox></Form.Item><Form.Item label="连接超时（秒）" name="timeoutSeconds"><InputNumber max={60} min={2} /></Form.Item></Space></>;
  if (type === 'KAFKA') return <><Form.Item label="Bootstrap Servers" name="bootstrapServers" rules={[{ required: true }]}><Input placeholder="broker.example.com:9092" /></Form.Item><div className="form-grid"><Form.Item label="Security Protocol" name="securityProtocol"><Select options={['PLAINTEXT', 'SASL_PLAINTEXT', 'SASL_SSL', 'SSL'].map((value) => ({ value }))} /></Form.Item><Form.Item label="SASL Mechanism" name="saslMechanism"><Select allowClear options={['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512'].map((value) => ({ value }))} /></Form.Item></div><Form.Item label="连接超时（秒）" name="timeoutSeconds"><InputNumber max={60} min={2} /></Form.Item></>;
  return <><div className="form-grid"><Form.Item label="Service URL" name="serviceUrl" rules={[{ required: true }]}><Input placeholder="pulsar://broker.example.com:6650" /></Form.Item><Form.Item label="Admin URL" name="adminUrl" rules={[{ required: true }]}><Input placeholder="https://broker.example.com:8443" /></Form.Item><Form.Item label="Tenant" name="tenant" rules={[{ required: true }]}><Input /></Form.Item><Form.Item label="Namespace" name="namespace" rules={[{ required: true }]}><Input /></Form.Item></div><Alert message="平台会拒绝 persistent://platform/* 及 platform tenant；消费位置和 Subscription 在管道页配置。" type="warning" /><Form.Item label="连接超时（秒）" name="timeoutSeconds"><InputNumber max={60} min={2} /></Form.Item></>;
}

function CredentialFields({ credentials, mode, type }: { credentials: CredentialSummary[]; mode: FormValues['credentialMode']; type: DataSourceType }) {
  if (mode === 'EXISTING') return <Form.Item label="已有凭据" name="existingSecretRef" rules={[{ required: true }]}><Select options={credentials.map((item) => ({ value: item.id, label: `${item.name} · ${item.provider}` }))} placeholder="选择可用凭据" /></Form.Item>;
  if (mode === 'FILE') return <><Form.Item label="凭据名称" name="credentialName" rules={[{ required: true }]}><Input /></Form.Item><Alert description="平台会从部署时配置的受保护 Secret 读取凭据；不会在页面、审计或 API 响应中显示其内容。" message="使用预置凭据" showIcon type="info" /><Form.Item hidden name="accessKeyRef" rules={[{ required: true }]}><Input /></Form.Item><Form.Item hidden name="secretKeyRef" rules={[{ required: true }]}><Input /></Form.Item></>;
  return <><Form.Item label="凭据名称" name="credentialName" rules={[{ required: true }]}><Input /></Form.Item>{type === 'S3_CSV' && <div className="form-grid"><Form.Item label="Access Key" name="accessKey" rules={[{ required: true }]}><Input.Password autoComplete="off" /></Form.Item><Form.Item label="Secret Key" name="secretKey" rules={[{ required: true }]}><Input.Password autoComplete="new-password" /></Form.Item></div>}{(type === 'MYSQL' || type === 'POSTGRESQL' || type === 'KAFKA') && <div className="form-grid"><Form.Item label="Username" name="username" rules={[{ required: true }]}><Input autoComplete="off" /></Form.Item><Form.Item label="Password" name="password" rules={[{ required: true }]}><Input.Password autoComplete="new-password" /></Form.Item></div>}{type === 'EXTERNAL_PULSAR' && <Form.Item label="Token" name="token" rules={[{ required: true }]}><Input.Password autoComplete="new-password" /></Form.Item>}</>;
}

function stageName(stage: string) { return ({ NETWORK: '地址解析与网络', TLS: 'TLS 握手', AUTHENTICATION: '身份认证', METADATA: '元数据访问', DISCOVERY: '资产发现' } as Record<string, string>)[stage] ?? stage; }
