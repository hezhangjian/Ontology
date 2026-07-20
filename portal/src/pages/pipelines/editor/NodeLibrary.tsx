import { Input, Tooltip, Typography } from 'antd';
import { useMemo, useState } from 'react';
import type { NodeType, PipelineMode } from '../types';

const { Text } = Typography;

export default function NodeLibrary({ mode, nodeTypes, onAdd }: { mode: PipelineMode; nodeTypes: NodeType[]; onAdd: (type: NodeType) => void }) {
  const [search, setSearch] = useState('');
  const grouped = useMemo(() => nodeTypes.filter((node) => node.modes.includes(mode) && node.type !== 'SOURCE')
    .filter((node) => `${node.label}${node.description}`.toLowerCase().includes(search.toLowerCase()))
    .reduce<Record<string, NodeType[]>>((result, node) => ({ ...result, [node.category]: [...(result[node.category] ?? []), node] }), {}), [mode, nodeTypes, search]);
  return (
    <aside className="pipeline-node-library" aria-label="节点库">
      <div className="editor-panel-title">节点库</div>
      <Input allowClear onChange={(event) => setSearch(event.target.value)} placeholder="搜索节点" size="small" value={search} />
      {Object.entries(grouped).sort(([left], [right]) => left.localeCompare(right)).map(([category, nodes]) => (
        <section key={category}>
          <Text type="secondary">{category}</Text>
          {nodes.map((node) => <Tooltip key={node.type} placement="right" title={node.description}>
            <button className={`library-node library-node-${node.category}`} draggable onClick={() => onAdd(node)}
              onDragStart={(event) => event.dataTransfer.setData('application/ontology-node', node.type)} type="button">
              <span>{node.label}</span><small>{node.type}</small>
            </button>
          </Tooltip>)}
        </section>
      ))}
    </aside>
  );
}
