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
  sourceField?: string;
  enumValues?: string[];
}

export interface PropertyView extends PropertyDraft {
  id: string;
  physicalKey: string;
}

export interface OntologyResource {
  id: string;
  kind: ResourceKind;
  apiName: string;
  displayName: string;
  description: string;
  physicalKey: string;
  ownerId: string;
  ownerName: string;
  maturity: string;
  promoted: boolean;
  tags: string[];
  lifecycle: string;
  version: number;
  activeVersion?: number;
  publishedRevision?: number;
  etag: number;
  definition: Record<string, unknown>;
  properties: PropertyView[];
  createdAt: string;
  updatedAt: string;
}

export interface ModelingSummary {
  ontologyRevision: number;
  lastPublishedAt?: string;
  publishHealth: string;
  unpublishedProposals: number;
  criticalIssues: number;
  projectionFailures: number;
  pendingReviews: number;
  resourceCounts: Record<ResourceKind, number>;
  recentResources: OntologyResource[];
}

export interface ValidationIssue {
  code: string;
  severity: 'ERROR' | 'WARNING';
  resourceId: string;
  field: string;
  message: string;
  recoveryAction: string;
}

export interface Proposal {
  id: string;
  title: string;
  description: string;
  status: string;
  baselineRevision: number;
  riskLevel: string;
  validation: ValidationIssue[];
  impact: Record<string, unknown>;
  resources: OntologyResource[];
  createdByName: string;
  createdAt: string;
  updatedAt: string;
  submittedAt?: string;
  publishedRevision?: number;
}

export interface Deployment {
  id: string;
  proposalId: string;
  targetRevision: number;
  status: string;
  currentStep: string;
  attempt: number;
  safeError?: string;
  steps: Array<{ order: number; name: string; status: string; externalResource?: string; safeError?: string }>;
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
  ownerName?: string;
  status: string;
  firstSeenAt: string;
  lastSeenAt: string;
}

export interface HistoryEntry {
  revision: number;
  status: string;
  activatedAt?: string;
  createdAt: string;
  resourceCount: number;
  proposalId?: string;
  proposalTitle?: string;
}
