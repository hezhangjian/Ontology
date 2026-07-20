import { ArrowLeftOutlined, CheckCircleOutlined, EyeOutlined, PlayCircleOutlined, RocketOutlined, SaveOutlined, SendOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Input, message, Modal, Result, Space, Spin, Table, Tabs, Tag, Timeline, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiProblem } from '../data-connections/services/dataConnections';
import BottomPanel from './editor/BottomPanel';
import NodeConfigPanel from './editor/NodeConfigPanel';
import NodeLibrary from './editor/NodeLibrary';
import PipelineCanvas from './editor/PipelineCanvas';
import PipelineToolbar from './editor/PipelineToolbar';
import { usePipelineEditor } from './hooks/usePipelineEditor';
import { pipelinesApi } from './services/pipelines';
import type { NodeType, Pipeline, PipelineGraph, PipelineNode, PipelineProposal, PipelineRun, PipelineVersion, PreviewRun, RuntimeSettings, ScheduleSettings, ValidationResult } from './types';

const { Paragraph, Text, Title } = Typography;

export default function PipelineEditorPage({ accessToken, id, initialTab = 'edit', isAdmin, navigate }: { accessToken: string; id: string; initialTab?: 'edit' | 'history' | 'proposals'; isAdmin: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => pipelinesApi(accessToken), [accessToken]);
  const [pipeline, setPipeline] = useState<Pipeline>();
  const [graph, setGraph] = useState<PipelineGraph>({ edges: [], nodes: [] });
  const [runtime, setRuntime] = useState<RuntimeSettings>();
  const [schedule, setSchedule] = useState<ScheduleSettings>();
  const [nodeTypes, setNodeTypes] = useState<NodeType[]>([]);
  const [validation, setValidation] = useState<ValidationResult>();
  const [preview, setPreview] = useState<PreviewRun>();
  const [proposals, setProposals] = useState<PipelineProposal[]>([]);
  const [versions, setVersions] = useState<PipelineVersion[]>([]);
  const [runs, setRuns] = useState<PipelineRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [saveState, setSaveState] = useState<'saved' | 'saving' | 'dirty' | 'conflict'>('saved');
  const [activeTab, setActiveTab] = useState(initialTab);
  const [problem, setProblem] = useState<ApiProblem>();
  const dirtyRef = useRef(false);
  const etagRef = useRef(0);
  const editor = usePipelineEditor();

  const loadSideData = useCallback(async () => {
    const [proposalData, versionData, runData] = await Promise.all([api.proposals(id), api.versions(id), api.runs(id)]);
    setProposals(proposalData); setVersions(versionData); setRuns(runData.items);
  }, [api, id]);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [{ data }, definitions] = await Promise.all([api.get(id), api.nodeTypes()]);
      setPipeline(data); setNodeTypes(definitions); etagRef.current = data.draft?.etag ?? data.version;
      setGraph(data.draft?.graph ?? { edges: [], nodes: [] }); setRuntime(data.draft?.runtime); setSchedule(data.draft?.schedule);
      setProblem(undefined); usePipelineEditor.getState().reset();
      await loadSideData();
      if (data.draft) {
        const result = await api.validate(id);
        setValidation(result); setGraph(result.normalizedGraph);
      }
    } catch (cause) { setProblem(cause instanceof ApiProblem ? cause : new ApiProblem('管道加载失败')); }
    finally { setLoading(false); }
  }, [api, id, loadSideData]);
  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (!dirtyRef.current || !pipeline || !runtime || !schedule) return;
    const timer = window.setTimeout(async () => {
      setSaveState('saving');
      try {
        const { data } = await api.updateDraft(pipeline.id, etagRef.current, { graph, runtime, schedule });
        etagRef.current = data.draft?.etag ?? etagRef.current;
        dirtyRef.current = false; setPipeline((current) => current ? { ...data, draft: { ...data.draft!, graph, runtime, schedule } } : data); setSaveState('saved');
      } catch (cause) {
        if (cause instanceof ApiProblem && cause.status === 409) { setSaveState('conflict'); }
        else { setSaveState('dirty'); message.error(cause instanceof Error ? cause.message : '草稿自动保存失败'); }
      }
    }, 700);
    return () => window.clearTimeout(timer);
  }, [api, graph, pipeline, runtime, schedule]);

  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'z') return;
      event.preventDefault();
      if (event.shiftKey) redo(); else undo();
    };
    window.addEventListener('keydown', shortcut);
    return () => window.removeEventListener('keydown', shortcut);
  });

  function changeGraph(next: PipelineGraph, snapshot = false) {
    if (snapshot) editor.snapshot(graph);
    setGraph(next); dirtyRef.current = true; setSaveState('dirty');
  }
  function changeNode(next: PipelineNode) { changeGraph({ ...graph, nodes: graph.nodes.map((node) => node.id === next.id ? next : node) }, true); }
  function changeRuntime(next: RuntimeSettings) { setRuntime(next); dirtyRef.current = true; setSaveState('dirty'); }
  function changeSchedule(next: ScheduleSettings) { setSchedule(next); dirtyRef.current = true; setSaveState('dirty'); }
  function addNode(definition: NodeType) {
    const id = `${definition.type.toLowerCase()}-${crypto.randomUUID().slice(0, 8)}`;
    changeGraph({ ...graph, nodes: [...graph.nodes, { config: {}, id, inputSchema: [], invalidReasons: [], name: definition.label, outputSchema: [], position: { x: 340 + graph.nodes.length * 30, y: 100 + graph.nodes.length * 35 }, type: definition.type }] }, true);
    editor.setSelectedNode(id);
  }
  function deleteSelected() {
    const id = editor.selectedNodeId;
    if (!id) return;
    if (graph.nodes.find((node) => node.id === id)?.type === 'SOURCE') { message.warning('源节点不能删除，请更换管道源资产'); return; }
    changeGraph({ edges: graph.edges.filter((edge) => edge.source !== id && edge.target !== id), nodes: graph.nodes.filter((node) => node.id !== id) }, true); editor.setSelectedNode(undefined);
  }
  function duplicateSelected() {
    const source = graph.nodes.find((node) => node.id === editor.selectedNodeId);
    if (!source || source.type === 'SOURCE') return;
    const id = `${source.type.toLowerCase()}-${crypto.randomUUID().slice(0, 8)}`;
    changeGraph({ ...graph, nodes: [...graph.nodes, { ...structuredClone(source), id, name: `${source.name} 副本`, position: { x: source.position.x + 50, y: source.position.y + 70 } }] }, true); editor.setSelectedNode(id);
  }
  function autoLayout() {
    changeGraph({ ...graph, nodes: graph.nodes.map((node, index) => ({ ...node, position: { x: 80 + (index % 4) * 260, y: 80 + Math.floor(index / 4) * 180 } })) }, true);
  }
  function undo() { const previous = editor.undo(graph); if (previous) { setGraph(previous); dirtyRef.current = true; setSaveState('dirty'); } }
  function redo() { const next = editor.redo(graph); if (next) { setGraph(next); dirtyRef.current = true; setSaveState('dirty'); } }

  async function validate() {
    try { const result = await api.validate(id); setValidation(result); setGraph(result.normalizedGraph); editor.setBottomTab('validation'); message[result.valid ? 'success' : 'warning'](result.valid ? '发布校验通过' : '发现阻止发布的问题'); }
    catch (cause) { message.error(cause instanceof Error ? cause.message : '校验失败'); }
  }
  async function runPreview() {
    const nodeId = editor.selectedNodeId ?? graph.nodes.find((node) => node.type.startsWith('ONTOLOGY_'))?.id;
    if (!nodeId) { message.warning('请先选择要预览的节点'); return; }
    try {
      editor.setBottomTab('preview'); const submitted = await api.preview(id, nodeId); setPreview(submitted);
      const poll = window.setInterval(async () => {
        try { const current = await api.getPreview(submitted.id); setPreview(current); if (!['SUBMITTED', 'RUNNING'].includes(current.status)) window.clearInterval(poll); }
        catch { window.clearInterval(poll); }
      }, 1000);
    } catch (cause) { message.error(cause instanceof Error ? cause.message : '预览提交失败'); }
  }
  async function cancelPreview() { if (preview) { await api.cancelPreview(preview.id); setPreview(await api.getPreview(preview.id)); } }
  function propose() {
    let title = `发布“${pipeline?.name ?? ''}”`;
    let summary = '';
    Modal.confirm({ title: '提交变更提议', content: <Space direction="vertical" style={{ width: '100%' }}><Input defaultValue={title} onChange={(event) => { title = event.target.value; }} placeholder="标题" /><Input.TextArea onChange={(event) => { summary = event.target.value; }} placeholder="说明变更、影响与预览证据" rows={4} /></Space>, okText: '提交审核', onOk: async () => { await api.propose(id, title, summary); await loadSideData(); setActiveTab('proposals'); message.success('变更提议已提交'); } });
  }
  async function publish() {
    try {
      const approved = proposals.find((proposal) => proposal.status === 'APPROVED');
      const version = await api.publish(id, approved?.id); message.success(`已发布不可变版本 v${version.version}`); await load();
    } catch (cause) { message.error(cause instanceof Error ? cause.message : '发布失败'); }
  }
  async function execute() {
    try { const run = pipeline?.mode === 'BATCH' ? await api.run(id) : pipeline?.runStatus === 'LIVE' ? await api.stop(id) : await api.start(id); message.success('运行操作已异步提交'); navigate(`/data/pipelines/${id}/runs/${run.id}`); }
    catch (cause) { message.error(cause instanceof Error ? cause.message : '运行操作失败'); }
  }

  if (loading) return <div className="center-loading"><Spin size="large" /></div>;
  if (problem || !pipeline || !runtime || !schedule) return <Result extra={<Button onClick={() => void load()}>重试</Button>} status="error" subTitle={problem?.message} title="无法打开管道" />;
  const selected = graph.nodes.find((node) => node.id === editor.selectedNodeId);
  const saveLabel = { conflict: '版本冲突', dirty: '待保存', saved: '已保存', saving: '保存中' }[saveState];
  return <div className="pipeline-editor-page">
    <header className="pipeline-editor-header"><Space><Button aria-label="返回管道列表" icon={<ArrowLeftOutlined />} onClick={() => navigate('/data/pipelines')} type="text" /><div><Space><Title level={4}>{pipeline.name}</Title><Tag>{pipeline.mode === 'BATCH' ? '批处理' : '流处理'}</Tag><Tag color={pipeline.publishedVersion ? 'blue' : 'default'}>{pipeline.publishedVersion ? `已发布 v${pipeline.publishedVersion}` : '草稿'}</Tag></Space><Text className={`save-state save-state-${saveState}`}><SaveOutlined /> {saveLabel}</Text></div></Space><Space><Button icon={<EyeOutlined />} onClick={() => void runPreview()}>预览</Button><Button icon={<CheckCircleOutlined />} onClick={() => void validate()}>校验</Button><Button icon={<SendOutlined />} onClick={propose}>提交审核</Button><Button icon={<RocketOutlined />} onClick={() => void publish()} type="primary">发布</Button><Button disabled={!pipeline.publishedVersion} icon={<PlayCircleOutlined />} onClick={() => void execute()}>{pipeline.mode === 'BATCH' ? '立即运行' : pipeline.runStatus === 'LIVE' ? '停止' : '启动'}</Button></Space></header>
    {saveState === 'conflict' && <Alert action={<Button onClick={() => void load()} size="small">重新加载</Button>} banner message="草稿已被其他用户更新。为避免静默覆盖，必须重新加载后显式合并。" type="error" />}
    <Tabs activeKey={activeTab} className="pipeline-resource-tabs" items={[{ key: 'edit', label: '编辑' }, { key: 'proposals', label: `变更提议 ${proposals.filter((proposal) => proposal.status === 'OPEN').length || ''}` }, { key: 'history', label: '历史' }]} onChange={(tab) => setActiveTab(tab as typeof activeTab)} />
    {activeTab === 'edit' && <div className="pipeline-editor-workspace"><NodeLibrary mode={pipeline.mode} nodeTypes={nodeTypes} onAdd={addNode} /><main className="pipeline-canvas-column"><PipelineToolbar canRedo={editor.future.length > 0} canUndo={editor.past.length > 0} hasSelection={Boolean(selected)} onAutoLayout={autoLayout} onDelete={deleteSelected} onDuplicate={duplicateSelected} onRedo={redo} onUndo={undo} /><PipelineCanvas graph={graph} nodeTypes={nodeTypes} onChange={changeGraph} onSelect={editor.setSelectedNode} /><BottomPanel active={editor.bottomTab} nodeSchema={selected?.outputSchema ?? selected?.inputSchema ?? []} onActive={editor.setBottomTab} onCancelPreview={() => void cancelPreview()} preview={preview} validation={validation} /></main><NodeConfigPanel node={selected} onNodeChange={changeNode} onRuntimeChange={changeRuntime} onScheduleChange={changeSchedule} pipeline={{ ...pipeline, draft: { ...pipeline.draft!, graph, runtime, schedule } }} /></div>}
    {activeTab === 'proposals' && <ProposalList isAdmin={isAdmin} onChange={loadSideData} pipelineId={id} proposals={proposals} api={api} />}
    {activeTab === 'history' && <HistoryList navigate={navigate} pipelineId={id} runs={runs} versions={versions} api={api} isAdmin={isAdmin} onChange={load} />}
  </div>;
}

