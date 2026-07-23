import type { CapabilityResponse, ExplorerHome, ExplorationView, ExportJob, ObjectDetail, ObjectListView, ObjectSetPage, ObjectSetRequest, SearchResponse } from './explorer.types';
import { activeOntologyId } from '../ontologyContext';

export class ExplorerApi {
  constructor(private readonly token: string) {}
  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`/api/v1/ontologies/${activeOntologyId()}${path}`, { ...init, headers: { Authorization: `Bearer ${this.token}`, 'Content-Type': 'application/json', ...(init?.headers ?? {}) } });
    if (!response.ok) { const problem = await response.json().catch(() => ({})) as { detail?: string; message?: string }; throw new Error(problem.detail ?? problem.message ?? `请求失败 (${response.status})`); }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }
  home = () => this.request<ExplorerHome>('/explorer/home');
  search = (query: string) => this.request<SearchResponse>('/search/objects', { method: 'POST', body: JSON.stringify({ query, mode: 'ALL', tab: 'ALL', size: 50 }) });
  query = (request: ObjectSetRequest) => this.request<ObjectSetPage>('/object-sets/query', { method: 'POST', body: JSON.stringify(request) });
  facets = (query: ObjectSetRequest, propertyIds: string[]) => this.request<Array<{ propertyId: string; displayName: string; buckets: Array<{ value: unknown; count: number }> }>>('/object-sets/facets', { method: 'POST', body: JSON.stringify({ query, propertyIds }) });
  compare = (objectTypeId: string, objectIds: string[]) => this.request<{ objects: ObjectDetail[]; differingProperties: string[]; commonProperties: string[] }>('/object-sets/compare', { method: 'POST', body: JSON.stringify({ objectTypeId, objectIds }) });
  object = (typeId: string, objectId: string) => this.request<ObjectDetail>(`/object-types/${typeId}/objects/${encodeURIComponent(objectId)}`);
  links = (typeId: string, objectId: string) => this.request<{ items: Array<{ relationId: string; linkTypeName: string; direction: string; targetObjectId: string; targetObjectTypeId: string; targetTitle: string }>; visibleCount: number }>(`/object-types/${typeId}/objects/${encodeURIComponent(objectId)}/links`, { method: 'POST', body: JSON.stringify({ direction: 'BOTH', pageSize: 25 }) });
  capabilities = (typeId: string, objectId: string) => this.request<CapabilityResponse>(`/object-types/${typeId}/objects/${encodeURIComponent(objectId)}/capabilities`);
  activity = (typeId: string, objectId: string) => this.request<Array<Record<string, unknown>>>(`/object-types/${typeId}/objects/${encodeURIComponent(objectId)}/activity`);
  provenance = (typeId: string, objectId: string) => this.request<Record<string, unknown>>(`/object-types/${typeId}/objects/${encodeURIComponent(objectId)}/provenance`);
  explorations = () => this.request<ExplorationView[]>('/explorations');
  exploration = (id: string) => this.request<ExplorationView>(`/explorations/${id}`);
  createExploration = (body: Record<string, unknown>) => this.request<ExplorationView>('/explorations', { method: 'POST', body: JSON.stringify(body) });
  shareExploration = (id: string) => this.request<ExplorationView>(`/explorations/${id}/share`, { method: 'POST' });
  lists = () => this.request<ObjectListView[]>('/object-lists');
  createList = (body: Record<string, unknown>) => this.request<ObjectListView>('/object-lists', { method: 'POST', body: JSON.stringify(body) });
  createSelection = (query: ObjectSetRequest, objectIds: string[]) => this.request<{ token: string; objectCount: number; expiresAt: string }>('/selection-tokens', { method: 'POST', body: JSON.stringify({ query, objectIds }) });
  export = (query: ObjectSetRequest, objectIds: string[], columns: string[], format: string) => this.request<ExportJob>('/exports', { method: 'POST', body: JSON.stringify({ query, objectIds, columns, format }) });
}
