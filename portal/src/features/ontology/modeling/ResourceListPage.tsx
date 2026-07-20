/* eslint-disable react-hooks/exhaustive-deps */
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Empty, Input, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { OntologyResource, ResourceKind } from './ontology.types';
import { resourcePath } from './OntologyOverviewPage';

const { Paragraph, Title } = Typography;
const labels: Record<ResourceKind, [string, string]> = {
  OBJECT_TYPE: ['对象类型', '定义业务对象身份、属性、映射和投影策略。'], LINK_TYPE: ['关系类型', '定义可从两侧遍历的稳定关系资源。'],
  INTERFACE: ['Interface', '用显式属性槽位统一跨对象类型查询。'], ACTION: ['Action', '用声明式规则定义允许发生的业务改变。'],
  FUNCTION: ['Function', '构建版本化、类型化、只读的可信计算。'],
};

export default function ResourceListPage({ accessToken, canBuild, kind, navigate }: { accessToken: string; canBuild: boolean; kind: ResourceKind; navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]);
  const [items, setItems] = useState<OntologyResource[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const load = () => { setLoading(true); api.listResources(kind, search).then(setItems).finally(() => setLoading(false)); };
  useEffect(() => { void load(); }, [kind, search]);
  return <div><div className="page-title-row"><div><Title level={2}>{labels[kind][0]}</Title><Paragraph>{labels[kind][1]}</Paragraph></div><Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>{canBuild && <Button icon={<PlusOutlined />} onClick={() => navigate(`/ontology/${resourcePath(kind, '').split('/')[2]}/new`)} type="primary">新建{labels[kind][0]}</Button>}</Space></div><div className="table-toolbar"><Input.Search allowClear onSearch={setSearch} placeholder={`搜索${labels[kind][0]}`} style={{ width: 340 }} /><Space><Tag>稳定 API 名</Tag><Tag>不可变版本</Tag></Space></div><Table columns={[{ title: '显示 / API 名称', dataIndex: 'displayName', render: (value, row) => <><a onClick={() => navigate(resourcePath(row.kind, row.id))}>{value}</a><br /><Typography.Text code>{row.apiName}</Typography.Text></> }, { title: '成熟度', dataIndex: 'maturity', render: (value) => <Tag color={value === 'ACTIVE' ? 'green' : value === 'DEPRECATED' ? 'orange' : 'blue'}>{value}</Tag> }, ...(kind === 'OBJECT_TYPE' ? [{ title: '核心', dataIndex: 'promoted', render: (value: boolean) => value ? <Tag color="gold">核心</Tag> : '—' }, { title: '属性', dataIndex: 'properties', render: (value: unknown[]) => value.length }] : []), { title: '生命周期', dataIndex: 'lifecycle', render: (value) => <Tag>{value}</Tag> }, { title: '版本', dataIndex: 'version', render: (value, row) => `v${value}${row.publishedRevision ? ` · r${row.publishedRevision}` : ''}` }, { title: '负责人', dataIndex: 'ownerName' }, { title: '更新时间', dataIndex: 'updatedAt', render: (value) => new Date(value).toLocaleString() }]} dataSource={items} loading={loading} locale={{ emptyText: <Empty description={`暂无${labels[kind][0]}`} /> }} onRow={(row) => ({ onClick: () => navigate(resourcePath(row.kind, row.id)) })} pagination={false} rowKey="id" size="small" /></div>;
}