function ProposalList({ api, isAdmin, onChange, pipelineId, proposals }: { api: ReturnType<typeof pipelinesApi>; isAdmin: boolean; onChange: () => Promise<void>; pipelineId: string; proposals: PipelineProposal[] }) {
  if (proposals.length === 0) return <Empty description="尚无变更提议" />;
  return <div className="pipeline-resource-content">{proposals.map((proposal) => <Alert action={proposal.status === 'OPEN' && isAdmin ? <Space><Button onClick={() => void api.reject(pipelineId, proposal.id, '需要修改').then(onChange)} size="small">拒绝</Button><Button onClick={() => void api.approve(pipelineId, proposal.id, '批准发布').then(onChange)} size="small" type="primary">批准</Button></Space> : undefined} description={<><Paragraph>{proposal.summary || '未填写说明'}</Paragraph><Text type="secondary">提交人 {proposal.submittedByName} · {new Date(proposal.submittedAt).toLocaleString()} · {proposal.validation.length} 个校验问题</Text></>} key={proposal.id} message={<Space><Tag color={proposal.riskLevel === 'HIGH' ? 'red' : 'blue'}>{proposal.riskLevel === 'HIGH' ? '高风险' : '普通'}</Tag><strong>{proposal.title}</strong><Tag>{proposal.status}</Tag></Space>} showIcon type={proposal.riskLevel === 'HIGH' ? 'warning' : 'info'} />)}</div>;
}

