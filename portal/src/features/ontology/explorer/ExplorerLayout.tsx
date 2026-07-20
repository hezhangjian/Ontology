import { ClockCircleOutlined, CompassOutlined, FolderOpenOutlined, SearchOutlined, StarOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Menu } from 'antd';
import type { ReactNode } from 'react';

const items = [
  { key: '/ontology/explorer', icon: <CompassOutlined />, label: '探索首页' },
  { key: '/ontology/explorer/search', icon: <SearchOutlined />, label: '搜索' },
  { key: '/ontology/explorer?section=recent', icon: <ClockCircleOutlined />, label: '最近' },
  { key: '/ontology/explorer?section=favorites', icon: <StarOutlined />, label: '收藏' },
  { key: '/ontology/explorer?section=explorations', icon: <FolderOpenOutlined />, label: '已保存探索' },
  { key: '/ontology/explorer?section=lists', icon: <UnorderedListOutlined />, label: '对象清单' },
];

export default function ExplorerLayout({ children, navigate, path }: { children: ReactNode; navigate: (path: string) => void; path: string }) {
  const selected = path.startsWith('/ontology/explorer/search') ? '/ontology/explorer/search' : '/ontology/explorer';
  return <div className="explorer-workspace"><aside className="explorer-subnav"><div className="ontology-subnav-title">对象探索</div><Menu items={items} mode="inline" onClick={({ key }) => navigate(key)} selectedKeys={[selected]} /></aside><main className="explorer-main">{children}</main></div>;
}
