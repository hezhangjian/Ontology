import { Button, Tag } from '@/shared/components/actions';
import { Descriptions } from '@/shared/components/data';
import { Space } from '@/shared/components/layout';
import { Drawer } from '@/shared/components/overlays';
import type { ObjectSummary, PropertyDefinition } from './explorer.types';

export default function ObjectPreviewPanel({ item, onClose, openFull, properties }: { item?: ObjectSummary; onClose: () => void; openFull: (item: ObjectSummary) => void; properties: PropertyDefinition[] }) {
  const visibleProperties = properties.filter((property) => !property.sensitive);
  return <Drawer open={Boolean(item)} onClose={onClose} title={item?.title} width={520} extra={item && <Button onClick={() => openFull(item)} type="primary">打开完整视图</Button>}>
    <Space><Tag color="success">质量通过</Tag></Space>
    <Descriptions bordered column={1} items={visibleProperties.map((property) => ({ key: property.id, label: property.displayName, children: format(item?.properties[property.displayName]) }))} />
  </Drawer>;
}

function format(value: unknown) {
  if (value == null) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
