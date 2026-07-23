/* eslint-disable react-hooks/exhaustive-deps */
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Empty, Input, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { ActionExecution, OntologyResource, ResourceKind } from './ontology.types';
import { resourcePath } from './OntologyOverviewPage';

const { Paragraph, Title } = Typography;
const labels: Record<ResourceKind, [string, string]> = {
  ACTION: ['Action', '用声明式规则定义允许发生的业务改变。'],
  FUNCTION: ['Function', '构建版本化、类型化、只读的可信计算。'],
  INTERFACE: ['Interface', '用显式属性槽位统一跨对象类型查询。'],
  LINK_TYPE: ['关系类型', '定义可从两侧遍历的稳定关系资源。'],
  OBJECT_TYPE: ['对象类型', '定义业务对象身份、属性、映射和投影策略。'],
};

export default function ResourceListPage({ accessToken, canBuild, kind, navigate }: { accessToken: string; canBuild: boolean; kind: ResourceKind; navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]);
  const [items, setItems] = useState<OntologyResource[]>([]);
  const [pendingActions, setPendingActions] = useState<ActionExecution[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const load = () => {
    setLoading(true);
    Promise.all([
      api.listResources(kind, search).then(setItems),
      kind === 'ACTION' ? api.actionExecutions('PENDING_APPROVAL').then(setPendingActions) : Promise.resolve(),
    ]).finally(() => setLoading(false));
  };
  const reviewAction = (execution: ActionExecution, decision: 'APPROVE' | 'REJECT') => Modal.confirm({
    title: decision === 'APPROVE' ? '批准此 Action？' : '拒绝此 Action？',
    content: `Execution ${execution.id} · Action v${execution.actionVersion}`,
    okButtonProps: { danger: decision === 'REJECT' },
    okText: decision === 'APPROVE' ? '批准并提交' : '拒绝',
    onOk: async () => {
      await api.actionReview(execution.id, decision, decision === 'APPROVE' ? '批准执行' : '拒绝执行');
      message.success(decision === 'APPROVE' ? 'Action 已批准并进入提交队列' : 'Action 已拒绝');
      load();
    },
  });
  const remove = (resource: OntologyResource) => Modal.confirm({ title: `永久删除“${resource.displayName}”？`, content: '版本、映射和直接依赖记录将一并删除。此操作不可撤销。', okButtonProps: { danger: true }, okText: '永久删除', onOk: async () => { await api.deleteResource(kind, resource.id); message.success('本体资源已删除'); load(); } });
  useEffect(() => { void load(); }, [kind, search]);
  const newPath = `/ontology/${resourcePath(kind, '').split('/')[2]}/new`;
  return <div>
    <div className="page-title-row">
      <div><Title level={2}>{labels[kind][0]}</Title><Paragraph>{labels[kind][1]}</Paragraph></div>
      <Space>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        {canBuild && kind === 'OBJECT_TYPE' && <Button onClick={() => navigate('/ontology/object-types/new/from-dataset')}>从 Dataset 创建</Button>}
        {canBuild && <Button icon={<PlusOutlined />} onClick={() => navigate(newPath)} type="primary">{kind === 'OBJECT_TYPE' ? '手工创建空对象' : `新建${labels[kind][0]}`}</Button>}
      </Space>
    </div>
    {kind === 'ACTION' && pendingActions.length > 0 && <Card title={`待审批 Action（${pendingActions.length}）`} style={{ marginBottom: 16 }}>
      <Table columns={[
        { title: 'Execution', dataIndex: 'id', render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
        { title: 'Action 版本', dataIndex: 'actionVersion', render: (value: number) => `v${value}` },
        { title: '提交人', dataIndex: 'submittedBy' },
        { title: '提交时间', dataIndex: 'submittedAt', render: (value: string) => new Date(value).toLocaleString() },
        { title: '操作', render: (_: unknown, row: ActionExecution) => <Space><Button onClick={() => reviewAction(row, 'APPROVE')} size="small" type="primary">批准</Button><Button danger onClick={() => reviewAction(row, 'REJECT')} size="small">拒绝</Button></Space> },
      ]} dataSource={pendingActions} pagination={false} rowKey="id" size="small" />
    </Card>}
    <div className="table-toolbar"><Input.Search allowClear onSearch={setSearch} placeholder={`搜索${labels[kind][0]}`} style={{ width: 340 }} /><Space><Tag>稳定 API 名</Tag><Tag>不可变版本</Tag></Space></div>
    <Table columns={[{ title: '显示 / API 名称', dataIndex: 'displayName', render: (value, row) => <><a onClick={() => navigate(resourcePath(row.kind, row.id))}>{value}</a><br /><Typography.Text code>{row.apiName}</Typography.Text></> }, { title: '成熟度', dataIndex: 'maturity', render: (value) => <Tag color={value === 'ACTIVE' ? 'green' : value === 'DEPRECATED' ? 'orange' : 'blue'}>{value}</Tag> }, ...(kind === 'OBJECT_TYPE' ? [{ title: '核心', dataIndex: 'promoted', render: (value: boolean) => value ? <Tag color="gold">核心</Tag> : '—' }, { title: '属性', dataIndex: 'properties', render: (value: unknown[]) => value.length }] : []), { title: '生命周期', dataIndex: 'lifecycle', render: (value) => <Tag>{value}</Tag> }, { title: '版本', dataIndex: 'version', render: (value, row) => `v${value}${row.publishedRevision ? ` · r${row.publishedRevision}` : ''}` }, { title: '负责人', dataIndex: 'ownerName' }, { title: '更新时间', dataIndex: 'updatedAt', render: (value) => new Date(value).toLocaleString() }, { title: '', width: 56, render: (_: unknown, row: OntologyResource) => <Button aria-label={`删除${row.displayName}`} danger icon={<DeleteOutlined />} onClick={(event) => { event.stopPropagation(); remove(row); }} type="text" /> }]} dataSource={items} loading={loading} locale={{ emptyText: <Empty description={`暂无${labels[kind][0]}`} /> }} onRow={(row) => ({ onClick: () => navigate(resourcePath(row.kind, row.id)) })} pagination={false} rowKey="id" size="small" />
  </div>;
}
