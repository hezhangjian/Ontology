# 人员对象实例接口：`dataai/employee`

本文基于当前运行平台中已存在的“人员”对象编写。已通过只读接口确认：本体 ID 为 `dataai`、对象类型 ID 为 `employee`，现有人员实例包括 `EMP001`（张一鸣）。只读输出的数据值来自当前平台快照，并按改造后的业务属性键展示；会修改数据的命令使用新的演示工号 `EMP-DEMO-001`，其响应为契约示例，执行后的时间、任务 ID 和版本号以实际结果为准。

## 当前人员对象模型

| API 属性键 | 类型 | 约束 |
| --- | --- | --- |
| `姓名` | STRING | 标题属性 |
| `工号` | STRING | 必填、主键、不可更新 |
| `类型` | STRING | 可更新 |
| `编码` | STRING | 可更新 |
| `五级部门` | STRING | 可更新 |

```sh
export API_BASE='http://localhost:4242'
export ONTOLOGY_ID='dataai'
export OBJECT_TYPE_ID='employee'
export INSTANCE_URL="$API_BASE/v1/ontologies/$ONTOLOGY_ID/object-types/$OBJECT_TYPE_ID/object-instances"
export EXISTING_OBJECT_ID='EMP001'
export DEMO_OBJECT_ID='EMP-DEMO-001'
```

## 1. 列表（Read）

`GET /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances`

```sh
curl -sS "$INSTANCE_URL?pageSize=25"
```

改造后的输出节选（数据来自当前平台）：

```json
{
  "type": "employee",
  "items": [
    {
      "id": "EMP001",
      "title": "张一鸣",
      "type": "employee",
      "properties": {
        "五级部门": "湖仓团队",
        "姓名": "张一鸣",
        "工号": "EMP001",
        "类型": "正式",
        "编码": "C001"
      },
      "version": 1,
      "createdAt": "2026-07-30T14:02:51.498146Z",
      "updatedAt": "2026-07-30T14:02:51.498146Z"
    }
  ],
  "total": 25,
  "totalIsLowerBound": true,
  "nextCursor": "RU1QMDI1"
}
```

使用返回的游标获取下一页：

```sh
curl -sSG "$INSTANCE_URL" --data-urlencode 'pageSize=25' --data-urlencode 'cursor=RU1QMDI1'
```

## 2. 条件查询（Read）

`POST /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/query`

```sh
curl -sS -X POST "$INSTANCE_URL/query" \
  -H 'Content-Type: application/json' \
  --data '{
    "properties": ["工号", "姓名", "类型", "五级部门"],
    "filters": [
      {"property": "五级部门", "operator": "eq", "value": "湖仓团队"},
      {"property": "类型", "operator": "eq", "value": "正式"}
    ],
    "filterOperator": "AND",
    "sort": [{"property": "工号", "direction": "asc"}],
    "pageSize": 25
  }'
```

输出结构为与列表接口相同的 `ObjectInstancePage`。筛选操作符支持 `eq`、`ne`、`gt`、`gte`、`lt`、`lte`、`between`、`in`、`not_in`、`exact`、`starts_with`、`is_empty`、`is_not_empty`。

输出节选：

```json
{"type":"employee","items":[{"id":"EMP001","title":"张一鸣","type":"employee","properties":{"五级部门":"湖仓团队","姓名":"张一鸣","工号":"EMP001","类型":"正式","编码":"C001"},"version":1}],"total":17,"totalIsLowerBound":false,"nextCursor":null}
```

## 3. 聚合（Read）

`POST /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/aggregate`

```sh
curl -sS -X POST "$INSTANCE_URL/aggregate" \
  -H 'Content-Type: application/json' \
  --data '{
    "groupBy": ["类型"],
    "metrics": [{"operation": "COUNT", "alias": "personCount"}]
  }'
```

改造后的输出（统计值来自当前平台）：

```json
{
  "rows": [
    {"类型": "外包", "personCount": 12},
    {"类型": "正式", "personCount": 88}
  ]
}
```

指标支持 `COUNT`、`DISTINCT_COUNT`、`SUM`、`AVG`、`MIN`、`MAX`。除 `COUNT` 外，指标通常需要提供 `property`。

## 4. 获取一个已有的人员对象（Read）

`GET /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/{objectId}`

