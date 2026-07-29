import { BranchesOutlined, DeleteOutlined, EditOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Descriptions, Table } from '@/shared/components/data';
import { Alert, Skeleton, message } from '@/shared/components/feedback';
import { Card, Space } from '@/shared/components/layout';
import { Tabs } from '@/shared/components/navigation';
import { Modal } from '@/shared/components/overlays';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useState } from 'react';
import { datasetsApi } from './datasetsApi';
import type { Dataset, DatasetPreview } from './types';

export default function DatasetDetailPage({ id, navigate }: { id: string; navigate: (path: string) => void }) {
  const api = useMemo(() => datasetsApi(), []); const [dataset,setDataset]=useState<Dataset>(); const [preview,setPreview]=useState<DatasetPreview>({ columns: [], rows: [], total: 0 });
  useEffect(()=>{let active=true; let timer:number|undefined; const load=async()=>{const data=await api.get(id);if(!active)return;setDataset(data);if(data.status==='READY'){setPreview(await api.preview(id));return;}timer=window.setTimeout(()=>void load(),1500);};void load();return()=>{active=false;if(timer)window.clearTimeout(timer);};},[api,id]); if(!dataset)return <Skeleton active/>;
  const columns=preview.columns.map((name)=>({title:name,dataIndex:name,key:name,width:160,ellipsis:true}));
  const remove=()=>Modal.confirm({title:`永久删除“${dataset.name}”？`,content:'将删除 MinIO 正文、OpenSearch 查询副本和 Dataset 元数据。此操作不可撤销。',okButtonProps:{danger:true},okText:'永久删除',onOk:async()=>{try{await api.remove(dataset.id);message.success('Dataset 已永久删除');navigate('/data/datasets');}catch(cause){message.error(cause instanceof Error?cause.message:'Dataset 删除失败');throw cause;}}});
  return <div className="dataset-detail-page"><div className="page-title-row"><div><Space><Typography.Title level={2}>{dataset.name}</Typography.Title><Tag color={dataset.status==='READY'?'green':dataset.status==='FAILED'?'red':'gold'}>{dataset.status==='READY'?'可用':dataset.status==='FAILED'?'失败':'Flink 生成中'}</Tag></Space><Typography.Paragraph>{dataset.description}</Typography.Paragraph></div><Space><Button icon={<EditOutlined/>} onClick={()=>navigate(`/data/datasets/${dataset.id}/edit`)}>编辑</Button><Button danger icon={<DeleteOutlined/>} onClick={remove}>永久删除</Button></Space></div>
    {dataset.status==='BUILDING'&&<Alert description="Flink 正在读取 CSV、执行管道并通过 Pulsar 交付结果。完成后本页会自动刷新。" message="Dataset 正在物化" showIcon type="info"/>}
    <Descriptions bordered column={4} items={[{key:'rows',label:'行数',children:dataset.rowCount.toLocaleString()},{key:'fields',label:'字段',children:dataset.fields.length},{key:'source',label:'来源',children:dataset.source.kind==='MANUAL'?'手动维护':<Button icon={<BranchesOutlined/>} onClick={()=>navigate(`/data/pipelines/${dataset.source.id}/edit`)} type="link">{dataset.source.name}</Button>},{key:'updated',label:'最近更新',children:new Date(dataset.updatedAt).toLocaleString()}]}/>
    <Tabs items={[{key:'preview',label:'数据预览',children:<Card><Table columns={columns} dataSource={preview.rows} pagination={false} rowKey={(_,index)=>String(index)} scroll={{x:'max-content'}} size="small"/><Typography.Text type="secondary">显示前 {preview.rows.length} 行，共 {preview.total.toLocaleString()} 行</Typography.Text></Card>},{key:'schema',label:'字段',children:<Table dataSource={dataset.fields} pagination={false} rowKey="name" columns={[{title:'字段名称',dataIndex:'name'},{title:'类型',dataIndex:'type'},{title:'可为空',dataIndex:'nullable',render:(value:boolean)=>value?'是':'否'},{title:'样例值',dataIndex:'samples',render:(values:unknown[])=>values.map(String).join(' · ')}]}/>},{key:'source',label:'来源',children:<Card><Typography.Text>{dataset.source.kind==='MANUAL'?'该 Dataset 独立于 Pipeline，由用户直接维护。':`由 Pipeline“${dataset.source.name}”生成，可重复物化。`}</Typography.Text></Card>}]} />
  </div>;
}
