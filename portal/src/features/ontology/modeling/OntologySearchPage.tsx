/* eslint-disable react-hooks/exhaustive-deps */
import { Empty, Input, List, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import { resourcePath } from './OntologyOverviewPage';

const { Paragraph, Title } = Typography;
export default function OntologySearchPage({ accessToken, navigate }: { accessToken: string; navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]); const initial = new URLSearchParams(window.location.search).get('q') ?? ''; const [query, setQuery] = useState(initial); const [items, setItems] = useState<Array<Record<string, unknown>>>([]);
  const search = (value: string) => { setQuery(value); void api.search(value).then(setItems); }; useEffect(() => { void api.search(initial).then(setItems); }, []);
  return <div><Title level={2}>搜索本体资源</Title><Paragraph>结果经过当前用户的元数据权限裁剪。</Paragraph><Input.Search enterButton onSearch={search} placeholder="名称、API 名称、属性、负责人或标签" size="large" value={query} onChange={(event) => setQuery(event.target.value)} /><List className="ontology-search-results" dataSource={items} locale={{ emptyText: <Empty description="没有匹配资源" /> }} renderItem={(item) => <List.Item onClick={() => navigate(resourcePath(String(item.kind), String(item.id)))}><List.Item.Meta description={String(item.description || '暂无说明')} title={<><a>{String(item.displayName)}</a> <Typography.Text code>{String(item.apiName)}</Typography.Text> <Tag>{String(item.kind)}</Tag></>} /></List.Item>} /></div>;
}
