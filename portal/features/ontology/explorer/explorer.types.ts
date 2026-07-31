export interface PropertyDefinition { id: string; apiName: string; displayName: string; valueType: string; primaryKey: boolean; titleProperty: boolean; searchable: boolean; filterable: boolean; sortable: boolean; sensitive: boolean }
export interface ObjectTypeDefinition { id: string; apiName: string; displayName: string; maturity: string; properties: PropertyDefinition[] }
export interface ObjectSummary { objectId: string; title: string; objectTypeApiName: string; objectTypeId: string; properties: Record<string, unknown>; redactedFields: string[]; quality: string; updatedAt: string }
export interface ObjectSetRequest { objectTypeId: string; where: Record<string, unknown>; sort: Array<{ propertyId: string; direction: string }>; pageSize: number; cursor?: string; columns: string[] }
export interface ObjectSetPage { objectTypeId: string; objectTypeName: string; visibleCount: number; countLowerBound: boolean; items: ObjectSummary[]; nextCursor?: string; queryFingerprint: string; indexUpdatedAt: string; properties: PropertyDefinition[] }
export interface ExplorerHome { objectTypes: ObjectTypeDefinition[]; objectCounts: Record<string, number>; searchStatus: string; indexUpdatedAt: string }
export interface SearchResponse { objects: ObjectSummary[]; objectTypes: ObjectTypeDefinition[]; visibleObjectCount: number; indexUpdatedAt: string }
export interface ObjectDetail { objectId: string; title: string; objectType: ObjectTypeDefinition; properties: Record<string, unknown>; redactedFields: string[]; quality: string; updatedAt: string }
export interface ObjectLink { relationId: string; linkTypeId: string; linkTypeName: string; direction: string; targetObjectId: string; targetObjectTypeId: string; targetTitle: string; edgeProperties: Record<string, unknown> }
export interface Capability { id: string; kind: string; displayName: string; apiName: string; executable: boolean; previewRequired: boolean }
export interface CapabilityResponse { actions: Capability[]; functions: Capability[]; openTo: string[] }
