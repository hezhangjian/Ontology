/* eslint-disable react-hooks/exhaustive-deps, react-refresh/only-export-components */
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Empty, Input, Row, Space, Statistic, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { ModelingSummary } from './ontology.types';

const { Paragraph, Title } = Typography;

export default function OntologyOverviewPage({ accessToken, canBuild, navigate }: { accessToken: string; canBuild: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]);
  const [summary, setSummary] = useState<ModelingSummary>();
  const [problem, setProblem] = useState('');
  const load = () => api.summary().then(setSummary).catch((error: Error) => setProblem(error.message));
  useEffect(() => { void load(); }, []);
  return <div>
    <div className="page-title-row"><div><Space><Title level={2}>本体管理</Title>{summary && <Tag color="blue">Revision {summary.ontologyRevision}</Tag>}</Space><Paragraph>共享语义控制面：稳定资源、不可变版本、审核与可恢复发布。</Paragraph></div><Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>{canBuild && <Button icon={<PlusOutlined />} onClick={() => navigate('/ontology/object-types/new')} type="primary">新建对象类型</Button>}</Space></div>
    {problem && <Alert message={problem} showIcon type="error" />}
    <Input.Search enterButton={<SearchOutlined />} onSearch={(value) => navigate(`/ontology/search?q=${encodeURIComponent(value)}`)} placeholder="搜索显示名称、API 名称、属性、负责人或标签" size="large" />
    <Row className="ontology-stat-row" gutter={12}>{[
      ['当前 Revision', summary?.ontologyRevision ?? '—'], ['发布健康', summary?.publishHealth ?? '—'], ['未发布 Proposal', summary?.unpublishedProposals ?? 0],
      ['待审核', summary?.pendingReviews ?? 0], ['严重健康问题', summary?.criticalIssues ?? 0], ['Projection 异常', summary?.projectionFailures ?? 0],
    ].map(([title, value]) => <Col key={String(title)} span={4}><Card size="small"><Statistic title={title} value={value} /></Card></Col>)}</Row>
    <div className="ontology-overview-grid"><Card title="最近编辑资源"><Table columns={[{ title: '资源', dataIndex: 'displayName', render: (value, row) => <a onClick={() => navigate(resourcePath(row.kind, row.id))}>{value}</a> }, { title: '类型', dataIndex: 'kind', render: tagKind }, { title: '状态', dataIndex: 'lifecycle', render: (value) => <Tag>{value}</Tag> }, { title: '负责人', dataIndex: 'ownerName' }]} dataSource={summary?.recentResources} locale={{ emptyText: <Empty /> }} pagination={false} rowKey="id" size="small" /></Card><Card title="对象—关系模型"><div className="ontology-mini-graph"><div>员工</div><span>member_of →</span><div>部门</div></div><Paragraph type="secondary">关系只保存一个稳定 edge，支持双向遍历。</Paragraph></Card></div>
  </div>;
}

function tagKind(value: string) { return <Tag color="geekblue">{value}</Tag>; }
export function resourcePath(kind: string, id: string) { return `/ontology/${({ OBJECT_TYPE: 'object-types', LINK_TYPE: 'link-types', INTERFACE: 'interfaces', ACTION: 'actions', FUNCTION: 'functions' } as Record<string, string>)[kind]}/${id}`; }
