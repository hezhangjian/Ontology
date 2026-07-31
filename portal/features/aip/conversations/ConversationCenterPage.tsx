import { CheckCircleOutlined, DatabaseOutlined, PlusOutlined, RobotOutlined, SearchOutlined, SendOutlined, ToolOutlined, UserOutlined } from '@/shared/icons';
import { Avatar, Button, Tag } from '@/shared/components/actions';
import { Collapse, List } from '@/shared/components/data';
import { Alert, Empty, Spin, message } from '@/shared/components/feedback';
import { Input } from '@/shared/components/forms';
import { Space } from '@/shared/components/layout';
import { Typography } from '@/shared/components/typography';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { randomUuid } from '@/shared/utils/randomUuid';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { conversationApi, type Conversation, type ConversationMessage, type ConversationStreamEvent, type ToolTrace } from './conversationApi';

const { Paragraph, Text, Title } = Typography;

function formatToolData(value: unknown) {
  if (value === undefined || value === null) return '无数据';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function MarkdownContent({ content }: { content: string }) {
  return <div className="conversation-content markdown-body">
    <ReactMarkdown
      components={{
        a: ({ children, href }) => <a href={href} rel="noreferrer" target="_blank">{children}</a>,
      }}
      remarkPlugins={[remarkGfm]}
    >
      {content}
    </ReactMarkdown>
  </div>;
}

function ToolTraces({ onConfirm, traces }: { onConfirm: (trace: ToolTrace) => void; traces: ToolTrace[] }) {
  if (traces.length === 0) return null;
  return <div className="tool-traces">
    <Collapse
      expandIconPosition="end"
      items={traces.map((trace) => ({
        children: <div className="tool-trace-detail">
          <section>
            <Text className="tool-trace-section-title" type="secondary">调用参数</Text>
            <pre><code>{formatToolData(trace.arguments)}</code></pre>
          </section>
          <section>
            <Text className="tool-trace-section-title" type="secondary">返回结果</Text>
            <pre><code>{formatToolData(trace.result)}</code></pre>
          </section>
          {trace.mutationPreview && trace.result !== null && <Button icon={<CheckCircleOutlined />} onClick={() => onConfirm(trace)} type="primary">确认执行此变更</Button>}
        </div>,
        key: trace.id,
        label: <div className="tool-trace-label">
          <Space size={8}><ToolOutlined /><Text>{trace.name}</Text></Space>
          <Tag bordered={false} color={trace.result === null ? 'processing' : trace.mutationPreview ? 'gold' : 'success'}>{trace.result === null ? '调用中' : trace.mutationPreview ? '待确认' : '已完成'}</Tag>
        </div>,
      }))}
      size="small"
    />
  </div>;
}

export default function ConversationCenterPage() {
  const api = useMemo(() => conversationApi(), []);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [current, setCurrent] = useState<Conversation>();
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const messagesRef = useRef<HTMLDivElement>(null);
  const load = useCallback(async () => {
    const values = await api.list();
    setConversations(values);
    if (!current && values[0]) setCurrent(await api.get(values[0].id));
  }, [api, current]);
  useEffect(() => { void load().catch((error: Error) => message.error(error.message)); }, [load]);
  useEffect(() => {
    const container = messagesRef.current;
    if (container) container.scrollTo({ behavior: 'smooth', top: container.scrollHeight });
  }, [current, loading]);
  const create = async () => { const value = await api.create(); setConversations((items) => [value, ...items]); setCurrent(value); };
  const send = async () => {
    const content = input.trim();
    if (!content || !current) return;
    const conversationId = current.id;
    setInput(''); setLoading(true);
    setCurrent({ ...current, messages: [...current.messages, { id: randomUuid(), role: 'user', content, createdAt: new Date().toISOString(), tools: [] }] });
    try {
      await api.send(conversationId, content, (event: ConversationStreamEvent) => {
        if (event.type === 'user_message') return;
        if (event.type === 'error') throw new Error(event.data.detail);
        if (event.type === 'done') {
          setCurrent((value) => value?.id === conversationId ? event.data.conversation : value);
          setConversations((items) => [event.data.conversation, ...items.filter((item) => item.id !== conversationId)]);
          return;
        }
        setCurrent((value) => {
          if (!value || value.id !== conversationId) return value;
          if (event.type === 'assistant_start') {
            const assistant: ConversationMessage = { id: event.data.id, role: 'assistant', content: '', createdAt: event.data.createdAt, tools: [] };
            return { ...value, messages: [...value.messages, assistant] };
          }
          const messages = value.messages.map((item, index, all) => {
            if (index !== all.length - 1 || item.role !== 'assistant') return item;
            if (event.type === 'content_delta') return { ...item, content: item.content + event.data.delta };
            if (event.type === 'tool_start') return { ...item, tools: [...item.tools, event.data] };
            if (event.type === 'tool_result') return { ...item, tools: item.tools.map((trace) => trace.id === event.data.id ? event.data : trace) };
            return item;
          });
          return { ...value, messages };
        });
      });
    } catch (error) {
      const detail = (error as Error).message;
      const unavailable = detail.includes('API Key 未配置');
      setCurrent((value) => value?.id !== conversationId ? value : { ...value, messages: value.messages.map((item, index, all) => index === all.length - 1 && item.role === 'assistant' && !item.content ? { ...item, content: unavailable ? 'DeepSeek API Key 未配置。请在运行后端的环境中设置 `DEEP_SEEK_API_KEY`，然后重启后端。' : `请求失败：${detail}` } : item) });
      if (!unavailable) message.error(detail);
    }
    finally { setLoading(false); }
  };
  const confirm = async (trace: ToolTrace) => {
    if (!current || !trace.result || typeof trace.result !== 'object') return;
    try {
      const result = trace.result as Record<string, unknown>;
      if (trace.name === 'preview_action') await api.confirmAction(current.id, String(result.actionTypeId), String(result.token));
      else await api.confirmRule(current.id, result);
      message.success('变更已确认并提交；可继续询问执行状态');
    } catch (error) { message.error((error as Error).message); }
  };
  const startWith = async (content: string) => {
    const value = await api.create();
    setConversations((items) => [value, ...items]);
    setCurrent(value);
    setInput(content);
  };
  return <div className="conversation-center">
    <aside className="conversation-sidebar">
      <div className="conversation-sidebar-heading"><strong>对话</strong><span>{conversations.length}</span></div>
      <Button block icon={<PlusOutlined />} onClick={() => void create()} type="primary">新建对话</Button>
      <List dataSource={conversations} locale={{ emptyText: '暂无对话' }} renderItem={(item) => <List.Item className={current?.id === item.id ? 'conversation-item active' : 'conversation-item'} onClick={() => void api.get(item.id).then(setCurrent)}><List.Item.Meta description={new Date(item.updatedAt).toLocaleString()} title={item.title} /></List.Item>} />
    </aside>
    <section className="conversation-workspace">
      <div className="conversation-heading"><div><Title level={3}>对话中心</Title><Paragraph>Agent 使用本体目录、对象集合、关系、管道和 Action 工具进行有证据的分析。</Paragraph></div><Tag color="blue">DeepSeek</Tag></div>
      {!current ? <div className="conversation-welcome">
        <div className="conversation-welcome-mark"><RobotOutlined /></div>
        <Title level={3}>从业务问题开始</Title>
        <Paragraph>Agent 会结合当前本体、对象、数据集和管道给出可追溯的分析。</Paragraph>
        <div className="conversation-starters">
          <button onClick={() => void startWith('查找当前本体中的核心对象类型，并说明它们之间的关系。')} type="button"><SearchOutlined /><span><strong>理解当前本体</strong><small>梳理对象类型与关系</small></span></button>
          <button onClick={() => void startWith('检查当前数据集和管道状态，找出需要关注的问题。')} type="button"><DatabaseOutlined /><span><strong>检查数据资产</strong><small>查看数据集与管道状态</small></span></button>
          <button onClick={() => void startWith('根据当前平台数据，给出三个值得优先分析的业务问题。')} type="button"><ToolOutlined /><span><strong>发现分析机会</strong><small>从已有数据提出问题</small></span></button>
        </div>
      </div> : <>
        <div className="conversation-messages" ref={messagesRef}>
          {current.messages.length === 0 && <Alert message="可以尝试：分析产线1最近一周 OEE 下降原因，并给出可执行的解决方案。" showIcon type="info" />}
          {current.messages.map((item) => <div className={`conversation-message ${item.role}`} key={item.id}>
            <Avatar className="conversation-avatar" icon={item.role === 'assistant' ? <RobotOutlined /> : <UserOutlined />} />
            <div className="conversation-bubble">
              <div className="conversation-message-meta">
                <Text strong>{item.role === 'assistant' ? '平台 Agent' : '我'}</Text>
                <Text type="secondary">{new Date(item.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</Text>
              </div>
              <ToolTraces onConfirm={(trace) => void confirm(trace)} traces={item.tools} />
              {item.content ? <MarkdownContent content={item.content} /> : item.role === 'assistant' && <div className="conversation-thinking"><Spin size="small" /><Text type="secondary">正在分析…</Text></div>}
            </div>
          </div>)}
          {loading && <div className="conversation-loading"><Spin/><Text type="secondary">Agent 正在查询平台并分析证据…</Text></div>}
        </div>
        <div className="conversation-composer"><Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} onChange={(event) => setInput(event.target.value)} onPressEnter={(event) => { if (!event.shiftKey) { event.preventDefault(); void send(); } }} placeholder="输入问题，Shift+Enter 换行" value={input} /><Button disabled={!input.trim()} icon={<SendOutlined />} loading={loading} onClick={() => void send()} type="primary">发送</Button></div>
      </>}
    </section>
  </div>;
}
