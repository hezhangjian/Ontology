export type ResourceKind = 'OBJECT_TYPE' | 'LINK_TYPE' | 'INTERFACE' | 'ACTION' | 'FUNCTION';

export interface PropertyDraft {
  apiName: string;
  displayName: string;
  description?: string;
  valueType: string;
  required: boolean;
  primaryKey: boolean;
  titleProperty: boolean;
  searchable: boolean;
  filterable: boolean;
  sortable: boolean;
  sensitive: boolean;
  actionWritable: boolean;
  sourceField?: string;
  enumValues?: string[];
}

export interface PropertyView extends PropertyDraft {
  id: string;
  physicalKey: string;
}

export interface OntologyResource {
  id: string;
  resourceId: string;
  kind: ResourceKind;
  displayName: string;
  description: string;
  physicalKey: string;
  maturity: string;
  promoted: boolean;
  tags: string[];
  lifecycle: string;
  definition: Record<string, unknown>;
  properties: PropertyView[];
  createdAt: string;
  updatedAt: string;
}

export interface ObjectTypeBackingView {
  sourceMode: 'ACTION' | 'PIPELINE';
  pipelineId?: string;
  pipelineName?: string;
  pipelineVersion?: number;
  pipelineLifecycle?: string;
  mappings: Array<{
    propertyId: string;
    propertyApiName: string;
    propertyDisplayName: string;
    sourceField: string;
    sinkNodeId: string;
    transformPath: string[];
  }>;
  lastRunStatus?: string;
  projectionStatus?: string;
  lastRunAt?: string;
  mappedPropertyCount: number;
  propertyCount: number;
  status: string;
}

export interface ModelingSummary {
  health: string;
  criticalIssues: number;
  projectionFailures: number;
  resourceCounts: Record<ResourceKind, number>;
  objectInstanceCounts: Record<string, number>;
  relationInstanceCounts: Record<string, number>;
  recentResources: OntologyResource[];
}

export interface HealthIssue {
  id: string;
  severity: string;
  category: string;
  resourceId?: string;
  resourceName?: string;
  title: string;
  evidence: string;
  recommendation: string;
  status: string;
  firstSeenAt: string;
  lastSeenAt: string;
}

export interface ActionExecution {
  id: string;
  actionTypeId: string;
  previewId: string;
  status: 'SUBMITTED' | 'PROJECTING' | 'SUCCEEDED' | 'DEGRADED' | 'FAILED';
  submittedAt: string;
  completedAt?: string;
  correlationId: string;
  traceId: string;
  safeError?: string;
}
