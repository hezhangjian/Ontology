import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Avatar,
  Button,
  Drawer,
  Dropdown,
  Empty,
  Flex,
  Input,
  message,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CloudSyncOutlined,
  DatabaseOutlined,
  EllipsisOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { ApiProblem, dataConnectionsApi } from './services/dataConnections';
import type { ConnectionStatus, DataSource, DataSourcePage, DataSourceType } from './types';

const { Paragraph, Text, Title } = Typography;

const typeLabels: Record<DataSourceType, string> = {
  EXTERNAL_PULSAR: '外部 Pulsar',
  KAFKA: 'Kafka',
  MYSQL: 'MySQL',
  POSTGRESQL: 'PostgreSQL',
  S3_CSV: 'MinIO/S3 CSV',
};

const statusLabels: Record<ConnectionStatus, string> = {
  DISABLED: '已停用',
  ERROR: '异常',
  HEALTHY: '正常',
  HEALTHY_RESTRICTED: '正常 · 发现受限',
  TESTING: '测试中',
  UNTESTED: '未测试',
};

const statusColors: Record<ConnectionStatus, string> = {
  DISABLED: 'default',
  ERROR: 'error',
  HEALTHY: 'success',
  HEALTHY_RESTRICTED: 'warning',
  TESTING: 'processing',
  UNTESTED: 'default',
};

const syncLabels: Record<DataSource['syncStatus'], string> = {
  ALL_FAILURE: '全部失败',
  IDLE: '空闲',
  NO_TASKS: '无任务',
  PARTIAL_FAILURE: '部分失败',
  RUNNING: '运行中',
  STREAMING: '实时同步',
};

interface Props {
  accessToken: string;
  isAdmin: boolean;
  navigate: (path: string) => void;
}

