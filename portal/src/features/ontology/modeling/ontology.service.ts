import { ApiProblem } from '../../../pages/data-connections/services/dataConnections';
import type { ActionExecution, Deployment, HealthIssue, HistoryEntry, ModelingSummary, OntologyResource, Proposal, PropertyView, ResourceKind } from './ontology.types';
import { activeOntologyId } from '../ontologyContext';

const base = () => `/api/v1/ontologies/${activeOntologyId()}`;
const segment: Record<ResourceKind, string> = {
  OBJECT_TYPE: 'object-types', LINK_TYPE: 'link-types', INTERFACE: 'interfaces', ACTION: 'action-types', FUNCTION: 'functions',
};

export function modelingApi(accessToken: string) {
  async function response<T>(path: string, init: RequestInit = {}): Promise<{ data: T; etag?: string }> {
    const result = await fetch(`${base()}${path}`, { ...init, headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json', ...init.headers } });
    if (!result.ok) {
      const problem = await result.json().catch(() => ({ detail: '请求未能完成' })) as { detail?: string; requestId?: string };
      throw new ApiProblem(problem.detail ?? '请求未能完成', problem.requestId, result.status);
    }
    if (result.status === 204) return { data: undefined as T, etag: result.headers.get('ETag') ?? undefined };
    return { data: await result.json() as T, etag: result.headers.get('ETag') ?? undefined };
  }
  const request = async <T,>(path: string, init: RequestInit = {}) => (await response<T>(path, init)).data;
  return {
    actionPreview: (id: string, parameters: Record<string, unknown>) => request<Record<string, unknown>>(`/action-types/${id}/previews`, { method: 'POST', body: JSON.stringify({ parameters }) }),
    actionExecutions: (status?: ActionExecution['status']) => request<ActionExecution[]>(`/action-executions${status ? `?status=${status}` : ''}`),
    actionReview: (id: string, decision: 'APPROVE' | 'REJECT', comment: string) => request<ActionExecution>(`/action-executions/${id}/reviews`, { method: 'POST', body: JSON.stringify({ comment, decision }) }),
    closeProposal: (id: string) => request<Proposal>(`/proposals/${id}/close`, { method: 'POST' }),
    createProposal: (body: Record<string, unknown>) => request<Proposal>('/proposals', { method: 'POST', body: JSON.stringify(body) }),
    createResource: (kind: ResourceKind, body: Record<string, unknown>) => request<OntologyResource>(`/${segment[kind]}`, { method: 'POST', body: JSON.stringify(body) }),
    deleteResource: (kind: ResourceKind, id: string) => request<void>(`/${segment[kind]}/${id}`, { method: 'DELETE' }),
    deployment: (id: string) => request<Deployment>(`/deployments/${id}`),
    functionTest: (id: string, inputs: Record<string, unknown>) => request<Record<string, unknown>>(`/functions/${id}/executions`, { method: 'POST', body: JSON.stringify({ inputs }) }),
    getProposal: (id: string) => request<Proposal>(`/proposals/${id}`),
    getResource: (kind: ResourceKind, id: string) => request<OntologyResource>(`/${segment[kind]}/${id}`),
    health: () => request<HealthIssue[]>('/health'),
    history: () => request<HistoryEntry[]>('/revisions'),
    listProperties: () => request<PropertyView[]>('/properties'),
    listProposals: () => request<Proposal[]>('/proposals'),
    listResources: (kind: ResourceKind, search = '') => request<OntologyResource[]>(`/${segment[kind]}${search ? `?search=${encodeURIComponent(search)}` : ''}`),
    publishProposal: (id: string) => request<Deployment>(`/proposals/${id}/publication`, { method: 'POST' }),
    retryProposal: (id: string) => request<Deployment>(`/proposals/${id}/retry`, { method: 'POST' }),
    reviewProposal: (id: string, decision: string, comment: string) => request<Proposal>(`/proposals/${id}/reviews`, { method: 'POST', body: JSON.stringify({ decision, comment }) }),
    search: (query: string) => request<Array<Record<string, unknown>>>(`/search?query=${encodeURIComponent(query)}`),
    submitProposal: (id: string) => request<Proposal>(`/proposals/${id}/submit`, { method: 'POST' }),
    summary: () => request<ModelingSummary>('/summary'),
    validateProposal: (id: string) => request<Proposal>(`/proposals/${id}/validate`, { method: 'POST' }),
  };
}
