/* eslint-disable react-hooks/exhaustive-deps */
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  PlusOutlined,
} from "@/shared/icons";
import { Button } from "@/shared/components/actions";
import { Table } from "@/shared/components/data";
import { Alert, message } from "@/shared/components/feedback";
import {
  Checkbox,
  Form,
  Input,
  InputNumber,
  Select,
} from "@/shared/components/forms";
import { Card, Col, Row } from "@/shared/components/layout";
import { Typography } from "@/shared/components/typography";
import { useEffect, useMemo, useState } from "react";
import { modelingApi } from "./ontology.service";
import type {
  OntologyResource,
  PropertyDraft,
  ResourceKind,
} from "./ontology.types";
import { resourcePath } from "./OntologyOverviewPage";

const { Text, Title } = Typography;
const kindTitle: Record<ResourceKind, string> = {
  OBJECT_TYPE: "对象类型",
  LINK_TYPE: "关系类型",
  INTERFACE: "Interface",
  ACTION: "Action",
  FUNCTION: "Function",
};

export default function ResourceEditorPage({
     kind,
  navigate,
}: {
    kind: ResourceKind;
  navigate: (path: string) => void;
}) {
  if (kind === "OBJECT_TYPE")
    return (
      <ObjectTypeWizard
                navigate={navigate}
      />
    );
  return (
    <GenericResourceEditor
            kind={kind}
      navigate={navigate}
    />
  );
}

