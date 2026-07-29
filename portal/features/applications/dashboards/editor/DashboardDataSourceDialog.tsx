import { Button } from '@/shared/components/actions';
import { Select } from '@/shared/components/forms';
import type { Dataset } from '../../../../pages/datasets/types';
import type { ObjectTypeDefinition } from '../../../ontology/explorer/explorer.types';
import { useEffect } from 'react';
import { createPortal } from 'react-dom';

type Props = {
  datasets: Dataset[];
  kind: 'DATASET' | 'OBJECT_SET';
  onCancel: () => void;
  onConfirm: () => void;
  onKindChange: (kind: 'DATASET' | 'OBJECT_SET') => void;
  onSourceChange: (id: string) => void;
  open: boolean;
  sourceId?: string;
  types: ObjectTypeDefinition[];
};

export default function DashboardDataSourceDialog({
  datasets,
  kind,
  onCancel,
  onConfirm,
  onKindChange,
  onSourceChange,
  open,
  sourceId,
  types,
}: Props) {
  useEffect(() => {
    if (!open) return;
    const previousOverflow = document.body.style.overflow;
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel();
    };
    document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', escape);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', escape);
    };
  }, [onCancel, open]);

  if (!open) return null;
  const options = kind === 'DATASET'
    ? datasets.map((item) => ({ value: item.id, label: `${item.name} · ${item.rowCount.toLocaleString()} 行` }))
    : types.map((item) => ({ value: item.id, label: item.displayName }));
  return createPortal(
    <div className="dashboard-source-dialog-backdrop" onMouseDown={(event) => {
      if (event.currentTarget === event.target) onCancel();
    }}>
      <section aria-labelledby="dashboard-source-dialog-title" aria-modal="true" className="dashboard-source-dialog" role="dialog">
        <header><h2 id="dashboard-source-dialog-title">添加看板数据源</h2></header>
        <div className="dashboard-source-dialog-body">
          <label><span>数据类型</span><Select onChange={(value) => onKindChange(value as 'DATASET' | 'OBJECT_SET')} options={[{value:'DATASET',label:'Dataset'},{value:'OBJECT_SET',label:'业务对象'}]} value={kind} /></label>
          <label><span>数据源</span><Select onChange={onSourceChange} options={options} placeholder={kind === 'DATASET' ? '选择 Dataset' : '选择业务对象'} value={sourceId} /></label>
        </div>
        <footer><Button onClick={onCancel}>取消</Button><Button disabled={!sourceId} onClick={onConfirm} type="primary">添加</Button></footer>
      </section>
    </div>,
    document.body,
  );
}
