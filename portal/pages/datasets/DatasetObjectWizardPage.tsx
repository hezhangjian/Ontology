import { ArrowLeftOutlined, DatabaseOutlined } from '@/shared/icons';
import { Button } from '@/shared/components/actions';
import { Steps, Table } from '@/shared/components/data';
import { Alert, Empty, message } from '@/shared/components/feedback';
import { Checkbox, Form, Input, Select } from '@/shared/components/forms';
import { Card, Space } from '@/shared/components/layout';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from '../../features/ontology/modeling/ontology.service';
import type { PropertyDraft } from '../../features/ontology/modeling/ontology.types';
import { datasetsApi } from './datasetsApi';
import { ApiProblem } from '../data-connections/services/dataConnections';
import type { Dataset, MappingPreview } from './types';

export default function DatasetObjectWizardPage({  navigate }: { navigate:(path:string)=>void }) {
  const dataApi = useMemo(() => datasetsApi(), []);
  const ontologyApi = useMemo(() => modelingApi(), []);
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [dataset, setDataset] = useState<Dataset>();
  const [mapping, setMapping] = useState<MappingPreview>();
  const [step, setStep] = useState(0);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();
  const [included, setIncluded] = useState<string[]>([]);

  useEffect(() => {
    void dataApi.list()
      .then((page) => setDatasets(page.items))
      .catch((error: unknown) => void message.error(problemMessage('加载 Dataset', error)));
  }, [dataApi]);
  const selectDataset = async (id: string) => {
    try {
      const value = await dataApi.get(id);
      setDataset(value);
      setIncluded([]);
      setMapping(undefined);
      form.resetFields();
    } catch (error) {
      message.error(problemMessage('读取 Dataset', error));
    }
  };
  const identity = Form.useWatch<string>('identityField', form);
  const title = Form.useWatch<string>('titleField', form);
  const preview = async () => {
    if (!dataset || !identity || !title) return void message.warning('请选择唯一标识和显示名称');
    try {
      setMapping(await dataApi.mappingPreview(dataset.id, identity, title));
      setStep(2);
    } catch (error) {
      message.error(problemMessage('生成对象映射预览', error));
    }
  };
  const create = async () => {
    if (!dataset || !mapping) return;
    const values = form.getFieldsValue(true);
    setBusy(true);
    let createdResourceId: string | undefined;
    let stage = '创建对象类型草稿';
    try {
      const properties: PropertyDraft[] = dataset.fields.filter((field) => included.includes(field.name)).map((field) => ({ actionWritable: field.name !== values.identityField, apiName: safeName(field.name), displayName: field.name, valueType: field.type === 'INTEGER' ? 'LONG' : field.type === 'DECIMAL' ? 'DECIMAL' : 'STRING', required: field.name === values.identityField, primaryKey: field.name === values.identityField, titleProperty: field.name === values.titleField, searchable: true, filterable: true, sortable: field.name === values.identityField, sensitive: false, sourceField: field.name }));
      const datasetMapping = Object.fromEntries(properties.map((property) => [String(property.sourceField), property.apiName]));
      const resource = await ontologyApi.createResource('OBJECT_TYPE', { datasetId: dataset.id, datasetMapping, id: values.id, description: values.description, displayName: values.displayName, maturity: 'ACTIVE', promoted: true, properties, sourceMode: 'DATASET', tags: ['Dataset'] });
      createdResourceId = resource.id;
      message.success(`已创建 ${resource.displayName}，正在导入预计 ${mapping.objectCount} 个对象`);
      navigate(`/ontology/object-types/${resource.id}`);
      return;
    } catch (error) {
      if (createdResourceId) {
        await ontologyApi.deleteResource('OBJECT_TYPE', createdResourceId).catch(() => undefined);
      }
      message.error(`${problemMessage(stage, error)}；本次创建产生的数据已自动回滚`);
    } finally { setBusy(false); }
  };
  const fieldOptions = dataset?.fields.map((field) => ({ label: `${field.name} · ${field.type}`, value: field.name })) ?? [];
  return <div className="dataset-object-wizard">
    <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/ontology/object-types')} type="text">返回对象类型</Button>
    <Typography.Title level={2}>从 Dataset 创建对象</Typography.Title>
    <Typography.Paragraph type="secondary">建模流程属于本体管理。Dataset 只作为可复用的数据来源。</Typography.Paragraph>
    <Steps current={step} items={['选择 Dataset', '定义对象', '确认身份和预览'].map((itemTitle) => ({ title: itemTitle }))} />
    <Form form={form} layout="vertical"><Card style={{ marginTop: 20 }}>
      {step === 0 && <>{datasets.length === 0 ? <Empty description="还没有可用 Dataset" image={<DatabaseOutlined style={{ fontSize: 56 }} />}><Button onClick={() => navigate('/data/pipelines/new')} type="primary">前往管道创建 Dataset</Button></Empty> : <><Form.Item label="数据来源" required><Select onChange={(value) => void selectDataset(value)} options={datasets.map((item) => ({ label: `${item.name} · ${item.rowCount.toLocaleString()} 行`, value: item.id }))} placeholder="选择一个 Dataset" showSearch /></Form.Item>{dataset && <Alert message={`已选择 ${dataset.name}`} description={`${dataset.rowCount.toLocaleString()} 行 · ${dataset.fields.length} 个字段`} showIcon type="success" />}<Button disabled={!dataset} onClick={() => setStep(1)} style={{ marginTop: 16 }} type="primary">下一步</Button></>}</>}
      {step === 1 && dataset && <><Form.Item label="对象名称" name="displayName" rules={[{ required: true }]}><Input onChange={(event) => { setIncluded(recommendedFields(event.target.value, dataset)); form.setFieldValue('id', recommendedId(event.target.value)); }} placeholder="例如：人员、部门、组长小组" /></Form.Item><Form.Item extra="用于接口路径和资源引用，发布后不可修改" label="对象类型 ID" name="id" rules={[{ required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_-]*$/, message: '以英文字母开头，只能包含字母、数字、下划线和连字符' }]}><Input placeholder="例如：employee" /></Form.Item><Form.Item label="说明" name="description"><Input.TextArea /></Form.Item><Form.Item label="唯一标识" name="identityField" rules={[{ required: true }]}><Select onChange={(value) => setIncluded((items) => Array.from(new Set([...items, value])))} options={fieldOptions} /></Form.Item><Form.Item label="显示名称" name="titleField" rules={[{ required: true }]}><Select onChange={(value) => setIncluded((items) => Array.from(new Set([...items, value])))} options={fieldOptions} /></Form.Item><Alert message="对象边界" description="只勾选描述这个对象自身的字段；月份、Token 用量等事实字段不应进入人员、部门或小组对象。" showIcon style={{ marginBottom: 16 }} type="info" /><Typography.Title level={4}>属性映射（已选择 {included.length} 个）</Typography.Title><Table dataSource={dataset.fields} pagination={false} rowKey="name" columns={[{ title: '包含', render: (_:unknown, row) => <Checkbox checked={included.includes(row.name)} disabled={row.name === identity || row.name === title} onChange={(event) => setIncluded((items) => event.target.checked ? [...items, row.name] : items.filter((item) => item !== row.name))} /> }, { title: 'Dataset 字段', dataIndex: 'name' }, { title: '属性名称', dataIndex: 'name' }, { title: '数据类型', dataIndex: 'type' }, { title: '样例', dataIndex: 'samples', render: (values:unknown[]) => values.map(String).join(' · ') }]} /><Space style={{ marginTop: 16 }}><Button onClick={() => setStep(0)}>上一步</Button><Button disabled={included.length === 0} onClick={() => void preview()} type="primary">预览对象</Button></Space></>}
      {step === 2 && mapping && <><Alert message={mapping.conflictCount === 0 && mapping.emptyIdentityCount === 0 ? '身份校验通过' : '需要返回 Pipeline 清理数据'} description={`Dataset ${mapping.sourceRows.toLocaleString()} 行 → 预计 ${mapping.objectCount.toLocaleString()} 个对象；重复行 ${mapping.duplicateCount.toLocaleString()}；空标识 ${mapping.emptyIdentityCount}；属性冲突 ${mapping.conflictCount}`} showIcon type={mapping.conflictCount === 0 && mapping.emptyIdentityCount === 0 ? 'success' : 'error'} /><Table dataSource={mapping.samples} pagination={false} rowKey={(row) => String(row[identity])} scroll={{ x: 'max-content' }} columns={included.slice(0, 8).map((field) => ({ title: field, dataIndex: field }))} /><Space style={{ marginTop: 16 }}><Button onClick={() => setStep(1)}>返回修改</Button><Button disabled={mapping.conflictCount > 0 || mapping.emptyIdentityCount > 0} loading={busy} onClick={() => void create()} type="primary">确认创建对象</Button></Space></>}
    </Card></Form>
  </div>;
}

