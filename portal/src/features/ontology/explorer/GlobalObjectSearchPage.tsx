import { ArrowRightOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Input, List, Skeleton, Space, Tabs, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { ExplorerApi } from './explorer.service';
import type { SearchResponse } from './explorer.types';

export default function GlobalObjectSearchPage({ accessToken, navigate }: { accessToken: string; navigate: (path: string) => void }) {
  const initial = new URLSearchParams(window.location.search).get('q') ?? ''; const api = useMemo(() => new ExplorerApi(accessToken), [accessToken]);
  const [query, setQuery] = useState(initial); const [result, setResult] = useState<SearchResponse>(); const [error, setError] = useState('');
  const run = (value: string) => { setError(''); window.history.replaceState({}, '', `/ontology/explorer/search?q=${encodeURIComponent(value)}`); void api.search(value).then(setResult).catch((cause: Error) => setError(cause.message)); };
  useEffect(() => { void api.search(initial).then(setResult).catch((cause: Error) => setError(cause.message)); }, [api, initial]);
  const objects = result?.objects ?? [];
  return <div className="explorer-page"><Typography.Title level={2}>全局对象搜索</Typography.Title><Input.Search aria-label="全局对象搜索" enterButton onChange={(event) => setQuery(event.target.value)} onSearch={run} prefix={<SearchOutlined />} size="large" value={query} />{error && <Alert message={error} showIcon type="error" />}{!result && !error ? <Skeleton active /> : <Tabs items={[
    { key: 'all', label: `全部 (${objects.length + (result?.objectTypes.length ?? 0)})`, children: <SearchObjects /> },
    { key: 'objects', label: `对象 (${objects.length})`, children: <SearchObjects /> },
    { key: 'types', label: `对象类型 (${result?.objectTypes.length ?? 0})`, children: <List dataSource={result?.objectTypes} renderItem={(item) => <List.Item actions={[<Button key="open" onClick={() => navigate(`/ontology/explorer/${item.id}`)} type="link">探索</Button>]}><List.Item.Meta title={item.displayName} description={item.apiName} /></List.Item>} /> },
    { key: 'explorations', label: `已保存探索 (${result?.explorations.length ?? 0})`, children: <List dataSource={result?.explorations} renderItem={(item) => <List.Item>{item.name}</List.Item>} /> },
    { key: 'lists', label: `对象清单 (${result?.lists.length ?? 0})`, children: <List dataSource={result?.lists} renderItem={(item) => <List.Item>{item.name}</List.Item>} /> },
  ]} />}</div>;
  function SearchObjects() { return objects.length ? <List dataSource={objects} renderItem={(item) => <List.Item actions={[<Button icon={<ArrowRightOutlined />} key="open" onClick={() => navigate(`/ontology/explorer/${item.objectTypeId}/${encodeURIComponent(item.objectId)}`)}>打开详情</Button>]}><List.Item.Meta title={<Space><strong>{item.title}</strong><Tag>{item.objectTypeApiName}</Tag><Tag color="success">{item.quality}</Tag></Space>} description={<span>{item.objectId} · {Object.entries(item.properties).slice(0, 3).map(([key, value]) => `${key}: ${String(value)}`).join(' · ')}</span>} /></List.Item>} /> : <Empty description="没有匹配的有权对象" />; }
}
