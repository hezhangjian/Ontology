import { activeOntologyId } from '../../ontology/ontologyContext';

export interface ToolTrace {
  id: string;
  name: string;
  arguments: Record<string, unknown>;
  result: unknown;
  mutationPreview: boolean;
}

export interface ConversationMessage {
  id: string;
  role: 'assistant' | 'user';
  content: string;
  createdAt: string;
  tools: ToolTrace[];
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messages: ConversationMessage[];
}

export type ConversationStreamEvent =
  | { type: 'assistant_start'; data: { id: string; createdAt: string } }
  | { type: 'content_delta'; data: { id: string; delta: string } }
  | { type: 'done'; data: { conversation: Conversation; message: ConversationMessage } }
  | { type: 'error'; data: { detail: string } }
  | { type: 'tool_result' | 'tool_start'; data: ToolTrace }
  | { type: 'user_message'; data: ConversationMessage };

export function conversationApi(accessToken: string) {
  const request = async <T,>(path: string, init: RequestInit = {}) => {
    const response = await fetch(`/api/agent/v1/conversations${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
        'X-Ontology-Id': activeOntologyId(),
        ...init.headers,
      },
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => ({})) as { detail?: string; message?: string };
      throw new Error(problem.detail ?? problem.message ?? `请求失败 (${response.status})`);
    }
    return response.json() as Promise<T>;
  };
  return {
    confirmAction: (conversationId: string, actionId: string, previewToken: string) => request<Record<string, unknown>>(`/${conversationId}/confirm-action`, { method: 'POST', body: JSON.stringify({ actionId, previewToken, idempotencyKey: `conversation:${conversationId}:${crypto.randomUUID()}` }) }),
    confirmRule: (conversationId: string, proposal: Record<string, unknown>) => request<Record<string, unknown>>(`/${conversationId}/confirm-rule-transform`, { method: 'POST', body: JSON.stringify(proposal) }),
    create: () => request<Conversation>('', { method: 'POST', body: JSON.stringify({}) }),
    get: (id: string) => request<Conversation>(`/${id}`),
    list: () => request<Conversation[]>(''),
    send: async (id: string, content: string, onEvent: (event: ConversationStreamEvent) => void) => {
      const response = await fetch(`/api/agent/v1/conversations/${id}/messages`, {
        method: 'POST',
        headers: {
          Accept: 'text/event-stream',
          Authorization: `Bearer ${accessToken}`,
          'Content-Type': 'application/json',
          'X-Ontology-Id': activeOntologyId(),
        },
        body: JSON.stringify({ content }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => ({})) as { detail?: string; message?: string };
        throw new Error(problem.detail ?? problem.message ?? `请求失败 (${response.status})`);
      }
      if (!response.body) throw new Error('浏览器未提供流式响应');
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n');
        const frames = buffer.split('\n\n');
        buffer = frames.pop() ?? '';
        for (const frame of frames) {
          const data = frame.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n');
          if (data) onEvent(JSON.parse(data) as ConversationStreamEvent);
        }
        if (done) break;
      }
      if (buffer.trim()) {
        const data = buffer.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n');
        if (data) onEvent(JSON.parse(data) as ConversationStreamEvent);
      }
    },
  };
}
