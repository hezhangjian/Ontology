/* eslint-disable react-hooks/exhaustive-deps, react-refresh/only-export-components */
import { DownOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Table } from '@/shared/components/data';
import { Alert, Empty } from '@/shared/components/feedback';
import { Input } from '@/shared/components/forms';
import { Card, Space } from '@/shared/components/layout';
import { Dropdown } from '@/shared/components/overlays';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useState } from 'react';
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { modelingApi } from './ontology.service';
import type { ModelingSummary, OntologyResource } from './ontology.types';

const { Paragraph, Title } = Typography;
const graphNodeTypes = { ontologyObject: OntologyObjectNode };

export default function OntologyOverviewPage({ navigate }: { navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(), []);
  const [summary, setSummary] = useState<ModelingSummary>();
  const [objects, setObjects] = useState<OntologyResource[]>([]);
  const [links, setLinks] = useState<OntologyResource[]>([]);
  const [problem, setProblem] = useState('');
  const load = () => Promise.all([api.summary(), api.listResources('OBJECT_TYPE'), api.listResources('LINK_TYPE')])
    .then(([nextSummary, nextObjects, nextLinks]) => { setSummary(nextSummary); setObjects(nextObjects); setLinks(nextLinks); setProblem(''); })
    .catch((error: Error) => setProblem(error.message));
  useEffect(() => { void load(); }, []);
  const graph = useMemo(
    () => ontologyGraph(
      objects,
      links,
      summary?.objectInstanceCounts ?? {},
      summary?.relationInstanceCounts ?? {},
    ),
    [objects, links, summary?.objectInstanceCounts, summary?.relationInstanceCounts],
  );
  return <div className="ontology-overview-page">
    <div className="page-title-row"><div><Title level={2}>模型总览</Title><Paragraph>这里定义当前本体包含哪些对象类型、属性和关系；具体人员、小组等实例请到“对象探索”中查看。</Paragraph></div><Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button icon={<PlusOutlined />} onClick={() => navigate('/ontology/object-types/new/from-dataset')} type="primary">从 Dataset 创建对象类型</Button><Dropdown menu={{items:[{key:'/ontology/object-types/new',label:'手工创建对象类型'},{key:'/ontology/link-types/new',label:'关系类型'},{key:'/ontology/interfaces/new',label:'接口'},{key:'/ontology/actions/new',label:'动作'},{key:'/ontology/functions/new',label:'函数'}],onClick:({key})=>navigate(key)}}><Button>新建其他 <DownOutlined/></Button></Dropdown></Space></div>
    {problem && <Alert message={problem} showIcon type="error" />}
    <div className="collection-toolbar overview-search"><Input.Search enterButton={<SearchOutlined />} onSearch={(value) => navigate(`/ontology/search?q=${encodeURIComponent(value)}`)} placeholder="搜索显示名称、ID、属性或标签" size="large" /><Space><Tag>{summary?.resourceCounts.OBJECT_TYPE ?? 0} 个对象类型</Tag><Tag color="blue">{Object.values(summary?.objectInstanceCounts ?? {}).reduce((sum, value) => sum + value, 0).toLocaleString()} 个对象实例</Tag><Tag color="green">{Object.values(summary?.relationInstanceCounts ?? {}).reduce((sum, value) => sum + value, 0).toLocaleString()} 条关系实例</Tag></Space></div>
    {(summary?.resourceCounts.OBJECT_TYPE??0)===0?<Card className="overview-empty-card" style={{marginTop:20}}><Empty description={<><Title level={4}>当前本体还没有对象类型</Title><Paragraph>可从 Dataset 映射对象类型，或手工创建；它不会影响其他本体。</Paragraph></>}><Space><Button onClick={()=>navigate('/ontology/object-types/new/from-dataset')} type="primary">选择 Dataset</Button><Button onClick={()=>navigate('/ontology/object-types/new')}>手工创建对象类型</Button></Space></Empty></Card>:<div className="ontology-overview-grid"><Card className="overview-card" title="对象类型与实例"><Table columns={[{ title: '对象类型', dataIndex: 'displayName', render: (value, row) => <a onClick={() => navigate(resourcePath(row.kind, row.id))}>{value}</a> }, { title: '实例数', render:(_,row)=><Tag color="blue">{(summary?.objectInstanceCounts?.[row.resourceId] ?? 0).toLocaleString()}</Tag> }, { title: '属性数', render:(_,row)=>row.properties?.length??0 }]} dataSource={objects} locale={{ emptyText: <Empty /> }} pagination={false} rowKey="id" size="small" /></Card><Card className="overview-card graph-card" title="对象关系图" extra={<a onClick={() => navigate('/ontology/link-types')}>管理关系</a>}>{graph.nodes.length ? <div className="ontology-graph"><ReactFlow edges={graph.edges} fitView fitViewOptions={{ padding: 0.25 }} nodeTypes={graphNodeTypes} nodes={graph.nodes} nodesDraggable={false} nodesConnectable={false} onNodeClick={(_, node) => navigate(resourcePath('OBJECT_TYPE', node.id))} proOptions={{ hideAttribution: true }}><Background gap={18} size={1} /><Controls showInteractive={false} /></ReactFlow></div> : <Empty description="暂无对象类型" />}<Paragraph type="secondary">节点显示真实对象实例数，连线显示已投影的关系实例数；点击节点可查看对象类型。</Paragraph></Card></div>}
  </div>;
}

