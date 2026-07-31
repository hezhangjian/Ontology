import { ArrowRightOutlined, SearchOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Table } from '@/shared/components/data';
import { Alert, Skeleton } from '@/shared/components/feedback';
import { Input } from '@/shared/components/forms';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useState } from 'react';
import { ExplorerApi } from './explorer.service';
import type { ExplorerHome } from './explorer.types';

export default function ExplorerHomePage({  navigate }: { navigate: (path: string) => void }) {
  const api = useMemo(() => new ExplorerApi(), []);
  const [home, setHome] = useState<ExplorerHome>(); const [query, setQuery] = useState(''); const [error, setError] = useState('');
  useEffect(() => { void api.home().then(setHome).catch((cause: Error) => setError(cause.message)); }, [api]);
  if (!home && !error) return <Skeleton active />;
  return <div className="explorer-page">
    <div className="explorer-hero"><div><Typography.Title level={2}>对象探索</Typography.Title><Typography.Text>浏览和搜索当前本体中有权访问的业务对象。</Typography.Text></div><Tag color={home?.searchStatus === 'HEALTHY' ? 'success' : 'warning'}>搜索服务 {home?.searchStatus ?? 'DEGRADED'}</Tag></div>
    {error && <Alert type="error" message={error} showIcon />}
    <Input.Search aria-label="搜索所有对象" enterButton="搜索" onChange={(event) => setQuery(event.target.value)} onSearch={() => navigate(`/ontology/explorer/search?q=${encodeURIComponent(query)}`)} placeholder="搜索对象或对象类型" prefix={<SearchOutlined />} size="large" value={query} />
    <section><div className="section-heading"><Typography.Title level={4}>对象类型</Typography.Title><Typography.Text type="secondary">{home?.objectTypes.length ?? 0} 个可访问类型 · 更新于 {home ? new Date(home.indexUpdatedAt).toLocaleString() : '—'}</Typography.Text></div>
      <Table dataSource={home?.objectTypes ?? []} pagination={false} rowKey="id" onRow={(row) => ({ onClick: () => navigate(`/ontology/explorer/${row.id}`) })} columns={[{ title: '业务对象', dataIndex: 'displayName', render: (value) => <strong>{value}</strong> }, { title: '对象实例', render: (_, row) => (home?.objectCounts[row.id] ?? 0).toLocaleString() }, { title: '业务属性', render: (_, row) => row.properties.filter((item) => !item.sensitive).length }, { title: '状态', render: () => <Tag color="success">可用</Tag> }, { title: '', render: (_, row) => <Button icon={<ArrowRightOutlined />} onClick={() => navigate(`/ontology/explorer/${row.id}`)} type="text">探索实例</Button> }]} />
    </section>
  </div>;
}
