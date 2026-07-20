import { ArrowLeftOutlined, CloseCircleOutlined, RedoOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Descriptions, message, Progress, Result, Row, Space, Spin, Statistic, Steps, Table, Tag, Timeline, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { pipelinesApi } from './services/pipelines';
import type { PipelineRunDetail } from './types';

const { Paragraph, Text, Title } = Typography;
const terminal = ['CANCELLED', 'COMPLETED', 'DEGRADED', 'FAILED', 'STOPPED'];
const stages = ['SUBMITTED', 'COMPILING', 'QUEUED', 'STARTING', 'READING', 'TRANSFORMING', 'PUBLISHING', 'PROJECTING', 'COMPLETED'];

export default function PipelineRunPage({ accessToken, isAdmin, navigate, pipelineId, runId }: { accessToken: string; isAdmin: boolean; navigate: (path: string) => void; pipelineId: string; runId: string }) {
  const api = useMemo(() => pipelinesApi(accessToken), [accessToken]);
  const [detail, setDetail] = useState<PipelineRunDetail>();
  const [problem, setProblem] = useState<string>();
  const load = useCallback(async () => {
    try { setDetail(await api.runDetail(runId)); setProblem(undefined); }
    catch (cause) { setProblem(cause instanceof Error ? cause.message : '运行详情加载失败'); }
  }, [api, runId]);
  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);
  useEffect(() => {
    if (!detail || terminal.includes(detail.run.status)) return;
    const timer = window.setInterval(() => void load(), 2000);
    return () => window.clearInterval(timer);
  }, [detail, load]);
  async function action(label: string, request: () => Promise<unknown>) { try { await request(); message.success(label); await load(); } catch (cause) { message.error(cause instanceof Error ? cause.message : '操作失败'); } }
  if (problem) return <Result extra={<Button onClick={() => void load()}>重试</Button>} status="error" subTitle={problem} title="无法读取运行详情" />;
  if (!detail) return <div className="center-loading"><Spin /></div>;
  const { run } = detail;
  const stageIndex = Math.max(0, stages.indexOf(run.status));
  const projectionPercent = run.projectionStatus === 'COMPLETED' || run.status === 'COMPLETED' ? 100 : 0;
  return <div className="pipeline-run-page">
    <div className="page-title-row"><Space align="start"><Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/data/pipelines/${pipelineId}/edit`)} type="text" /><div><Space><Title level={2}>{run.pipelineName}</Title><Tag color={statusColor(run.status)}>{run.status}</Tag></Space><Paragraph>Run {run.id} · 不可变版本 v{run.pipelineVersion}</Paragraph></div></Space><Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>{!terminal.includes(run.status) && <Button danger icon={<CloseCircleOutlined />} onClick={() => void action('取消请求已提交；已投影对象不会撤销', () => api.cancel(run.id))}>取消</Button>}{['FAILED', 'CANCELLED', 'DEGRADED'].includes(run.status) && <Button icon={<RedoOutlined />} onClick={() => void action('已创建新的重试 Run', () => api.retry(run.id))}>重试</Button>}{isAdmin && run.status === 'DEGRADED' && <Button onClick={() => void action('DLQ 重放已创建', () => api.replayDlq(run.id))}>重放 DLQ</Button>}</Space></div>
    {run.status === 'PROJECTING' && <Alert description="只有 Projection batch ack、HugeGraph 和 OpenSearch 明确完成后，批任务才会进入 COMPLETED。" message="Flink 已发布完成，正在等待 Projection" showIcon type="info" />}
    {run.status === 'DEGRADED' && <Alert description="HugeGraph 中已成功的数据不会因 OpenSearch 失败而回滚；可从图数据重建搜索索引。" message="Projection 降级" showIcon type="warning" />}
    <Card><Steps current={stageIndex} items={stages.map((stage) => ({ description: stage === 'PROJECTING' ? run.projectionStatus : undefined, status: run.status === 'FAILED' && stage === stages[Math.max(0, stageIndex)] ? 'error' : undefined, title: stage }))} size="small" /></Card>
    <Row gutter={16}><Col span={6}><Card><Statistic title="读取" value={run.readCount} /></Card></Col><Col span={6}><Card><Statistic title="发布" value={run.writtenCount} /></Card></Col><Col span={6}><Card><Statistic title="拒绝" value={run.rejectedCount} /></Card></Col><Col span={6}><Card><Statistic suffix="%" title="Projection" value={projectionPercent} /></Card></Col></Row>
    <div className="pipeline-run-grid"><Card title="运行真相"><Descriptions column={1} size="small"><Descriptions.Item label="Flink Job ID">{run.flinkJobId ?? '等待提交'}</Descriptions.Item><Descriptions.Item label="Correlation ID">{run.correlationId}</Descriptions.Item><Descriptions.Item label="触发方式">{run.triggerType}</Descriptions.Item><Descriptions.Item label="Retry of">{run.retryOf ?? '—'}</Descriptions.Item><Descriptions.Item label="Savepoint">{run.savepointPath ?? '—'}</Descriptions.Item><Descriptions.Item label="请求人">{run.requestedByName}</Descriptions.Item></Descriptions>{run.status === 'RUNNING' && <Progress percent={99} status="active" />}</Card><Card title="事件时间线"><Timeline items={detail.events.map((event) => ({ children: <div><Space><Tag>{event.status ?? 'INFO'}</Tag><Text strong>{event.eventType}</Text></Space><Paragraph>{event.message}</Paragraph><Text type="secondary">{new Date(event.occurredAt).toLocaleString()}</Text></div> }))} /></Card></div>
    <Card title="脱敏运行日志"><Table columns={[{ dataIndex: 'occurredAt', title: '时间', render: (value) => value ? new Date(value).toLocaleString() : '—' }, { dataIndex: 'level', title: '级别', render: (value) => <Tag>{value}</Tag> }, { dataIndex: 'message', title: '消息' }]} dataSource={detail.logs.map((log, index) => ({ ...log, key: index }))} pagination={false} size="small" /></Card>
  </div>;
}

function statusColor(status: string) {
  if (status === 'COMPLETED' || status === 'RUNNING') return 'success';
  if (status === 'FAILED' || status === 'CANCELLED') return 'error';
  if (status === 'DEGRADED') return 'warning';
  return 'processing';
}
