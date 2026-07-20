import { Button, Empty, List, Skeleton, Space, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { ExplorerApi } from './explorer.service';
import type { ExplorationView, ObjectListView } from './explorer.types';

export default function SavedResourcesPage({ accessToken, kind, navigate }: { accessToken: string; kind: 'explorations' | 'lists'; navigate: (path: string) => void }) {
  const api = useMemo(() => new ExplorerApi(accessToken), [accessToken]); const [items, setItems] = useState<Array<ExplorationView | ObjectListView>>();
  useEffect(() => { void (kind === 'explorations' ? api.explorations() : api.lists()).then(setItems); }, [api, kind]);
  if (!items) return <Skeleton active />;
  return <div className="explorer-page"><Typography.Title level={2}>{kind === 'explorations' ? '已保存探索' : '对象清单'}</Typography.Title><Typography.Paragraph>{kind === 'explorations' ? '动态 Exploration 每次打开查询当前数据。' : '静态清单只保存对象引用，不保存对象正文，也不会随查询自动变化。'}</Typography.Paragraph>{items.length ? <List dataSource={items} renderItem={(item) => <List.Item actions={[<Button key="open" onClick={() => navigate(kind === 'explorations' ? `/ontology/explorer/explorations/${item.id}` : `/ontology/explorer/${item.objectTypeId}`)}>打开</Button>]}><List.Item.Meta title={<Space><strong>{item.name}</strong><Tag>{item.visibility}</Tag></Space>} description={`${item.objectTypeName} · ${'itemCount' in item ? `${item.itemCount} 个引用` : `v${item.version}`} · ${item.ownerName}`} /></List.Item>} /> : <Empty description="暂无保存内容" />}</div>;
}
