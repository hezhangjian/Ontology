import { ApartmentOutlined, AppstoreOutlined, AuditOutlined, CheckSquareOutlined, DashboardOutlined, FieldStringOutlined, FunctionOutlined, HistoryOutlined, LinkOutlined, SettingOutlined, WarningOutlined } from '@ant-design/icons';
import { Menu } from 'antd';
import type { ReactNode } from 'react';

const items = [
  { key: '/ontology', icon: <DashboardOutlined />, label: '概览' },
  { key: '/ontology/object-types', icon: <AppstoreOutlined />, label: '对象类型' },
  { key: '/ontology/properties', icon: <FieldStringOutlined />, label: '属性目录' },
  { key: '/ontology/link-types', icon: <LinkOutlined />, label: '关系类型' },
  { key: '/ontology/interfaces', icon: <ApartmentOutlined />, label: 'Interface' },
  { key: '/ontology/actions', icon: <CheckSquareOutlined />, label: 'Action' },
  { key: '/ontology/functions', icon: <FunctionOutlined />, label: 'Function' },
  { key: '/ontology/proposals', icon: <AuditOutlined />, label: '变更提议' },
  { key: '/ontology/health', icon: <WarningOutlined />, label: '健康问题' },
  { key: '/ontology/history', icon: <HistoryOutlined />, label: '变更历史' },
  { key: '/ontology/settings', icon: <SettingOutlined />, label: '设置' },
];

export default function OntologyResourceLayout({ children, navigate, path }: { children: ReactNode; navigate: (path: string) => void; path: string }) {
  const selected = items.find((item) => item.key !== '/ontology' && path.startsWith(item.key))?.key ?? '/ontology';
  return <div className="ontology-workspace"><aside className="ontology-subnav"><div className="ontology-subnav-title">共享本体</div><Menu items={items} mode="inline" onClick={({ key }) => navigate(key)} selectedKeys={[selected]} /></aside><main className="ontology-main">{children}</main></div>;
}