function ObjectTypeWizard({
     navigate,
}: {
    navigate: (path: string) => void;
}) {
  const api = useMemo(() => modelingApi(), []);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  const generatedObjectName = useMemo(
    () => `ObjectType${Date.now().toString(36)}`,
    [],
  );
  const [properties, setProperties] = useState<PropertyDraft[]>([
    property("id", "唯一标识", true, false),
    property("name", "名称", false, true),
  ]);
  async function save() {
    await form.validateFields(["displayName", "id"]);
    const values = form.getFieldsValue(true) as Record<
      string,
      string | boolean
    >;
    setSaving(true);
    try {
      const resource = await api.createResource("OBJECT_TYPE", {
        ...values,
        sourceMode: "PIPELINE",
        maturity: "ACTIVE",
        promoted: true,
        tags: splitTags(
          typeof values.tags === "string" ? values.tags : undefined,
        ),
        properties,
      });
      message.success(`${resource.displayName}已创建，可以直接使用`);
      navigate("/ontology/object-types");
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setSaving(false);
    }
  }
  const update = (index: number, value: Partial<PropertyDraft>) =>
    setProperties((items) =>
      items.map((item, itemIndex) =>
        itemIndex === index ? { ...item, ...value } : item,
      ),
    );
  return (
    <div className="ontology-editor-page">
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate("/ontology/object-types")}
        type="text"
      >
        返回对象类型
      </Button>
      <div className="ontology-editor-heading">
        <Title level={2}>创建对象类型</Title>
      </div>
      <Form
        form={form}
        layout="vertical"
        initialValues={{ id: generatedObjectName }}
      >
        <Card className="ontology-wizard-card">
          <Row gutter={18}>
            <Col span={12}>
              <Form.Item
                label="对象类型名称"
                name="displayName"
                rules={[{ required: true }]}
              >
                <Input
                  onChange={(event) =>
                    form.setFieldValue(
                      "id",
                      automaticApiName(event.target.value, generatedObjectName),
                    )
                  }
                  placeholder="例如：人员、小组、设备"
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                extra="用于接口路径和资源引用，创建后不可修改"
                label="对象类型 ID"
                name="id"
                rules={[
                  { required: true },
                  {
                    pattern: /^[A-Za-z][A-Za-z0-9_-]{0,159}$/,
                    message: "以英文字母开头，只能包含字母、数字、下划线和连字符",
                  },
                ]}
              >
                <Input placeholder="例如：employee" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="说明（可选）" name="description">
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Alert
            message="技术名称、校验、ETag 和发布由系统自动处理"
            showIcon
            type="info"
          />
          <div className="tab-toolbar">
            <div>
              <Title level={4}>字段</Title>
              <Text type="secondary">勾选一个唯一标识和一个展示名称即可。</Text>
            </div>
            <Button
              icon={<PlusOutlined />}
              onClick={() =>
                setProperties((items) => [
                  ...items,
                  property(`field${items.length + 1}`, "", false, false),
                ])
              }
            >
              添加字段
            </Button>
          </div>
          <Table
            dataSource={properties}
            pagination={false}
            rowKey={(_, index) => String(index)}
            size="small"
            columns={[
              {
                title: "字段名称",
                render: (_, row, index) => (
                  <Input
                    onChange={(event) =>
                      update(index, {
                        displayName: event.target.value,
                        apiName: automaticApiName(
                          event.target.value,
                          `field${index + 1}`,
                        ),
                      })
                    }
                    value={row.displayName}
                  />
                ),
              },
              {
                title: "类型",
                render: (_, row, index) => (
                  <Select
                    onChange={(value) => update(index, { valueType: value })}
                    options={[
                      { value: "STRING", label: "文字" },
                      { value: "LONG", label: "整数" },
                      { value: "DECIMAL", label: "小数" },
                      { value: "DATE", label: "日期" },
                      { value: "DATETIME", label: "时间" },
                    ]}
                    value={row.valueType}
                  />
                ),
              },
              {
                title: "唯一标识",
                render: (_, row, index) => (
                  <Checkbox
                    checked={row.primaryKey}
                    onChange={(event) =>
                      setProperties((items) =>
                        items.map((item, i) => ({
                          ...item,
                          primaryKey: i === index && event.target.checked,
                          required:
                            i === index && event.target.checked
                              ? true
                              : item.required,
                        })),
                      )
                    }
                  />
                ),
              },
              {
                title: "展示名称",
                render: (_, row, index) => (
                  <Checkbox
                    checked={row.titleProperty}
                    onChange={(event) =>
                      setProperties((items) =>
                        items.map((item, i) => ({
                          ...item,
                          titleProperty: i === index && event.target.checked,
                        })),
                      )
                    }
                  />
                ),
              },
              {
                title: "",
                render: (_, __, index) => (
                  <Button
                    aria-label="删除字段"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() =>
                      setProperties((items) =>
                        items.filter((_, i) => i !== index),
                      )
                    }
                    type="text"
                  />
                ),
              },
            ]}
          />
        </Card>
      </Form>
      <div className="ontology-wizard-actions">
        <Button loading={saving} onClick={() => void save()} type="primary">
          创建对象类型
        </Button>
      </div>
    </div>
  );
}

