/* eslint-disable react-hooks/exhaustive-deps */
import { ArrowLeftOutlined, DeleteOutlined, ExperimentOutlined, SendOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Empty, Modal, Space, Table, Tabs, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { OntologyResource, ResourceKind } from './ontology.types';

const { Paragraph, Text, Title } = Typography;
const titles: Record<ResourceKind, string> = { OBJECT_TYPE: '对象类型', LINK_TYPE: '关系类型', INTERFACE: 'Interface', ACTION: 'Action', FUNCTION: 'Function' };

export default function ResourceDetailPage({ accessToken, canBuild, id, isAdmin, kind, navigate }: { accessToken: string; canBuild: boolean; id: string; isAdmin: boolean; kind: ResourceKind; navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]); const [resource, setResource] = useState<OntologyResource>();
  useEffect(() => { void api.getResource(kind, id).then(setResource).catch((error: Error) => message.error(error.message)); }, [id, kind]);
  if (!resource) return <Card loading />;
  const currentResource = resource;
  async function propose() { const proposal = await api.createProposal({ title: `发布 ${currentResource.displayName}`, description: `审核 ${currentResource.apiName} v${currentResource.version}`, resourceIds: [currentResource.id] }); message.success('Proposal 已创建'); navigate(`/ontology/proposals/${proposal.id}`); }
  async function actionTest() { try { const result = await api.actionPreview(id, { value: '预览值' }); Modal.info({ title: 'Action Preview', width: 680, content: <pre className="json-preview">{JSON.stringify(result, null, 2)}</pre> }); } catch (error) { message.error((error as Error).message); } }
  async function functionTest() { try { const result = await api.functionTest(id, {}); Modal.info({ title: 'Function 测试', width: 680, content: <pre className="json-preview">{JSON.stringify(result, null, 2)}</pre> }); } catch (error) { message.error((error as Error).message); } }
  function remove() { Modal.confirm({ title: `永久删除“${currentResource.displayName}”？`, content: '版本、映射和直接依赖记录将一并删除。此操作不可撤销。', okButtonProps: { danger: true }, okText: '永久删除', onOk: async () => { await api.deleteResource(kind, id); message.success('本体资源已删除'); navigate(base); } }); }
  const base = `/ontology/${segment(kind)}`;
  const tabs = kind === 'OBJECT_TYPE' ? objectTabs(resource) : kind === 'ACTION' ? actionTabs(resource) : kind === 'FUNCTION' ? functionTabs(resource) : genericTabs(resource);
  return <div><Button icon={<ArrowLeftOutlined />} onClick={() => navigate(base)} type="text">返回{titles[kind]}</Button><div className="ontology-resource-header"><div><Space><Title level={2}>{resource.displayName}</Title><Tag color={resource.lifecycle === 'PUBLISHED' ? 'green' : 'blue'}>{resource.lifecycle}</Tag><Tag>{resource.maturity}</Tag>{resource.promoted && <Tag color="gold">核心对象</Tag>}</Space><Paragraph><Text code>{resource.apiName}</Text> · 物理键 <Text code>{resource.physicalKey}</Text> · v{resource.version}{resource.publishedRevision ? ` / revision ${resource.publishedRevision}` : ''}</Paragraph></div><Space>{kind === 'ACTION' && canBuild && <Button icon={<ExperimentOutlined />} onClick={() => void actionTest()}>Preview</Button>}{kind === 'FUNCTION' && canBuild && <Button icon={<ExperimentOutlined />} onClick={() => void functionTest()}>测试</Button>}{canBuild && resource.lifecycle === 'DRAFT' && <Button icon={<SendOutlined />} onClick={() => void propose()} type="primary">创建变更提议</Button>}{isAdmin && resource.lifecycle === 'PUBLISHED' && <Button onClick={() => message.info('回滚会创建新 Proposal，不修改历史')}>从此版本创建回滚</Button>}<Button danger icon={<DeleteOutlined />} onClick={remove}>永久删除</Button></Space></div><Tabs items={tabs} /></div>;
}

