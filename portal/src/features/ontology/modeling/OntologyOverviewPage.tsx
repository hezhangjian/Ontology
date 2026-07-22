/* eslint-disable react-hooks/exhaustive-deps, react-refresh/only-export-components */
import { DownOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Dropdown, Empty, Input, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { ModelingSummary } from './ontology.types';

const { Paragraph, Title } = Typography;

export default function OntologyOverviewPage({ accessToken, canBuild, navigate }: { accessToken: string; canBuild: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]);
  const [summary, setSummary] = useState<ModelingSummary>();
  const [problem, setProblem] = useState('');
  const load = () => api.summary().then(setSummary).catch((error: Error) => setProblem(error.message));
  useEffect(() => { void load(); }, []);
  return <div>
    <div className="page-title-row"><div><Title level={2}>本体管理</Title><Paragraph>把数据集中的字段组织成人员、部门、订单等业务对象，并建立它们之间的关系。</Paragraph></div><Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>{canBuild && <Button icon={<PlusOutlined />} onClick={() => navigate('/ontology/object-types/new/from-dataset')} type="primary">从 Dataset 创建对象</Button>}{canBuild && <Dropdown menu={{items:[{key:'/ontology/object-types/new',label:'手工创建空对象'},{key:'/ontology/link-types/new',label:'关系类型'},{key:'/ontology/interfaces/new',label:'接口'},{key:'/ontology/actions/new',label:'动作'},{key:'/ontology/functions/new',label:'函数'}],onClick:({key})=>navigate(key)}}><Button>新建其他 <DownOutlined/></Button></Dropdown>}</Space></div>
    {problem && <Alert message={problem} showIcon type="error" />}
    <Input.Search enterButton={<SearchOutlined />} onSearch={(value) => navigate(`/ontology/search?q=${encodeURIComponent(value)}`)} placeholder="搜索显示名称、API 名称、属性、负责人或标签" size="large" />
    {(summary?.resourceCounts.OBJECT_TYPE??0)===0?<Card style={{marginTop:20}}><Empty description={<><Title level={4}>从数据开始建立业务模型</Title><Paragraph>选择一个 Dataset，平台会自动把字段映射为业务对象属性。</Paragraph></>}><Space><Button onClick={()=>navigate('/ontology/object-types/new/from-dataset')} type="primary">选择 Dataset</Button><Button onClick={()=>navigate('/ontology/object-types/new')}>手工创建空对象</Button></Space></Empty></Card>:<div className="ontology-overview-grid"><Card title="对象与能力"><Table columns={[{ title: '资源', dataIndex: 'displayName', render: (value, row) => <a onClick={() => navigate(resourcePath(row.kind, row.id))}>{value}</a> }, { title: '类型', dataIndex: 'kind', render: tagKind }, { title: '属性数', render:(_,row)=>row.properties?.length??0 }, { title: '负责人', dataIndex: 'ownerName' }]} dataSource={summary?.recentResources} locale={{ emptyText: <Empty /> }} pagination={false} rowKey="id" size="small" /></Card><Card title="对象—关系图"><div className="ontology-mini-graph">{summary?.recentResources.filter((item)=>item.kind==='OBJECT_TYPE').slice(0,5).map((item,index)=><Space key={item.id}><button className="link-button" onClick={()=>navigate(resourcePath(item.kind,item.id))}>{item.displayName}<small> · {item.properties.length} 个属性</small></button>{index<(summary?.recentResources.length??0)-1&&<span>— 关系 —</span>}</Space>)}</div><Paragraph type="secondary">点击对象节点可进入属性、数据来源、关系和使用位置。</Paragraph></Card></div>}
  </div>;
}

function tagKind(value: string) { return <Tag color="geekblue">{value}</Tag>; }
export function resourcePath(kind: string, id: string) { return `/ontology/${({ OBJECT_TYPE: 'object-types', LINK_TYPE: 'link-types', INTERFACE: 'interfaces', ACTION: 'actions', FUNCTION: 'functions' } as Record<string, string>)[kind]}/${id}`; }
