import { randomUuid } from '@/shared/utils/randomUuid';
import { ApiProblem } from '../../data-connections/services/dataConnections';
import type {
  NodeType,
  Pipeline,
  PipelineGraph,
  PipelinePage,
  PipelineRun,
  PipelineRunDetail,
  PreviewRun,
  RuntimeSettings,
  ScheduleSettings,
  ValidationResult,
} from '../types';
import { activeOntologyId } from '../../../features/ontology/ontologyContext';

const base = () => `/v1/ontologies/${activeOntologyId()}`;

export function pipelinesApi() {
  async function response<T>(path: string, init: RequestInit = {}): Promise<{ data: T; etag?: string }> {
    const result = await fetch(`${base()}${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init.headers },
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
    cancel: (runId: string) => request<PipelineRun>(`/pipeline-runs/${runId}`, { method: 'DELETE' }),
    cancelPreview: (id: string) => request<void>(`/pipeline-previews/${id}/cancel`, { method: 'POST' }),
    create: (body: Record<string, unknown>) => response<Pipeline>('/pipelines', { method: 'POST', body: JSON.stringify(body) }),
    delete: (id: string) => request<void>(`/pipelines/${id}`, { method: 'DELETE' }),
    duplicate: (id: string) => response<Pipeline>(`/pipelines/${id}/duplicate`, { method: 'POST' }),
    get: (id: string) => response<Pipeline>(`/pipelines/${id}`),
    getPreview: (id: string) => request<PreviewRun>(`/pipeline-previews/${id}`),
    list: (query: string) => request<PipelinePage>(`/pipelines${query ? `?${query}` : ''}`),
    nodeTypes: () => request<NodeType[]>('/pipeline-node-types'),
    pause: (id: string) => request<Pipeline>(`/pipelines/${id}/pause`, { method: 'POST' }),
    preview: (id: string, nodeId: string, limit = 100) => request<PreviewRun>(`/pipelines/${id}/previews`, { method: 'POST', body: JSON.stringify({ limit, nodeId }) }),
    publish: (id: string, startAfterPublish = false) => request<Pipeline>(`/pipelines/${id}/publication`, { method: 'POST', body: JSON.stringify({ acknowledgeWarnings: true, startAfterPublish }) }),
    replayDlq: (runId: string) => request<PipelineRun>(`/pipeline-runs/${runId}/replay-dlq`, { method: 'POST' }),
    resetOffsets: (id: string, position: string) => request<Pipeline>(`/pipelines/${id}/reset-offsets`, { method: 'POST', body: JSON.stringify({ acknowledgeDuplicateOrLossRisk: true, position, specificOffsets: {} }) }),
    resume: (id: string) => request<Pipeline>(`/pipelines/${id}/resume`, { method: 'POST' }),
    retry: (runId: string) => request<PipelineRun>(`/pipeline-runs/${runId}/retry`, { method: 'POST' }),
    run: (id: string) => request<PipelineRun>(`/pipelines/${id}/runs`, { method: 'POST', headers: { 'Idempotency-Key': randomUuid() }, body: JSON.stringify({}) }),
    runDetail: (runId: string) => request<PipelineRunDetail>(`/pipeline-runs/${runId}`),
    runs: (id: string, page = 0) => request<{ items: PipelineRun[]; total: number }>(`/pipelines/${id}/runs?page=${page}&size=20`),
    savepoint: (id: string) => request<PipelineRun>(`/pipeline-runs/${id}/savepoints`, { method: 'POST' }),
    start: (id: string) => request<PipelineRun>(`/pipelines/${id}/start`, { method: 'POST' }),
    stop: (id: string) => request<PipelineRun>(`/pipeline-runs/${id}/stop`, { method: 'POST', body: JSON.stringify({ drain: true }) }),
    updateDraft: (id: string, etag: number, body: { graph?: PipelineGraph; runtime?: RuntimeSettings; schedule?: ScheduleSettings; name?: string; description?: string }) => response<Pipeline>(`/pipelines/${id}/draft`, { method: 'PUT', headers: { 'If-Match': String(etag) }, body: JSON.stringify(body) }),
    validate: (id: string) => request<ValidationResult>(`/pipelines/${id}/validate`, { method: 'POST' }),
  };
}
