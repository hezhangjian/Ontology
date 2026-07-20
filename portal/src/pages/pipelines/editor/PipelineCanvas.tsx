import { Background, Controls, MiniMap, ReactFlow, addEdge, applyEdgeChanges, applyNodeChanges, type Connection, type Edge, type EdgeChange, type Node, type NodeChange } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useCallback } from 'react';
import type { NodeType, PipelineGraph } from '../types';

interface Props {
  graph: PipelineGraph;
  nodeTypes: NodeType[];
  onChange: (graph: PipelineGraph, snapshot?: boolean) => void;
  onSelect: (id?: string) => void;
}

export default function PipelineCanvas({ graph, nodeTypes, onChange, onSelect }: Props) {
  const nodes: Node[] = graph.nodes.map((node) => ({
    data: { label: <div className="pipeline-node-label"><strong>{node.name}</strong><small>{node.type}</small>{node.invalidReasons.length > 0 && <span>{node.invalidReasons.length} 个错误</span>}</div> },
    id: node.id,
    position: node.position,
    type: node.type === 'SOURCE' ? 'input' : node.type.startsWith('ONTOLOGY_') ? 'output' : 'default',
  }));
  const edges: Edge[] = graph.edges.map((edge) => ({ ...edge, animated: false }));

  const nodeChanges = useCallback((changes: NodeChange[]) => {
    const updated = applyNodeChanges(changes, nodes);
    const removed = new Set(changes.filter((change) => change.type === 'remove').map((change) => change.id));
    if (graph.nodes.some((node) => removed.has(node.id) && node.type === 'SOURCE')) return;
    onChange({
      ...graph,
      edges: graph.edges.filter((edge) => !removed.has(edge.source) && !removed.has(edge.target)),
      nodes: graph.nodes
        .filter((node) => !removed.has(node.id))
        .map((node) => ({ ...node, position: updated.find((item) => item.id === node.id)?.position ?? node.position })),
    }, removed.size > 0);
  }, [graph, nodes, onChange]);
  const edgeChanges = useCallback((changes: EdgeChange[]) => {
    const updated = applyEdgeChanges(changes, edges);
    onChange({ ...graph, edges: updated.map(({ id, source, target }) => ({ id, source, target })) }, true);
  }, [edges, graph, onChange]);
  const connect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target || connection.source === connection.target) return;
    const updated = addEdge({ ...connection, id: `edge-${crypto.randomUUID()}` }, edges);
    onChange({ ...graph, edges: updated.map(({ id, source, target }) => ({ id, source, target })) }, true);
  }, [edges, graph, onChange]);
  const drop = (event: React.DragEvent) => {
    event.preventDefault();
    const type = event.dataTransfer.getData('application/ontology-node');
    const definition = nodeTypes.find((item) => item.type === type);
    if (!definition) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const id = `${type.toLowerCase()}-${crypto.randomUUID().slice(0, 8)}`;
    onChange({ ...graph, nodes: [...graph.nodes, { config: {}, id, inputSchema: [], invalidReasons: [], name: definition.label, outputSchema: [], position: { x: event.clientX - bounds.left, y: event.clientY - bounds.top }, type }] }, true);
  };

  return <div className="pipeline-canvas" onDragOver={(event) => event.preventDefault()} onDrop={drop}>
    <ReactFlow edges={edges} fitView nodes={nodes} onConnect={connect} onEdgesChange={edgeChanges} onNodeClick={(_, node) => onSelect(node.id)} onNodesChange={nodeChanges} onPaneClick={() => onSelect(undefined)}>
      <Background color="#d7ddea" gap={20} /><Controls /><MiniMap pannable zoomable />
    </ReactFlow>
  </div>;
}