function objectTabs(resource: OntologyResource) { return [
  { key: 'overview', label: '概览', children: <Overview resource={resource} /> },
  { key: 'properties', label: `属性 (${resource.properties.length})`, children: <Table columns={[{ title: '显示 / API 名称', render: (_, row) => <>{row.displayName}<br /><Text code>{row.apiName}</Text></> }, { title: '类型', dataIndex: 'valueType' }, { title: '约束', render: (_, row) => <Space>{row.primaryKey && <Tag color="blue">主键</Tag>}{row.titleProperty && <Tag>标题</Tag>}{row.required && <Tag>必填</Tag>}{row.sensitive && <Tag color="red">敏感 / 不索引</Tag>}</Space> }, { title: '物理键', dataIndex: 'physicalKey', render: (value) => <Text code>{value}</Text> }, { title: '来源字段', dataIndex: 'sourceField', render: (value) => value || 'Action' }]} dataSource={resource.properties} pagination={false} rowKey="id" size="small" /> },
  { key: 'mapping', label: '数据映射', children: <Evidence title="单一写入所有权" lines={[`来源模式：${String(resource.definition.sourceMode ?? 'ACTION')}`, `主 Pipeline：${String(resource.definition.primaryPipelineId ?? '未绑定')}`, 'Projection Contract 使用稳定属性 ID 与精确 ontology revision。']} /> },
  { key: 'relations', label: '关系', children: <Empty description="关系从 Link Type 统一管理；不复制反向 edge" /> },
  { key: 'actions', label: 'Action', children: <Empty description="发布后的可用 Action 会显示在此处" /> },
  { key: 'projection', label: '索引与投影', children: <Evidence title="投影部署" lines={['HugeGraph: ontology_object / 稳定主键', 'OpenSearch: platform-ontology-objects alias', `当前状态：${resource.activeVersion ? 'HEALTHY' : 'NOT_DEPLOYED'}`]} /> },
  { key: 'usage', label: '使用位置', children: <Empty description="尚无应用、Agent 或 API Consumer 引用" /> },
  { key: 'versions', label: '版本', children: <Evidence title={`不可变版本 v${resource.version}`} lines={[`生命周期：${resource.lifecycle}`, `Revision：${resource.publishedRevision ?? '尚未发布'}`, '新版本不会自动替换下游精确绑定。']} /> },
  { key: 'settings', label: '设置', children: <Overview resource={resource} /> },
]; }

function actionTabs(resource: OntologyResource) { return ['概览', '参数', '规则', '提交条件', '审批', '预览与测试', '使用位置', '版本', '设置'].map((label, index) => ({ key: String(index), label, children: index === 0 ? <Overview resource={resource} /> : <pre className="json-preview">{JSON.stringify(resource.definition, null, 2)}</pre> })); }
function functionTabs(resource: OntologyResource) { return ['概览', '输入输出', '逻辑', '测试', '配置', '使用位置', '版本', '设置'].map((label, index) => ({ key: String(index), label, children: index === 0 ? <Overview resource={resource} /> : <pre className="json-preview">{JSON.stringify(resource.definition, null, 2)}</pre> })); }
function genericTabs(resource: OntologyResource) { return [{ key: 'overview', label: '概览', children: <Overview resource={resource} /> }, { key: 'definition', label: '定义', children: <pre className="json-preview">{JSON.stringify(resource.definition, null, 2)}</pre> }, { key: 'versions', label: '版本', children: <Evidence title="不可变版本" lines={[`v${resource.version}`, resource.lifecycle, `Revision ${resource.publishedRevision ?? '—'}`]} /> }, { key: 'settings', label: '设置', children: <Overview resource={resource} /> }]; }

function Overview({ resource }: { resource: OntologyResource }) { return <Card><Descriptions column={2} items={[{ key: 'description', label: '描述', children: resource.description || '暂无说明' }, { key: 'owner', label: '负责人', children: resource.ownerName }, { key: 'maturity', label: '成熟度', children: resource.maturity }, { key: 'tags', label: '标签', children: <Space>{resource.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}</Space> }, { key: 'created', label: '创建时间', children: new Date(resource.createdAt).toLocaleString() }, { key: 'updated', label: '更新时间', children: new Date(resource.updatedAt).toLocaleString() }]} /></Card>; }
function Evidence({ lines, title }: { lines: string[]; title: string }) { return <Alert description={<ul>{lines.map((line) => <li key={line}>{line}</li>)}</ul>} message={title} showIcon type="info" />; }
function segment(kind: ResourceKind) { return ({ OBJECT_TYPE: 'object-types', LINK_TYPE: 'link-types', INTERFACE: 'interfaces', ACTION: 'actions', FUNCTION: 'functions' } as const)[kind]; }
