import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Radio,
  Result,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import {
  ArrowLeftOutlined,
  DatabaseOutlined,
  EllipsisOutlined,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { ApiProblem, dataConnectionsApi } from './services/dataConnections';
import type { AssetPreview, CredentialInput, CredentialSummary, DataSource, DataSourceAsset, Overview, PipelineRun, PipelineSummary } from './types';

const { Paragraph, Text, Title } = Typography;

interface Props {
  accessToken: string;
  assetId?: string;
  id: string;
  isAdmin: boolean;
  navigate: (path: string) => void;
}

export default function DataConnectionDetailPage({ accessToken, assetId, id, isAdmin, navigate }: Props) {
  const api = useMemo(() => dataConnectionsApi(accessToken), [accessToken]);
  const params = new URLSearchParams(window.location.search);
  const [tab, setTab] = useState(params.get('tab') ?? (assetId ? 'assets' : 'overview'));
  const [source, setSource] = useState<DataSource>();
  const [overview, setOverview] = useState<Overview>();
  const [assets, setAssets] = useState<DataSourceAsset[]>([]);
  const [selectedAsset, setSelectedAsset] = useState<DataSourceAsset>();
  const [pipelines, setPipelines] = useState<PipelineSummary[]>([]);
  const [runs, setRuns] = useState<PipelineRun[]>([]);
  const [preview, setPreview] = useState<AssetPreview>();
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState<ApiProblem>();
  const [rotateOpen, setRotateOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [sourceValue, overviewValue, assetsValue, pipelinesValue, runsValue] = await Promise.all([
        api.get(id), api.overview(id), api.listAssets(id), api.pipelines(id), api.runs(id),
      ]);
      setSource(sourceValue); setOverview(overviewValue); setAssets(assetsValue.items); setPipelines(pipelinesValue); setRuns(runsValue); setProblem(undefined);
      if (assetId) setSelectedAsset(await api.getAsset(id, assetId));
    } catch (cause) { setProblem(cause instanceof ApiProblem ? cause : new ApiProblem('连接详情加载失败')); }
    finally { setLoading(false); }
  }, [api, assetId, id]);

  useEffect(() => { void load(); }, [load]);

  const changeTab = (key: string) => { setTab(key); window.history.replaceState({}, '', `/data/connections/${id}?tab=${key}`); };
  const openAsset = async (asset: DataSourceAsset) => { setPreview(undefined); setSelectedAsset(await api.getAsset(id, asset.id)); window.history.pushState({}, '', `/data/connections/${id}/assets/${asset.id}`); };
  const closeAsset = () => { setSelectedAsset(undefined); setPreview(undefined); window.history.pushState({}, '', `/data/connections/${id}?tab=assets`); };

  async function retest() { try { const result = await api.retest(id); message[result.status === 'ERROR' ? 'error' : 'success'](result.status === 'ERROR' ? '连接测试失败' : '连接测试通过'); await load(); } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '测试失败'); } }
  async function discover() { try { const run = await api.discover(id); message.success(`资产刷新完成：${run.discoveredCount} 个`); await load(); } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '刷新失败'); } }
  async function toggle() { if (!source) return; try { if (source.status === 'DISABLED') await api.enable(id); else await api.disable(id); message.success(source.status === 'DISABLED' ? '连接已恢复，请重新测试' : '连接已停用'); await load(); } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '操作失败'); } }
  async function remove() { try { await api.delete(id); message.success('连接已删除'); navigate('/data/connections'); } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '删除失败'); } }
  function confirmToggle() {
    if (!source) return;
    const restoring = source.status === 'DISABLED';
    Modal.confirm({
      title: restoring ? '确认恢复连接？' : '确认停用连接？',
      content: restoring
        ? '恢复后必须重新测试，测试通过前不能启动发现、预览或管道。'
        : `关联管道 ${source.pipelineReferenceCount} 个，活动运行 ${source.activeRunCount} 个。停用会阻止新任务，但保留历史运行、血缘、审计和对象。`,
      okText: restoring ? '恢复连接' : '停用连接',
      onOk: toggle,
    });
  }
  function confirmRemove() {
    if (!source) return;
    Modal.confirm({
      title: `永久删除“${source.name}”？`,
      content: `将同时删除关联的 ${source.pipelineReferenceCount} 个管道及其运行记录。此操作不可撤销。`,
      okButtonProps: { danger: true },
      okText: '永久删除',
      onOk: remove,
    });
  }
  async function inferSchema() { if (!selectedAsset) return; try { await api.inferSchema(id, selectedAsset.id); message.success('Schema 推断完成'); setSelectedAsset(await api.getAsset(id, selectedAsset.id)); } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '推断失败'); } }
  async function loadPreview(limit = 50) { if (!selectedAsset) return; try { setPreview(await api.preview(id, selectedAsset.id, limit)); } catch (cause) { message.error(cause instanceof ApiProblem ? cause.message : '预览失败'); } }

  if (loading) return <div className="center-state"><Spin size="large" /><Text>正在加载连接详情…</Text></div>;
  if (problem || !source || !overview) return <Result extra={<Button onClick={() => void load()}>重新加载</Button>} status="error" subTitle={problem?.requestId ? `请求编号：${problem.requestId}` : undefined} title={problem?.message ?? '连接不存在'} />;

  const assetColumns: ColumnsType<DataSourceAsset> = [
    { title: '名称与路径', dataIndex: 'name', render: (_, asset) => <><a onClick={() => void openAsset(asset)}>{asset.name}</a><br /><Text type="secondary">{asset.fullPath}</Text></> },
    { title: '类型', dataIndex: 'assetType', width: 100 }, { title: 'Schema', dataIndex: 'schemaStatus', width: 110, render: (value) => <Tag color={value === 'CHANGED' ? 'warning' : value === 'READY' ? 'success' : 'default'}>{value}</Tag> },
    { title: '字段', dataIndex: 'fieldCount', width: 75 }, { title: 'Partition', dataIndex: 'partitionCount', width: 90, render: (value) => value ?? '-' },
    { title: '权限', dataIndex: 'permissionStatus', width: 115, render: (value) => <Tag>{value}</Tag> }, { title: '发现时间', dataIndex: 'discoveredAt', width: 170, render: (value) => new Date(value).toLocaleString() },
  ];
  const connectionUsable = source.status === 'HEALTHY' || source.status === 'HEALTHY_RESTRICTED';

  const tabItems = [
    { key: 'overview', label: '概览', children: <OverviewTab overview={overview} /> },
    { key: 'assets', label: `资产 (${assets.length})`, children: <div><div className="tab-toolbar"><Input.Search allowClear onSearch={async (value) => setAssets((await api.listAssets(id, value)).items)} placeholder="搜索资产路径" style={{ width: 320 }} /><Button disabled={!connectionUsable} icon={<ReloadOutlined />} onClick={() => void discover()}>刷新资产</Button></div><Table columns={assetColumns} dataSource={assets} locale={{ emptyText: <Empty description="尚未发现资产" /> }} pagination={{ pageSize: 20 }} rowKey="id" size="small" /></div> },
    { key: 'pipelines', label: '同步任务', children: <PipelinesTab disabled={!connectionUsable} id={id} navigate={navigate} pipelines={pipelines} /> },
    { key: 'runs', label: '运行记录', children: <RunsTab runs={runs} /> },
    { key: 'settings', label: '设置', children: <SettingsTab navigate={navigate} onRotate={() => setRotateOpen(true)} source={source} /> },
  ];

  return (
    <div className="connection-detail-page">
      {params.get('created') === 'true' && <Alert closable message="连接创建成功" description="现在可以浏览资产或创建管道；平台不会自动启动数据消费。" showIcon type="success" />}
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/data/connections')} type="link">数据连接</Button>
      <div className="connection-header"><div className="connection-identity"><div className="connection-logo"><DatabaseOutlined /></div><div><Space><Title level={2}>{source.name}</Title><Tag color={source.status === 'HEALTHY' ? 'success' : source.status === 'ERROR' ? 'error' : source.status === 'HEALTHY_RESTRICTED' ? 'warning' : 'default'}>{source.status}</Tag><Tag>{source.syncStatus}</Tag></Space><Paragraph>{source.description || '暂无说明'} · {source.ownerName} · 最近检查 {source.lastCheckedAt ? new Date(source.lastCheckedAt).toLocaleString() : '从未'}</Paragraph></div></div><Space><Button disabled={source.status === 'DISABLED'} icon={<ExperimentOutlined />} onClick={() => void retest()}>测试连接</Button><Button disabled={!connectionUsable} icon={<PlusOutlined />} onClick={() => navigate(`/data/pipelines/new?connectionId=${id}`)} type="primary">创建管道</Button><Dropdown menu={{ items: [{ key: 'edit', label: '编辑配置', onClick: () => navigate(`/data/connections/${id}/edit`) }, { key: 'rotate', label: '轮换凭据', onClick: () => setRotateOpen(true) }, { type: 'divider' }, { key: 'toggle', label: source.status === 'DISABLED' ? '恢复连接' : '停用连接', onClick: confirmToggle }, { key: 'delete', danger: true, label: '永久删除', onClick: confirmRemove }] }} trigger={['click']}><Button aria-label="更多操作" icon={<EllipsisOutlined />} /></Dropdown></Space></div>
      <Tabs activeKey={tab} items={tabItems} onChange={changeTab} />
      <Drawer destroyOnClose onClose={closeAsset} open={Boolean(selectedAsset)} title={selectedAsset?.name} width={720}>
        {selectedAsset && <Tabs items={[
          { key: 'summary', label: '概览', children: <Descriptions bordered column={1} items={[{ key: 'path', label: '完整路径', children: selectedAsset.fullPath }, { key: 'type', label: '类型', children: selectedAsset.assetType }, { key: 'permission', label: '读取权限', children: selectedAsset.permissionStatus }, { key: 'version', label: 'Schema 版本', children: selectedAsset.schemaVersion }, { key: 'discovered', label: '发现时间', children: new Date(selectedAsset.discoveredAt).toLocaleString() }]} /> },
          { key: 'schema', label: `Schema (${selectedAsset.fields.length})`, children: <><Button onClick={() => void inferSchema()}>重新推断 Schema</Button><Table dataSource={selectedAsset.fields} pagination={false} rowKey="id" size="small" columns={[{ title: '字段', dataIndex: 'name' }, { title: '推断类型', dataIndex: 'inferredType' }, { title: '原始类型', dataIndex: 'originalType' }, { title: '可空', dataIndex: 'nullable', render: (value) => value ? '是' : '否' }, { title: '敏感', dataIndex: 'sensitive', render: (value) => value ? '已脱敏' : '否' }]} /></> },
          { key: 'preview', label: '数据预览', children: <><Space><Button disabled={!connectionUsable} onClick={() => void loadPreview(50)} type="primary">预览 50 行</Button><Button disabled={!connectionUsable} onClick={() => void loadPreview(100)}>预览 100 行</Button></Space>{preview && <><Alert message={`响应上限 ${(preview.maxBytes / 1024 / 1024).toFixed(0)} MiB${preview.truncated ? '，结果已截断' : ''}`} type="info" /><Table columns={preview.columns.map((column) => ({ title: column, dataIndex: column, key: column }))} dataSource={preview.rows.map((row, index) => ({ ...row, key: index }))} pagination={false} scroll={{ x: true }} size="small" /></>}</> },
          { key: 'usage', label: '使用情况', children: selectedAsset.usedByPipeline ? <Alert message="此资产已被管道引用" type="warning" /> : <Empty description="暂无下游引用" /> },
        ]} />}
      </Drawer>
      <RotateCredentialModal accessToken={accessToken} id={id} isAdmin={isAdmin} onClose={() => setRotateOpen(false)} onSuccess={async () => { setRotateOpen(false); await load(); }} open={rotateOpen} source={source} />
    </div>
  );
}