export default function DataConnectionListPage({ accessToken, navigate }: Props) {
  const api = useMemo(() => dataConnectionsApi(accessToken), [accessToken]);
  const initial = useMemo(() => new URLSearchParams(window.location.search), []);
  const [search, setSearch] = useState(initial.get('search') ?? '');
  const [type, setType] = useState(initial.get('type') ?? '');
  const [status, setStatus] = useState(initial.get('status') ?? '');
  const [owner, setOwner] = useState(initial.get('owner') ?? '');
  const [page, setPage] = useState(Number(initial.get('page') ?? 0));
  const [data, setData] = useState<DataSourcePage>();
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState<ApiProblem>();
  const [diagnostic, setDiagnostic] = useState<DataSource>();
  const [testingId, setTestingId] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    const params = new URLSearchParams();
    if (search) params.set('search', search);
    if (type) params.set('type', type);
    if (status) params.set('status', status);
    if (owner) params.set('owner', owner);
    if (page) params.set('page', String(page));
    params.set('size', '20');
    window.history.replaceState({}, '', `/data/connections?${params.toString()}`);
    try {
      setData(await api.list(params.toString()));
      setProblem(undefined);
    } catch (cause) {
      setProblem(cause instanceof ApiProblem ? cause : new ApiProblem('连接列表加载失败'));
    } finally {
      setLoading(false);
    }
  }, [api, owner, page, search, status, type]);

  useEffect(() => { void load(); }, [load]);

  async function testConnection(source: DataSource) {
    setTestingId(source.id);
    try {
      const result = await api.retest(source.id);
      message.success(result.status === 'HEALTHY_RESTRICTED' ? '连接正常，资产发现受限' : '连接测试通过');
      await load();
    } catch (cause) {
      message.error(cause instanceof ApiProblem ? cause.message : '连接测试失败');
      await load();
    } finally {
      setTestingId(undefined);
    }
  }

  async function toggleDisabled(source: DataSource) {
    try {
      if (source.status === 'DISABLED') await api.enable(source.id); else await api.disable(source.id);
      message.success(source.status === 'DISABLED' ? '连接已恢复，请重新测试' : '连接已停用');
      await load();
    } catch (cause) {
      message.error(cause instanceof ApiProblem ? cause.message : '操作失败');
    }
  }

  async function deleteConnection(source: DataSource) {
    try {
      await api.delete(source.id);
      message.success('连接已永久删除');
      await load();
    } catch (cause) {
      message.error(cause instanceof ApiProblem ? cause.message : '删除失败');
    }
  }

  function confirmToggle(source: DataSource) {
    const restoring = source.status === 'DISABLED';
    Modal.confirm({
      title: restoring ? `恢复“${source.name}”？` : `停用“${source.name}”？`,
      content: restoring
        ? '恢复后必须重新测试，测试通过前不能启动发现、预览或管道。'
        : `关联管道 ${source.pipelineReferenceCount} 个，活动运行 ${source.activeRunCount} 个。历史运行、血缘、审计和对象会保留。`,
      okText: restoring ? '恢复连接' : '停用连接',
      onOk: () => toggleDisabled(source),
    });
  }

  function confirmDelete(source: DataSource) {
    Modal.confirm({
      title: `永久删除“${source.name}”？`,
      content: `将同时删除关联的 ${source.pipelineReferenceCount} 个管道及其运行记录。此操作不可撤销。`,
      okButtonProps: { danger: true },
      okText: '永久删除',
      onOk: () => deleteConnection(source),
    });
  }

  const columns: ColumnsType<DataSource> = [
    {
      title: '连接名称', dataIndex: 'name', width: 280,
      render: (_, source) => (
        <Flex align="center" gap={10}>
          <div className="connection-type-icon"><DatabaseOutlined /></div>
          <div><a onClick={(event) => { event.stopPropagation(); navigate(`/data/connections/${source.id}`); }}>{source.name}</a><br /><Text type="secondary">{source.description || '暂无说明'}</Text></div>
        </Flex>
      ),
    },
    { title: '类型', dataIndex: 'type', width: 140, render: (value: DataSourceType) => typeLabels[value] },
    {
      title: '连接状态', dataIndex: 'status', width: 145,
      render: (value: ConnectionStatus, source) => <Tag color={statusColors[value]} onClick={(event) => { event.stopPropagation(); if (value === 'ERROR') setDiagnostic(source); }}>{statusLabels[value]}</Tag>,
    },
    { title: '资产', dataIndex: 'assetCount', width: 80, sorter: (a, b) => a.assetCount - b.assetCount },
    { title: '同步状态', dataIndex: 'syncStatus', width: 110, render: (value: DataSource['syncStatus']) => syncLabels[value] },
    {
      title: '最近检查', dataIndex: 'lastCheckedAt', width: 130,
      render: (value?: string) => value ? <Tooltip title={new Date(value).toLocaleString()}>{relativeTime(value)}</Tooltip> : '从未检查',
    },
    {
      title: '负责人', dataIndex: 'ownerName', width: 135,
      render: (value: string) => <Space size={6}><Avatar size={24}>{value.slice(0, 1)}</Avatar>{value}</Space>,
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 125, render: (value: string) => relativeTime(value) },
    {
      title: '', key: 'actions', width: 54, fixed: 'right',
      render: (_, source) => (
        <Dropdown
          menu={{
            onClick: ({ domEvent }) => domEvent.stopPropagation(),
            items: [
              { key: 'view', label: '查看详情', onClick: () => navigate(`/data/connections/${source.id}`) },
              { key: 'test', label: '测试连接', disabled: source.status === 'DISABLED', onClick: () => void testConnection(source) },
              { key: 'assets', label: '浏览资产', onClick: () => navigate(`/data/connections/${source.id}?tab=assets`) },
              { key: 'pipeline', label: '创建管道', disabled: !isUsable(source), onClick: () => navigate(`/data/pipelines/new?connectionId=${source.id}`) },
              { key: 'edit', label: '编辑配置', onClick: () => navigate(`/data/connections/${source.id}/edit`) },
              { type: 'divider' },
              { key: 'disable', label: source.status === 'DISABLED' ? '恢复连接' : '停用连接', onClick: () => confirmToggle(source) },
              { key: 'delete', danger: true, label: '永久删除', onClick: () => confirmDelete(source) },
            ],
          }}
          trigger={['click']}
        ><Button aria-label={`${source.name} 更多操作`} icon={<EllipsisOutlined />} loading={testingId === source.id} onClick={(event) => event.stopPropagation()} type="text" /></Dropdown>
      ),
    },
  ];

  const reset = () => { setSearch(''); setType(''); setStatus(''); setOwner(''); setPage(0); };

  return (
    <div className="connections-page">
      <div className="page-title-row">
        <div><Title level={2}>数据连接</Title><Paragraph>安全连接、测试并发现外部数据资产，供管道构建使用。</Paragraph></div>
        <Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button icon={<PlusOutlined />} onClick={() => navigate('/data/connections/new')} type="primary">新建连接</Button></Space>
      </div>
      <div className="compact-stats">
        <span>全部 <strong>{data?.counts.all ?? 0}</strong></span><span>正常 <strong>{data?.counts.healthy ?? 0}</strong></span><span>异常 <strong className="danger-text">{data?.counts.error ?? 0}</strong></span><span>未测试 <strong>{data?.counts.untested ?? 0}</strong></span>
      </div>
      <div className="filter-bar">
        <Input allowClear onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="搜索连接名称" prefix={<SearchOutlined />} value={search} />
        <Select allowClear onChange={(value) => { setType(value ?? ''); setPage(0); }} options={Object.entries(typeLabels).map(([value, label]) => ({ value, label }))} placeholder="连接类型" value={type || undefined} />
        <Select allowClear onChange={(value) => { setStatus(value ?? ''); setPage(0); }} options={Object.entries(statusLabels).map(([value, label]) => ({ value, label }))} placeholder="连接状态" value={status || undefined} />
        <Input allowClear onChange={(event) => { setOwner(event.target.value); setPage(0); }} placeholder="负责人 ID" value={owner} />
        <Button onClick={reset}>重置筛选</Button>
      </div>
      {problem && <Alert action={<Button onClick={() => void load()} size="small">重新加载</Button>} description={`请求编号：${problem.requestId ?? '未生成'}`} message={problem.message} showIcon type="error" />}
      <Table<DataSource>
        columns={columns}
        dataSource={data?.items}
        loading={loading}
        locale={{ emptyText: search || type || status || owner ? <Empty description="没有符合当前筛选条件的连接"><Button onClick={reset}>清除筛选</Button></Empty> : <Empty image={<CloudSyncOutlined />} description="支持 MinIO/S3 CSV、MySQL、PostgreSQL、Kafka 和外部 Pulsar"><Button onClick={() => navigate('/data/connections/new')} type="primary">新建第一个连接</Button></Empty> }}
        onRow={(source) => ({ onClick: () => navigate(`/data/connections/${source.id}`) })}
        pagination={{ current: page + 1, pageSize: 20, total: data?.total, showSizeChanger: false, onChange: (value) => setPage(value - 1) }}
        rowKey="id"
        scroll={{ x: 1320 }}
        size="small"
      />
      <Drawer onClose={() => setDiagnostic(undefined)} open={Boolean(diagnostic)} title="连接异常诊断" width={480}>
        {diagnostic?.lastError ? <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert message={diagnostic.lastError.reason} showIcon type="error" />
          <Text><strong>失败阶段：</strong>{diagnostic.lastError.stage}</Text><Text><strong>发生时间：</strong>{new Date(diagnostic.lastError.occurredAt).toLocaleString()}</Text><Text><strong>请求编号：</strong>{diagnostic.lastError.requestId}</Text><Text><strong>建议操作：</strong>{diagnostic.lastError.suggestion}</Text>
          <Space><Button onClick={() => navigator.clipboard.writeText(`${diagnostic.lastError?.stage} / ${diagnostic.lastError?.requestId}`)}>复制诊断信息</Button><Button onClick={() => void testConnection(diagnostic)} type="primary">重新测试</Button></Space>
        </Space> : <Empty description="暂无诊断信息" />}
      </Drawer>
    </div>
  );
}

function relativeTime(value: string) {
  const seconds = Math.max(1, Math.round((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
  return `${Math.floor(seconds / 86400)} 天前`;
}

function isUsable(source: DataSource) {
  return source.status === 'HEALTHY' || source.status === 'HEALTHY_RESTRICTED';
}
