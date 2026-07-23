import type {
  AssetPreview,
  ConnectionTestResult,
  CredentialInput,
  CredentialSummary,
  DataSource,
  DataSourceAsset,
  DataSourcePage,
  DataSourceType,
  Overview,
  PipelineRun,
  PipelineSummary,
  Problem,
} from '../types';
import { activeOntologyId } from '../../../features/ontology/ontologyContext';

const base = () => `/api/v1/ontologies/${activeOntologyId()}`;

export class ApiProblem extends Error {
  constructor(
    message: string,
    readonly requestId?: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

export function dataConnectionsApi(accessToken: string) {
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await fetch(`${base()}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
        ...init.headers,
      },
    });
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({ detail: '请求未能完成' }))) as Partial<Problem>;
      throw new ApiProblem(problem.detail ?? '请求未能完成', problem.requestId, response.status);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }

  async function multipart<T>(path: string, body: FormData): Promise<T> {
    const response = await fetch(`${base()}${path}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
      body,
    });
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({ detail: '请求未能完成' }))) as Partial<Problem>;
      throw new ApiProblem(problem.detail ?? '请求未能完成', problem.requestId, response.status);
    }
    return response.json() as Promise<T>;
  }

  return {
    create: (body: {
      name: string;
      description?: string;
      type: DataSourceType;
      ownerId: string;
      ownerName: string;
      tags: string[];
      config: Record<string, unknown>;
      credential: CredentialInput;
      testToken: string;
    }) => request<DataSource>('/connections', { method: 'POST', body: JSON.stringify(body) }),
    delete: (id: string) => request<void>(`/connections/${id}`, { method: 'DELETE' }),
    disable: (id: string) => request<DataSource>(`/connections/${id}/disable`, { method: 'POST' }),
    discover: (id: string) => request<{ taskId: string; status: string; discoveredCount: number }>(`/connections/${id}/discover`, { method: 'POST' }),
    enable: (id: string) => request<DataSource>(`/connections/${id}/enable`, { method: 'POST' }),
    get: (id: string) => request<DataSource>(`/connections/${id}`),
    getAsset: (id: string, assetId: string) => request<DataSourceAsset>(`/connections/${id}/assets/${assetId}`),
    inferSchema: (id: string, assetId: string) => request(`/connections/${id}/assets/${assetId}/infer-schema`, { method: 'POST' }),
    importLocalCsv: (name: string, description: string | undefined, tags: string[], files: File[]) => {
      const form = new FormData();
      form.append('name', name);
      if (description) form.append('description', description);
      tags.forEach((tag) => form.append('tags', tag));
      files.forEach((file) => form.append('files', file, file.webkitRelativePath || file.name));
      return multipart<DataSource>('/connections/local-csv', form);
    },
    list: (query: string) => request<DataSourcePage>(`/connections${query ? `?${query}` : ''}`),
    listAssets: (id: string, search = '') => request<{ items: DataSourceAsset[]; total: number }>(`/connections/${id}/assets?size=100&search=${encodeURIComponent(search)}`),
    listCredentials: () => request<CredentialSummary[]>('/credentials?usable=true'),
    overview: (id: string) => request<Overview>(`/connections/${id}/overview`),
    pipelines: (id: string) => request<PipelineSummary[]>(`/connections/${id}/pipelines`),
    preview: (id: string, assetId: string, limit = 50) => request<AssetPreview>(`/connections/${id}/assets/${assetId}/preview`, { method: 'POST', body: JSON.stringify({ limit }) }),
    retest: (id: string) => request<ConnectionTestResult>(`/connections/${id}/test`, { method: 'POST' }),
    rotate: (id: string, credential: CredentialInput) => request<CredentialSummary>(`/connections/${id}/rotate-credential`, { method: 'POST', body: JSON.stringify({ credential }) }),
    runs: (id: string) => request<PipelineRun[]>(`/connections/${id}/runs`),
    test: (body: { type: DataSourceType; config: Record<string, unknown>; credential: CredentialInput }) =>
      request<ConnectionTestResult>('/connection-tests', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, version: number, body: Partial<DataSource>) => request<DataSource>(`/connections/${id}`, {
      method: 'PATCH',
      headers: { 'If-Match': String(version) },
      body: JSON.stringify(body),
    }),
  };
}