function OverviewTab({ overview }: { overview: Overview }) {
  const source = overview.connection;
  return <div className="overview-grid"><Card title="连接信息"><Descriptions column={1} size="small" items={[{ key: 'type', label: '类型', children: source.type }, { key: 'target', label: '非敏感地址', children: String(source.config.endpoint ?? source.config.host ?? source.config.bootstrapServers ?? source.config.serviceUrl) }, { key: 'owner', label: '负责人', children: source.ownerName }, { key: 'credential', label: '凭据状态', children: `${source.credential.status} · ${source.credential.provider}` }, { key: 'created', label: '创建时间', children: new Date(source.createdAt).toLocaleString() }]} /></Card><Card title="健康状态">{overview.health.length ? overview.health.map((stage) => <div className="health-row" key={stage.stage}><Tag color={stage.status === 'PASSED' ? 'success' : stage.status === 'WARNING' ? 'warning' : 'error'}>{stage.status}</Tag><strong>{stage.stage}</strong><Text type="secondary">{stage.message}</Text></div>) : <Empty description="尚无测试记录" />}</Card><Card title="资产摘要"><Descriptions column={1} items={Object.entries(overview.assetSummary).map(([key, value]) => ({ key, label: key, children: value }))} /><Text type="secondary">共 {source.assetCount} 个可用资产</Text></Card><Card title="同步任务摘要">{Object.keys(overview.pipelineSummary).length ? <Descriptions column={1} items={Object.entries(overview.pipelineSummary).map(([key, value]) => ({ key, label: key, children: value }))} /> : <Empty description="暂无同步任务" />}</Card><Card className="wide-card" title="最近活动">{overview.recentActivity.length ? overview.recentActivity.map((event) => <div className="activity-row" key={event.id}><Text>{event.summary}</Text><Text type="secondary">{event.actorName} · {new Date(event.occurredAt).toLocaleString()}</Text></div>) : <Empty description="暂无活动" />}</Card></div>;
}

