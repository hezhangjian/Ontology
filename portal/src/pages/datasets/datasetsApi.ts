import { ApiProblem } from '../data-connections/services/dataConnections';
import type { Dataset, DatasetFilter, DatasetMetric, DatasetPage, DatasetPreview, DatasetQueryResult, MappingPreview } from './types';

const base = '/api/ontology/v1';
export function datasetsApi(accessToken: string) {
  async function request<T>(path: string, init: RequestInit = {}) {
    const response = await fetch(`${base}${path}`, { ...init, headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json', ...init.headers } });
    if (!response.ok) { const problem = await response.json().catch(() => ({ detail: '请求失败' })) as { detail?: string; requestId?: string }; throw new ApiProblem(problem.detail ?? '请求失败', problem.requestId, response.status); }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }
  return {
    get: (id: string) => request<Dataset>(`/datasets/${id}`),
    list: (search = '') => request<DatasetPage>(`/datasets${search ? `?search=${encodeURIComponent(search)}` : ''}`),
    mappingPreview: (id: string, identityField: string, titleField: string) => request<MappingPreview>(`/datasets/${id}/mapping-preview?identityField=${encodeURIComponent(identityField)}&titleField=${encodeURIComponent(titleField)}`),
    materialize: (pipelineId: string, name?: string, description?: string) => request<Dataset>(`/pipelines/${pipelineId}/materialize-dataset`, { method: 'POST', body: JSON.stringify({ name, description }) }),
    preview: (id: string, limit = 50, offset = 0) => request<DatasetPreview>(`/datasets/${id}/preview?limit=${limit}&offset=${offset}`),
    remove: (id: string) => request<void>(`/datasets/${id}`, { method: 'DELETE' }),
    query: (id: string, dimensions: string[], metrics: DatasetMetric[], filters: DatasetFilter[] = [], orderBy?: string, orderDirection = 'DESC', limit = 1000) => request<DatasetQueryResult>(`/datasets/${id}/query`, { method: 'POST', body: JSON.stringify({ dimensions, filters, limit, metrics, orderBy, orderDirection }) }),
  };
}
