export type DataSourceType = 'S3_CSV' | 'MYSQL' | 'POSTGRESQL' | 'KAFKA' | 'EXTERNAL_PULSAR';
export type ConnectionStatus = 'UNTESTED' | 'TESTING' | 'HEALTHY' | 'HEALTHY_RESTRICTED' | 'ERROR' | 'DISABLED';

export interface CredentialInput {
  mode: 'MANAGED' | 'EXISTING' | 'FILE';
  name?: string;
  existingSecretRef?: string;
  fileRefs?: Record<string, string>;
  values?: Record<string, string>;
}

export interface CredentialSummary {
  id: string;
  name: string;
  provider: 'MANAGED' | 'FILE';
  credentialType: string;
  status: 'CONFIGURED' | 'EXPIRING' | 'REVOKED';
  referenceCount: number;
  createdAt: string;
  rotatedAt?: string;
}

export interface Diagnostic {
  stage: string;
  occurredAt: string;
  reason: string;
  requestId: string;
  suggestion: string;
  technicalDetail?: string;
}

export interface TestStage {
  stage: 'NETWORK' | 'TLS' | 'AUTHENTICATION' | 'METADATA' | 'DISCOVERY';
  status: 'PENDING' | 'RUNNING' | 'PASSED' | 'WARNING' | 'FAILED';
  message: string;
  durationMs: number;
}

export interface ConnectionTestResult {
  requestId: string;
  status: ConnectionStatus;
  stages: TestStage[];
  assetCount: number;
  configFingerprint: string;
  testToken?: string;
  expiresAt: string;
}

export interface DataSource {
  id: string;
  name: string;
  description?: string;
  type: DataSourceType;
  ownerId: string;
  ownerName: string;
  tags: string[];
  config: Record<string, unknown>;
  credential: CredentialSummary;
  status: ConnectionStatus;
  syncStatus: 'NO_TASKS' | 'IDLE' | 'RUNNING' | 'STREAMING' | 'PARTIAL_FAILURE' | 'ALL_FAILURE';
  assetCount: number;
  lastCheckedAt?: string;
  lastError?: Diagnostic;
  version: number;
  pipelineReferenceCount: number;
  activeRunCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface DataSourcePage {
  items: DataSource[];
  page: number;
  size: number;
  total: number;
  counts: Record<string, number>;
  filters: Record<string, unknown>;
}

export interface AssetField {
  id: string;
  name: string;
  inferredType: string;
  originalType?: string;
  nullable: boolean;
  sensitive: boolean;
  primaryKeyCandidate: boolean;
  sampleValue?: string;
}

export interface DataSourceAsset {
  id: string;
  name: string;
  fullPath: string;
  parentPath?: string;
  assetType: string;
  status: 'AVAILABLE' | 'NEW' | 'UNAVAILABLE';
  schemaStatus: 'UNKNOWN' | 'READY' | 'CHANGED' | 'ERROR';
  schemaHash?: string;
  schemaVersion: number;
  fieldCount: number;
  sizeBytes?: number;
  estimatedRows?: number;
  partitionCount?: number;
  permissionStatus: 'READABLE' | 'METADATA_ONLY' | 'DENIED';
  usedByPipeline: boolean;
  discoveredAt: string;
  fields: AssetField[];
}

export interface Overview {
  connection: DataSource;
  health: TestStage[];
  assetSummary: Record<string, number>;
  pipelineSummary: Record<string, number>;
  recentRuns: PipelineRun[];
  recentActivity: AuditEvent[];
  sectionErrors: Record<string, Diagnostic>;
}

export interface PipelineSummary {
  id: string;
  name: string;
  sourceAsset?: string;
  mode: string;
  status: string;
  ownerName: string;
  recentRunAt?: string;
}

export interface PipelineRun {
  id: string;
  pipelineName: string;
  sourceAsset?: string;
  triggerType: string;
  status: string;
  startedAt: string;
  durationMs?: number;
  readCount: number;
  writtenCount: number;
  rejectedCount: number;
  flinkJobId?: string;
}

export interface AuditEvent {
  id: string;
  action: string;
  actorName: string;
  occurredAt: string;
  summary: string;
}

export interface AssetPreview {
  columns: string[];
  rows: Record<string, unknown>[];
  truncated: boolean;
  maxBytes: number;
}

export interface Problem {
  title: string;
  detail: string;
  requestId: string;
}