function PipelinesTab({ disabled, id, navigate, pipelines }: { disabled: boolean; id: string; navigate: (path: string) => void; pipelines: PipelineSummary[] }) {
  return <><div className="tab-toolbar"><Input.Search placeholder="搜索管道" style={{ width: 320 }} /><Button disabled={disabled} icon={<PlusOutlined />} onClick={() => navigate(`/data/pipelines/new?connectionId=${id}`)} type="primary">新建管道</Button></div><Table dataSource={pipelines} locale={{ emptyText: <Empty description="暂无引用此连接的同步任务" /> }} rowKey="id" size="small" columns={[{ title: '管道名称', dataIndex: 'name' }, { title: '源资产', dataIndex: 'sourceAsset' }, { title: '模式', dataIndex: 'mode' }, { title: '状态', dataIndex: 'status' }, { title: '负责人', dataIndex: 'ownerName' }]} /></>;
}

function RunsTab({ runs }: { runs: PipelineRun[] }) {
  return <><div className="tab-toolbar"><Input.Search placeholder="搜索运行 ID / 管道" style={{ width: 320 }} /></div><Table dataSource={runs} locale={{ emptyText: <Empty description="暂无运行记录" /> }} rowKey="id" size="small" columns={[{ title: '运行 ID', dataIndex: 'id', ellipsis: true }, { title: '管道', dataIndex: 'pipelineName' }, { title: '源资产', dataIndex: 'sourceAsset' }, { title: '触发方式', dataIndex: 'triggerType' }, { title: '开始时间', dataIndex: 'startedAt', render: (value) => new Date(value).toLocaleString() }, { title: '读取', dataIndex: 'readCount' }, { title: '写入', dataIndex: 'writtenCount' }, { title: '拒绝', dataIndex: 'rejectedCount' }, { title: '状态', dataIndex: 'status' }]} /></>;
}

