import { ApiProblem } from '../../../pages/data-connections/services/dataConnections';
import type { ActionExecution, HealthIssue, ModelingSummary, ObjectTypeBackingView, OntologyResource, PropertyView, ResourceKind } from './ontology.types';
import { activeOntologyId } from '../ontologyContext';

const base = () => `/v1/ontologies/${activeOntologyId()}`;
const segment: Record<ResourceKind, string> = {
  OBJECT_TYPE: 'object-types', LINK_TYPE: 'link-types', INTERFACE: 'interfaces', ACTION: 'action-types', FUNCTION: 'functions',
};

export function modelingApi() {
  async function response<T>(path: string, init: RequestInit = {}): Promise<{ data: T; etag?: string }> {
    const result = await fetch(`${base()}${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...init.headers } });
    if (!result.ok) {
      const problem = await result.json().catch(() => ({ detail: '请求未能完成' })) as { detail?: string; requestId?: string };
      throw new ApiProblem(problem.detail ?? '请求未能完成', problem.requestId, result.status);
    }
    if (result.status === 204) return { data: undefined as T, etag: result.headers.get('ETag') ?? undefined };
    return { data: await result.json() as T, etag: result.headers.get('ETag') ?? undefined };
  }
  const request = async <T,>(path: string, init: RequestInit = {}) => (await response<T>(path, init)).data;
  return {
    actionPreview: (id: string, parameters: Record<string, unknown>, objectId?: string) => request<Record<string, unknown>>(`/action-types/${id}/previews`, { method: 'POST', body: JSON.stringify({ objectId, parameters }) }),
    actionExecutions: (status?: ActionExecution['status']) => request<ActionExecution[]>(`/action-executions${status ? `?status=${status}` : ''}`),
    createResource: (kind: ResourceKind, body: Record<string, unknown>) => request<OntologyResource>(`/${segment[kind]}`, { method: 'POST', body: JSON.stringify(body) }),
    deleteResource: (kind: ResourceKind, id: string) => request<void>(`/${segment[kind]}/${id}`, { method: 'DELETE' }),
    functionTest: (id: string, inputs: Record<string, unknown>) => request<Record<string, unknown>>(`/functions/${id}/executions`, { method: 'POST', body: JSON.stringify({ inputs }) }),
    getResource: (kind: ResourceKind, id: string) => request<OntologyResource>(`/${segment[kind]}/${id}`),
    health: () => request<HealthIssue[]>('/health'),
    listProperties: () => request<PropertyView[]>('/properties'),
    listResources: (kind: ResourceKind, search = '') => request<OntologyResource[]>(`/${segment[kind]}${search ? `?search=${encodeURIComponent(search)}` : ''}`),
    objectTypeBacking: (id: string) => request<ObjectTypeBackingView>(`/object-types/${id}/backing`),
    search: (query: string) => request<Array<Record<string, unknown>>>(`/search?query=${encodeURIComponent(query)}`),
    summary: () => request<ModelingSummary>('/summary'),
    updateResource: (kind: ResourceKind, id: string, body: { id?: string; displayName?: string; description?: string }) =>
      request<OntologyResource>(`/${segment[kind]}/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
  };
}
