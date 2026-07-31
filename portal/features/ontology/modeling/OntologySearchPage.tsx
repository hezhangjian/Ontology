/* eslint-disable react-hooks/exhaustive-deps */
import { Tag } from '@/shared/components/actions';
import { List } from '@/shared/components/data';
import { Empty } from '@/shared/components/feedback';
import { Input } from '@/shared/components/forms';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import { resourcePath } from './OntologyOverviewPage';

const { Paragraph, Title } = Typography;
export default function OntologySearchPage({  navigate }: { navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(), []); const initial = new URLSearchParams(window.location.search).get('q') ?? ''; const [query, setQuery] = useState(initial); const [items, setItems] = useState<Array<Record<string, unknown>>>([]);
  const search = (value: string) => { setQuery(value); void api.search(value).then(setItems); }; useEffect(() => { void api.search(initial).then(setItems); }, []);
  return <div><Title level={2}>搜索本体资源</Title><Paragraph>搜索当前本体中的模型定义。</Paragraph><Input.Search enterButton onSearch={search} placeholder="名称、ID、属性或标签" size="large" value={query} onChange={(event) => setQuery(event.target.value)} /><List className="ontology-search-results" dataSource={items} locale={{ emptyText: <Empty description="没有匹配资源" /> }} renderItem={(item) => <List.Item onClick={() => navigate(resourcePath(String(item.kind), String(item.id)))}><List.Item.Meta description={String(item.description || '暂无说明')} title={<><a>{String(item.displayName)}</a> <Typography.Text code>{String(item.id)}</Typography.Text> <Tag>{String(item.kind)}</Tag></>} /></List.Item>} /></div>;
}
