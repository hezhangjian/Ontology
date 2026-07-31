export type PipelineMode = 'BATCH' | 'STREAMING';
export type PipelineLifecycle = 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED' | 'PAUSED' | 'ARCHIVED';
export type PipelineRunStatus = 'NEVER_RUN' | 'HEALTHY' | 'RUNNING' | 'LIVE' | 'DEGRADED' | 'FAILED';

export interface FieldSchema {
  name: string;
  type: string;
  nullable: boolean;
  sensitive: boolean;
  sourceNodeId: string;
}

export interface PipelineNode {
  id: string;
  type: string;
  name: string;
  position: { x: number; y: number };
  config: Record<string, unknown>;
  inputSchema: FieldSchema[];
  outputSchema: FieldSchema[];
  invalidReasons: string[];
}

export interface PipelineEdge {
  id: string;
  source: string;
  target: string;
}

export interface PipelineGraph {
  nodes: PipelineNode[];
  edges: PipelineEdge[];
}

export interface RuntimeSettings {
  parallelism: number;
  checkpointIntervalMs: number;
  restartAttempts: number;
  offsetPolicy: string;
  eventTimeField?: string;
  watermarkDelayMs: number;
}

export interface ScheduleSettings {
  type: 'MANUAL' | 'CRON' | 'AT';
  cronExpression?: string;
  runAt?: string;
  concurrencyPolicy: 'SKIP' | 'QUEUE' | 'CANCEL_PREVIOUS';
  enabled: boolean;
}

export interface PipelineDraft {
  graph: PipelineGraph;
  runtime: RuntimeSettings;
  schedule: ScheduleSettings;
  updatedBy: string;
  updatedAt: string;
}

export interface Pipeline {
  id: string;
  name: string;
  description?: string;
  template: string;
  mode: PipelineMode;
  lifecycle: PipelineLifecycle;
  runStatus: PipelineRunStatus;
  dataSourceId: string;
  dataSourceName: string;
  sourceAssetId: string;
  sourceAssetName: string;
  targetSummary: string;
  scheduleSummary: string;
  ownerId: string;
  ownerName: string;
  lastRunAt?: string;
  createdAt: string;
  updatedAt: string;
  draft?: PipelineDraft;
}

export interface PipelinePage {
  items: Pipeline[];
  page: number;
  size: number;
  total: number;
  counts: Record<string, number>;
  appliedFilters: Record<string, string>;
}

export interface ValidationIssue {
  id: string;
  category: 'GRAPH' | 'SCHEMA' | 'SOURCE' | 'OUTPUT' | 'SEMANTICS' | 'RUNTIME' | 'SECURITY';
  severity: 'ERROR' | 'WARNING' | 'INFO';
  nodeId?: string;
  title: string;
  detail: string;
  recoveryAction: string;
}

export interface ValidationResult {
  valid: boolean;
  issues: ValidationIssue[];
  normalizedGraph: PipelineGraph;
  impact: Record<string, unknown>;
  contentHash: string;
}

export interface NodeType {
  type: string;
  label: string;
  category: string;
  modes: PipelineMode[];
  minInputs: number;
  maxInputs: number;
  source: boolean;
  output: boolean;
  description: string;
}

export interface PreviewRun {
  id: string;
  pipelineId: string;
  draftEtag: number;
  nodeId: string;
  status: 'SUBMITTED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED';
  flinkJobId?: string;
  rows: Record<string, unknown>[];
  schema: FieldSchema[];
  diagnostic?: Record<string, unknown>;
  startedAt: string;
  completedAt?: string;
  expiresAt: string;
}

export interface PipelineRun {
  id: string;
  pipelineId: string;
  pipelineName: string;
  retryOf?: string;
  triggerType: string;
  status: string;
  flinkJobId?: string;
  correlationId: string;
  readCount: number;
  writtenCount: number;
  rejectedCount: number;
  projectionStatus?: string;
  savepointPath?: string;
  diagnostic?: Record<string, unknown>;
  requestedByName: string;
  startedAt: string;
  completedAt?: string;
  updatedAt: string;
}

export interface PipelineRunDetail {
  run: PipelineRun;
  stages: Array<Record<string, unknown>>;
  events: Array<{ sequence: number; eventType: string; status?: string; message: string; occurredAt: string }>;
  metrics: Record<string, unknown>;
  logs: Array<Record<string, unknown>>;
}