function GenericResourceEditor({
     kind,
  navigate,
}: {
    kind: ResourceKind;
  navigate: (path: string) => void;
}) {
  const api = useMemo(() => modelingApi(), []);
  const [form] = Form.useForm();
  const [objects, setObjects] = useState<OntologyResource[]>([]);
  const [links, setLinks] = useState<OntologyResource[]>([]);
  const [saving, setSaving] = useState(false);
  const generatedApiName = useMemo(
    () => `${kind.replace("_", "")}${Date.now().toString(36)}`,
    [kind],
  );
  useEffect(() => {
    void Promise.all([
      api.listResources("OBJECT_TYPE").then(setObjects),
      api.listResources("LINK_TYPE").then(setLinks),
    ]);
  }, []);
  async function save() {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const target = values.targetObjectTypeId || values.leftObjectTypeId;
      const targetObject = objects.find((item) => item.resourceId === target);
      const firstProperty = targetObject?.properties[0];
      const body: Record<string, unknown> = {
        ...values,
        maturity: "ACTIVE",
        promoted: true,
        tags: [],
      };
      if (kind === "LINK_TYPE")
        Object.assign(body, {
          leftDisplayName: values.displayName,
          rightDisplayName: `反向${values.displayName}`,
        });
      if (kind === "INTERFACE")
        Object.assign(body, {
          slots: [
            {
              apiName: "name",
              displayName: "名称",
              valueType: "STRING",
              required: true,
            },
          ],
          implementations: firstProperty
            ? [
                {
                  objectTypeId: target,
                  slotApiName: "name",
                  propertyId: firstProperty.id,
                },
              ]
            : [],
        });
      if (kind === "ACTION") {
        const selectedProperties =
          values.operation === "CREATE"
            ? targetObject?.properties.filter(
                (property) =>
                  property.primaryKey ||
                  property.required ||
                  (values.propertyIds ?? []).includes(property.id),
              ) ?? []
            : values.operation === "UPDATE"
              ? targetObject?.properties.filter((property) =>
                  (values.propertyIds ?? []).includes(property.id),
                ) ?? []
              : [];
        const parameters = ["CREATE", "UPDATE"].includes(values.operation)
          ? selectedProperties.map((property) => ({
                apiName: property.apiName,
                displayName: property.displayName,
                valueType: property.valueType,
                required:
                  values.operation === "UPDATE" ? true : property.required,
                sensitive: property.sensitive,
              }))
          : ["LINK", "UNLINK"].includes(values.operation)
            ? [
                {
                  apiName: "sourceObjectId",
                  displayName: "起点对象 ID",
                  valueType: "STRING",
                  required: true,
                  sensitive: false,
                },
                {
                  apiName: "targetObjectId",
                  displayName: "终点对象 ID",
                  valueType: "STRING",
                  required: true,
                  sensitive: false,
                },
              ]
            : [];
        const rules = ["CREATE", "UPDATE"].includes(values.operation)
          ? selectedProperties.map((property) => ({
              operation: "SET_PROPERTY",
              targetPropertyId: property.id,
              valueFrom: property.apiName,
            }))
          : ["LINK", "UNLINK"].includes(values.operation)
            ? [
                {
                  operation: values.operation,
                  relationTypeId: values.relationTypeId,
                  sourceObjectIdFrom: "sourceObjectId",
                  targetObjectIdFrom: "targetObjectId",
                },
              ]
            : [];
        Object.assign(body, {
          operation: values.operation,
          parameters,
          rules,
        });
      }
      if (kind === "FUNCTION") {
        const filterProperty = targetObject?.properties.find(
          (property) => property.id === values.filterPropertyId,
        );
        const functionKind = values.functionKind ?? "QUERY";
        const parameters =
          functionKind === "GET" || functionKind === "RELATIONS"
            ? [
                {
                  apiName: "objectId",
                  displayName: "对象 ID",
                  required: true,
                  sensitive: false,
                  valueType: "STRING",
                },
              ]
            : filterProperty
              ? [
                  {
                    apiName: "filterValue",
                    displayName: `${filterProperty.displayName}筛选值`,
                    required: true,
                    sensitive: filterProperty.sensitive,
                    valueType: filterProperty.valueType,
                  },
                ]
              : [];
        const operation =
          functionKind === "GET"
            ? "GET_OBJECT"
            : functionKind === "RELATIONS"
              ? "TRAVERSE_LINKS"
              : "QUERY_OBJECT_SET";
        const argumentsByKind =
          functionKind === "GET"
            ? { objectId: "$inputs.objectId", objectTypeId: target }
            : functionKind === "RELATIONS"
              ? {
                  direction: "BOTH",
                  linkTypeIds: values.relationTypeId ? [values.relationTypeId] : [],
                  objectId: "$inputs.objectId",
                  objectTypeId: target,
                  pageSize: values.maxResults,
                }
              : {
                  columns: [],
                  objectTypeId: target,
                  pageSize: values.maxResults,
                  sort: [],
                  where: filterProperty
                    ? {
                        operator: values.filterOperator ?? "eq",
                        propertyId: filterProperty.id,
                        type: "property",
                        value: "$inputs.filterValue",
                      }
                    : {},
                };
        Object.assign(body, {
          outputType:
            functionKind === "GET"
              ? "OBJECT"
              : functionKind === "RELATIONS"
                ? "LINK_PAGE"
                : "OBJECT_SET",
          queryDsl: {
            result: "$steps.result",
            steps: [
              {
                arguments: argumentsByKind,
                id: "result",
                operation,
              },
            ],
          },
          dependencyIds: [
            ...(target ? [target] : []),
            ...(values.relationTypeId ? [values.relationTypeId] : []),
          ],
          timeoutMs: values.timeoutMs,
          maxResults: values.maxResults,
          cacheSeconds: values.cacheSeconds,
          parameters,
        });
      }
      const resource = await api.createResource(kind, body);
      message.success(`${resource.displayName}已创建`);
      navigate(`/ontology/${resourcePath(kind, "").split("/")[2]}`);
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setSaving(false);
    }
  }
  const objectOptions = objects
    .map((item) => ({ label: item.displayName, value: item.resourceId }));
  const linkOptions = links
    .map((item) => ({ label: item.displayName, value: item.resourceId }));
  const actionOperation = Form.useWatch("operation", form);
  const actionTargetId = Form.useWatch("targetObjectTypeId", form);
  const functionKind = Form.useWatch("functionKind", form);
  const functionTargetId = Form.useWatch("targetObjectTypeId", form);
  const sourceObjectId = Form.useWatch("leftObjectTypeId", form);
  const linkSourceMode = Form.useWatch("sourceMode", form);
  const sourceProperties =
    objects.find((item) => item.resourceId === sourceObjectId)?.properties ?? [];
  const actionProperties =
    objects.find((item) => item.resourceId === actionTargetId)?.properties ?? [];
  const functionProperties =
    objects.find((item) => item.resourceId === functionTargetId)?.properties ?? [];
  return (
    <div className="ontology-editor-page">
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() =>
          navigate(`/ontology/${resourcePath(kind, "").split("/")[2]}`)
        }
        type="text"
      >
        返回{kindTitle[kind]}
      </Button>
      <div className="ontology-editor-heading">
        <Title level={2}>新建{kindTitle[kind]}</Title>
      </div>
      <Card>
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            id: generatedApiName,
            cardinality: "N:1",
            sourceMode: "FOREIGN_KEY",
            operation: "UPDATE",
            outputType: "TABLE",
            maxResults: 100,
            timeoutMs: 5000,
            cacheSeconds: 60,
            functionKind: "QUERY",
            filterOperator: "eq",
          }}
        >
          <Row gutter={18}>
            <Col span={12}>
              <Form.Item
                label="名称"
                name="displayName"
                rules={[{ required: true }]}
              >
                <Input
                  onChange={(event) =>
                    form.setFieldValue(
                      "id",
                      automaticApiName(event.target.value, generatedApiName),
                    )
                  }
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                extra="用于接口路径和资源引用，创建后不可修改"
                label={`${kindTitle[kind]} ID`}
                name="id"
                rules={[
                  { required: true },
                  {
                    pattern: /^[A-Za-z][A-Za-z0-9_-]{0,159}$/,
                    message: "以英文字母开头，只能包含字母、数字、下划线和连字符",
                  },
                ]}
              >
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="说明（可选）" name="description">
                <Input />
              </Form.Item>
            </Col>
            {kind === "LINK_TYPE" && (
              <>
                <Col span={8}>
                  <Form.Item
                    label="起点对象类型"
                    name="leftObjectTypeId"
                    rules={[{ required: true }]}
                  >
                    <Select options={objectOptions} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item
                    label="目标对象类型"
                    name="rightObjectTypeId"
                    rules={[{ required: true }]}
                  >
                    <Select options={objectOptions} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="关联数量" name="cardinality">
                    <Select
                      options={[
                        { value: "1:1", label: "一对一" },
                        { value: "1:N", label: "一对多" },
                        { value: "N:1", label: "多对一" },
                        { value: "N:M", label: "多对多" },
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="关系来源" name="sourceMode">
                    <Select
                      options={[
                        { value: "FOREIGN_KEY", label: "外键自动生成" },
                        { value: "MANUAL", label: "Action 手工维护" },
                        { value: "PIPELINE", label: "Pipeline 输出维护" },
                      ]}
                    />
                  </Form.Item>
                </Col>
                {linkSourceMode === "FOREIGN_KEY" && <Col span={12}>
                  <Form.Item
                    extra="例如选择人员.group_id；该值与小组主键相同的实例会形成关系边。"
                    label="通过起点对象的哪个字段关联"
                    name="sourcePropertyId"
                    rules={[{ required: true, message: "请选择实例关联字段" }]}
                  >
                    <Select
                      disabled={!sourceObjectId}
                      options={sourceProperties.map((property) => ({
                        label: `${property.displayName} (${property.apiName})`,
                        value: property.id,
                      }))}
                      placeholder="选择外键字段"
                    />
                  </Form.Item>
                </Col>}
                {linkSourceMode === "PIPELINE" && <Col span={12}>
                  <Form.Item
                    extra="填写负责该关系输出的已发布 Pipeline ID。"
                    label="主 Pipeline ID"
                    name="primaryPipelineId"
                    rules={[{ required: true, message: "请填写主 Pipeline ID" }]}
                  >
                    <Input placeholder="UUID" />
                  </Form.Item>
                </Col>}
              </>
            )}
            {kind === "INTERFACE" && (
              <Col span={12}>
                <Form.Item
                  label="适用对象类型"
                  name="leftObjectTypeId"
                  rules={[{ required: true }]}
                >
                  <Select options={objectOptions} />
                </Form.Item>
              </Col>
            )}
            {kind === "ACTION" && (
              <>
                <Col span={8}>
                  <Form.Item
                    label="目标对象类型"
                    name="targetObjectTypeId"
                    rules={[{ required: true }]}
                  >
                    <Select options={objectOptions} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="操作类型" name="operation">
                    <Select
                      options={[
                        "CLEAR_OVERRIDES",
                        "CREATE",
                        "DELETE",
                        "LINK",
                        "UNLINK",
                        "UPDATE",
                      ].map((value) => ({ value }))}
                    />
                  </Form.Item>
                </Col>
                {["CREATE", "UPDATE"].includes(actionOperation) && (
                  <Col span={24}>
                    <Form.Item
                      extra={
                        actionOperation === "CREATE"
                          ? "主键和必填属性会自动加入；这里选择需要额外填写的属性。"
                          : "每个选中属性都会生成同类型参数，并映射到明确的修改规则。"
                      }
                      label={
                        actionOperation === "CREATE"
                          ? "创建时填写的属性"
                          : "允许修改的属性"
                      }
                      name="propertyIds"
                      rules={
                        actionOperation === "UPDATE"
                          ? [{ required: true, message: "至少选择一个可修改属性" }]
                          : []
                      }
                    >
                      <Select
                        mode="multiple"
                        options={actionProperties
                          .filter(
                            (property) =>
                              actionOperation === "CREATE" ||
                              (property.actionWritable && !property.primaryKey),
                          )
                          .map((property) => ({
                            label: `${property.displayName} · ${property.valueType}${property.primaryKey ? " · 主键" : ""}`,
                            value: property.id,
                          }))}
                        placeholder="选择属性"
                      />
                    </Form.Item>
                  </Col>
                )}
                {["LINK", "UNLINK"].includes(actionOperation) && (
                  <Col span={12}>
                    <Form.Item
                      label="关系类型"
                      name="relationTypeId"
                      rules={[{ required: true }]}
                    >
                      <Select options={linkOptions} />
                    </Form.Item>
                  </Col>
                )}
              </>
            )}
            {kind === "FUNCTION" && (
              <>
                <Col span={8}>
                  <Form.Item
                    label="使用哪个对象类型"
                    name="targetObjectTypeId"
                    rules={[{ required: true }]}
                  >
                    <Select options={objectOptions} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="Function 能力" name="functionKind">
                    <Select
                      options={[
                        { label: "查询对象集合", value: "QUERY" },
                        { label: "按 ID 获取对象", value: "GET" },
                        { label: "查询对象关系", value: "RELATIONS" },
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="超时 (ms)" name="timeoutMs">
                    <InputNumber
                      max={30000}
                      min={100}
                      style={{ width: "100%" }}
                    />
                  </Form.Item>
                </Col>
                {functionKind !== "GET" && <Col span={8}>
                  <Form.Item label="每次最多返回" name="maxResults">
                    <Select
                      options={[25, 50, 100].map((value) => ({
                        label: `${value} 条`,
                        value,
                      }))}
                    />
                  </Form.Item>
                </Col>}
                <Col span={8}>
                  <Form.Item label="缓存时间（秒）" name="cacheSeconds">
                    <InputNumber max={3600} min={0} style={{ width: "100%" }} />
                  </Form.Item>
                </Col>
                {functionKind === "QUERY" && <>
                  <Col span={8}>
                    <Form.Item
                      extra="不选择时返回该类型的全部可见对象。"
                      label="按属性筛选（可选）"
                      name="filterPropertyId"
                    >
                      <Select
                        allowClear
                        options={functionProperties
                          .filter((property) => property.filterable && !property.sensitive)
                          .map((property) => ({
                            label: `${property.displayName} · ${property.valueType}`,
                            value: property.id,
                          }))}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item label="筛选方式" name="filterOperator">
                      <Select
                        options={[
                          { label: "等于", value: "eq" },
                          { label: "不等于", value: "ne" },
                          { label: "包含", value: "contains_any_word" },
                          { label: "大于", value: "gt" },
                          { label: "小于", value: "lt" },
                        ]}
                      />
                    </Form.Item>
                  </Col>
                </>}
                {functionKind === "RELATIONS" && <Col span={8}>
                  <Form.Item
                    extra="不选择时遍历该对象的全部关系类型。"
                    label="限制关系类型（可选）"
                    name="relationTypeId"
                  >
                    <Select allowClear options={linkOptions} />
                  </Form.Item>
                </Col>}
              </>
            )}
          </Row>
          <Alert
            message={{
              ACTION: "Action 将统一经过 Preview、条件校验、幂等执行和投影写回。",
              FUNCTION: "Function 是可复用的类型化只读能力：可查询对象、按 ID 取对象或遍历真实关系，并供对象页、应用和 Agent 调用。",
              INTERFACE: "Interface 通过明确的属性槽位统一多个对象类型，不隐藏字段映射。",
              LINK_TYPE: "关系类型定义结构；实例关系由外键、Action 或 Pipeline 投影生成。",
              OBJECT_TYPE: "对象类型必须明确选择由 Pipeline 映射或由 Action 写入。",
            }[kind]}
            showIcon
            type="info"
          />
          <div className="edit-actions">
            <Button
              onClick={() =>
                navigate(`/ontology/${resourcePath(kind, "").split("/")[2]}`)
              }
            >
              取消
            </Button>
            <Button loading={saving} onClick={() => void save()} type="primary">
              创建{kindTitle[kind]}
            </Button>
          </div>
        </Form>
      </Card>
    </div>
  );
}

function splitTags(value?: string) {
  return (
    value
      ?.split(",")
      .map((tag) => tag.trim())
      .filter(Boolean) ?? []
  );
}

function automaticApiName(value: string, fallback: string) {
  const ascii = value.trim().replace(/[^A-Za-z0-9_]/g, "");
  return /^[A-Za-z]/.test(ascii) ? ascii : fallback;
}

function property(
  apiName: string,
  displayName: string,
  primaryKey: boolean,
  titleProperty: boolean,
  valueType = "STRING",
): PropertyDraft {
  return {
    apiName,
    displayName,
    valueType,
    required: primaryKey,
    primaryKey,
    titleProperty,
    searchable: true,
    filterable: true,
    sortable: primaryKey,
    sensitive: false,
    actionWritable: !primaryKey,
  };
}
