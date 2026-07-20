import { Alert, Empty } from 'antd';
import type { DashboardBatchResult, DashboardDefinition } from '../types';
import WidgetFrame from './WidgetFrame';

export default function DashboardRuntime({ definition, pageId, result, onCrossFilter, onOpenObject }: { definition: DashboardDefinition; pageId: string; result?: DashboardBatchResult; onCrossFilter: (value: unknown) => void; onOpenObject: (typeId: string, objectId: string) => void }) {
  const widgets = definition.widgets.filter((item) => item.pageId === pageId).sort((a,b) => Number(a.layout.desktop?.y ?? 0) - Number(b.layout.desktop?.y ?? 0));
  if (!widgets.length) return <Empty description="这个页面还没有组件" />;
  return <>{result?.status === 'PARTIAL' && <Alert message="部分组件失败，其余结果仍可用" showIcon type="warning" />}<div className="dashboard-runtime-grid">{widgets.map((widget) => <div key={widget.id} style={{ gridColumn: `span ${Math.max(1, Math.min(24, widget.layout.desktop?.w ?? 12))}` }}><WidgetFrame onCrossFilter={onCrossFilter} onOpenObject={onOpenObject} result={result?.widgets.find((item) => item.widgetId === widget.id)} widget={widget} /></div>)}</div></>;
}
