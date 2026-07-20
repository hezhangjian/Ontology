import { Button, Input, Space, Tag } from 'antd';
import { useState } from 'react';
import type { DashboardFilter } from '../types';

export default function DashboardFilterBar({ filters, onApply, values }: { filters: DashboardFilter[]; onApply: (values: Record<string, unknown>) => void; values: Record<string, unknown> }) {
  const [pending, setPending] = useState<Record<string, unknown>>(values);
  const manual = filters.some((item) => item.applyMode !== 'AUTO');
  const update = (id: string, value: string) => { const next = { ...pending, [id]: value || undefined }; setPending(next); if (!manual) onApply(next); };
  return <div className="dashboard-filter-bar"><Space wrap>{filters.map((filter) => <Input aria-label={filter.name} key={filter.id} onChange={(event) => update(filter.id, event.target.value)} placeholder={filter.name} value={String(pending[filter.id] ?? '')} />)}{manual && <Button onClick={() => onApply(pending)} type="primary">应用筛选</Button>}<Button onClick={() => { setPending({}); onApply({}); }}>恢复默认</Button>{Object.entries(values).filter(([,value]) => value != null && value !== '').map(([id,value]) => <Tag closable key={id} onClose={() => update(id, '')}>{filters.find((item) => item.id === id)?.name ?? '交叉筛选'}：{String(value)}</Tag>)}</Space></div>;
}
