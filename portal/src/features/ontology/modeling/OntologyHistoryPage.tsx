/* eslint-disable react-hooks/exhaustive-deps */
import { Alert, Table, Tag, Timeline, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { HistoryEntry } from './ontology.types';

const { Paragraph, Title } = Typography;
export default function OntologyHistoryPage({ accessToken }: { accessToken: string }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]); const [items, setItems] = useState<HistoryEntry[]>([]); useEffect(() => { void api.history().then(setItems); }, []);
  return <div><div className="page-title-row"><div><Title level={2}>变更历史</Title><Paragraph>全局 revision、不可变发布记录和受限回滚入口。</Paragraph></div></div><Alert message="回滚会复制历史兼容元数据到新 Proposal；不会修改历史或自动恢复对象值。" showIcon type="info" /><div className="ontology-history-grid"><Timeline items={items.map((item) => ({ color: item.status === 'ACTIVE' ? 'green' : 'gray', children: <><strong>Revision {item.revision}</strong> <Tag>{item.status}</Tag><Paragraph>{item.proposalTitle || '平台初始化'} · {item.activatedAt ? new Date(item.activatedAt).toLocaleString() : '未激活'}</Paragraph></> }))} /><Table columns={[{ title: 'Revision', dataIndex: 'revision', render: (value) => `r${value}` }, { title: '状态', dataIndex: 'status', render: (value) => <Tag>{value}</Tag> }, { title: 'Proposal', dataIndex: 'proposalTitle', render: (value) => value || '初始化' }, { title: '对象契约数', dataIndex: 'resourceCount' }, { title: '激活时间', dataIndex: 'activatedAt', render: (value) => value ? new Date(value).toLocaleString() : '—' }]} dataSource={items} pagination={false} rowKey="revision" size="small" /></div></div>;
}
