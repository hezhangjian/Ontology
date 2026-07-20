import { ApiProblem } from '../../../pages/data-connections/services/dataConnections';
import type { Deployment, HealthIssue, HistoryEntry, ModelingSummary, OntologyResource, Proposal, PropertyView, ResourceKind } from './ontology.types';

const base = '/api/ontology/v1/modeling';
const segment: Record<ResourceKind, string> = {
  OBJECT_TYPE: 'object-types', LINK_TYPE: 'link-types', INTERFACE: 'interfaces', ACTION: 'actions', FUNCTION: 'functions',
};

export function modelingApi(accessToken: string) {
  async function response<T>(path: string, init: RequestInit = {}): Promise<{ data: T; etag?: string }> {
    const result = await fetch(`${base}${path}`, { ...init, headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json', ...init.headers } });
    if (!result.ok) {
      const problem = await result.json().catch(() => ({ detail: '请求未能完成' })) as { detail?: string; requestId?: string };
      throw new ApiProblem(problem.detail ?? '请求未能完成', problem.requestId, result.status);
    }
    return { data: await result.json() as T, etag: result.headers.get('ETag') ?? undefined };
  }
  const request = async <T,>(path: string, init: RequestInit = {}) => (await response<T>(path, init)).data;
  return {
    actionPreview: (id: string, parameters: Record<string, unknown>) => request<Record<string, unknown>>(`/actions/${id}/preview`, { method: 'POST', body: JSON.stringify({ parameters }) }),
    closeProposal: (id: string) => request<Proposal>(`/proposals/${id}/close`, { method: 'POST' }),
    createProposal: (body: Record<string, unknown>) => request<Proposal>('/proposals', { method: 'POST', body: JSON.stringify(body) }),
    createResource: (kind: ResourceKind, body: Record<string, unknown>) => request<OntologyResource>(`/${segment[kind]}`, { method: 'POST', body: JSON.stringify(body) }),
    deployment: (id: string) => request<Deployment>(`/deployments/${id}`),
    functionTest: (id: string, inputs: Record<string, unknown>) => request<Record<string, unknown>>(`/functions/${id}/test`, { method: 'POST', body: JSON.stringify({ inputs }) }),
    getProposal: (id: string) => request<Proposal>(`/proposals/${id}`),
    getResource: (kind: ResourceKind, id: string) => request<OntologyResource>(`/${segment[kind]}/${id}`),
    health: () => request<HealthIssue[]>('/health'),
    history: () => request<HistoryEntry[]>('/history'),
    listProperties: () => request<PropertyView[]>('/properties'),
    listProposals: () => request<Proposal[]>('/proposals'),
    listResources: (kind: ResourceKind, search = '') => request<OntologyResource[]>(`/${segment[kind]}${search ? `?search=${encodeURIComponent(search)}` : ''}`),
    publishProposal: (id: string) => request<Deployment>(`/proposals/${id}/publish`, { method: 'POST' }),
    retryProposal: (id: string) => request<Deployment>(`/proposals/${id}/retry`, { method: 'POST' }),
    reviewProposal: (id: string, decision: string, comment: string) => request<Proposal>(`/proposals/${id}/reviews`, { method: 'POST', body: JSON.stringify({ decision, comment }) }),
    search: (query: string) => request<Array<Record<string, unknown>>>(`/search?query=${encodeURIComponent(query)}`),
    submitProposal: (id: string) => request<Proposal>(`/proposals/${id}/submit`, { method: 'POST' }),
    summary: () => request<ModelingSummary>('/summary'),
    validateProposal: (id: string) => request<Proposal>(`/proposals/${id}/validate`, { method: 'POST' }),
  };
}
