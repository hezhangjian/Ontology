import { Button, Input, List, Space } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { DashboardPage } from '../types';

export default function PageNavigator({ active, onAdd, onChange, onSelect, pages }: { active: string; onAdd: () => void; onChange: (pages: DashboardPage[]) => void; onSelect: (id: string) => void; pages: DashboardPage[] }) {
  const moveUp = (page: DashboardPage) => onChange(pages.map((item) => item.id === page.id ? { ...item, order: item.order - 1 } : item.order === page.order - 1 ? { ...item, order: item.order + 1 } : item));
  return <div><div className="editor-section-title">页面 <Button aria-label="添加页面" icon={<PlusOutlined />} onClick={onAdd} size="small" type="text" /></div><List dataSource={[...pages].sort((a,b) => a.order-b.order)} renderItem={(page, index) => <List.Item className={page.id === active ? 'active' : ''} onClick={() => onSelect(page.id)}><Space direction="vertical" size={2}><Input bordered={false} onChange={(event) => onChange(pages.map((item) => item.id === page.id ? { ...item, name: event.target.value } : item))} value={page.name} /><Space><Button disabled={index === 0} onClick={(event) => { event.stopPropagation(); moveUp(page); }} size="small">上移</Button><Button danger disabled={pages.length === 1} onClick={(event) => { event.stopPropagation(); onChange(pages.filter((item) => item.id !== page.id).map((item, order) => ({ ...item, order }))); }} size="small">删除</Button></Space></Space></List.Item>} /></div>;
}
