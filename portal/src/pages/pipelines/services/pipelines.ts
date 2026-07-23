import { ApiProblem } from '../../data-connections/services/dataConnections';
import type {
  NodeType,
  Pipeline,
  PipelineGraph,
  PipelinePage,
  PipelineProposal,
  PipelineRun,
  PipelineRunDetail,
  PipelineVersion,
  PreviewRun,
  RuntimeSettings,
  ScheduleSettings,
  ValidationResult,
} from '../types';
import { activeOntologyId } from '../../../features/ontology/ontologyContext';

const base = '/api/ontology/v1';

export function pipelinesApi(accessToken: string) {
  async function response<T>(path: string, init: RequestInit = {}): Promise<{ data: T; etag?: string }> {
    const result = await fetch(`${base}${path}`, {
      ...init,
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json', 'X-Ontology-Id': activeOntologyId(), 'X-Workspace-Id': activeOntologyId(), ...init.headers },
    });
    if (!result.ok) {
      const problem = await result.json().catch(() => ({ detail: '请求未能完成' })) as { detail?: string; requestId?: string };
      throw new ApiProblem(problem.detail ?? '请求未能完成', problem.requestId, result.status);
    }
    if (result.status === 204) return { data: undefined as T, etag: result.headers.get('ETag') ?? undefined };
    return { data: await result.json() as T, etag: result.headers.get('ETag') ?? undefined };
  }

  const request = async <T,>(path: string, init: RequestInit = {}) => (await response<T>(path, init)).data;
  return {
    archive: (id: string) => request<Pipeline>(`/pipelines/${id}/archive`, { method: 'POST' }),
    approve: (id: string, proposalId: string, comment = '') => request<PipelineProposal>(`/pipelines/${id}/proposals/${proposalId}/approve`, { method: 'POST', body: JSON.stringify({ comment }) }),
    cancel: (runId: string) => request<PipelineRun>(`/pipeline-runs/${runId}/cancel`, { method: 'POST' }),
    cancelPreview: (id: string) => request<void>(`/pipeline-previews/${id}/cancel`, { method: 'POST' }),
    create: (body: Record<string, unknown>) => response<Pipeline>('/pipelines', { method: 'POST', body: JSON.stringify(body) }),
    delete: (id: string) => request<void>(`/pipelines/${id}`, { method: 'DELETE' }),
    diff: (id: string) => request<Record<string, unknown>>(`/pipelines/${id}/diff`),
    duplicate: (id: string) => request<Pipeline>(`/pipelines/${id}/duplicate`, { method: 'POST' }),
    get: (id: string) => response<Pipeline>(`/pipelines/${id}`),
    getPreview: (id: string) => request<PreviewRun>(`/pipeline-previews/${id}`),
    list: (query: string) => request<PipelinePage>(`/pipelines${query ? `?${query}` : ''}`),
    nodeTypes: () => request<NodeType[]>('/pipeline-node-types'),
    pause: (id: string) => request<Pipeline>(`/pipelines/${id}/pause`, { method: 'POST' }),
    preview: (id: string, nodeId: string, limit = 100) => request<PreviewRun>(`/pipelines/${id}/preview`, { method: 'POST', body: JSON.stringify({ limit, nodeId }) }),
    proposals: (id: string) => request<PipelineProposal[]>(`/pipelines/${id}/proposals`),
    propose: (id: string, title: string, summary: string) => request<PipelineProposal>(`/pipelines/${id}/proposals`, { method: 'POST', body: JSON.stringify({ summary, title }) }),
    publish: (id: string, proposalId?: string, startAfterPublish = false) => request<PipelineVersion>(`/pipelines/${id}/publish`, { method: 'POST', body: JSON.stringify({ acknowledgeWarnings: true, proposalId, startAfterPublish }) }),
    reject: (id: string, proposalId: string, comment = '') => request<PipelineProposal>(`/pipelines/${id}/proposals/${proposalId}/reject`, { method: 'POST', body: JSON.stringify({ comment }) }),
    replayDlq: (runId: string) => request<PipelineRun>(`/pipeline-runs/${runId}/replay-dlq`, { method: 'POST' }),
    resetOffsets: (id: string, position: string) => request<Pipeline>(`/pipelines/${id}/reset-offsets`, { method: 'POST', body: JSON.stringify({ acknowledgeDuplicateOrLossRisk: true, position, specificOffsets: {} }) }),
    resume: (id: string) => request<Pipeline>(`/pipelines/${id}/resume`, { method: 'POST' }),
    retry: (runId: string) => request<PipelineRun>(`/pipeline-runs/${runId}/retry`, { method: 'POST' }),
    rollback: (id: string, version: number) => request<Pipeline>(`/pipelines/${id}/rollback`, { method: 'POST', body: JSON.stringify({ acknowledgeDataNotReverted: true, version }) }),
    run: (id: string) => request<PipelineRun>(`/pipelines/${id}/run`, { method: 'POST' }),
    runDetail: (runId: string) => request<PipelineRunDetail>(`/pipeline-runs/${runId}`),
    runs: (id: string, page = 0) => request<{ items: PipelineRun[]; total: number }>(`/pipelines/${id}/runs?page=${page}&size=20`),
    savepoint: (id: string) => request<PipelineRun>(`/pipelines/${id}/savepoint`, { method: 'POST' }),
    start: (id: string) => request<PipelineRun>(`/pipelines/${id}/start`, { method: 'POST' }),
    stop: (id: string) => request<PipelineRun>(`/pipelines/${id}/stop`, { method: 'POST', body: JSON.stringify({ drain: true }) }),
    updateDraft: (id: string, etag: number, body: { graph?: PipelineGraph; runtime?: RuntimeSettings; schedule?: ScheduleSettings; name?: string; description?: string }) => response<Pipeline>(`/pipelines/${id}/draft`, { method: 'PATCH', headers: { 'If-Match': String(etag) }, body: JSON.stringify(body) }),
    validate: (id: string) => request<ValidationResult>(`/pipelines/${id}/validate`, { method: 'POST' }),
    versions: (id: string) => request<PipelineVersion[]>(`/pipelines/${id}/versions`),
  };
}
