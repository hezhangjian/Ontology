/* eslint-disable react-hooks/exhaustive-deps */
import { Input, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { PropertyView } from './ontology.types';

const { Paragraph, Text, Title } = Typography;
export default function PropertyCatalogPage({ accessToken }: { accessToken: string }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]); const [items, setItems] = useState<PropertyView[]>([]); const [query, setQuery] = useState('');
  useEffect(() => { void api.listProperties().then(setItems); }, []); const filtered = items.filter((item) => `${item.displayName} ${item.apiName} ${item.physicalKey}`.toLowerCase().includes(query.toLowerCase()));
  return <div><div className="page-title-row"><div><Title level={2}>属性目录</Title><Paragraph>稳定属性 ID、类型约束、索引策略与来源字段的统一目录。</Paragraph></div></div><Input.Search allowClear onChange={(event) => setQuery(event.target.value)} placeholder="搜索属性" style={{ marginBottom: 16, width: 360 }} /><Table columns={[{ title: '显示 / API 名称', render: (_, row) => <>{row.displayName}<br /><Text code>{row.apiName}</Text></> }, { title: '类型', dataIndex: 'valueType' }, { title: '约束', render: (_, row) => <Space>{row.primaryKey && <Tag color="blue">主键</Tag>}{row.required && <Tag>必填</Tag>}{row.sensitive && <Tag color="red">敏感</Tag>}{row.searchable && <Tag color="green">搜索</Tag>}</Space> }, { title: '物理键', dataIndex: 'physicalKey', render: (value) => <Text code>{value}</Text> }, { title: '来源字段', dataIndex: 'sourceField', render: (value) => value || 'Action' }]} dataSource={filtered} pagination={false} rowKey="id" size="small" /></div>;
}
