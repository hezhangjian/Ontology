import { ArrowRightOutlined, SearchOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { List } from '@/shared/components/data';
import { Alert, Empty, Skeleton } from '@/shared/components/feedback';
import { Input } from '@/shared/components/forms';
import { Space } from '@/shared/components/layout';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useState } from 'react';
import { ExplorerApi } from './explorer.service';
import type { SearchResponse } from './explorer.types';

export default function GlobalObjectSearchPage({  navigate }: { navigate: (path: string) => void }) {
  const initial = new URLSearchParams(window.location.search).get('q') ?? ''; const api = useMemo(() => new ExplorerApi(), []);
  const [query, setQuery] = useState(initial); const [result, setResult] = useState<SearchResponse>(); const [error, setError] = useState('');
  const run = (value: string) => { setError(''); window.history.replaceState({}, '', `/ontology/explorer/search?q=${encodeURIComponent(value)}`); void api.search(value).then(setResult).catch((cause: Error) => setError(cause.message)); };
  useEffect(() => { void api.search(initial).then(setResult).catch((cause: Error) => setError(cause.message)); }, [api, initial]);
  const objects = result?.objects ?? [];
  const objectTypes = result?.objectTypes ?? [];
  return <div className="explorer-page global-search-page"><div className="explorer-page-heading"><Typography.Title level={2}>搜索对象</Typography.Title><Typography.Paragraph>在当前权限范围内查找对象实例和对象类型。</Typography.Paragraph></div><Input.Search aria-label="搜索对象" enterButton="搜索" onChange={(event) => setQuery(event.target.value)} onSearch={run} placeholder="输入名称或业务关键词" prefix={<SearchOutlined />} size="large" value={query} />{error && <Alert message={error} showIcon type="error" />}{!result && !error ? <Skeleton active /> : objects.length || objectTypes.length ? <div className="search-results">
    {objects.length > 0 && <section className="search-result-section"><Typography.Title level={4}>对象 <span className="search-result-count">{objects.length}</span></Typography.Title><SearchObjects /></section>}
    {objectTypes.length > 0 && <section className="search-result-section"><Typography.Title level={4}>对象类型 <span className="search-result-count">{objectTypes.length}</span></Typography.Title><List dataSource={objectTypes} renderItem={(item) => <List.Item actions={[<Button key="open" onClick={() => navigate(`/ontology/explorer/${item.id}`)} type="link">探索</Button>]}><List.Item.Meta title={item.displayName} description={`${item.properties.filter((property) => !property.sensitive).length} 个业务属性`} /></List.Item>} /></section>}
  </div> : <Empty description={query ? '没有匹配的有权对象' : '当前没有可搜索的对象'} image={<SearchOutlined />} />}</div>;
  function SearchObjects() { return objects.length ? <List dataSource={objects} renderItem={(item) => <List.Item actions={[<Button icon={<ArrowRightOutlined />} key="open" onClick={() => navigate(`/ontology/explorer/${item.objectTypeId}/${encodeURIComponent(item.objectId)}`)}>打开详情</Button>]}><List.Item.Meta title={<Space><strong>{item.title}</strong><Tag color="success">正常</Tag></Space>} description={Object.values(item.properties).filter((value) => value != null && String(value) !== item.title).slice(0, 3).map(String).join(' · ') || '业务对象'} /></List.Item>} /> : <Empty description={query ? '没有匹配的有权对象' : '输入关键词搜索当前本体中的对象'} image={<SearchOutlined />} />; }
}
