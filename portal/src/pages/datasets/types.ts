export interface DatasetField { name: string; type: string; nullable: boolean; samples: unknown[] }
export interface Dataset { id: string; name: string; description: string; pipelineId: string; pipelineName: string; fields: DatasetField[]; rowCount: number; status: 'BUILDING' | 'READY' | 'FAILED'; ownerName: string; createdAt: string; updatedAt: string }
export interface DatasetPage { items: Dataset[]; total: number }
export interface DatasetPreview { columns: string[]; rows: Record<string, unknown>[]; total: number }
export interface DatasetMetric { operation: 'SUM' | 'AVG' | 'MIN' | 'MAX' | 'COUNT' | 'DISTINCT_COUNT' | 'SUM_PER_DISTINCT'; field?: string; distinctField?: string; label: string }
export interface DatasetDimension { field: string; label: string; timeGrain?: 'NONE' | 'DAY' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR' }
export interface DatasetFilter { field: string; operator: 'EQUALS' | 'FIELD_EQUALS' | 'IN' | 'NOT_IN'; values: string[]; comparisonField?: string }
export interface DatasetQueryResult { dimensions: string[]; metrics: string[]; rows: Record<string, unknown>[]; scannedRows: number }
export interface MappingPreview { identityField: string; titleField: string; sourceRows: number; objectCount: number; emptyIdentityCount: number; duplicateCount: number; conflictCount: number; samples: Record<string, unknown>[] }
