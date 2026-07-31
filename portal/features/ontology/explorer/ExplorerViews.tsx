import { BarChartOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Table } from '@/shared/components/data';
import { Empty, Progress } from '@/shared/components/feedback';
import { Card, Space } from '@/shared/components/layout';
import { Typography } from '@/shared/components/typography';
import type { ObjectSetPage, ObjectSummary, PropertyDefinition } from './explorer.types';

export function ObjectTableView({ page, selected, setSelected, open }: { page: ObjectSetPage; selected: string[]; setSelected: (ids: string[]) => void; open: (item: ObjectSummary) => void }) {
  const properties = page.properties.slice(0, 6);
  return <Table dataSource={page.items} pagination={false} rowKey="objectId" rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys.map(String)) }} onRow={(row) => ({ onDoubleClick: () => open(row) })} columns={[{ title: '标题', fixed: 'left', render: (_, row) => <Button onClick={() => open(row)} type="link">{row.title}</Button> }, ...properties.map((property) => ({ title: property.displayName, render: (_: unknown, row: ObjectSummary) => format(row.properties[property.displayName]) })), { title: '质量', render: () => <Tag color="success">通过</Tag> }]} scroll={{ x: 900 }} />;
}

export function ObjectCardView({ page, open }: { page: ObjectSetPage; open: (item: ObjectSummary) => void }) {
  const properties = page.properties.filter((property) => !property.sensitive).slice(0, 6);
  return <div className="object-card-grid">{page.items.map((item) => <Card actions={[<Button key="open" onClick={() => open(item)} type="link">查看</Button>]} key={item.objectId} title={item.title}><Space direction="vertical">{properties.map((property) => <div key={property.id}><Typography.Text type="secondary">{property.displayName}</Typography.Text><br />{format(item.properties[property.displayName])}</div>)}<Tag color="success">质量通过</Tag></Space></Card>)}</div>;
}

export function QuickAnalysisView({ page, onFilter }: { page: ObjectSetPage; onFilter: (property: PropertyDefinition, value: unknown) => void }) {
  const candidates = page.properties.filter((item) => item.filterable).slice(0, 6);
  return candidates.length ? <div className="analysis-grid">{candidates.map((property) => { const counts = new Map<string, number>(); page.items.forEach((item) => { const value = String(item.properties[property.displayName] ?? '未设置'); counts.set(value, (counts.get(value) ?? 0) + 1); }); const buckets = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8); return <Card key={property.id} title={<Space><BarChartOutlined />{property.displayName}</Space>}>{buckets.map(([value, count]) => <button className="analysis-bucket" key={value} onClick={() => onFilter(property, value)}><span>{value}</span><Progress percent={Math.round(count / Math.max(page.items.length, 1) * 100)} showInfo={false} /><strong>{count}</strong></button>)}</Card>; })}</div> : <Empty description="没有可聚合字段" />;
}

function format(value: unknown) { if (value == null) return <Typography.Text type="secondary">—</Typography.Text>; if (typeof value === 'object') return <code>{JSON.stringify(value)}</code>; return String(value); }
