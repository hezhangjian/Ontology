import type { DashboardBatchResult, DashboardDefinition, DashboardDetail, DashboardDraft, DashboardPlan, DashboardSummary, DashboardValidation, DashboardVersion } from '../types';
import { activeOntologyId } from '../../../ontology/ontologyContext';

export class DashboardApi {
  constructor(private readonly token: string) {}
  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`/api/ontology/v1${path}`, { ...init, headers: { Authorization: `Bearer ${this.token}`, 'Content-Type': 'application/json', 'X-Ontology-Id': activeOntologyId(), 'X-Workspace-Id': activeOntologyId(), ...(init?.headers ?? {}) } });
    if (!response.ok) { const problem = await response.json().catch(() => ({})) as { detail?: string; message?: string }; throw new Error(problem.detail ?? problem.message ?? `请求失败 (${response.status})`); }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }
  list = (keyword = '', lifecycle = '', favorites = false) => this.request<DashboardSummary[]>(`/dashboards?keyword=${encodeURIComponent(keyword)}&lifecycle=${encodeURIComponent(lifecycle)}&favorites=${favorites}`);
  create = (body: Record<string, unknown>) => this.request<DashboardDetail>('/dashboards', { method: 'POST', body: JSON.stringify(body) });
  detail = (id: string) => this.request<DashboardDetail>(`/dashboards/${id}`);
  delete = (id: string) => this.request<void>(`/dashboards/${id}`, { method: 'DELETE' });
  patch = (id: string, etag: number, body: Record<string, unknown>) => this.request<DashboardDetail>(`/dashboards/${id}`, { method: 'PATCH', headers: { 'If-Match': String(etag) }, body: JSON.stringify(body) });
  copy = (id: string) => this.request<DashboardDetail>(`/dashboards/${id}/copy`, { method: 'POST' });
  archive = (id: string) => this.request<DashboardDetail>(`/dashboards/${id}/archive`, { method: 'POST' });
  restore = (id: string) => this.request<DashboardDetail>(`/dashboards/${id}/restore`, { method: 'POST' });
  favorite = (id: string, value: boolean) => this.request<void>(`/dashboards/${id}/favorite`, { method: value ? 'PUT' : 'DELETE' });
  draft = (id: string) => this.request<DashboardDraft>(`/dashboards/${id}/draft`);
  lock = (id: string, force = false) => this.request<{ holderName: string; expiresAt: string; editable: boolean }>(`/dashboards/${id}/edit-lock`, { method: 'POST', body: JSON.stringify({ force }) });
  renewLock = (id: string) => this.request(`/dashboards/${id}/edit-lock/renew`, { method: 'POST' });
  releaseLock = (id: string) => this.request<void>(`/dashboards/${id}/edit-lock`, { method: 'DELETE' });
  saveDraft = (id: string, etag: number, definition: DashboardDefinition) => this.request<DashboardDraft>(`/dashboards/${id}/draft`, { method: 'PUT', headers: { 'If-Match': String(etag) }, body: JSON.stringify(definition) });
  validate = (id: string) => this.request<DashboardValidation>(`/dashboards/${id}/validate`, { method: 'POST' });
  publish = (id: string, releaseNotes = '') => this.request<DashboardVersion>(`/dashboards/${id}/publish`, { method: 'POST', body: JSON.stringify({ releaseNotes }) });
  versions = (id: string) => this.request<DashboardVersion[]>(`/dashboards/${id}/versions`);
  version = (id: string, versionId: string) => this.request<DashboardVersion>(`/dashboards/${id}/versions/${versionId}`);
  createDraftFromVersion = (id: string, versionId: string) => this.request<DashboardDraft>(`/dashboards/${id}/versions/${versionId}/create-draft`, { method: 'POST' });
  plan = (id: string) => this.request<DashboardPlan>(`/dashboards/${id}/query-plan`);
  execute = (planId: string, pageId: string, widgetIds: string[], filters: Record<string, unknown>, refreshId = crypto.randomUUID()) => this.request<DashboardBatchResult>(`/dashboard-query-plans/${planId}/widgets:batch`, { method: 'POST', body: JSON.stringify({ pageId, widgetIds, filters, refreshId }) });
}
