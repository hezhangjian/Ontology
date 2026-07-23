import { ArrowLeftOutlined, EyeOutlined, PlayCircleOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, Button, message, Result, Space, Spin, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ExplorerApi } from '../../features/ontology/explorer/explorer.service';
import type { ObjectTypeDefinition } from '../../features/ontology/explorer/explorer.types';
import { modelingApi } from '../../features/ontology/modeling/ontology.service';
import type { OntologyResource } from '../../features/ontology/modeling/ontology.types';
import { ApiProblem, dataConnectionsApi } from '../data-connections/services/dataConnections';
import type { DataSource, DataSourceAsset } from '../data-connections/types';
import BottomPanel from './editor/BottomPanel';
import NodeConfigPanel from './editor/NodeConfigPanel';
import NodeLibrary from './editor/NodeLibrary';
import PipelineCanvas from './editor/PipelineCanvas';
import PipelineToolbar from './editor/PipelineToolbar';
import { usePipelineEditor } from './hooks/usePipelineEditor';
import { pipelinesApi } from './services/pipelines';
import { datasetsApi } from '../datasets/datasetsApi';
import type { NodeType, Pipeline, PipelineGraph, PipelineNode, PreviewRun, RuntimeSettings, ScheduleSettings, ValidationResult } from './types';

const { Text, Title } = Typography;

function mergeValidationGraph(draft: PipelineGraph, normalized: PipelineGraph): PipelineGraph {
  const normalizedById = new Map(normalized.nodes.map((node) => [node.id, node]));
  return {
    edges: draft.edges,
    nodes: draft.nodes.map((node) => {
      const checked = normalizedById.get(node.id);
      return checked ? { ...checked, position: node.position } : node;
    }),
  };
}

function layoutGraph(graph: PipelineGraph): PipelineGraph {
  const incoming = new Map(graph.nodes.map((node) => [node.id, 0]));
  const outgoing = new Map(graph.nodes.map((node) => [node.id, [] as string[]]));
  graph.edges.forEach((edge) => {
    if (!incoming.has(edge.source) || !incoming.has(edge.target)) return;
    incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1);
    outgoing.get(edge.source)?.push(edge.target);
  });

  const depth = new Map<string, number>();
  const queue = graph.nodes
    .filter((node) => (incoming.get(node.id) ?? 0) === 0)
    .sort((left, right) => Number(right.type === 'SOURCE') - Number(left.type === 'SOURCE'))
    .map((node) => node.id);
  queue.forEach((id) => depth.set(id, 0));
  for (let index = 0; index < queue.length; index += 1) {
    const id = queue[index];
    for (const target of outgoing.get(id) ?? []) {
      depth.set(target, Math.max(depth.get(target) ?? 0, (depth.get(id) ?? 0) + 1));
      incoming.set(target, (incoming.get(target) ?? 0) - 1);
      if (incoming.get(target) === 0) queue.push(target);
    }
  }

  const rows = new Map<number, number>();
  return {
    ...graph,
    nodes: graph.nodes.map((node) => {
      const column = depth.get(node.id) ?? 0;
      const row = rows.get(column) ?? 0;
      rows.set(column, row + 1);
      return { ...node, position: { x: 80 + column * 240, y: 80 + row * 150 } };
    }),
  };
}

interface Props {
  accessToken: string;
  id: string;
  initialTab?: 'edit' | 'history' | 'proposals';
  isAdmin: boolean;
  navigate: (path: string) => void;
}