function HistoryList({ api, isAdmin, navigate, onChange, pipelineId, runs, versions }: { api: ReturnType<typeof pipelinesApi>; isAdmin: boolean; navigate: (path: string) => void; onChange: () => Promise<void>; pipelineId: string; runs: PipelineRun[]; versions: PipelineVersion[] }) {
  return <div className="pipeline-resource-content pipeline-history-grid"><section><Title level={4}>不可变版本</Title><Timeline items={versions.map((version) => ({ children: <div><Space><Text strong>v{version.version}</Text><Tag>{version.contentHash.slice(0, 10)}</Tag></Space><Paragraph>{version.publishedByName} · {new Date(version.publishedAt).toLocaleString()}</Paragraph>{isAdmin && <Button onClick={() => Modal.confirm({ title: `激活历史版本 v${version.version}？`, content: '只影响后续运行，不撤销已写业务数据。', onOk: () => api.rollback(pipelineId, version.version).then(onChange) })} size="small">回滚到此版本</Button>}</div> }))} /></section><section><Title level={4}>最近运行</Title><Table columns={[{ dataIndex: 'id', title: 'Run ID', render: (value) => <a onClick={() => navigate(`/data/pipelines/${pipelineId}/runs/${value}`)}>{value.slice(0, 8)}</a> }, { dataIndex: 'pipelineVersion', title: '版本', render: (value) => `v${value}` }, { dataIndex: 'status', title: '状态', render: (value) => <Tag>{value}</Tag> }, { dataIndex: 'writtenCount', title: '写入' }]} dataSource={runs} pagination={false} rowKey="id" size="small" /></section></div>;
}