```sh
curl -i "$INSTANCE_URL/$EXISTING_OBJECT_ID"
```

改造后的输出（数据来自当前平台）：

```http
HTTP/1.1 200 OK
ETag: "1"
Content-Type: application/json

{"id":"EMP001","title":"张一鸣","type":"employee","properties":{"五级部门":"湖仓团队","姓名":"张一鸣","工号":"EMP001","类型":"正式","编码":"C001"},"version":1,"correlationId":null,"projectionStatus":null,"createdAt":"2026-07-30T14:02:51.498146Z","updatedAt":"2026-07-30T14:02:51.498146Z"}
```

## 5. 创建人员对象（Create）

`POST /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances`

```sh
curl -i -X POST "$INSTANCE_URL" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: employee-demo-001-create-v1' \
  --data '{
    "properties": {
      "工号": "EMP-DEMO-001",
      "姓名": "接口演示人员",
      "类型": "正式",
      "编码": "DEMO001",
      "五级部门": "平台研发部"
    }
  }'
```

示例输出。保存 `ETag`，后续更新和删除必须使用其实际值：

```http
HTTP/1.1 201 Created
Location: /v1/ontologies/dataai/object-types/employee/object-instances/EMP-DEMO-001
ETag: "1"
Content-Type: application/json

{"id":"EMP-DEMO-001","title":"接口演示人员","type":"employee","properties":{"工号":"EMP-DEMO-001","姓名":"接口演示人员","类型":"正式","编码":"DEMO001","五级部门":"平台研发部"},"version":1}
```

## 6. 更新演示人员（Update）

`PATCH /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/{objectId}`

```sh
curl -i -X PATCH "$INSTANCE_URL/$DEMO_OBJECT_ID" \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "1"' \
  --data '{
    "properties": {
      "类型": "外包",
      "五级部门": "Agent 团队"
    }
  }'
```

示例输出：

```http
HTTP/1.1 200 OK
ETag: "2"
Content-Type: application/json

{"id":"EMP-DEMO-001","title":"接口演示人员","type":"employee","properties":{"工号":"EMP-DEMO-001","姓名":"接口演示人员","类型":"外包","编码":"DEMO001","五级部门":"Agent 团队"},"version":2}
```

这是合并更新：未提供的属性保持不变；属性设为 `null` 会移除该属性的手工覆盖。主键 `工号` 不可更新。`If-Match` 请始终换成读取或写入响应中最新的 ETag。

## 7. 批量变更（Create / Update / Delete）

`POST /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/bulk`

批量创建：

```sh
curl -sS -X POST "$INSTANCE_URL/bulk" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: employee-demo-bulk-create-v1' \
  --data '{
    "operation": "CREATE",
    "atomic": true,
    "items": [
      {"properties":{"工号":"EMP-DEMO-002","姓名":"批量演示一","类型":"正式"}},
      {"properties":{"工号":"EMP-DEMO-003","姓名":"批量演示二","类型":"外包"}}
    ]
  }'
```

示例输出：

```json
{"items":[{"index":0,"id":"EMP-DEMO-002","status":"CREATED","version":1},{"index":1,"id":"EMP-DEMO-003","status":"CREATED","version":1}]}
```

批量更新或删除时，项目必须包含当前 `id` 与 `version`。例如批量删除上面两个演示对象：

```sh
curl -sS -X POST "$INSTANCE_URL/bulk" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: employee-demo-bulk-delete-v1' \
  --data '{"operation":"DELETE","items":[{"id":"EMP-DEMO-002","version":1},{"id":"EMP-DEMO-003","version":1}]}'
```

示例输出：

```json
{"items":[{"index":0,"id":"EMP-DEMO-002","status":"DELETED","version":1},{"index":1,"id":"EMP-DEMO-003","status":"DELETED","version":1}]}
```

`operation` 可为 `CREATE`、`UPDATE`、`DELETE`、`UPSERT`；`atomic` 默认为 `false`，此时每一项单独返回状态。

## 8. 获取投影状态（Read）

`GET /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/{objectId}/projection-status`

```sh
curl -sS "$INSTANCE_URL/$EXISTING_OBJECT_ID/projection-status"
```

改造后的输出（状态值来自当前平台）：

