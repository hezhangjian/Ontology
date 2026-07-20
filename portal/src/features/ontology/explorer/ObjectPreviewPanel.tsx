import { Drawer, Button, Descriptions, Space, Tabs, Tag, Typography } from 'antd';
import type { ObjectSummary } from './explorer.types';

export default function ObjectPreviewPanel({ item, onClose, openFull }: { item?: ObjectSummary; onClose: () => void; openFull: (item: ObjectSummary) => void }) {
  return <Drawer open={Boolean(item)} onClose={onClose} title={item?.title} width={520} extra={item && <Button onClick={() => openFull(item)}>打开 Full View</Button>}><Space><Tag>{item?.objectTypeApiName}</Tag><Tag color="success">质量通过</Tag><Typography.Text code>{item?.objectId}</Typography.Text></Space><Tabs items={[{ key: 'overview', label: '概览', children: <Descriptions bordered column={1} items={Object.entries(item?.properties ?? {}).map(([key, value]) => ({ key, label: key, children: typeof value === 'object' ? JSON.stringify(value) : String(value) }))} /> }, { key: 'actions', label: 'Action', children: '可执行 Action 将在 Full View 中按权限加载。' }, { key: 'relations', label: '关系', children: '打开 Full View 查看权威 HugeGraph 关系。' }]} /></Drawer>;
}