function SettingsTab({ navigate, onRotate, source }: { navigate: (path: string) => void; onRotate: () => void; source: DataSource }) {
  return <div className="settings-stack"><Card extra={<Button onClick={() => navigate(`/data/connections/${source.id}/edit`)}>编辑</Button>} title="基本信息"><Descriptions column={2} items={[{ key: 'name', label: '名称', children: source.name }, { key: 'owner', label: '负责人', children: source.ownerName }, { key: 'description', label: '说明', children: source.description || '-' }, { key: 'tags', label: '标签', children: source.tags.map((tag) => <Tag key={tag}>{tag}</Tag>) }]} /></Card><Card title="连接参数"><Descriptions column={2} items={Object.entries(source.config).map(([key, value]) => ({ key, label: key, children: String(value) }))} /></Card><Card extra={<Button onClick={onRotate}>轮换凭据</Button>} title="凭据与证书"><Descriptions column={2} items={[{ key: 'name', label: '凭据名称', children: source.credential.name }, { key: 'provider', label: 'Provider', children: source.credential.provider }, { key: 'type', label: '类型', children: source.credential.credentialType }, { key: 'refs', label: '引用数量', children: source.credential.referenceCount }]} /><Alert message="凭据正文不可读取。轮换会先测试新凭据；失败时旧凭据继续有效。" type="info" /></Card><Card title="访问范围与权限"><Paragraph>配置只包含经过类型校验的 Bucket/Prefix、Schema 或 Topic 范围；缩小范围需先检查受影响管道。</Paragraph></Card><Card className="danger-zone" title="危险操作"><Paragraph>停用会阻止新发现、预览和管道启动，但保留历史运行、血缘、审计和已生成对象。</Paragraph></Card></div>;
}

