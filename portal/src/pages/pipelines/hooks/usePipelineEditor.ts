import { create } from 'zustand';
import type { PipelineGraph } from '../types';

type BottomTab = 'preview' | 'schema' | 'validation' | 'logs';

interface EditorState {
  bottomTab: BottomTab;
  future: PipelineGraph[];
  panelOpen: boolean;
  past: PipelineGraph[];
  selectedNodeId?: string;
  setBottomTab: (tab: BottomTab) => void;
  setPanelOpen: (open: boolean) => void;
  setSelectedNode: (id?: string) => void;
  snapshot: (graph: PipelineGraph) => void;
  undo: (current: PipelineGraph) => PipelineGraph | undefined;
  redo: (current: PipelineGraph) => PipelineGraph | undefined;
  reset: () => void;
}

export const usePipelineEditor = create<EditorState>((set, get) => ({
  bottomTab: 'validation',
  future: [],
  panelOpen: true,
  past: [],
  redo: (current) => {
    const future = get().future;
    const next = future.at(-1);
    if (!next) return undefined;
    set({ future: future.slice(0, -1), past: [...get().past, structuredClone(current)] });
    return structuredClone(next);
  },
  reset: () => set({ bottomTab: 'validation', future: [], panelOpen: true, past: [], selectedNodeId: undefined }),
  selectedNodeId: undefined,
  setBottomTab: (bottomTab) => set({ bottomTab, panelOpen: true }),
  setPanelOpen: (panelOpen) => set({ panelOpen }),
  setSelectedNode: (selectedNodeId) => set({ selectedNodeId }),
  snapshot: (graph) => set((state) => ({ future: [], past: [...state.past.slice(-29), structuredClone(graph)] })),
  undo: (current) => {
    const past = get().past;
    const previous = past.at(-1);
    if (!previous) return undefined;
    set({ future: [...get().future, structuredClone(current)], past: past.slice(0, -1) });
    return structuredClone(previous);
  },
}));
