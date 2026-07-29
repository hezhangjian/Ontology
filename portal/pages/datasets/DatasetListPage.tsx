import { DatabaseOutlined, DeleteOutlined, PlusOutlined, SearchOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Table } from '@/shared/components/data';
import { Empty, message } from '@/shared/components/feedback';
import { Input } from '@/shared/components/forms';
import { Modal } from '@/shared/components/overlays';
import { Typography } from '@/shared/components/typography';
import { Space } from '@/shared/components/layout';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { datasetsApi } from './datasetsApi';
import type { Dataset } from './types';

export default function DatasetListPage({ navigate }: { navigate: (path: string) => void }) {
  const api = useMemo(() => datasetsApi(), []); const [items, setItems] = useState<Dataset[]>([]); const [search, setSearch] = useState('');
  const load = useCallback(() => api.list(search).then((page) => setItems(page.items)), [api, search]); useEffect(() => { void load(); }, [load]);
  const remove = (dataset: Dataset) => Modal.confirm({ title: `永久删除“${dataset.name}”？`, content: '将删除 MinIO 正文、OpenSearch 查询副本和 Dataset 元数据。若看板仍在使用，平台会阻止删除并指出依赖。', okButtonProps: { danger: true }, okText: '永久删除', onOk: async () => { try { await api.remove(dataset.id); message.success('Dataset 已永久删除'); await load(); } catch (cause) { message.error(cause instanceof Error ? cause.message : 'Dataset 删除失败'); throw cause; } } });
  return <div className="dataset-list-page collection-page"><div className="page-title-row"><div><Typography.Title level={2}>数据集</Typography.Title><Typography.Paragraph>管理手动维护或由 Pipeline 生成的可复用数据产品。</Typography.Paragraph></div><Space><Button onClick={() => navigate('/data/pipelines/new')}>从 Pipeline 生成</Button><Button icon={<PlusOutlined />} onClick={() => navigate('/data/datasets/new')} type="primary">手动创建</Button></Space></div>
    <div className="collection-toolbar"><Input.Search allowClear onChange={(event) => setSearch(event.target.value)} onSearch={() => void load()} placeholder="搜索数据集" prefix={<SearchOutlined />} /><span>{items.length} 个数据产品</span></div>
    {items.length === 0 ? <div className="page-empty-surface"><div className="empty-state-icon"><DatabaseOutlined /></div><Typography.Title level={4}>还没有数据集</Typography.Title><Typography.Paragraph>可以直接手动创建，也可以通过 Pipeline 生成。</Typography.Paragraph><Button onClick={() => navigate('/data/datasets/new')} type="primary">手动创建 Dataset</Button></div> : <Table dataSource={items} rowKey="id" columns={[{ title:'名称',dataIndex:'name',render:(value:string,item:Dataset)=><button className="link-button" onClick={()=>navigate(`/data/datasets/${item.id}`)}>{value}</button>},{title:'来源',render:(_:unknown,item:Dataset)=>item.source.kind==='MANUAL'?'手动维护':item.source.name},{title:'行数',dataIndex:'rowCount',render:(value:number)=>value.toLocaleString()},{title:'字段',render:(_:unknown,item:Dataset)=>item.fields.length},{title:'状态',dataIndex:'status',render:(value:string)=><Tag color={value==='READY'?'green':'gold'}>{value==='READY'?'可用':'生成中'}</Tag>},{title:'最近更新',dataIndex:'updatedAt',render:(value:string)=>new Date(value).toLocaleString()},{title:'操作',render:(_:unknown,item:Dataset)=><Space><Button onClick={()=>navigate(`/data/datasets/${item.id}/edit`)} type="text">编辑</Button><Button danger icon={<DeleteOutlined/>} onClick={()=>remove(item)} type="text">删除</Button></Space>}]} />}
  </div>;
}
