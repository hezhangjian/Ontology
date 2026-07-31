import type { CapabilityResponse, ExplorerHome, ObjectDetail, ObjectSetPage, ObjectSetRequest, SearchResponse } from './explorer.types';
import { activeOntologyId } from '../ontologyContext';

export class ExplorerApi {
  constructor() {}
  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`/v1/ontologies/${activeOntologyId()}${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) } });
    if (!response.ok) { const problem = await response.json().catch(() => ({})) as { detail?: string; message?: string }; throw new Error(problem.detail ?? problem.message ?? `请求失败 (${response.status})`); }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }
  home = () => this.request<ExplorerHome>('/explorer/home');
  search = (query: string) => this.request<SearchResponse>('/search/objects', { method: 'POST', body: JSON.stringify({ query, mode: 'ALL', tab: 'ALL', size: 50 }) });
  query = (request: ObjectSetRequest) => this.request<ObjectSetPage>('/object-sets/query', { method: 'POST', body: JSON.stringify(request) });
  facets = (query: ObjectSetRequest, propertyIds: string[]) => this.request<Array<{ propertyId: string; displayName: string; buckets: Array<{ value: unknown; count: number }> }>>('/object-sets/facets', { method: 'POST', body: JSON.stringify({ query, propertyIds }) });
  object = (typeId: string, objectId: string) => this.request<ObjectDetail>(`/explorer/object-types/${typeId}/objects/${encodeURIComponent(objectId)}`);
  links = (typeId: string, objectId: string) => this.request<{ items: Array<{ relationId: string; linkTypeName: string; direction: string; targetObjectId: string; targetObjectTypeId: string; targetTitle: string }>; visibleCount: number }>(`/explorer/object-types/${typeId}/objects/${encodeURIComponent(objectId)}/links`, { method: 'POST', body: JSON.stringify({ direction: 'BOTH', pageSize: 25 }) });
  capabilities = (typeId: string, objectId: string) => this.request<CapabilityResponse>(`/explorer/object-types/${typeId}/objects/${encodeURIComponent(objectId)}/capabilities`);
  activity = (typeId: string, objectId: string) => this.request<Array<Record<string, unknown>>>(`/explorer/object-types/${typeId}/objects/${encodeURIComponent(objectId)}/activity`);
  provenance = (typeId: string, objectId: string) => this.request<Record<string, unknown>>(`/explorer/object-types/${typeId}/objects/${encodeURIComponent(objectId)}/provenance`);
}
