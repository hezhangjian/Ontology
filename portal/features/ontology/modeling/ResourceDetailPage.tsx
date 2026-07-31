/* eslint-disable react-hooks/exhaustive-deps */
import { ArrowLeftOutlined, DeleteOutlined, EditOutlined, ExperimentOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Descriptions, Table } from '@/shared/components/data';
import { Alert, Empty, message } from '@/shared/components/feedback';
import { Form, Input, InputNumber, Select } from '@/shared/components/forms';
import { Card, Space } from '@/shared/components/layout';
import { Tabs } from '@/shared/components/navigation';
import { Modal } from '@/shared/components/overlays';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { ObjectTypeBackingView, OntologyResource, ResourceKind } from './ontology.types';

const { Paragraph, Text, Title } = Typography;
const titles: Record<ResourceKind, string> = {
  ACTION: 'Action',
  FUNCTION: 'Function',
  INTERFACE: 'Interface',
  LINK_TYPE: '关系类型',
  OBJECT_TYPE: '对象类型',
};

interface ParameterDefinition {
  apiName: string;
  displayName: string;
  valueType: string;
  required?: boolean;
  sensitive?: boolean;
  defaultValue?: unknown;
}

interface RuleDefinition {
  operation?: string;
  relationTypeId?: string;
  targetPropertyId?: string;
  valueFrom?: string;
  sourceObjectIdFrom?: string;
  targetObjectIdFrom?: string;
}

interface FunctionStep {
  id: string;
  operation: string;
  arguments?: Record<string, unknown>;
}

