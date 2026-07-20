/* eslint-disable react-hooks/exhaustive-deps */
import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { HealthIssue } from './ontology.types';

const { Paragraph, Title } = Typography;
export default function OntologyHealthPage({ accessToken }: { accessToken: string }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]); const [items, setItems] = useState<HealthIssue[]>([]); const load = () => api.health().then(setItems); useEffect(() => { void load(); }, []);
  return <div><div className="page-title-row"><div><Title level={2}>健康问题</Title><Paragraph>聚合 Schema、Projection、发布、质量和消费风险，不复制敏感失败正文。</Paragraph></div><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></div>{items.some((item) => item.severity === 'CRITICAL') && <Alert message="存在严重本体健康问题，旧 active revision 仍在服务。" showIcon type="error" />}<Table columns={[{ title: '严重度', dataIndex: 'severity', render: (value) => <Tag color={value === 'CRITICAL' ? 'red' : value === 'ERROR' ? 'volcano' : 'orange'}>{value}</Tag> }, { title: '问题', render: (_, row) => <><strong>{row.title}</strong><br /><Typography.Text type="secondary">{row.evidence}</Typography.Text></> }, { title: '分类', dataIndex: 'category' }, { title: '资源', dataIndex: 'resourceName', render: (value) => value || '平台' }, { title: '负责人', dataIndex: 'ownerName', render: (value) => value || '未分配' }, { title: '建议修复', dataIndex: 'recommendation' }, { title: '最近发生', dataIndex: 'lastSeenAt', render: (value) => new Date(value).toLocaleString() }]} dataSource={items} locale={{ emptyText: <Empty description="当前没有持久化健康问题" /> }} pagination={false} rowKey="id" size="small" /></div>;
}
