/* eslint-disable react-hooks/exhaustive-deps */
import { ArrowLeftOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Checkbox, Col, Form, Input, InputNumber, Row, Select, Table, Typography, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { OntologyResource, PropertyDraft, ResourceKind } from './ontology.types';
import { resourcePath } from './OntologyOverviewPage';

const { Text, Title } = Typography;
const kindTitle: Record<ResourceKind, string> = { OBJECT_TYPE: '对象类型', LINK_TYPE: '关系类型', INTERFACE: 'Interface', ACTION: 'Action', FUNCTION: 'Function' };

export default function ResourceEditorPage({ accessToken, displayName, kind, navigate, userId }: { accessToken: string; displayName: string; kind: ResourceKind; navigate: (path: string) => void; userId: string }) {
  if (kind === 'OBJECT_TYPE') return <ObjectTypeWizard accessToken={accessToken} displayName={displayName} navigate={navigate} userId={userId} />;
  return <GenericResourceEditor accessToken={accessToken} displayName={displayName} kind={kind} navigate={navigate} userId={userId} />;
}

function ObjectTypeWizard({ accessToken, displayName, navigate, userId }: { accessToken: string; displayName: string; navigate: (path: string) => void; userId: string }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  const generatedObjectName = useMemo(() => `ObjectType${Date.now().toString(36)}`, []);
  const [properties, setProperties] = useState<PropertyDraft[]>([
    property('id', '唯一标识', true, false),
    property('name', '名称', false, true),
  ]);
  async function save() {
    await form.validateFields(['displayName', 'apiName']);
    const values = form.getFieldsValue(true) as Record<string, string | boolean>; setSaving(true);
    try {
      const resource = await api.createResource('OBJECT_TYPE', { ...values, ownerId: userId, ownerName: displayName, sourceMode: 'PIPELINE', maturity: 'ACTIVE', promoted: true, tags: splitTags(typeof values.tags === 'string' ? values.tags : undefined), properties });
      const proposal = await api.createProposal({ title: `创建${resource.displayName}`, description: '简化模式自动校验并发布', resourceIds: [resource.id] });
      await api.validateProposal(proposal.id);
      await api.submitProposal(proposal.id);
      await api.reviewProposal(proposal.id, 'APPROVED', '简化模式自动通过');
      const deployment = await api.publishProposal(proposal.id);
      for (let attempt = 0; attempt < 30; attempt += 1) {
        const current = await api.deployment(deployment.id);
        if (current.status === 'SUCCEEDED') {
          message.success('本体已创建，可以用于数据映射和看板');
          navigate('/ontology/object-types');
          return;
        }
        if (current.status === 'FAILED') throw new Error(current.safeError || '本体创建失败');
        await new Promise((resolve) => window.setTimeout(resolve, 500));
      }
      throw new Error('本体仍在创建，请稍后刷新列表');
    } catch (error) { message.error((error as Error).message); } finally { setSaving(false); }
  }
  const update = (index: number, value: Partial<PropertyDraft>) => setProperties((items) => items.map((item, itemIndex) => itemIndex === index ? { ...item, ...value } : item));
  return <div className="ontology-editor-page"><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/ontology/object-types')} type="text">返回本体列表</Button><div className="ontology-editor-heading"><Title level={2}>创建本体</Title></div>
    <Form form={form} layout="vertical" initialValues={{ apiName: generatedObjectName }}>
      <Card className="ontology-wizard-card"><Row gutter={18}><Col span={12}><Form.Item label="本体名称" name="displayName" rules={[{ required: true }]}><Input onChange={(event) => form.setFieldValue('apiName', automaticApiName(event.target.value, generatedObjectName))} placeholder="例如：设备、订单、客户" /></Form.Item></Col><Form.Item hidden name="apiName"><Input /></Form.Item><Col span={12}><Form.Item label="说明（可选）" name="description"><Input /></Form.Item></Col></Row>
      <Alert message="技术名称、版本、校验和发布由系统自动处理" showIcon type="info" />
      <div className="tab-toolbar"><div><Title level={4}>字段</Title><Text type="secondary">勾选一个唯一标识和一个展示名称即可。</Text></div><Button icon={<PlusOutlined />} onClick={() => setProperties((items) => [...items, property(`field${items.length + 1}`, '', false, false)])}>添加字段</Button></div>
      <Table dataSource={properties} pagination={false} rowKey={(_, index) => String(index)} size="small" columns={[{ title: '字段名称', render: (_, row, index) => <Input onChange={(event) => update(index, { displayName: event.target.value, apiName: automaticApiName(event.target.value, `field${index + 1}`) })} value={row.displayName} /> }, { title: '类型', render: (_, row, index) => <Select onChange={(value) => update(index, { valueType: value })} options={[{value:'STRING',label:'文字'},{value:'LONG',label:'整数'},{value:'DECIMAL',label:'小数'},{value:'DATE',label:'日期'},{value:'DATETIME',label:'时间'}]} value={row.valueType} /> }, { title: '唯一标识', render: (_, row, index) => <Checkbox checked={row.primaryKey} onChange={(event) => setProperties((items) => items.map((item, i) => ({ ...item, primaryKey: i === index && event.target.checked, required: i === index && event.target.checked ? true : item.required })))} /> }, { title: '展示名称', render: (_, row, index) => <Checkbox checked={row.titleProperty} onChange={(event) => setProperties((items) => items.map((item, i) => ({ ...item, titleProperty: i === index && event.target.checked })))} /> }, { title: '', render: (_, __, index) => <Button aria-label="删除字段" danger icon={<DeleteOutlined />} onClick={() => setProperties((items) => items.filter((_, i) => i !== index))} type="text" /> }]} /></Card>
    </Form><div className="ontology-wizard-actions"><Button loading={saving} onClick={() => void save()} type="primary">创建本体</Button></div>
  </div>;
}

function GenericResourceEditor({ accessToken, displayName, kind, navigate, userId }: { accessToken: string; displayName: string; kind: ResourceKind; navigate: (path: string) => void; userId: string }) {
  const api = useMemo(() => modelingApi(accessToken), [accessToken]); const [form] = Form.useForm(); const [objects, setObjects] = useState<OntologyResource[]>([]); const [saving, setSaving] = useState(false);
  const generatedApiName = useMemo(() => `${kind.replace('_', '')}${Date.now().toString(36)}`, [kind]);
  useEffect(() => { void api.listResources('OBJECT_TYPE').then(setObjects); }, []);
  async function save() { const values = await form.validateFields(); setSaving(true); try { const target = values.targetObjectTypeId || values.leftObjectTypeId; const targetObject = objects.find((item) => item.id === target); const firstProperty = targetObject?.properties[0]; const body: Record<string, unknown> = { ...values, ownerId: userId, ownerName: displayName, maturity: 'ACTIVE', promoted: true, tags: [] };
      if (kind === 'LINK_TYPE') Object.assign(body, { sourceMode: 'FOREIGN_KEY', leftDisplayName: values.displayName, rightDisplayName: `反向${values.displayName}` });
      if (kind === 'INTERFACE') Object.assign(body, { slots: [{ apiName: 'name', displayName: '名称', valueType: 'STRING', required: true }], implementations: firstProperty ? [{ objectTypeId: target, slotApiName: 'name', propertyId: firstProperty.id }] : [] });
      if (kind === 'ACTION') Object.assign(body, { operation: values.operation, approvalPolicy: values.approvalPolicy, parameters: [{ apiName: 'value', displayName: '新值', valueType: 'STRING', required: true, sensitive: false }], rules: firstProperty ? [{ type: 'SET_PROPERTY', targetPropertyId: firstProperty.id, valueFrom: 'value' }] : [], submitCondition: {} });
      if (kind === 'FUNCTION') Object.assign(body, { outputType: values.outputType, queryDsl: { fromObjectTypeId: target, operation: 'FILTER', limit: 100 }, dependencyIds: target ? [target] : [], timeoutMs: values.timeoutMs, maxResults: 1000, cacheSeconds: 60, parameters: [] });
      const resource = await api.createResource(kind, body);
      const proposal = await api.createProposal({ title: `创建${resource.displayName}`, description: '简化模式自动校验并发布', resourceIds: [resource.id] });
      await api.validateProposal(proposal.id);
      await api.submitProposal(proposal.id);
      await api.reviewProposal(proposal.id, 'APPROVED', '简化模式自动通过');
      const deployment = await api.publishProposal(proposal.id);
      for (let attempt = 0; attempt < 30; attempt += 1) {
        const current = await api.deployment(deployment.id);
        if (current.status === 'SUCCEEDED') {
          message.success(`${kindTitle[kind]}已创建`);
          navigate(`/ontology/${resourcePath(kind, '').split('/')[2]}`);
          return;
        }
        if (current.status === 'FAILED') throw new Error(current.safeError || `${kindTitle[kind]}创建失败`);
        await new Promise((resolve) => window.setTimeout(resolve, 500));
      }
      throw new Error(`${kindTitle[kind]}仍在创建，请稍后刷新列表`);
    } catch (error) { message.error((error as Error).message); } finally { setSaving(false); } }
  const objectOptions = objects
    .filter((item) => item.lifecycle === 'PUBLISHED')
    .map((item) => ({ label: item.displayName, value: item.id }));
  return <div className="ontology-editor-page"><Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/ontology/${resourcePath(kind, '').split('/')[2]}`)} type="text">返回{kindTitle[kind]}</Button><div className="ontology-editor-heading"><Title level={2}>新建{kindTitle[kind]}</Title></div><Card><Form form={form} layout="vertical" initialValues={{ apiName: generatedApiName, cardinality: 'N:1', operation: 'UPDATE', approvalPolicy: 'NONE', outputType: 'TABLE', timeoutMs: 5000 }}><Row gutter={18}><Col span={12}><Form.Item label="名称" name="displayName" rules={[{ required: true }]}><Input onChange={(event) => form.setFieldValue('apiName', automaticApiName(event.target.value, generatedApiName))} /></Form.Item></Col><Form.Item hidden name="apiName"><Input /></Form.Item><Col span={12}><Form.Item label="说明（可选）" name="description"><Input /></Form.Item></Col>{kind === 'LINK_TYPE' && <><Col span={8}><Form.Item label="从哪个本体" name="leftObjectTypeId" rules={[{ required: true }]}><Select options={objectOptions} /></Form.Item></Col><Col span={8}><Form.Item label="关联到哪个本体" name="rightObjectTypeId" rules={[{ required: true }]}><Select options={objectOptions} /></Form.Item></Col><Col span={8}><Form.Item label="关联数量" name="cardinality"><Select options={[{value:'1:1',label:'一对一'},{value:'1:N',label:'一对多'},{value:'N:1',label:'多对一'},{value:'N:M',label:'多对多'}]} /></Form.Item></Col></>}{kind === 'INTERFACE' && <Col span={12}><Form.Item label="适用本体" name="leftObjectTypeId" rules={[{ required: true }]}><Select options={objectOptions} /></Form.Item></Col>}{kind === 'ACTION' && <><Col span={8}><Form.Item label="目标本体" name="targetObjectTypeId" rules={[{ required: true }]}><Select options={objectOptions} /></Form.Item></Col><Col span={8}><Form.Item label="操作类型" name="operation"><Select options={['CREATE', 'UPDATE', 'RETIRE', 'LINK', 'UNLINK'].map((value) => ({ value }))} /></Form.Item></Col><Col span={8}><Form.Item label="是否需要审批" name="approvalPolicy"><Select options={[{value:'NONE',label:'不需要'},{value:'ALWAYS',label:'需要'},{value:'CONDITIONAL',label:'按条件'}]} /></Form.Item></Col></>}{kind === 'FUNCTION' && <><Col span={8}><Form.Item label="使用哪个本体" name="targetObjectTypeId" rules={[{ required: true }]}><Select options={objectOptions} /></Form.Item></Col><Col span={8}><Form.Item label="输出形式" name="outputType"><Select options={['SCALAR', 'OBJECT_SET', 'TABLE', 'BOOLEAN'].map((value) => ({ value }))} /></Form.Item></Col><Col span={8}><Form.Item label="超时 (ms)" name="timeoutMs"><InputNumber max={30000} min={100} style={{ width: '100%' }} /></Form.Item></Col></>}</Row><Alert message="技术名称、校验和发布由系统自动处理" showIcon type="info" /><div className="edit-actions"><Button onClick={() => navigate(`/ontology/${resourcePath(kind, '').split('/')[2]}`)}>取消</Button><Button loading={saving} onClick={() => void save()} type="primary">创建{kindTitle[kind]}</Button></div></Form></Card></div>;
}

function splitTags(value?: string) { return value?.split(',').map((tag) => tag.trim()).filter(Boolean) ?? []; }

function automaticApiName(value: string, fallback: string) {
  const ascii = value.trim().replace(/[^A-Za-z0-9_]/g, '');
  return /^[A-Za-z]/.test(ascii) ? ascii : fallback;
}

function property(apiName: string, displayName: string, primaryKey: boolean, titleProperty: boolean, valueType = 'STRING'): PropertyDraft {
  return { apiName, displayName, valueType, required: primaryKey, primaryKey, titleProperty, searchable: true, filterable: true, sortable: primaryKey, sensitive: false };
}
