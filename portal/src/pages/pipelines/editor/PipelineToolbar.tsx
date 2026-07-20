import { AimOutlined, CopyOutlined, DeleteOutlined, RedoOutlined, UndoOutlined } from '@ant-design/icons';
import { Button, Space, Tooltip } from 'antd';

export default function PipelineToolbar({ canRedo, canUndo, hasSelection, onAutoLayout, onDelete, onDuplicate, onRedo, onUndo }: {
  canRedo: boolean;
  canUndo: boolean;
  hasSelection: boolean;
  onAutoLayout: () => void;
  onDelete: () => void;
  onDuplicate: () => void;
  onRedo: () => void;
  onUndo: () => void;
}) {
  return <div className="pipeline-canvas-toolbar"><Space size={2}>
    <Tooltip title="撤销 Ctrl+Z"><Button aria-label="撤销" disabled={!canUndo} icon={<UndoOutlined />} onClick={onUndo} size="small" /></Tooltip>
    <Tooltip title="重做 Ctrl+Shift+Z"><Button aria-label="重做" disabled={!canRedo} icon={<RedoOutlined />} onClick={onRedo} size="small" /></Tooltip>
    <Tooltip title="复制节点"><Button aria-label="复制节点" disabled={!hasSelection} icon={<CopyOutlined />} onClick={onDuplicate} size="small" /></Tooltip>
    <Tooltip title="删除节点"><Button aria-label="删除节点" danger disabled={!hasSelection} icon={<DeleteOutlined />} onClick={onDelete} size="small" /></Tooltip>
    <Tooltip title="自动布局"><Button aria-label="自动布局" icon={<AimOutlined />} onClick={onAutoLayout} size="small" /></Tooltip>
  </Space></div>;
}