type OntologyGraphNode = Node<{ label: string }, 'ontologyObject'>;
type HandlePosition = 'bottom' | 'left' | 'right' | 'top';

function OntologyObjectNode({ data }: NodeProps<OntologyGraphNode>) {
  const positions: Array<[HandlePosition, Position]> = [
    ['bottom', Position.Bottom],
    ['left', Position.Left],
    ['right', Position.Right],
    ['top', Position.Top],
  ];
  return <div className="ontology-graph-node">
    {positions.flatMap(([name, position]) => [
      <Handle className="ontology-graph-handle" id={`source-${name}`} isConnectable={false} key={`source-${name}`} position={position} type="source" />,
      <Handle className="ontology-graph-handle" id={`target-${name}`} isConnectable={false} key={`target-${name}`} position={position} type="target" />,
    ])}
    {data.label}
  </div>;
}

function ontologyGraph(
  objects: OntologyResource[],
  links: OntologyResource[],
  objectCounts: Record<string, number>,
  relationCounts: Record<string, number>,
): { nodes: OntologyGraphNode[]; edges: Edge[] } {
  const radius = Math.max(170, objects.length * 40);
  const nodes: OntologyGraphNode[] = objects.map((object, index) => {
    const angle = (index / Math.max(objects.length, 1)) * Math.PI * 2 - Math.PI / 2;
    return {
      id: object.id,
      position: { x: radius + Math.cos(angle) * radius, y: radius + Math.sin(angle) * radius },
      data: { label: `${object.displayName} · ${(objectCounts[object.resourceId] ?? 0).toLocaleString()} 实例` },
      type: 'ontologyObject',
    };
  });
  const publicIdByResourceId = new Map(objects.map((object) => [object.resourceId, object.id]));
  const positionById = new Map(nodes.map((node) => [node.id, node.position]));
  const edges: Edge[] = links.flatMap((link) => {
    const source = publicIdByResourceId.get(String(link.definition.leftObjectTypeId ?? ''));
    const target = publicIdByResourceId.get(String(link.definition.rightObjectTypeId ?? ''));
    if (!source || !target) return [];
    const handles = edgeHandles(positionById.get(source), positionById.get(target), source === target);
    return [{
      id: link.id,
      source,
      sourceHandle: `source-${handles.source}`,
      target,
      targetHandle: `target-${handles.target}`,
      label: `${link.displayName} · ${(relationCounts[link.resourceId] ?? 0).toLocaleString()} 条`,
      labelBgBorderRadius: 4,
      labelBgPadding: [6, 3],
      labelBgStyle: { fill: '#fbfcfe', fillOpacity: 0.94 },
      labelStyle: { fill: '#42516b', fontSize: 11 },
      markerEnd: { color: '#6079d9', type: MarkerType.ArrowClosed },
      style: { stroke: '#6079d9', strokeWidth: 1.6 },
    }];
  });
  return { nodes, edges };
}

function edgeHandles(
  source: { x: number; y: number } | undefined,
  target: { x: number; y: number } | undefined,
  selfReferential: boolean,
): { source: HandlePosition; target: HandlePosition } {
  if (selfReferential) return { source: 'right', target: 'top' };
  const deltaX = (target?.x ?? 0) - (source?.x ?? 0);
  const deltaY = (target?.y ?? 0) - (source?.y ?? 0);
  if (Math.abs(deltaX) > Math.abs(deltaY)) {
    return deltaX >= 0
      ? { source: 'right', target: 'left' }
      : { source: 'left', target: 'right' };
  }
  return deltaY >= 0
    ? { source: 'bottom', target: 'top' }
    : { source: 'top', target: 'bottom' };
}

export function resourcePath(kind: string, id: string) { return `/ontology/${({ OBJECT_TYPE: 'object-types', LINK_TYPE: 'link-types', INTERFACE: 'interfaces', ACTION: 'actions', FUNCTION: 'functions' } as Record<string, string>)[kind]}/${id}`; }
