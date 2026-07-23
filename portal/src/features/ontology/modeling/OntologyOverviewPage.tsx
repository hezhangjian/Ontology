/* eslint-disable react-hooks/exhaustive-deps, react-refresh/only-export-components */
import { DownOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Dropdown, Empty, Input, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Background, Controls, MarkerType, ReactFlow, type Edge, type Node } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { modelingApi } from './ontology.service';
import type { ModelingSummary, OntologyResource } from './ontology.types';

const { Paragraph, Title } = Typography;

export default function OntologyOverviewPage({ accessToken, canBuild, navigate }: { accessToken: string; canBuild: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]);
  const [summary, setSummary] = useState<ModelingSummary>();
  const [objects, setObjects] = useState<OntologyResource[]>([]);
  const [links, setLinks] = useState<OntologyResource[]>([]);
  const [problem, setProblem] = useState('');
  const load = () => Promise.all([api.summary(), api.listResources('OBJECT_TYPE'), api.listResources('LINK_TYPE')])
    .then(([nextSummary, nextObjects, nextLinks]) => { setSummary(nextSummary); setObjects(nextObjects); setLinks(nextLinks); setProblem(''); })
    .catch((error: Error) => setProblem(error.message));
  useEffect(() => { void load(); }, []);
  const graph = useMemo(() => ontologyGraph(objects, links), [objects, links]);
  return <div>
    <div className="page-title-row"><div><Title level={2}>模型总览</Title><Paragraph>这里定义当前本体包含哪些对象类型、属性和关系；具体人员、小组等实例请到“对象探索”中查看。</Paragraph></div><Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>{canBuild && <Button icon={<PlusOutlined />} onClick={() => navigate('/ontology/object-types/new/from-dataset')} type="primary">从 Dataset 创建对象类型</Button>}{canBuild && <Dropdown menu={{items:[{key:'/ontology/object-types/new',label:'手工创建对象类型'},{key:'/ontology/link-types/new',label:'关系类型'},{key:'/ontology/interfaces/new',label:'接口'},{key:'/ontology/actions/new',label:'动作'},{key:'/ontology/functions/new',label:'函数'}],onClick:({key})=>navigate(key)}}><Button>新建其他 <DownOutlined/></Button></Dropdown>}</Space></div>
    {problem && <Alert message={problem} showIcon type="error" />}
    <Input.Search enterButton={<SearchOutlined />} onSearch={(value) => navigate(`/ontology/search?q=${encodeURIComponent(value)}`)} placeholder="搜索显示名称、API 名称、属性、负责人或标签" size="large" />
    {(summary?.resourceCounts.OBJECT_TYPE??0)===0?<Card style={{marginTop:20}}><Empty description={<><Title level={4}>当前本体还没有对象类型</Title><Paragraph>可从 Dataset 映射对象类型，或手工创建；它不会影响其他本体。</Paragraph></>}><Space><Button onClick={()=>navigate('/ontology/object-types/new/from-dataset')} type="primary">选择 Dataset</Button><Button onClick={()=>navigate('/ontology/object-types/new')}>手工创建对象类型</Button></Space></Empty></Card>:<div className="ontology-overview-grid"><Card title="对象类型与能力"><Table columns={[{ title: '资源', dataIndex: 'displayName', render: (value, row) => <a onClick={() => navigate(resourcePath(row.kind, row.id))}>{value}</a> }, { title: '类型', dataIndex: 'kind', render: tagKind }, { title: '属性数', render:(_,row)=>row.properties?.length??0 }, { title: '负责人', dataIndex: 'ownerName' }]} dataSource={summary?.recentResources} locale={{ emptyText: <Empty /> }} pagination={false} rowKey="id" size="small" /></Card><Card title="对象关系图" extra={<a onClick={() => navigate('/ontology/link-types')}>管理关系</a>}>{graph.nodes.length ? <div className="ontology-graph"><ReactFlow edges={graph.edges} fitView fitViewOptions={{ padding: 0.25 }} nodes={graph.nodes} nodesDraggable={false} nodesConnectable={false} onNodeClick={(_, node) => navigate(resourcePath('OBJECT_TYPE', node.id))} proOptions={{ hideAttribution: true }}><Background gap={18} size={1} /><Controls showInteractive={false} /></ReactFlow></div> : <Empty description="暂无对象类型" />}<Paragraph type="secondary">节点是对象类型，连线是已定义的关系类型。人员实例通过关系边指向具体小组实例。</Paragraph></Card></div>}
  </div>;
}

function ontologyGraph(objects: OntologyResource[], links: OntologyResource[]): { nodes: Node[]; edges: Edge[] } {
  const radius = Math.max(130, objects.length * 34);
  const nodes: Node[] = objects.map((object, index) => {
    const angle = (index / Math.max(objects.length, 1)) * Math.PI * 2 - Math.PI / 2;
    return { id: object.id, position: { x: radius + Math.cos(angle) * radius, y: radius + Math.sin(angle) * radius }, data: { label: `${object.displayName} · ${object.properties.length} 属性` }, className: 'ontology-graph-node' };
  });
  const ids = new Set(objects.map((object) => object.id));
  const edges: Edge[] = links.flatMap((link) => {
    const source = String(link.definition.leftObjectTypeId ?? '');
    const target = String(link.definition.rightObjectTypeId ?? '');
    if (!ids.has(source) || !ids.has(target)) return [];
    return [{ id: link.id, source, target, label: `${link.displayName} · ${String(link.definition.cardinality ?? '')}`, markerEnd: { type: MarkerType.ArrowClosed }, style: { stroke: '#6079d9', strokeWidth: 1.6 }, labelStyle: { fill: '#42516b', fontSize: 11 } }];
  });
  return { nodes, edges };
}

function tagKind(value: string) { return <Tag color="geekblue">{value}</Tag>; }
export function resourcePath(kind: string, id: string) { return `/ontology/${({ OBJECT_TYPE: 'object-types', LINK_TYPE: 'link-types', INTERFACE: 'interfaces', ACTION: 'actions', FUNCTION: 'functions' } as Record<string, string>)[kind]}/${id}`; }
