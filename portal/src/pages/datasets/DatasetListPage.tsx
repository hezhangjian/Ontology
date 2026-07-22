import { DatabaseOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Empty, Input, message, Modal, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { datasetsApi } from './datasetsApi';
import type { Dataset } from './types';

export default function DatasetListPage({ accessToken, canBuild, navigate }: { accessToken: string; canBuild: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => datasetsApi(accessToken), [accessToken]); const [items, setItems] = useState<Dataset[]>([]); const [search, setSearch] = useState('');
  const load = useCallback(() => api.list(search).then((page) => setItems(page.items)), [api, search]); useEffect(() => { void load(); }, [load]);
  const remove = (dataset: Dataset) => Modal.confirm({ title: `永久删除“${dataset.name}”？`, content: '将删除 MinIO 正文、OpenSearch 查询副本和 Dataset 元数据。若看板仍在使用，平台会阻止删除并指出依赖。', okButtonProps: { danger: true }, okText: '永久删除', onOk: async () => { try { await api.remove(dataset.id); message.success('Dataset 已永久删除'); await load(); } catch (cause) { message.error(cause instanceof Error ? cause.message : 'Dataset 删除失败'); throw cause; } } });
  return <div className="dataset-list-page"><div className="page-title-row"><div><Typography.Title level={2}>数据集</Typography.Title><Typography.Paragraph>管理管道产出的可复用数据产品，在这里查看预览、字段质量和数据来源。</Typography.Paragraph></div>{canBuild && <Button icon={<PlusOutlined />} onClick={() => navigate('/data/pipelines/new')} type="primary">新建 Dataset</Button>}</div>
    <Input.Search allowClear onChange={(event) => setSearch(event.target.value)} onSearch={() => void load()} placeholder="搜索数据集" style={{ maxWidth: 360, marginBottom: 16 }} />
    {items.length === 0 ? <Empty description="还没有数据集" image={<DatabaseOutlined style={{ fontSize: 56 }} />}><Button onClick={() => navigate('/data/connections/new')} type="primary">连接或上传数据</Button></Empty> : <Table dataSource={items} rowKey="id" columns={[{ title:'名称',dataIndex:'name',render:(value:string,item:Dataset)=><button className="link-button" onClick={()=>navigate(`/data/datasets/${item.id}`)}>{value}</button>},{title:'来源管道',dataIndex:'pipelineName'},{title:'行数',dataIndex:'rowCount',render:(value:number)=>value.toLocaleString()},{title:'字段',render:(_:unknown,item:Dataset)=>item.fields.length},{title:'状态',dataIndex:'status',render:(value:string)=><Tag color={value==='READY'?'green':'gold'}>{value==='READY'?'可用':'生成中'}</Tag>},{title:'最近更新',dataIndex:'updatedAt',render:(value:string)=>new Date(value).toLocaleString()},{title:'操作',render:(_:unknown,item:Dataset)=>canBuild?<Button danger icon={<DeleteOutlined/>} onClick={()=>remove(item)} type="text">删除</Button>:null}]} />}
  </div>;
}