export default function PipelineEditorPage({ accessToken, id, navigate }: Props) {
  const api = useMemo(() => pipelinesApi(accessToken), [accessToken]);
  const datasetApi = useMemo(() => datasetsApi(accessToken), [accessToken]);
  const explorerApi = useMemo(() => new ExplorerApi(accessToken), [accessToken]);
  const ontologyApi = useMemo(() => modelingApi(accessToken), [accessToken]);
  const connectionsApi = useMemo(() => dataConnectionsApi(accessToken), [accessToken]);
  const [pipeline, setPipeline] = useState<Pipeline>();
  const [graph, setGraph] = useState<PipelineGraph>({ edges: [], nodes: [] });
  const [runtime, setRuntime] = useState<RuntimeSettings>();
  const [schedule, setSchedule] = useState<ScheduleSettings>();
  const [nodeTypes, setNodeTypes] = useState<NodeType[]>([]);
  const [objectTypes, setObjectTypes] = useState<ObjectTypeDefinition[]>([]);
  const [linkTypes, setLinkTypes] = useState<OntologyResource[]>([]);
  const [connections, setConnections] = useState<DataSource[]>([]);
  const [lookupAssets, setLookupAssets] = useState<Record<string, DataSourceAsset[]>>({});
  const [validation, setValidation] = useState<ValidationResult>();
  const [preview, setPreview] = useState<PreviewRun>();
  const [loading, setLoading] = useState(true);
  const [saveState, setSaveState] = useState<'saved' | 'saving' | 'dirty' | 'conflict'>('saved');
  const [problem, setProblem] = useState<ApiProblem>();
  const dirtyRef = useRef(false);
  const etagRef = useRef(0);
  const lookupAssetLoadsRef = useRef(new Set<string>());
  const editor = usePipelineEditor();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [{ data }, definitions, explorerHome, links, connectionPage] = await Promise.all([
        api.get(id), api.nodeTypes(), explorerApi.home(), ontologyApi.listResources('LINK_TYPE'), connectionsApi.list('size=100'),
      ]);
      setPipeline(data); setNodeTypes(definitions); setObjectTypes(explorerHome.objectTypes); setLinkTypes(links); etagRef.current = data.draft?.etag ?? data.version;
      setConnections(connectionPage.items);
      setGraph(data.draft?.graph ?? { edges: [], nodes: [] }); setRuntime(data.draft?.runtime); setSchedule(data.draft?.schedule);
      setProblem(undefined); usePipelineEditor.getState().reset();
      if (data.draft) {
        const result = await api.validate(id);
        const validatedDraft = mergeValidationGraph(data.draft.graph, result.normalizedGraph);
        const preparedGraph = {
          ...validatedDraft,
          nodes: validatedDraft.nodes.map((node) => node.type === 'SELECT'
            && (!Array.isArray(node.config.fields) || node.config.fields.length === 0)
            ? { ...node, config: { ...node.config, fields: node.inputSchema.map((field) => ({ source: field.name, target: field.name })) } }
            : node),
        };
        const selectedAllFields = preparedGraph.nodes.some((node, index) => node !== validatedDraft.nodes[index]);
        setValidation(result); setGraph(preparedGraph);
        if (selectedAllFields) { dirtyRef.current = true; setSaveState('dirty'); }
        usePipelineEditor.getState().setSelectedNode(preparedGraph.nodes.find((node) => node.type === 'DATASET_OUTPUT')?.id);
      }
    } catch (cause) { setProblem(cause instanceof ApiProblem ? cause : new ApiProblem('管道加载失败')); }
    finally { setLoading(false); }
  }, [api, connectionsApi, explorerApi, id, ontologyApi]);
  useEffect(() => { void load(); }, [load]);

  const saveDraft = useCallback(async () => {
    if (!dirtyRef.current || !runtime || !schedule) return;
    setSaveState('saving');
    try {
      const { data } = await api.updateDraft(id, etagRef.current, { graph, runtime, schedule });
      etagRef.current = data.draft?.etag ?? etagRef.current;
      const checked = await api.validate(id);
      dirtyRef.current = false;
      setValidation(checked);
      const checkedDraft = mergeValidationGraph(graph, checked.normalizedGraph);
      setGraph(checkedDraft);
      setPipeline((current) => current ? { ...data, draft: { ...data.draft!, graph: checkedDraft, runtime, schedule } } : data);
      setSaveState('saved');
    } catch (cause) {
      if (cause instanceof ApiProblem && cause.status === 409) setSaveState('conflict');
      else { setSaveState('dirty'); message.error(cause instanceof Error ? cause.message : '草稿自动保存失败'); }
      throw cause;
    }
  }, [api, graph, id, runtime, schedule]);

  useEffect(() => {
    if (!dirtyRef.current) return;
    const timer = window.setTimeout(() => { void saveDraft().catch(() => undefined); }, 700);
    return () => window.clearTimeout(timer);
  }, [saveDraft]);

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
  const loadLookupAssets = useCallback(async (connectionId: string) => {
    if (lookupAssets[connectionId]) return;
    try {
      const result = await connectionsApi.listAssets(connectionId);
      setLookupAssets((current) => ({ ...current, [connectionId]: result.items }));
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '辅助数据资产加载失败');
    }
  }, [connectionsApi, lookupAssets]);
  const loadLookupAsset = useCallback(async (connectionId: string, assetId: string) => {
    const key = `${connectionId}:${assetId}`;
    if (lookupAssetLoadsRef.current.has(key)) return;
    lookupAssetLoadsRef.current.add(key);
    try {
      const asset = await connectionsApi.getAsset(connectionId, assetId);
      setLookupAssets((current) => ({
        ...current,
        [connectionId]: (current[connectionId] ?? []).map((item) => item.id === asset.id ? asset : item),
      }));
    } catch (cause) {
      lookupAssetLoadsRef.current.delete(key);
      message.error(cause instanceof Error ? cause.message : '辅助数据字段加载失败');
    }
  }, [connectionsApi]);
  function addNode(definition: NodeType) {
    const id = `${definition.type.toLowerCase()}-${crypto.randomUUID().slice(0, 8)}`;
    const selectedId = editor.selectedNodeId;
    const incoming = selectedId ? graph.edges.filter((edge) => edge.target === selectedId) : [];
    const edges = definition.type !== 'SOURCE' && selectedId && incoming.length === 1
      ? [...graph.edges.filter((edge) => edge.id !== incoming[0].id), { ...incoming[0], target: id }, { id: `edge-${crypto.randomUUID()}`, source: id, target: selectedId }]
      : graph.edges;
    changeGraph({ edges, nodes: [...graph.nodes, { config: {}, id, inputSchema: [], invalidReasons: [], name: definition.label, outputSchema: [], position: { x: 340 + graph.nodes.length * 30, y: 100 + graph.nodes.length * 35 }, type: definition.type }] }, true);
    editor.setSelectedNode(id);
  }
  function deleteSelected() {
    const id = editor.selectedNodeId;
    if (!id) return;
    if (graph.nodes.find((node) => node.id === id)?.type === 'SOURCE'
      && graph.nodes.filter((node) => node.type === 'SOURCE').length === 1) {
      message.warning('至少保留一个源节点');
      return;
    }
    changeGraph({ edges: graph.edges.filter((edge) => edge.source !== id && edge.target !== id), nodes: graph.nodes.filter((node) => node.id !== id) }, true); editor.setSelectedNode(undefined);
  }
  function duplicateSelected() {
    const source = graph.nodes.find((node) => node.id === editor.selectedNodeId);
    if (!source) return;
    const id = `${source.type.toLowerCase()}-${crypto.randomUUID().slice(0, 8)}`;
    changeGraph({ ...graph, nodes: [...graph.nodes, { ...structuredClone(source), id, name: `${source.name} 副本`, position: { x: source.position.x + 50, y: source.position.y + 70 } }] }, true); editor.setSelectedNode(id);
  }
  function autoLayout() {
    changeGraph(layoutGraph(graph), true);
  }
  function undo() { const previous = editor.undo(graph); if (previous) { setGraph(previous); dirtyRef.current = true; setSaveState('dirty'); } }
  function redo() { const next = editor.redo(graph); if (next) { setGraph(next); dirtyRef.current = true; setSaveState('dirty'); } }

  async function runPreview() {
    const nodeId = editor.selectedNodeId ?? graph.nodes.find((node) => node.type === 'DATASET_OUTPUT' || node.type.startsWith('ONTOLOGY_'))?.id;
    if (!nodeId) { message.warning('请先选择要预览的节点'); return; }
    try {
      await saveDraft();
      editor.setBottomTab('preview'); const submitted = await api.preview(id, nodeId); setPreview(submitted);
      const poll = window.setInterval(async () => {
        try { const current = await api.getPreview(submitted.id); setPreview(current); if (!['SUBMITTED', 'RUNNING'].includes(current.status)) window.clearInterval(poll); }
        catch { window.clearInterval(poll); }
      }, 1000);
    } catch (cause) { message.error(cause instanceof Error ? cause.message : '预览提交失败'); }
  }
  async function cancelPreview() { if (preview) { await api.cancelPreview(preview.id); setPreview(await api.getPreview(preview.id)); } }
  async function finishAndRun() {
    try {
      await saveDraft();
      const checked = await api.validate(id);
      setValidation(checked);
      setGraph(mergeValidationGraph(graph, checked.normalizedGraph));
      if (!checked.valid) {
        editor.setBottomTab('validation');
        message.error('请先补全右侧的数据集输出和字段配置');
        return;
      }
      const output = checked.normalizedGraph.nodes.find((node) => node.type === 'DATASET_OUTPUT');
      const dataset = await datasetApi.materialize(id, String(output?.config.datasetName ?? ''), String(output?.config.description ?? ''));
      message.success('已提交 Flink 物化任务，可在 Dataset 页面查看进度');
      navigate(`/data/datasets/${dataset.id}`);
    } catch (cause) { message.error(cause instanceof Error ? cause.message : '生成数据集失败'); }
  }

  const selected = graph.nodes.find((node) => node.id === editor.selectedNodeId);
  useEffect(() => {
    const connectionId = selected?.type === 'SOURCE' ? String(selected.config.connectionId ?? '') : '';
    if (connectionId && !lookupAssets[connectionId]) void loadLookupAssets(connectionId);
  }, [loadLookupAssets, lookupAssets, selected?.config.connectionId, selected?.type]);
  useEffect(() => {
    if (selected?.type !== 'SOURCE') return;
    const connectionId = String(selected.config.connectionId ?? '');
    const assetId = String(selected.config.assetId ?? '');
    const asset = lookupAssets[connectionId]?.find((item) => item.id === assetId);
    if (connectionId && assetId && asset && asset.fields.length === 0) void loadLookupAsset(connectionId, assetId);
  }, [loadLookupAsset, lookupAssets, selected?.config.assetId, selected?.config.connectionId, selected?.type]);

  if (loading) return <div className="center-loading"><Spin size="large" /></div>;
  if (problem || !pipeline || !runtime || !schedule) return <Result extra={<Button onClick={() => void load()}>重试</Button>} status="error" subTitle={problem?.message} title="无法打开管道" />;
  const saveLabel = { conflict: '版本冲突', dirty: '待保存', saved: '已保存', saving: '保存中' }[saveState];
  return <div className="pipeline-editor-page">
    <header className="pipeline-editor-header"><Space><Button aria-label="返回管道列表" icon={<ArrowLeftOutlined />} onClick={() => navigate('/data/pipelines')} type="text" /><div><Space><Title level={4}>{pipeline.name}</Title><Tag>{pipeline.mode === 'BATCH' ? '批处理' : '实时'}</Tag></Space><Text className={`save-state save-state-${saveState}`}><SaveOutlined /> {saveLabel}</Text></div></Space><Space><Button icon={<EyeOutlined />} onClick={() => void runPreview()}>预览数据</Button><Button icon={<PlayCircleOutlined />} onClick={() => void finishAndRun()} type="primary">生成数据集</Button></Space></header>
    {saveState === 'conflict' && <Alert action={<Button onClick={() => void load()} size="small">重新加载</Button>} banner message="草稿已被其他用户更新。为避免静默覆盖，必须重新加载后显式合并。" type="error" />}
    <div className="pipeline-editor-workspace"><NodeLibrary mode={pipeline.mode} nodeTypes={nodeTypes} onAdd={addNode} /><main className="pipeline-canvas-column"><PipelineToolbar canRedo={editor.future.length > 0} canUndo={editor.past.length > 0} hasSelection={Boolean(selected)} onAutoLayout={autoLayout} onDelete={deleteSelected} onDuplicate={duplicateSelected} onRedo={redo} onUndo={undo} /><PipelineCanvas graph={graph} nodeTypes={nodeTypes} onChange={changeGraph} onSelect={editor.setSelectedNode} /><BottomPanel active={editor.bottomTab} nodeSchema={selected?.outputSchema ?? selected?.inputSchema ?? []} onActive={editor.setBottomTab} onCancelPreview={() => void cancelPreview()} preview={preview} validation={validation} /></main><NodeConfigPanel connections={connections} linkTypes={linkTypes} lookupAssets={lookupAssets} node={selected} objectTypes={objectTypes} onLookupAssetChange={(connectionId, assetId) => void loadLookupAsset(connectionId, assetId)} onLookupConnectionChange={(connectionId) => void loadLookupAssets(connectionId)} onNodeChange={changeNode} onRuntimeChange={changeRuntime} onScheduleChange={changeSchedule} pipeline={{ ...pipeline, draft: { ...pipeline.draft!, graph, runtime, schedule } }} /></div>
  </div>;
}