interface RotateValues {
  accessKey?: string;
  accessKeyRef?: string;
  credentialMode: 'EXISTING' | 'FILE' | 'MANAGED';
  credentialName?: string;
  existingSecretRef?: string;
  password?: string;
  secretKey?: string;
  secretKeyRef?: string;
  token?: string;
  username?: string;
}

function RotateCredentialModal({ accessToken, id, isAdmin, onClose, onSuccess, open, source }: {
  accessToken: string;
  id: string;
  isAdmin: boolean;
  onClose: () => void;
  onSuccess: () => Promise<void>;
  open: boolean;
  source: DataSource;
}) {
  const api = useMemo(() => dataConnectionsApi(accessToken), [accessToken]);
  const [form] = Form.useForm<RotateValues>();
  const [credentials, setCredentials] = useState<CredentialSummary[]>([]);
  const [rotating, setRotating] = useState(false);
  const mode = Form.useWatch('credentialMode', form) ?? 'MANAGED';

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({ credentialMode: isAdmin ? 'FILE' : 'MANAGED', credentialName: `${source.name} 轮换凭据` });
    void api.listCredentials().then(setCredentials).catch(() => setCredentials([]));
  }, [api, form, isAdmin, open, source.name]);

  async function submit() {
    const values = await form.validateFields();
    let credential: CredentialInput;
    if (values.credentialMode === 'EXISTING') {
      credential = { mode: 'EXISTING', existingSecretRef: values.existingSecretRef };
    } else if (values.credentialMode === 'FILE') {
      credential = {
        mode: 'FILE',
        name: values.credentialName,
        fileRefs: source.type === 'S3_CSV'
          ? { accessKey: `file://${values.accessKeyRef}`, secretKey: `file://${values.secretKeyRef}` }
          : source.type === 'EXTERNAL_PULSAR'
            ? { token: `file://${values.secretKeyRef}` }
            : { password: `file://${values.secretKeyRef}`, username: `file://${values.accessKeyRef}` },
      };
    } else {
      const secretValues: Record<string, string> = {};
      if (source.type === 'S3_CSV') { secretValues.accessKey = values.accessKey ?? ''; secretValues.secretKey = values.secretKey ?? ''; }
      if (source.type === 'MYSQL' || source.type === 'POSTGRESQL' || source.type === 'KAFKA') { secretValues.password = values.password ?? ''; secretValues.username = values.username ?? ''; }
      if (source.type === 'EXTERNAL_PULSAR') secretValues.token = values.token ?? '';
      credential = { mode: 'MANAGED', name: values.credentialName, values: secretValues };
    }
    setRotating(true);
    try {
      await api.rotate(id, credential);
      message.success('新凭据测试通过并已完成轮换');
      await onSuccess();
    } catch (cause) {
      message.error(cause instanceof ApiProblem ? `${cause.message}；旧凭据仍然有效` : '轮换失败；旧凭据仍然有效');
    } finally { setRotating(false); }
  }

  return <Modal confirmLoading={rotating} destroyOnClose onCancel={onClose} onOk={() => void submit()} okText="测试并轮换" open={open} title="轮换连接凭据">
    <Alert message="新凭据通过真实连接测试后才会原子替换；测试失败不会中断当前连接。" showIcon type="info" />
    <Form form={form} layout="vertical">
      <Form.Item label="凭据来源" name="credentialMode">
        <Radio.Group>
          <Radio value="MANAGED">新建平台凭据</Radio>
          <Radio value="EXISTING">使用已有凭据</Radio>
          {isAdmin && <Radio value="FILE">预置 Docker Secret</Radio>}
        </Radio.Group>
      </Form.Item>
      {mode === 'EXISTING' ? <Form.Item label="已有凭据" name="existingSecretRef" rules={[{ required: true }]}><Select options={credentials.filter((item) => item.id !== source.credential.id && item.credentialType === source.type).map((item) => ({ label: `${item.name} · ${item.provider}`, value: item.id }))} /></Form.Item> : <>
        <Form.Item label="新凭据名称" name="credentialName" rules={[{ required: true }]}><Input /></Form.Item>
        {mode === 'FILE' ? <>
          {source.type !== 'EXTERNAL_PULSAR' && <Form.Item label={source.type === 'S3_CSV' ? 'Access Key Secret 名称' : 'Username Secret 名称'} name="accessKeyRef" rules={[{ required: true }]}><Input placeholder={source.type === 'S3_CSV' ? 'minio_root_user' : 'database_username'} /></Form.Item>}
          <Form.Item label={source.type === 'S3_CSV' ? 'Secret Key Secret 名称' : source.type === 'EXTERNAL_PULSAR' ? 'Token Secret 名称' : 'Password Secret 名称'} name="secretKeyRef" rules={[{ required: true }]}><Input /></Form.Item>
        </> : <>
          {source.type === 'S3_CSV' && <><Form.Item label="Access Key" name="accessKey" rules={[{ required: true }]}><Input.Password /></Form.Item><Form.Item label="Secret Key" name="secretKey" rules={[{ required: true }]}><Input.Password /></Form.Item></>}
          {(source.type === 'MYSQL' || source.type === 'POSTGRESQL' || source.type === 'KAFKA') && <><Form.Item label="Username" name="username" rules={[{ required: true }]}><Input /></Form.Item><Form.Item label="Password" name="password" rules={[{ required: true }]}><Input.Password /></Form.Item></>}
          {source.type === 'EXTERNAL_PULSAR' && <Form.Item label="Token" name="token" rules={[{ required: true }]}><Input.Password /></Form.Item>}
        </>}
      </>}
    </Form>
  </Modal>;
}