function safeName(value:string) { const ascii = value.replace(/[^A-Za-z0-9_]/g, ''); return /^[A-Za-z]/.test(ascii) ? ascii : `field_${Array.from(value).map((char) => char.codePointAt(0)?.toString(16)).join('_')}`; }
function recommendedFields(name: string, dataset: Dataset) {
  const presets: Record<string, string[]> = {
    人员: ['employee_id', 'employee_name', 'employee_code', 'employee_type'],
    部门: ['level_1_org_name', 'level_2_org_name', 'level_3_org_name', 'level_4_org_name', 'level_5_org_name'],
    组长小组: ['leader_group_id', 'leader_group_name'],
    Token使用记录: ['month', 'employee_id', 'agent_type_name', 'total_tokens'],
  };
  const available = new Set(dataset.fields.map((field) => field.name));
  return (presets[name.replace(/\s/g, '')] ?? []).filter((field) => available.has(field));
}
function recommendedId(name: string) {
  return ({ 人员: 'employee', 部门: 'organization-unit', 组长小组: 'leader-group', Token使用记录: 'token-usage' } as Record<string, string>)[name.replace(/\s/g, '')] ?? '';
}

function problemMessage(stage: string, error: unknown) {
  if (error instanceof ApiProblem) {
    return `${stage}失败：${error.message}${error.requestId ? `（请求编号 ${error.requestId}）` : ''}`;
  }
  return `${stage}失败：${error instanceof Error ? error.message : '未知错误'}`;
}