export default function ResourceDetailPage({
  id,
  kind,
  navigate,
}: {
  id: string;
  kind: ResourceKind;
  navigate: (path: string) => void;
}) {
  const api = useMemo(() => modelingApi(), []);
  const [resource, setResource] = useState<OntologyResource>();
  const [backing, setBacking] = useState<ObjectTypeBackingView>();
  const [target, setTarget] = useState<OntologyResource>();
  const [leftTarget, setLeftTarget] = useState<OntologyResource>();
  const [rightTarget, setRightTarget] = useState<OntologyResource>();
  const [relatedActions, setRelatedActions] = useState<OntologyResource[]>([]);
  const [relatedLinks, setRelatedLinks] = useState<OntologyResource[]>([]);
  const [editOpen, setEditOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [identityForm] = Form.useForm<{ description: string; displayName: string; id: string }>();
  useEffect(() => {
    void api
      .getResource(kind, id)
      .then(setResource)
      .catch((error: Error) => message.error(error.message));
  }, [id, kind]);
  useEffect(() => {
    if (kind === 'OBJECT_TYPE') {
      void api.objectTypeBacking(id).then(setBacking).catch(() => setBacking(undefined));
    }
  }, [id, kind]);
  useEffect(() => {
    const targetId = resource?.definition.targetObjectTypeId;
    if ((kind === 'ACTION' || kind === 'FUNCTION') && typeof targetId === 'string') {
      void api.listResources('OBJECT_TYPE')
        .then((items) => setTarget(items.find((item) => item.resourceId === targetId)))
        .catch(() => setTarget(undefined));
    }
  }, [kind, resource?.definition.targetObjectTypeId]);
  useEffect(() => {
    if (kind !== 'LINK_TYPE' || !resource) return;
    const leftId = String(resource.definition.leftObjectTypeId ?? '');
    const rightId = String(resource.definition.rightObjectTypeId ?? '');
    void api.listResources('OBJECT_TYPE')
      .then((items) => {
        setLeftTarget(items.find((item) => item.resourceId === leftId));
        setRightTarget(items.find((item) => item.resourceId === rightId));
      })
      .catch(() => {
        setLeftTarget(undefined);
        setRightTarget(undefined);
      });
  }, [kind, resource?.definition.leftObjectTypeId, resource?.definition.rightObjectTypeId]);
  useEffect(() => {
    if (kind !== 'OBJECT_TYPE' || !resource) return;
    void Promise.all([api.listResources('LINK_TYPE'), api.listResources('ACTION')])
      .then(([links, actions]) => {
        const resourceId = resource.resourceId;
        setRelatedLinks(links.filter((item) =>
          String(item.definition.leftObjectTypeId ?? '') === resourceId ||
          String(item.definition.rightObjectTypeId ?? '') === resourceId));
        setRelatedActions(actions.filter((item) => String(item.definition.targetObjectTypeId ?? '') === resourceId));
      })
      .catch(() => {
        setRelatedActions([]);
        setRelatedLinks([]);
      });
  }, [kind, resource?.resourceId]);
  if (!resource) return <Card loading />;

  const current = resource;
  const base = `/ontology/${segment(kind)}`;
  async function saveIdentity() {
    const values = await identityForm.validateFields();
    setEditing(true);
    try {
      const updated = await api.updateResource(kind, current.id, values);
      setResource(updated);
      setEditOpen(false);
      message.success('名称和 ID 已更新');
      if (updated.id !== current.id) navigate(`${base}/${updated.id}`);
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setEditing(false);
    }
  }
  function remove() {
    const published = current.lifecycle === 'PUBLISHED';
    Modal.confirm({
      content: '资源、实例投影和直接依赖记录将永久删除。',
      okButtonProps: { danger: true },
      okText: published ? '确认删除' : '永久删除',
      onOk: async () => {
        await api.deleteResource(kind, id);
        message.success(published ? '本体资源及实例投影已删除' : '本体草稿已删除');
        navigate(base);
      },
      title: `${published ? '删除' : '永久删除'}“${current.displayName}”？`,
    });
  }

  const tabs =
    kind === 'OBJECT_TYPE'
      ? objectTabs(resource, backing, relatedLinks, relatedActions, navigate)
      : kind === 'ACTION'
        ? actionTabs(resource, target, api)
        : kind === 'FUNCTION'
          ? functionTabs(resource, target, api)
          : kind === 'LINK_TYPE'
            ? linkTabs(resource, leftTarget, rightTarget)
            : kind === 'INTERFACE'
              ? interfaceTabs(resource)
              : genericTabs(resource);
  return (
    <div>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(base)} type="text">
        返回{titles[kind]}
      </Button>
      <div className="ontology-resource-header">
        <div>
          <Space>
            <Title level={2}>{resource.displayName}</Title>
            <Tag color={resource.lifecycle === 'PUBLISHED' ? 'green' : 'blue'}>
              {resource.lifecycle}
            </Tag>
            <Tag>{resource.maturity}</Tag>
            {resource.promoted && <Tag color="gold">核心对象</Tag>}
          </Space>
          <Paragraph><Text code>{resource.id}</Text></Paragraph>
          {resource.description && <Paragraph type="secondary">{resource.description}</Paragraph>}
        </div>
        <Space>
          <Button icon={<EditOutlined />} onClick={() => {
            identityForm.setFieldsValue({ description: resource.description, displayName: resource.displayName, id: resource.id });
            setEditOpen(true);
          }}>修改名称和 ID</Button>
          <Button danger icon={<DeleteOutlined />} onClick={remove}>
            {resource.lifecycle === 'PUBLISHED' ? '删除' : '永久删除'}
          </Button>
        </Space>
      </div>
      <Tabs items={tabs} />
      <Modal cancelText="取消" confirmLoading={editing} okText="保存" onCancel={() => setEditOpen(false)} onOk={() => void saveIdentity()} open={editOpen} title={`修改${titles[kind]}`}>
        <Form form={identityForm} layout="vertical">
          <Form.Item label="显示名称" name="displayName" rules={[{ required: true, message: '请输入显示名称' }, { max: 240 }]}><Input /></Form.Item>
          <Form.Item extra="修改后页面地址和 API 路径会使用新 ID，内部数据关联不会变化。" label={`${titles[kind]} ID`} name="id" rules={[{ required: true, message: '请输入 ID' }, { pattern: /^[A-Za-z][A-Za-z0-9_-]{0,159}$/, message: '以英文字母开头，只能包含字母、数字、下划线和连字符' }]}><Input /></Form.Item>
          <Form.Item label="说明" name="description"><Input.TextArea rows={4} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

function objectTabs(
  resource: OntologyResource,
  backing: ObjectTypeBackingView | undefined,
  relatedLinks: OntologyResource[],
  relatedActions: OntologyResource[],
  navigate: (path: string) => void,
) {
  return [
    { children: <Overview resource={resource} />, key: 'overview', label: '概览' },
    {
      children: (
        <Table
          columns={[
            {
              render: (_, row) => (
                <>
                  {row.displayName}
                  <br />
                  <Text code>{row.apiName}</Text>
                </>
              ),
              title: '显示 / API 名称',
            },
            { dataIndex: 'valueType', title: '类型' },
            {
              render: (_, row) => (
                <Space>
                  {row.primaryKey && <Tag color="blue">主键</Tag>}
                  {row.titleProperty && <Tag>标题</Tag>}
                  {row.required && <Tag>必填</Tag>}
                  {row.actionWritable && !row.primaryKey && <Tag color="green">Action 可写</Tag>}
                  {row.sensitive && <Tag color="red">敏感</Tag>}
                </Space>
              ),
              title: '约束',
            },
          ]}
          dataSource={resource.properties}
          pagination={false}
          rowKey="id"
          size="small"
        />
      ),
      key: 'properties',
      label: `属性 (${resource.properties.length})`,
    },
    {
      children: <BackingPanel backing={backing} navigate={navigate} resource={resource} />,
      key: 'mapping',
      label: '数据映射',
    },
    {
      children: <RelatedResourceTable items={relatedLinks} navigate={navigate} type="relation" />,
      key: 'relations',
      label: `关系 (${relatedLinks.length})`,
    },
    {
      children: <RelatedResourceTable items={relatedActions} navigate={navigate} type="action" />,
      key: 'actions',
      label: `Action (${relatedActions.length})`,
    },
    {
      children: (
        <Card>
          <Descriptions
            column={2}
            items={[
              {
                children: backing?.projectionStatus ?? (resource.lifecycle === 'PUBLISHED' ? '等待首次投影' : '未部署'),
                key: 'state',
                label: '对象数据同步状态',
              },
              { children: resource.lifecycle === 'PUBLISHED' ? '可用于对象搜索' : '发布后可用', key: 'search', label: '搜索状态' },
            ]}
          />
        </Card>
      ),
      key: 'projection',
      label: '运行状态',
    },
    { children: <Empty description="暂无应用、Pipeline 或 API 使用位置" />, key: 'usage', label: '使用位置' },
    { children: <Overview resource={resource} />, key: 'settings', label: '设置' },
  ];
}

function BackingPanel({
  backing,
  navigate,
  resource,
}: {
  backing?: ObjectTypeBackingView;
  navigate: (path: string) => void;
  resource: OntologyResource;
}) {
  if (!backing) return <Alert message="来源配置加载失败" showIcon type="error" />;
  if (backing.sourceMode === 'ACTION') {
    return (
      <Alert
        description="对象实例由 Action 创建和修改，不依赖数据管道。"
        message="Action 写入对象"
        showIcon
        type="info"
      />
    );
  }
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Alert
        description={`${backing.mappedPropertyCount}/${backing.propertyCount} 个属性已映射 · 最近运行 ${backing.lastRunStatus ?? '尚未运行'} · 投影 ${backing.projectionStatus ?? '尚无状态'}`}
        message={`映射状态：${backing.status}`}
        showIcon
        type={backing.status === 'HEALTHY' ? 'success' : backing.status === 'FAILED' ? 'error' : 'warning'}
      />
      <Card
        extra={
          backing.pipelineId ? (
            <Button onClick={() => navigate(`/data/pipelines/${backing.pipelineId}`)} type="link">
              打开 Pipeline
            </Button>
          ) : undefined
        }
        title="主数据管道"
      >
        <Descriptions
          column={2}
          items={[
            { children: backing.pipelineName ?? '未绑定', key: 'pipeline', label: 'Pipeline' },
            { children: backing.pipelineVersion ? `v${backing.pipelineVersion}` : '—', key: 'version', label: '版本' },
            { children: backing.pipelineLifecycle ?? '—', key: 'lifecycle', label: '生命周期' },
            { children: backing.lastRunAt ? new Date(backing.lastRunAt).toLocaleString() : '—', key: 'run', label: '最近运行' },
          ]}
        />
      </Card>
      <Table
        columns={[
          {
            render: (_, row) => (
              <>
                {row.propertyDisplayName}
                <br />
                <Text code>{row.propertyApiName}</Text>
              </>
            ),
            title: '对象属性',
          },
          { dataIndex: 'sourceField', render: (value) => <Text code>{value}</Text>, title: '来源字段' },
          { dataIndex: 'sinkNodeId', title: '输出节点' },
          {
            render: (_, row) => resource.properties.find((property) => property.id === row.propertyId)?.valueType ?? '—',
            title: '目标类型',
          },
        ]}
        dataSource={backing.mappings}
        locale={{ emptyText: <Empty description="主 Pipeline 尚未发布属性映射" /> }}
        pagination={false}
        rowKey="propertyId"
        size="small"
      />
    </Space>
  );
}

function actionTabs(
  resource: OntologyResource,
  target: OntologyResource | undefined,
  api: ReturnType<typeof modelingApi>,
) {
  const parameters = list<ParameterDefinition>(resource.definition.parameters);
  const rules = list<RuleDefinition>(resource.definition.rules);
  return [
    {
      children: (
        <Card>
          <Descriptions
            column={2}
            items={[
              { children: operationLabel(resource.definition.operation), key: 'operation', label: '业务操作' },
              { children: target?.displayName ?? String(resource.definition.targetObjectTypeId ?? '—'), key: 'target', label: '目标对象类型' },
              { children: parameters.length, key: 'parameters', label: '参数数' },
              { children: rules.length, key: 'rules', label: '规则数' },
            ]}
          />
        </Card>
      ),
      key: 'overview',
      label: '概览',
    },
    { children: <ParameterTable parameters={parameters} />, key: 'parameters', label: `参数 (${parameters.length})` },
    {
      children: (
        <Table
          columns={[
            { dataIndex: 'operation', render: operationLabel, title: '操作' },
            {
              render: (_, row) =>
                target?.properties.find((property) => property.id === row.targetPropertyId)?.displayName ??
                row.relationTypeId ??
                '—',
              title: '目标',
            },
            { dataIndex: 'valueFrom', render: (value) => value ? <Text code>{value}</Text> : '—', title: '值来源参数' },
          ]}
          dataSource={rules}
          locale={{ emptyText: <Empty description="该操作不需要属性或关系规则" /> }}
          pagination={false}
          rowKey={(_, index) => String(index)}
          size="small"
        />
      ),
      key: 'rules',
      label: `规则 (${rules.length})`,
    },
    {
      children: (
        <Alert
          description={hasDefinition(resource.definition.submitCondition) ? '已配置参数或对象条件；Preview 时会执行校验。' : '没有额外提交条件。'}
          message="提交条件"
          showIcon
          type="info"
        />
      ),
      key: 'criteria',
      label: '提交条件',
    },
    {
      children: <ActionTestPanel api={api} parameters={parameters} resource={resource} />,
      key: 'test',
      label: '预览与测试',
    },
    { children: <Empty description="暂无对象视图、应用或 Agent 使用记录" />, key: 'usage', label: '使用位置' },
    { children: <Overview resource={resource} />, key: 'settings', label: '设置' },
  ];
}

function ActionTestPanel({
  api,
  parameters,
  resource,
}: {
  api: ReturnType<typeof modelingApi>;
  parameters: ParameterDefinition[];
  resource: OntologyResource;
}) {
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const operation = String(resource.definition.operation ?? 'UPDATE');
  async function preview() {
    const values = await form.validateFields();
    const { objectId, ...parameterValues } = values;
    setBusy(true);
    try {
      const result = await api.actionPreview(resource.id, parameterValues, objectId);
      const diff = list<Record<string, unknown>>(result.visibleDiff);
      Modal.info({
        content: (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Alert message="预览完成，可直接执行" showIcon type="success" />
            <Table
              columns={[
                { dataIndex: 'operation', title: '变化' },
                { dataIndex: 'property', title: '属性' },
                { dataIndex: 'before', render: displayValue, title: '修改前' },
                { dataIndex: 'after', render: displayValue, title: '修改后' },
                { dataIndex: 'targetId', title: '目标 ID' },
              ]}
              dataSource={diff}
              pagination={false}
              rowKey={(_, index) => String(index)}
              size="small"
            />
          </Space>
        ),
        title: 'Action Preview',
        width: 820,
      });
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card title="生成真实 Preview">
      <Form form={form} layout="vertical">
        {operation !== 'CREATE' && (
          <Form.Item label="目标对象 ID" name="objectId" rules={[{ required: true }]}>
            <Input placeholder="输入要操作的对象主键" />
          </Form.Item>
        )}
        {parameters.map((parameter) => (
          <ParameterInput key={parameter.apiName} parameter={parameter} />
        ))}
        <Button icon={<ExperimentOutlined />} loading={busy} onClick={() => void preview()} type="primary">
          生成 Preview
        </Button>
      </Form>
    </Card>
  );
}

function ParameterInput({ parameter }: { parameter: ParameterDefinition }) {
  const rules = parameter.required ? [{ required: true, message: `请输入${parameter.displayName}` }] : [];
  if (parameter.valueType === 'BOOLEAN') {
    return (
      <Form.Item label={parameter.displayName} name={parameter.apiName} rules={rules}>
        <Select options={[{ label: '是', value: true }, { label: '否', value: false }]} />
      </Form.Item>
    );
  }
  if (['DECIMAL', 'INTEGER', 'LONG'].includes(parameter.valueType)) {
    return (
      <Form.Item label={parameter.displayName} name={parameter.apiName} rules={rules}>
        <InputNumber style={{ width: '100%' }} />
      </Form.Item>
    );
  }
  return (
    <Form.Item label={`${parameter.displayName} · ${parameter.valueType}`} name={parameter.apiName} rules={rules}>
      <Input type={parameter.sensitive ? 'password' : 'text'} />
    </Form.Item>
  );
}

function ParameterTable({ parameters }: { parameters: ParameterDefinition[] }) {
  return (
    <Table
      columns={[
        {
          render: (_, row) => (
            <>
              {row.displayName}
              <br />
              <Text code>{row.apiName}</Text>
            </>
          ),
          title: '参数',
        },
        { dataIndex: 'valueType', title: '类型' },
        { dataIndex: 'required', render: (value) => (value ? <Tag color="blue">必填</Tag> : '可选'), title: '约束' },
        { dataIndex: 'defaultValue', render: displayValue, title: '默认值' },
      ]}
      dataSource={parameters}
      locale={{ emptyText: <Empty description="没有参数" /> }}
      pagination={false}
      rowKey="apiName"
      size="small"
    />
  );
}

function functionTabs(
  resource: OntologyResource,
  target: OntologyResource | undefined,
  api: ReturnType<typeof modelingApi>,
) {
  const parameters = list<ParameterDefinition>(resource.definition.parameters);
  const dsl = asRecord(resource.definition.queryDsl);
  const steps = list<FunctionStep>(dsl.steps);
  return [
    {
      children: (
        <Card>
          <Descriptions
            column={2}
            items={[
              { children: parameters.length, key: 'inputs', label: '输入参数' },
              { children: String(resource.definition.outputType ?? '—'), key: 'output', label: '输出类型' },
              { children: target?.displayName ?? '多对象类型', key: 'target', label: '主要对象类型' },
              { children: steps.length, key: 'steps', label: '逻辑步骤' },
            ]}
          />
        </Card>
      ),
      key: 'signature',
      label: '输入输出',
    },
    {
      children: (
        <Table
          columns={[
            { dataIndex: 'id', render: (value) => <Text code>{value}</Text>, title: '步骤' },
            { dataIndex: 'operation', title: '操作' },
            {
              render: (_, row) => {
                const args = asRecord(row.arguments);
                return String(args.objectTypeId ?? args.functionId ?? '使用上一步结果');
              },
              title: '数据来源',
            },
          ]}
          dataSource={steps}
          pagination={false}
          rowKey="id"
          size="small"
        />
      ),
      key: 'logic',
      label: `逻辑 (${steps.length})`,
    },
    {
      children: <FunctionTestPanel api={api} parameters={parameters} resource={resource} />,
      key: 'test',
      label: '测试',
    },
    {
      children: (
        <Card>
          <Descriptions
            column={2}
            items={[
              { children: `${String(resource.definition.timeoutMs ?? 5000)} ms`, key: 'timeout', label: '超时' },
              { children: String(resource.definition.maxResults ?? 1000), key: 'results', label: '最大结果数' },
              { children: `${String(resource.definition.cacheSeconds ?? 0)} 秒`, key: 'cache', label: '缓存' },
              { children: '只读本体 DSL', key: 'runtime', label: '运行时' },
            ]}
          />
        </Card>
      ),
      key: 'configuration',
      label: '配置',
    },
    { children: <Empty description="暂无看板、Action 或 Agent 使用记录" />, key: 'usage', label: '使用位置' },
    { children: <Overview resource={resource} />, key: 'settings', label: '设置' },
  ];
}

function FunctionTestPanel({
  api,
  parameters,
  resource,
}: {
  api: ReturnType<typeof modelingApi>;
  parameters: ParameterDefinition[];
  resource: OntologyResource;
}) {
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<Record<string, unknown>>();
  async function execute() {
    const inputs = await form.validateFields();
    setBusy(true);
    try {
      setResult(await api.functionTest(resource.id, inputs));
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const value = result?.result;
  const items = asRecord(value).items;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Card title="测试输入">
        <Form form={form} layout="vertical">
          {parameters.map((parameter) => (
            <ParameterInput key={parameter.apiName} parameter={parameter} />
          ))}
          <Button icon={<ExperimentOutlined />} loading={busy} onClick={() => void execute()} type="primary">
            执行 Function
          </Button>
        </Form>
      </Card>
      {result && (
        <Card title={`执行结果 · ${String(result.durationMs ?? '—')} ms${result.cacheHit ? ' · 缓存命中' : ''}`}>
          {Array.isArray(items) ? (
            <Table
              columns={objectColumns(items)}
              dataSource={items}
              pagination={false}
              rowKey={(_, index) => String(index)}
              size="small"
            />
          ) : (
            <Descriptions items={[{ children: displayValue(value), key: 'result', label: '结果' }]} />
          )}
        </Card>
      )}
    </Space>
  );
}

function RelatedResourceTable({
  items,
  navigate,
  type,
}: {
  items: OntologyResource[];
  navigate: (path: string) => void;
  type: 'action' | 'relation';
}) {
  if (items.length === 0) {
    return <Empty description={type === 'relation' ? '暂无关联此对象类型的关系' : '暂无适用于此对象类型的 Action'} />;
  }
  return (
    <Table
      columns={[
        {
          render: (_: unknown, row: OntologyResource) => (
            <>
              <a onClick={() => navigate(`/ontology/${segment(row.kind)}/${row.id}`)}>{row.displayName}</a>
              <br />
              <Text code>{row.id}</Text>
            </>
          ),
          title: type === 'relation' ? '关系' : 'Action',
        },
        ...(type === 'relation'
          ? [
              {
                render: (_: unknown, row: OntologyResource) =>
                  `${String(row.definition.leftDisplayName ?? '起点')} → ${String(row.definition.rightDisplayName ?? '终点')}`,
                title: '业务方向',
              },
              { dataIndex: 'definition', render: (definition: Record<string, unknown>) => String(definition.cardinality ?? '—'), title: '基数' },
              { dataIndex: 'definition', render: (definition: Record<string, unknown>) => sourceModeLabel(definition.sourceMode), title: '数据来源' },
            ]
          : [
              { dataIndex: 'definition', render: (definition: Record<string, unknown>) => operationLabel(definition.operation), title: '业务操作' },
            ]),
        { dataIndex: 'lifecycle', render: (value: string) => <Tag>{value}</Tag>, title: '状态' },
      ]}
      dataSource={items}
      pagination={false}
      rowKey="resourceId"
      size="small"
    />
  );
}

function linkTabs(resource: OntologyResource, left: OntologyResource | undefined, right: OntologyResource | undefined) {
  const definition = resource.definition;
  return [
    {
      children: (
        <Card>
          <Descriptions
            column={2}
            items={[
              { children: left?.displayName ?? '对象类型不可用', key: 'left', label: '起点对象类型' },
              { children: right?.displayName ?? '对象类型不可用', key: 'right', label: '终点对象类型' },
              { children: String(definition.leftDisplayName ?? '起点'), key: 'leftName', label: '从起点看到的名称' },
              { children: String(definition.rightDisplayName ?? '终点'), key: 'rightName', label: '从终点看到的名称' },
              { children: String(definition.cardinality ?? '—'), key: 'cardinality', label: '关系基数' },
              { children: sourceModeLabel(definition.sourceMode), key: 'source', label: '关系来源' },
            ]}
          />
        </Card>
      ),
      key: 'definition',
      label: '关系定义',
    },
    {
      children: (
        <Alert
          description={definition.sourceMode === 'FOREIGN_KEY'
            ? '平台根据对象属性中的外键值自动建立和更新关系。'
            : '关系实例由已发布 Pipeline 的输出生成。'}
          message={sourceModeLabel(definition.sourceMode)}
          showIcon
          type="info"
        />
      ),
      key: 'mapping',
      label: '生成方式',
    },
    { children: <Overview resource={resource} />, key: 'settings', label: '基本信息' },
  ];
}

function interfaceTabs(resource: OntologyResource) {
  const slots = list<Record<string, unknown>>(resource.definition.slots);
  const implementations = list<Record<string, unknown>>(resource.definition.implementations);
  return [
    { children: <Overview resource={resource} />, key: 'overview', label: '概览' },
    {
      children: (
        <Table
          columns={[
            { dataIndex: 'displayName', title: '属性名称' },
            { dataIndex: 'apiName', render: (value) => <Text code>{value}</Text>, title: '属性 ID' },
            { dataIndex: 'valueType', title: '数据类型' },
            { dataIndex: 'required', render: (value) => value ? <Tag color="blue">必填</Tag> : '可选', title: '约束' },
          ]}
          dataSource={slots}
          locale={{ emptyText: '该接口还没有定义统一属性' }}
          pagination={false}
          rowKey={(_, index) => String(index)}
          size="small"
        />
      ),
      key: 'slots',
      label: `统一属性 (${slots.length})`,
    },
    {
      children: implementations.length > 0
        ? <Alert description={`已有 ${implementations.length} 个对象类型实现该接口，字段映射会在对象类型详情中展示。`} message="接口实现" showIcon type="success" />
        : <Empty description="还没有对象类型实现该接口" />,
      key: 'implementations',
      label: `对象类型 (${implementations.length})`,
    },
    { children: <Overview resource={resource} />, key: 'settings', label: '基本信息' },
  ];
}

function genericTabs(resource: OntologyResource) {
  const definition = asRecord(resource.definition);
  return [
    { children: <Overview resource={resource} />, key: 'overview', label: '概览' },
    {
      children: (
        <Card>
          <Descriptions
            column={2}
            items={Object.entries(definition)
              .filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value))
              .map(([key, value]) => ({ children: displayValue(value), key, label: key }))}
          />
        </Card>
      ),
      key: 'definition',
      label: '定义',
    },
    { children: <Overview resource={resource} />, key: 'settings', label: '设置' },
  ];
}

function operationLabel(value: unknown) {
  return {
    CREATE: '创建对象',
    DELETE: '删除对象',
    LINK: '建立关系',
    UNLINK: '解除关系',
    UPDATE: '修改对象',
  }[String(value)] ?? String(value ?? '—');
}


function sourceModeLabel(value: unknown) {
  return {
    FOREIGN_KEY: '根据对象外键自动生成',
    MANUAL: '由 Action 维护',
    PIPELINE: '由 Pipeline 生成',
  }[String(value)] ?? String(value ?? '—');
}

function Overview({ resource }: { resource: OntologyResource }) {
  return (
    <Card>
      <Descriptions
        column={2}
        items={[
          { children: resource.description || '暂无说明', key: 'description', label: '描述' },
          { children: resource.maturity, key: 'maturity', label: '成熟度' },
          {
            children: <Space>{resource.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}</Space>,
            key: 'tags',
            label: '标签',
          },
          { children: new Date(resource.createdAt).toLocaleString(), key: 'created', label: '创建时间' },
          { children: new Date(resource.updatedAt).toLocaleString(), key: 'updated', label: '更新时间' },
        ]}
      />
    </Card>
  );
}

function list<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : [];
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function hasDefinition(value: unknown) {
  return Object.keys(asRecord(value)).length > 0;
}

function displayValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.map(String).join('、');
  if (typeof value === 'object') return '结构化结果';
  return String(value);
}

function objectColumns(rows: unknown[]) {
  const first = asRecord(rows[0]);
  return Object.keys(first)
    .filter((key) => key !== 'properties')
    .slice(0, 6)
    .map((key) => ({ dataIndex: key, render: displayValue, title: key }));
}

function segment(kind: ResourceKind) {
  return {
    ACTION: 'actions',
    FUNCTION: 'functions',
    INTERFACE: 'interfaces',
    LINK_TYPE: 'link-types',
    OBJECT_TYPE: 'object-types',
  }[kind];
}
