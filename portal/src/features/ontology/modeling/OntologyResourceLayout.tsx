import { ApartmentOutlined, AppstoreOutlined, CheckSquareOutlined, DashboardOutlined, FieldStringOutlined, FunctionOutlined, LinkOutlined, SettingOutlined } from '@ant-design/icons';
import { Menu } from 'antd';
import type { ReactNode } from 'react';

const items = [
  { key: '/ontology', icon: <DashboardOutlined />, label: '概览' },
  { key: '/ontology/object-types', icon: <AppstoreOutlined />, label: '对象类型' },
  { key: '/ontology/properties', icon: <FieldStringOutlined />, label: '属性' },
  { key: '/ontology/link-types', icon: <LinkOutlined />, label: '关系类型' },
  { key: 'advanced', icon: <ApartmentOutlined />, label: '高级能力', children: [
    { key: '/ontology/interfaces', icon: <ApartmentOutlined />, label: '接口' },
    { key: '/ontology/actions', icon: <CheckSquareOutlined />, label: '动作' },
    { key: '/ontology/functions', icon: <FunctionOutlined />, label: '函数' },
  ] },
  { key: '/ontology/settings', icon: <SettingOutlined />, label: '设置' },
];

export default function OntologyResourceLayout({ children, navigate, path }: { children: ReactNode; navigate: (path: string) => void; path: string }) {
  const selected = items.find((item) => item.key !== '/ontology' && path.startsWith(item.key))?.key ?? '/ontology';
  return <div className="ontology-workspace"><aside className="ontology-subnav"><div className="ontology-subnav-title">本体建模</div><Menu items={items} mode="inline" onClick={({ key }) => navigate(key)} selectedKeys={[selected]} /></aside><main className="ontology-main">{children}</main></div>;
}
