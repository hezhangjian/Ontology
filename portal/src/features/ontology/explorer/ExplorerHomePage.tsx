import { ArrowRightOutlined, DatabaseOutlined, FolderOpenOutlined, SearchOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Input, List, Skeleton, Space, Statistic, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { ExplorerApi } from './explorer.service';
import type { ExplorerHome } from './explorer.types';

export default function ExplorerHomePage({ accessToken, navigate }: { accessToken: string; navigate: (path: string) => void }) {
  const api = useMemo(() => new ExplorerApi(accessToken), [accessToken]);
  const [home, setHome] = useState<ExplorerHome>(); const [query, setQuery] = useState(''); const [error, setError] = useState('');
  useEffect(() => { void api.home().then(setHome).catch((cause: Error) => setError(cause.message)); }, [api]);
  if (!home && !error) return <Skeleton active />;
  return <div className="explorer-page">
    <div className="explorer-hero"><div><Typography.Title level={2}>对象探索</Typography.Title><Typography.Text>搜索、筛选和分析有权访问的业务对象；所有写入必须通过已发布 Action。</Typography.Text></div><Tag color={home?.searchStatus === 'HEALTHY' ? 'success' : 'warning'}>搜索服务 {home?.searchStatus ?? 'DEGRADED'}</Tag></div>
    {error && <Alert type="error" message={error} showIcon />}
    <Input.Search aria-label="搜索所有对象" enterButton="搜索对象" onChange={(event) => setQuery(event.target.value)} onSearch={() => navigate(`/ontology/explorer/search?q=${encodeURIComponent(query)}`)} placeholder="搜索对象、对象类型、已保存探索和清单" prefix={<SearchOutlined />} size="large" value={query} />
    <div className="explorer-stats"><Card><Statistic prefix={<DatabaseOutlined />} title="可访问对象类型" value={home?.objectTypes.length ?? 0} /></Card><Card><Statistic prefix={<FolderOpenOutlined />} title="我的探索" value={home?.explorations.length ?? 0} /></Card><Card><Statistic prefix={<UnorderedListOutlined />} title="对象清单" value={home?.lists.length ?? 0} /></Card></div>
    <section><div className="section-heading"><Typography.Title level={4}>核心对象类型</Typography.Title><Typography.Text type="secondary">索引时间 {home ? new Date(home.indexUpdatedAt).toLocaleString() : '—'}</Typography.Text></div>
      <Table dataSource={home?.objectTypes ?? []} pagination={false} rowKey="id" onRow={(row) => ({ onClick: () => navigate(`/ontology/explorer/${row.id}`) })} columns={[{ title: '对象类型', render: (_, row) => <Space><strong>{row.displayName}</strong><code>{row.apiName}</code></Space> }, { title: '成熟度', dataIndex: 'maturity', render: (value) => <Tag color="blue">{value}</Tag> }, { title: '属性', render: (_, row) => row.properties.filter((item) => !item.sensitive).length }, { title: '', render: () => <Button icon={<ArrowRightOutlined />} type="text">打开探索</Button> }]} />
    </section>
    <div className="explorer-home-grid"><Card title="最近对象">{home?.recentObjects.length ? <List dataSource={home.recentObjects} renderItem={(item) => <List.Item actions={[<Button key="open" onClick={() => navigate(`/ontology/explorer/${item.objectTypeId}/${encodeURIComponent(item.objectId)}`)} type="link">打开</Button>]}><List.Item.Meta title={item.title} description={`${item.objectTypeApiName} · ${item.objectId}`} /></List.Item>} /> : <Empty description="暂无最近对象" image={Empty.PRESENTED_IMAGE_SIMPLE} />}</Card><Card title="已保存探索">{home?.explorations.length ? <List dataSource={home.explorations} renderItem={(item) => <List.Item onClick={() => navigate(`/ontology/explorer/explorations/${item.id}`)}><List.Item.Meta title={item.name} description={`${item.objectTypeName} · ${item.visibility}`} /></List.Item>} /> : <Empty description="保存查询后会显示在这里" image={Empty.PRESENTED_IMAGE_SIMPLE} />}</Card></div>
  </div>;
}