```json
{"objectId":"EMP001","authoritativeVersion":1,"correlationId":null,"targets":[{"target":"HUGEGRAPH","version":1,"status":"PROJECTED","lastError":null,"updatedAt":"2026-07-30T14:02:53.267015Z"},{"target":"OPENSEARCH","version":1,"status":"PROJECTED","lastError":null,"updatedAt":"2026-07-30T14:03:55.957582Z"}]}
```

## 9. 从当前人员数据集导入（Create）

`POST /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/imports`

当前人员模型的来源数据集为 `dataset-9ba9f6e3`，字段映射如下：

```sh
curl -sS -X POST "$INSTANCE_URL/imports" \
  -H 'Content-Type: application/json' \
  --data '{
    "datasetId": "dataset-9ba9f6e3",
    "identityField": "工号",
    "titleField": "姓名",
    "fieldMappings": {
      "姓名": "姓名",
      "工号": "工号",
      "类型": "类型",
      "编码": "编码",
      "五级部门": "五级部门"
    },
    "mode": "UPSERT"
  }'
```

示例输出：

```json
{"id":"<importJobId>","status":"QUEUED","inserted":0,"updated":0,"deleted":0,"unchanged":0,"failed":0,"createdAt":"<timestamp>","completedAt":null}
```

## 10. 查询、错误与取消导入（Read / Delete）

```sh
export IMPORT_JOB_ID='<importJobId>'

# 查询导入任务
curl -sS "$INSTANCE_URL/imports/$IMPORT_JOB_ID"

# 查询逐行导入错误
curl -sS "$INSTANCE_URL/imports/$IMPORT_JOB_ID/errors"

# 取消未完成的导入任务（202 Accepted）
curl -sS -X DELETE "$INSTANCE_URL/imports/$IMPORT_JOB_ID"
```

以上三个命令的示例输出依次为：

```json
{"id":"<importJobId>","status":"COMPLETED","inserted":100,"updated":0,"deleted":0,"unchanged":0,"failed":0,"createdAt":"<timestamp>","completedAt":"<timestamp>"}
```

```json
[{"rowNumber":7,"objectId":"EMP007","fieldId":"类型","errorCode":"PROPERTY_VALUE_INVALID","safeMessage":"属性值不符合 STRING 类型"}]
```

```json
{"id":"<importJobId>","status":"CANCELLED","inserted":0,"updated":0,"deleted":0,"unchanged":0,"failed":0,"createdAt":"<timestamp>","completedAt":"<timestamp>"}
```

## 11. 投影对账及结果查询（Create / Read）

`POST /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/reconciliations`

```sh
curl -sS -X POST "$INSTANCE_URL/reconciliations" \
  -H 'Content-Type: application/json' \
  --data '{"repair": false}'
```

示例输出：

```json
{"id":"<reconciliationJobId>","status":"QUEUED","missing":0,"stale":0,"extra":0,"repaired":0}
```

`repair: false` 仅检查 HugeGraph 和 OpenSearch 投影差异；省略该字段或传递 `true` 会尝试修复。查询结果：

```sh
export RECONCILIATION_JOB_ID='<reconciliationJobId>'
curl -sS "$INSTANCE_URL/reconciliations/$RECONCILIATION_JOB_ID"
```

示例输出：

```json
{"job":{"id":"<reconciliationJobId>","status":"COMPLETED","missing":0,"stale":0,"extra":0,"repaired":0},"differences":[]}
```

## 12. 删除演示人员（Delete）

`DELETE /v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances/{objectId}`

执行第 6 步后，示例对象的 ETag 为 `"2"`：

```sh
curl -i -X DELETE "$INSTANCE_URL/$DEMO_OBJECT_ID" \
  -H 'If-Match: "2"'
```

示例输出：

```http
HTTP/1.1 204 No Content
```

## 状态码与重试规则

| 操作 | 成功状态码 |
| --- | --- |
| 列表、查询、聚合、获取对象、投影状态、查询导入或对账结果 | `200 OK` |
| 创建对象 | `201 Created` |
| 批量变更 | `200 OK` |
| 创建/取消导入、发起对账 | `202 Accepted` |
| 删除对象 | `204 No Content` |

创建单对象和批量变更必须提供 `Idempotency-Key`，重试时复用同一个键和同一请求体。更新、删除必须提供最新的 `If-Match`（ETag）；版本落后时应重新读取对象后再执行操作。
