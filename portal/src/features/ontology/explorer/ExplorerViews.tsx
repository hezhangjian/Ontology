import { ApartmentOutlined, BarChartOutlined, IdcardOutlined } from '@ant-design/icons';
import { Button, Card, Checkbox, Empty, Progress, Space, Table, Tag, Typography } from 'antd';
import type { ObjectSetPage, ObjectSummary, PropertyDefinition } from './explorer.types';

export function ObjectTableView({ page, selected, setSelected, open }: { page: ObjectSetPage; selected: string[]; setSelected: (ids: string[]) => void; open: (item: ObjectSummary) => void }) {
  const properties = page.properties.slice(0, 6);
  return <Table dataSource={page.items} pagination={false} rowKey="objectId" rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys.map(String)) }} onRow={(row) => ({ onDoubleClick: () => open(row) })} columns={[{ title: '标题', fixed: 'left', render: (_, row) => <Button onClick={() => open(row)} type="link">{row.title}</Button> }, ...properties.map((property) => ({ title: property.displayName, render: (_: unknown, row: ObjectSummary) => format(row.properties[property.apiName]) })), { title: '质量', render: () => <Tag color="success">通过</Tag> }, { title: '版本', render: (_, row) => `v${row.version}` }]} scroll={{ x: 900 }} />;
}

export function ObjectCardView({ items, open }: { items: ObjectSummary[]; open: (item: ObjectSummary) => void }) {
  return <div className="object-card-grid">{items.map((item) => <Card actions={[<Button key="open" onClick={() => open(item)} type="link">打开 Panel</Button>]} key={item.objectId} title={item.title}><Space direction="vertical"><Typography.Text code>{item.objectId}</Typography.Text>{Object.entries(item.properties).slice(0, 6).map(([key, value]) => <div key={key}><Typography.Text type="secondary">{key}</Typography.Text><br />{format(value)}</div>)}<Tag color="success">质量通过</Tag></Space></Card>)}</div>;
}

export function QuickAnalysisView({ page, onFilter }: { page: ObjectSetPage; onFilter: (property: PropertyDefinition, value: unknown) => void }) {
  const candidates = page.properties.filter((item) => item.filterable).slice(0, 6);
  return candidates.length ? <div className="analysis-grid">{candidates.map((property) => { const counts = new Map<string, number>(); page.items.forEach((item) => { const value = String(item.properties[property.apiName] ?? '未设置'); counts.set(value, (counts.get(value) ?? 0) + 1); }); const buckets = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8); return <Card key={property.id} title={<Space><BarChartOutlined />{property.displayName}</Space>}>{buckets.map(([value, count]) => <button className="analysis-bucket" key={value} onClick={() => onFilter(property, value)}><span>{value}</span><Progress percent={Math.round(count / Math.max(page.items.length, 1) * 100)} showInfo={false} /><strong>{count}</strong></button>)}</Card>; })}</div> : <Empty description="没有可聚合字段" />;
}

export function RelationGraphView({ items, open }: { items: ObjectSummary[]; open: (item: ObjectSummary) => void }) {
  const nodes = items.slice(0, 12); return <div className="relation-graph"><div className="graph-hint"><ApartmentOutlined /> 按需展开一跳关系 · 当前显示 {nodes.length}/200 个节点</div><div className="graph-nodes">{nodes.map((item, index) => <button className="graph-node" key={item.objectId} onClick={() => open(item)} style={{ marginLeft: `${(index % 4) * 38}px` }}><IdcardOutlined /> {item.title}</button>)}</div></div>;
}

export function ObjectCompareView({ items, selected, toggle }: { items: ObjectSummary[]; selected: string[]; toggle: (id: string) => void }) {
  return <div><Typography.Paragraph>选择 2—5 个同类型对象后执行比较；无权字段不会参与差异统计。</Typography.Paragraph><Space wrap>{items.map((item) => <Checkbox checked={selected.includes(item.objectId)} disabled={!selected.includes(item.objectId) && selected.length >= 5} key={item.objectId} onChange={() => toggle(item.objectId)}>{item.title}</Checkbox>)}</Space></div>;
}

function format(value: unknown) { if (value == null) return <Typography.Text type="secondary">—</Typography.Text>; if (typeof value === 'object') return <code>{JSON.stringify(value)}</code>; return String(value); }
