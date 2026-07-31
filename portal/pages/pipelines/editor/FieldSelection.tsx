import { Button } from '@/shared/components/actions';
import { Checkbox, Input } from '@/shared/components/forms';
import { Typography } from '@/shared/components/typography';
import { SearchOutlined } from '@/shared/icons';
import { useMemo, useState } from 'react';
import type { FieldSchema } from '../types';

const { Text } = Typography;

interface Props {
  fields: FieldSchema[];
  label?: string;
  maxHeight?: number;
  onChange: (fields: string[]) => void;
  selected: string[];
}

export default function FieldSelection({ fields, label = '字段', maxHeight = 280, onChange, selected }: Props) {
  const [query, setQuery] = useState('');
  const selectedSet = useMemo(() => new Set(selected), [selected]);
  const visible = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return normalized
      ? fields.filter((field) => `${field.name} ${field.type}`.toLowerCase().includes(normalized))
      : fields;
  }, [fields, query]);
  const visibleNames = visible.map((field) => field.name);
  const allVisibleSelected = visibleNames.length > 0 && visibleNames.every((field) => selectedSet.has(field));

  const toggle = (field: string) => {
    onChange(selectedSet.has(field)
      ? selected.filter((item) => item !== field)
      : [...selected, field]);
  };
  const toggleVisible = () => {
    if (allVisibleSelected) {
      const visibleSet = new Set(visibleNames);
      onChange(selected.filter((field) => !visibleSet.has(field)));
      return;
    }
    onChange(Array.from(new Set([...selected, ...visibleNames])));
  };

  return <section className="field-selection">
    <header className="field-selection-header">
      <div>
        <strong>{label}</strong>
        <Text type="secondary">已选 {selected.length} / {fields.length}</Text>
      </div>
      <div className="field-selection-actions">
        <Button disabled={visible.length === 0} onClick={toggleVisible} type="text">{allVisibleSelected ? '取消当前' : '选择当前'}</Button>
        <Button disabled={selected.length === 0} onClick={() => onChange([])} type="text">清空</Button>
      </div>
    </header>
    {fields.length > 5 && <Input
      onChange={(event) => setQuery(event.target.value)}
      placeholder="搜索字段名或类型"
      prefix={<SearchOutlined />}
      value={query}
    />}
    <div className="field-selection-list" style={{ maxHeight }}>
      {visible.map((field) => <div className="field-selection-row" key={field.name}>
        <Checkbox
          aria-label={`选择字段 ${field.name}`}
          checked={selectedSet.has(field.name)}
          onChange={() => toggle(field.name)}
        >
          <span className="field-selection-name">
            <strong>{field.name}</strong>
            <small>{field.type}{field.nullable ? ' · 可空' : ' · 必填'}{field.sensitive ? ' · 敏感' : ''}</small>
          </span>
        </Checkbox>
      </div>)}
      {visible.length === 0 && <div className="field-selection-empty">没有匹配字段</div>}
    </div>
  </section>;
}
