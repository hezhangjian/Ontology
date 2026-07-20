import { Button, Space } from 'antd';
const widgets = [['METRIC','指标卡'],['BAR','柱状图'],['PIE','饼图'],['OBJECT_TABLE','对象表格'],['MARKDOWN','富文本'],['SECTION','分节标题']];
export default function WidgetPalette({ disabled, onAdd }: { disabled: boolean; onAdd: (type: string) => void }) { return <div><div className="editor-section-title">组件库</div><Space direction="vertical" style={{width:'100%'}}>{widgets.map(([type,label]) => <Button disabled={disabled && !['MARKDOWN','SECTION'].includes(type)} key={type} onClick={() => onAdd(type)}>{label}</Button>)}</Space></div>; }
