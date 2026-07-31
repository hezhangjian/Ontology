import { Background, Controls, Handle, MiniMap, Position, ReactFlow, addEdge, applyEdgeChanges, applyNodeChanges, type Connection, type Edge, type EdgeChange, type Node, type NodeChange, type NodeProps } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { randomUuid } from '@/shared/utils/randomUuid';
import { useCallback } from 'react';
import type { NodeType, PipelineGraph } from '../types';

interface Props {
  graph: PipelineGraph;
  nodeTypes: NodeType[];
  onChange: (graph: PipelineGraph, snapshot?: boolean) => void;
  onSelect: (id?: string) => void;
}

type PipelineFlowNode = Node<{
  canReceive: boolean;
  canSend: boolean;
  invalidCount: number;
  label: string;
  type: string;
}, 'pipeline'>;

function PipelineFlowNodeComponent({ data, isConnectable }: NodeProps<PipelineFlowNode>) {
  return <div className="pipeline-flow-node">
    {data.canReceive && <Handle aria-label="输入端口" position={Position.Left} type="target" isConnectable={isConnectable} />}
    <div className="pipeline-node-label">
      <strong>{data.label}</strong>
      <small>{data.type}</small>
      {data.invalidCount > 0 && <span>{data.invalidCount} 个错误</span>}
    </div>
    {data.canSend && <Handle aria-label="输出端口" position={Position.Right} type="source" isConnectable={isConnectable} />}
  </div>;
}

const flowNodeTypes = { pipeline: PipelineFlowNodeComponent };

export default function PipelineCanvas({ graph, nodeTypes, onChange, onSelect }: Props) {
  const nodes: PipelineFlowNode[] = graph.nodes.map((node) => {
    const definition = nodeTypes.find((item) => item.type === node.type);
    return {
    data: {
      canReceive: !definition?.source,
      canSend: !definition?.output,
      invalidCount: node.invalidReasons.length,
      label: node.name,
      type: node.type,
    },
    id: node.id,
    initialHeight: 48,
    initialWidth: 160,
    position: node.position,
    type: 'pipeline',
  };
  });
  const edges: Edge[] = graph.edges.map((edge) => ({ ...edge, animated: false }));

  const nodeChanges = useCallback((changes: NodeChange[]) => {
    const graphChanges = changes.filter((change) => change.type === 'position' || change.type === 'remove');
    if (graphChanges.length === 0) return;
    const updated = applyNodeChanges(graphChanges, nodes);
    const removed = new Set(graphChanges.filter((change) => change.type === 'remove').map((change) => change.id));
    const remainingSources = graph.nodes.filter((node) => node.type === 'SOURCE' && !removed.has(node.id)).length;
    if (remainingSources === 0) return;
    const nextNodes = graph.nodes
      .filter((node) => !removed.has(node.id))
      .map((node) => ({ ...node, position: updated.find((item) => item.id === node.id)?.position ?? node.position }));
    const moved = nextNodes.some((node) => {
      const current = graph.nodes.find((item) => item.id === node.id);
      return current && (current.position.x !== node.position.x || current.position.y !== node.position.y);
    });
    if (!moved && removed.size === 0) return;
    onChange({
      ...graph,
      edges: graph.edges.filter((edge) => !removed.has(edge.source) && !removed.has(edge.target)),
      nodes: nextNodes,
    }, removed.size > 0);
  }, [graph, nodes, onChange]);
  const edgeChanges = useCallback((changes: EdgeChange[]) => {
    const updated = applyEdgeChanges(changes, edges);
    onChange({ ...graph, edges: updated.map(({ id, source, target }) => ({ id, source, target })) }, true);
  }, [edges, graph, onChange]);
  const connect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target || connection.source === connection.target) return;
    const updated = addEdge({ ...connection, id: `edge-${randomUuid()}` }, edges);
    onChange({ ...graph, edges: updated.map(({ id, source, target }) => ({ id, source, target })) }, true);
  }, [edges, graph, onChange]);
  const drop = (event: React.DragEvent) => {
    event.preventDefault();
    const type = event.dataTransfer.getData('application/ontology-node');
    const definition = nodeTypes.find((item) => item.type === type);
    if (!definition) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const id = `${type.toLowerCase()}-${randomUuid().slice(0, 8)}`;
    onChange({ ...graph, nodes: [...graph.nodes, { config: {}, id, inputSchema: [], invalidReasons: [], name: definition.label, outputSchema: [], position: { x: event.clientX - bounds.left, y: event.clientY - bounds.top }, type }] }, true);
  };

  return <div className="pipeline-canvas" onDragOver={(event) => event.preventDefault()} onDrop={drop}>
    <ReactFlow edges={edges} fitView nodeTypes={flowNodeTypes} nodes={nodes} onConnect={connect} onEdgesChange={edgeChanges} onNodeClick={(_, node) => onSelect(node.id)} onNodesChange={nodeChanges} onPaneClick={() => onSelect(undefined)}>
      <Background color="#d7ddea" gap={20} /><Controls /><MiniMap style={{ pointerEvents: 'none' }} />
    </ReactFlow>
  </div>;
}
