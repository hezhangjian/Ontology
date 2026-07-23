import { BranchesOutlined, EllipsisOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Avatar, Button, Dropdown, Empty, Flex, Input, message, Modal, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiProblem } from '../data-connections/services/dataConnections';
import { pipelinesApi } from './services/pipelines';
import type { Pipeline, PipelineLifecycle, PipelineMode, PipelinePage, PipelineRunStatus } from './types';

const { Paragraph, Text, Title } = Typography;
const modeLabels: Record<PipelineMode, string> = { BATCH: '批处理', STREAMING: '流处理' };
const lifecycleLabels: Record<PipelineLifecycle, string> = { ARCHIVED: '已归档', DRAFT: '草稿', IN_REVIEW: '待审核', PAUSED: '已暂停', PUBLISHED: '已发布' };
const runStatusLabels: Record<PipelineRunStatus, string> = { DEGRADED: '降级', FAILED: '失败', HEALTHY: '健康', LIVE: '实时运行', NEVER_RUN: '从未运行', RUNNING: '运行中' };

export default function PipelineListPage({ accessToken, navigate }: { accessToken: string; isAdmin: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => pipelinesApi(accessToken), [accessToken]);
  const initial = useMemo(() => new URLSearchParams(window.location.search), []);
  const [search, setSearch] = useState(initial.get('search') ?? '');
  const [mode, setMode] = useState(initial.get('mode') ?? '');
  const [lifecycle, setLifecycle] = useState(initial.get('lifecycle') ?? '');
  const [runStatus, setRunStatus] = useState(initial.get('runStatus') ?? '');
  const [owner, setOwner] = useState(initial.get('owner') ?? '');
  const [page, setPage] = useState(Number(initial.get('page') ?? 0));
  const [data, setData] = useState<PipelinePage>();
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState<ApiProblem>();

  const load = useCallback(async () => {
    setLoading(true);
    const query = new URLSearchParams();
    if (search) query.set('search', search);
    if (mode) query.set('mode', mode);
    if (lifecycle) query.set('lifecycle', lifecycle);
    if (runStatus) query.set('runStatus', runStatus);
    if (owner) query.set('owner', owner);
    if (page) query.set('page', String(page));
    query.set('size', '20');
    window.history.replaceState({}, '', `/data/pipelines?${query.toString()}`);
    try { setData(await api.list(query.toString())); setProblem(undefined); }
    catch (cause) { setProblem(cause instanceof ApiProblem ? cause : new ApiProblem('管道列表加载失败')); }
    finally { setLoading(false); }
  }, [api, lifecycle, mode, owner, page, runStatus, search]);
  useEffect(() => { void load(); }, [load]);

  async function operation(label: string, action: () => Promise<unknown>) {
    try { await action(); message.success(label); await load(); }
    catch (cause) { message.error(cause instanceof Error ? cause.message : '操作失败'); }
  }

  const columns: ColumnsType<Pipeline> = [
    { title: '管道', dataIndex: 'name', width: 245, render: (_, pipeline) => <Flex align="center" gap={10}><div className="connection-type-icon"><BranchesOutlined /></div><div><a onClick={(event) => { event.stopPropagation(); navigate(`/data/pipelines/${pipeline.id}/edit`); }}>{pipeline.name}</a><br /><Text type="secondary">{pipeline.description || '暂无说明'}</Text></div></Flex> },
    { title: '源连接 / 资产', width: 190, render: (_, pipeline) => <><Text>{pipeline.dataSourceName}</Text><br /><Text type="secondary">{pipeline.sourceAssetName}</Text></> },
    { title: '目标', dataIndex: 'targetSummary', width: 160 },
    { title: '模式', dataIndex: 'mode', width: 90, render: (value: PipelineMode) => modeLabels[value] },
    { title: '生命周期', dataIndex: 'lifecycle', width: 100, render: (value: PipelineLifecycle) => <Tag color={value === 'PUBLISHED' ? 'blue' : value === 'IN_REVIEW' ? 'gold' : 'default'}>{lifecycleLabels[value]}</Tag> },
    { title: '运行状态', dataIndex: 'runStatus', width: 105, render: (value: PipelineRunStatus) => <Tag color={value === 'HEALTHY' || value === 'LIVE' ? 'success' : value === 'FAILED' ? 'error' : value === 'DEGRADED' ? 'warning' : value === 'RUNNING' ? 'processing' : 'default'}>{runStatusLabels[value]}</Tag> },
    { title: '调度', dataIndex: 'scheduleSummary', width: 130 },
    { title: '最近运行', dataIndex: 'lastRunAt', width: 120, render: (value?: string) => value ? <Tooltip title={new Date(value).toLocaleString()}>{relativeTime(value)}</Tooltip> : '从未运行' },
    { title: '负责人', dataIndex: 'ownerName', width: 130, render: (value: string) => <Space size={6}><Avatar size={22}>{value.slice(0, 1)}</Avatar>{value}</Space> },
    { title: '', fixed: 'right', width: 50, render: (_, pipeline) => <Dropdown menu={{ items: [
      { key: 'open', label: '打开编辑器', onClick: () => navigate(`/data/pipelines/${pipeline.id}/edit`) },
      { key: 'runs', label: '查看运行', onClick: () => navigate(`/data/pipelines/${pipeline.id}`) },
      { key: 'execute', label: pipeline.mode === 'BATCH' ? '立即运行' : pipeline.runStatus === 'LIVE' ? '停止流任务' : '启动流任务', disabled: pipeline.lifecycle !== 'PUBLISHED', onClick: () => void operation('运行操作已提交', () => pipeline.mode === 'BATCH' ? api.run(pipeline.id) : pipeline.runStatus === 'LIVE' ? api.stop(pipeline.id) : api.start(pipeline.id)) },
      { key: 'duplicate', label: '复制', onClick: () => void operation('已创建管道副本', () => api.duplicate(pipeline.id)) },
      { key: 'pause', label: pipeline.lifecycle === 'PAUSED' ? '恢复' : '暂停', disabled: !pipeline.publishedVersion, onClick: () => void operation(pipeline.lifecycle === 'PAUSED' ? '已恢复' : '已暂停', () => pipeline.lifecycle === 'PAUSED' ? api.resume(pipeline.id) : api.pause(pipeline.id)) },
      { key: 'archive', label: '归档', disabled: pipeline.lifecycle === 'ARCHIVED' || ['LIVE', 'RUNNING'].includes(pipeline.runStatus), onClick: () => Modal.confirm({ title: `归档“${pipeline.name}”？`, content: '不可变版本、运行历史和血缘将保留。', onOk: () => operation('已归档', () => api.archive(pipeline.id)) }) },
      { key: 'delete', danger: true, disabled: pipeline.lifecycle !== 'ARCHIVED', label: pipeline.lifecycle === 'ARCHIVED' ? '永久删除' : '永久删除（请先归档）', onClick: () => Modal.confirm({ title: `永久删除“${pipeline.name}”？`, content: '版本、运行记录、调度与映射将一并删除；若仍有关联 Dataset，平台会要求先删除 Dataset。此操作不可撤销。', okButtonProps: { danger: true }, okText: '永久删除', onOk: () => operation('已删除管道', () => api.delete(pipeline.id)) }) },
    ], onClick: ({ domEvent }) => domEvent.stopPropagation() }} trigger={['click']}><Button aria-label={`${pipeline.name} 更多操作`} icon={<EllipsisOutlined />} onClick={(event) => event.stopPropagation()} type="text" /></Dropdown> },
  ];
  const reset = () => { setSearch(''); setMode(''); setLifecycle(''); setRunStatus(''); setOwner(''); setPage(0); };
  return <div className="pipelines-page">
    <div className="page-title-row"><div><Title level={2}>管道构建</Title><Paragraph>使用统一 Pipeline IR 编排可验证的批处理线性链，Flink 是唯一计算引擎。</Paragraph></div><Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button icon={<PlusOutlined />} onClick={() => navigate('/data/pipelines/new')} type="primary">新建管道</Button></Space></div>
    <div className="compact-stats"><span>全部 <strong>{data?.counts.ALL ?? 0}</strong></span>{Object.entries(lifecycleLabels).map(([key, label]) => <span key={key}>{label} <strong>{data?.counts[key] ?? 0}</strong></span>)}</div>
    <div className="filter-bar pipeline-filter-bar"><Input allowClear onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="搜索名称或说明" prefix={<SearchOutlined />} value={search} /><Select allowClear onChange={(value) => { setMode(value ?? ''); setPage(0); }} options={Object.entries(modeLabels).map(([value, label]) => ({ label, value }))} placeholder="模式" value={mode || undefined} /><Select allowClear onChange={(value) => { setLifecycle(value ?? ''); setPage(0); }} options={Object.entries(lifecycleLabels).map(([value, label]) => ({ label, value }))} placeholder="生命周期" value={lifecycle || undefined} /><Select allowClear onChange={(value) => { setRunStatus(value ?? ''); setPage(0); }} options={Object.entries(runStatusLabels).map(([value, label]) => ({ label, value }))} placeholder="运行状态" value={runStatus || undefined} /><Input allowClear onChange={(event) => { setOwner(event.target.value); setPage(0); }} placeholder="负责人 ID" value={owner} /><Button onClick={reset}>重置</Button></div>
    {problem && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={`请求编号：${problem.requestId ?? '未生成'}`} message={problem.message} showIcon type="error" />}
    <Table columns={columns} dataSource={data?.items} loading={loading} locale={{ emptyText: <Empty description="还没有管道"><Button onClick={() => navigate('/data/pipelines/new')} type="primary">新建第一个管道</Button></Empty> }} onRow={(pipeline) => ({ onClick: () => navigate(`/data/pipelines/${pipeline.id}/edit`) })} pagination={{ current: page + 1, pageSize: 20, total: data?.total, showSizeChanger: false, onChange: (value) => setPage(value - 1) }} rowKey="id" scroll={{ x: 1450 }} size="small" />
  </div>;
}

function relativeTime(value: string) {
  const seconds = Math.max(1, Math.round((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
  return `${Math.floor(seconds / 86400)} 天前`;
}
