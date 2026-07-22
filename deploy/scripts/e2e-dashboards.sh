#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/deploy/scripts/compose.sh"
api=http://localhost:9080/api/ontology/v1
work_dir=$(mktemp -d)
stamp=$(date +%s)

cleanup() {
  for index in 1 2 3 4; do
    ${compose} --profile '*' exec -T ontology-core curl -fsS -X DELETE \
      "http://opensearch:9200/platform-ontology-objects/_doc/p09-small-${stamp}-${index}?refresh=true" >/dev/null 2>&1 || true
  done
  rm -rf "${work_dir}"
}
trap cleanup EXIT INT TERM

fail() { echo "E:dashboards-page failed: $1" >&2; exit 1; }
sql() { ${compose} --profile '*' exec -T postgres psql -U ontology -d ontology -Atc "$1"; }

viewer_token=local
builder_token=local
admin_api_token=local
builder_subject=local-user
auth_viewer="Authorization: Bearer ${viewer_token}"
auth_builder="Authorization: Bearer ${builder_token}"
auth_admin="Authorization: Bearer ${admin_api_token}"

for service in apisix frontend hugegraph ontology-core opensearch postgres; do
  service_state=$(${compose} --profile '*' ps --format json "${service}" | jq -r 'if type == "array" then .[0].State else .State end')
  [ "${service_state}" = running ] || fail "${service} is not running"
done

employee_type=00000000-0000-0000-0000-000000000102
department_property=20000000-0000-0000-0000-000000000203
revision=$(sql "SELECT revision FROM control.ontology_revisions WHERE status='ACTIVE'")

create_body=$(jq -cn --arg name "P09 运营分析 ${stamp}" '{name:$name,description:"P09 immutable analytics dashboard",visibility:"ORGANIZATION",refreshPolicy:"MANUAL",tags:["p09","acceptance"]}')
curl -fsS -X POST "${api}/dashboards" -H "${auth_builder}" -H 'Content-Type: application/json' -d "${create_body}" > "${work_dir}/created.json"
dashboard_id=$(jq -er .summary.id "${work_dir}/created.json")
draft_etag=$(jq -er .activeDraft.etag "${work_dir}/created.json")
curl -fsS -X POST "${api}/dashboards/${dashboard_id}/edit-lock" -H "${auth_builder}" -H 'Content-Type: application/json' -d '{}' | jq -e '.editable==true and (.leaseToken|length>20)' >/dev/null || fail "editor lease was not acquired"

page_one=$(uuidgen | tr '[:upper:]' '[:lower:]')
page_two=$(uuidgen | tr '[:upper:]' '[:lower:]')
source_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
metric_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
bar_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
table_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
markdown_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
second_metric_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
filter_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
layout() { jq -cn --argjson x "$1" --argjson y "$2" --argjson w "$3" --argjson h "$4" '{desktop:{x:$x,y:$y,w:$w,h:$h},tablet:{x:0,y:$y,w:12,h:$h},mobile:{x:0,y:$y,w:1,h:$h}}'; }
metric_layout=$(layout 0 0 6 3)
bar_layout=$(layout 6 0 12 6)
table_layout=$(layout 0 6 24 7)
markdown_layout=$(layout 18 0 6 3)
second_layout=$(layout 0 0 8 3)
definition=$(jq -cn \
  --arg page1 "${page_one}" --arg page2 "${page_two}" --arg source "${source_id}" --arg type "${employee_type}" --argjson revision "${revision}" \
  --arg metric "${metric_id}" --arg bar "${bar_id}" --arg table "${table_id}" --arg markdown "${markdown_id}" --arg second "${second_metric_id}" \
  --arg filter "${filter_id}" --arg property "${department_property}" --argjson metricLayout "${metric_layout}" --argjson barLayout "${bar_layout}" \
  --argjson tableLayout "${table_layout}" --argjson markdownLayout "${markdown_layout}" --argjson secondLayout "${second_layout}" '
  {schemaVersion:1,pages:[{id:$page1,name:"经营概览",description:"授权聚合",order:0},{id:$page2,name:"对象明细",description:"按需加载",order:1}],
   dataSources:[{id:$source,name:"员工 Object Set",kind:"OBJECT_SET",objectTypeId:$type,query:{objectTypeId:$type,where:{},sort:[],pageSize:50,columns:[]},ontologyRevision:$revision}],
   widgets:[{id:$metric,pageId:$page1,dataSourceId:$source,type:"METRIC",title:"可见员工",description:"count",layout:$metricLayout,config:{aggregation:"count"},interaction:{}},
            {id:$bar,pageId:$page1,dataSourceId:$source,type:"BAR",title:"部门分布",description:"suppression aware",layout:$barLayout,config:{dimensionPropertyId:$property},interaction:{drilldown:true}},
            {id:$markdown,pageId:$page1,type:"MARKDOWN",title:"口径说明",description:"",layout:$markdownLayout,config:{markdown:"当前用户权限下的实时对象分析"},interaction:{}},
            {id:$table,pageId:$page1,dataSourceId:$source,type:"OBJECT_TABLE",title:"员工对象",description:"server paged",layout:$tableLayout,config:{},interaction:{}},
            {id:$second,pageId:$page2,dataSourceId:$source,type:"METRIC",title:"第二页总数",description:"lazy",layout:$secondLayout,config:{aggregation:"count"},interaction:{}}],
   filters:[{id:$filter,name:"部门",valueType:"STRING",controlType:"DROPDOWN",scope:"GLOBAL",defaultValue:null,required:false,allowEmpty:true,sensitive:false,applyMode:"MANUAL"}],
   filterBindings:[{filterId:$filter,dataSourceId:$source,propertyId:$property,operator:"eq"}],settings:{timezone:"Asia/Shanghai",weekStart:"MONDAY",fiscalYearStartMonth:1,suppressionThreshold:5}}')
curl -fsS -X PUT "${api}/dashboards/${dashboard_id}/draft" -H "${auth_builder}" -H 'Content-Type: application/json' -H "If-Match: ${draft_etag}" -d "${definition}" > "${work_dir}/saved.json"
saved_etag=$(jq -er .etag "${work_dir}/saved.json")
[ "${saved_etag}" -gt "${draft_etag}" ] || fail "draft ETag did not advance"
stale_code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "${api}/dashboards/${dashboard_id}/draft" -H "${auth_builder}" -H 'Content-Type: application/json' -H "If-Match: ${draft_etag}" -d "${definition}")
[ "${stale_code}" = 409 ] || fail "stale draft ETag was not rejected"
curl -fsS -X POST "${api}/dashboards/${dashboard_id}/validate" -H "${auth_builder}" | jq -e '.valid==true and .estimatedCost>0 and (.definitionHash|length==64)' >/dev/null || fail "valid dashboard did not become READY"
curl -fsS -X POST "${api}/dashboards/${dashboard_id}/publish" -H "${auth_builder}" -H 'Content-Type: application/json' -d '{"releaseNotes":"P09 E2E v1"}' > "${work_dir}/v1.json"
jq -e '.version==1 and .healthStatus=="HEALTHY" and (.queryPlanHash|length==64)' "${work_dir}/v1.json" >/dev/null || fail "immutable v1 publication failed"

curl -fsS "${api}/dashboards/${dashboard_id}/query-plan" -H "${auth_viewer}" > "${work_dir}/plan.json"
plan_id=$(jq -er .id "${work_dir}/plan.json")
refresh_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
batch=$(jq -cn --arg page "${page_one}" --arg metric "${metric_id}" --arg bar "${bar_id}" --arg table "${table_id}" --arg refresh "${refresh_id}" '{pageId:$page,widgetIds:[$metric,$bar,$table],filters:{},refreshId:$refresh}')
curl -fsS -X POST "${api}/dashboard-query-plans/${plan_id}/widgets:batch" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${batch}" > "${work_dir}/viewer-run.json"
jq -e --arg metric "${metric_id}" --arg table "${table_id}" '.status=="SUCCEEDED" and (.widgets|length==3) and (first(.widgets[]|select(.widgetId==$metric)).data.value>0) and (first(.widgets[]|select(.widgetId==$table)).data.items|all(.properties|has("email")|not))' "${work_dir}/viewer-run.json" >/dev/null || fail "viewer query plan did not execute authorized widgets"
viewer_count=$(jq -er --arg metric "${metric_id}" 'first(.widgets[]|select(.widgetId==$metric)).data.value' "${work_dir}/viewer-run.json")
curl -fsS -X POST "${api}/dashboard-query-plans/${plan_id}/widgets:batch" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${batch}" | jq -e '.cacheHits==3' >/dev/null || fail "caller-scoped dashboard cache did not hit"
curl -fsS -X POST "${api}/dashboard-query-plans/${plan_id}/widgets:batch" -H "${auth_admin}" -H 'Content-Type: application/json' -d "${batch}" | jq -e '.cacheHits==0' >/dev/null || fail "dashboard cache leaked across security contexts"

for index in 1 2 3 4; do
  ${compose} --profile '*' exec -T ontology-core curl -fsS -X PUT "http://opensearch:9200/platform-ontology-objects/_doc/p09-small-${stamp}-${index}?refresh=true" \
    -H 'Content-Type: application/json' -d "{\"graph_element_id\":\"p09-small-${stamp}-${index}\",\"object_type\":\"Employee\",\"object_id\":\"P09-SMALL-${stamp}-${index}\",\"ontology_revision\":${revision},\"entity_version\":1,\"correlation_id\":\"p09-small-${stamp}\",\"occurred_at\":\"2026-07-20T00:00:00Z\",\"visibility_tokens\":[\"authenticated\"],\"properties\":{\"name\":\"P09 Small ${index}\",\"department\":\"P09 Small Cell ${stamp}\"}}" >/dev/null
done
fresh_batch=$(printf '%s' "${batch}" | jq -c '.refreshId="10000000-0000-0000-0000-000000000001"')
curl -fsS -X POST "${api}/dashboard-query-plans/${plan_id}/widgets:batch" -H "${auth_builder}" -H 'Content-Type: application/json' -d "${fresh_batch}" > "${work_dir}/suppressed.json"
jq -e --arg bar "${bar_id}" --arg label "P09 Small Cell ${stamp}" 'first(.widgets[]|select(.widgetId==$bar)).data.buckets|any(.label==$label and .suppressed==true and .value==null)' "${work_dir}/suppressed.json" >/dev/null || fail "small-group suppression was not applied"

filter_batch=$(printf '%s' "${batch}" | jq -c --arg filter "${filter_id}" '.filters={($filter):"Research"}|.refreshId="20000000-0000-0000-0000-000000000002"')
curl -fsS -X POST "${api}/dashboard-query-plans/${plan_id}/widgets:batch" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${filter_batch}" | jq -e --arg metric "${metric_id}" --argjson total "${viewer_count}" 'first(.widgets[]|select(.widgetId==$metric)).data.value < $total' >/dev/null || fail "explicit stable-property filter binding was not applied"
curl -fsS -X POST "${api}/dashboard-query-plans/${plan_id}/drilldown-token" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "$(jq -cn --arg widget "${bar_id}" '{widgetId:$widget,value:"Research",filters:{}}')" | jq -e '.targetKind=="OBJECT_EXPLORER" and (.token|length>40)' >/dev/null || fail "declared drilldown token was not signed"

viewer_draft_code=$(curl -sS -o /dev/null -w '%{http_code}' "${api}/dashboards/${dashboard_id}/draft" -H "${auth_viewer}")
[ "${viewer_draft_code}" = 403 ] || fail "Viewer could read an editor draft"
curl -fsS -X PUT "${api}/dashboards/${dashboard_id}/favorite" -H "${auth_viewer}" >/dev/null
curl -fsS "${api}/dashboards?favorites=true" -H "${auth_viewer}" | jq -e --arg id "${dashboard_id}" 'any(.[];.id==$id and .favorite==true)' >/dev/null || fail "personal dashboard favorite failed"

curl -fsS -X POST "${api}/dashboards/${dashboard_id}/edit-lock" -H "${auth_builder}" -H 'Content-Type: application/json' -d '{}' >/dev/null
curl -fsS "${api}/dashboards/${dashboard_id}/draft" -H "${auth_builder}" > "${work_dir}/v2-draft.json"
v2_etag=$(jq -er .etag "${work_dir}/v2-draft.json")
invalid_definition=$(printf '%s' "${definition}" | jq -c --arg id "${markdown_id}" '(.widgets[]|select(.id==$id).config.markdown)="<script>alert(1)</script>"')
curl -fsS -X PUT "${api}/dashboards/${dashboard_id}/draft" -H "${auth_builder}" -H 'Content-Type: application/json' -H "If-Match: ${v2_etag}" -d "${invalid_definition}" > "${work_dir}/invalid-saved.json"
invalid_etag=$(jq -er .etag "${work_dir}/invalid-saved.json")
curl -fsS -X POST "${api}/dashboards/${dashboard_id}/validate" -H "${auth_builder}" | jq -e '.valid==false and any(.issues[];.code=="UNSAFE_MARKDOWN")' >/dev/null || fail "unsafe Markdown did not block publication"
publish_code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${api}/dashboards/${dashboard_id}/publish" -H "${auth_builder}" -H 'Content-Type: application/json' -d '{}')
[ "${publish_code}" = 422 ] || fail "invalid dashboard publication was not rejected"
curl -fsS "${api}/dashboards/${dashboard_id}" -H "${auth_viewer}" | jq -e '.currentVersion.version==1' >/dev/null || fail "failed validation displaced the live version"
curl -fsS -X PUT "${api}/dashboards/${dashboard_id}/draft" -H "${auth_builder}" -H 'Content-Type: application/json' -H "If-Match: ${invalid_etag}" -d "${definition}" >/dev/null
curl -fsS -X POST "${api}/dashboards/${dashboard_id}/validate" -H "${auth_builder}" | jq -e '.valid==true' >/dev/null
curl -fsS -X POST "${api}/dashboards/${dashboard_id}/publish" -H "${auth_builder}" -H 'Content-Type: application/json' -d '{"releaseNotes":"P09 E2E v2 recovery"}' > "${work_dir}/v2.json"
jq -e '.version==2' "${work_dir}/v2.json" >/dev/null || fail "recovered draft did not publish v2"
v1_id=$(jq -er .id "${work_dir}/v1.json")
v2_id=$(jq -er .id "${work_dir}/v2.json")
curl -fsS "${api}/dashboards/${dashboard_id}/versions" -H "${auth_viewer}" | jq -e 'length==2 and .[0].version==2 and .[1].version==1' >/dev/null || fail "immutable version history was incomplete"
curl -fsS "${api}/dashboards/${dashboard_id}/versions/diff?from=${v1_id}&to=${v2_id}" -H "${auth_viewer}" | jq -e '.fromVersion==1 and .toVersion==2' >/dev/null || fail "version diff failed"

copy_id=$(curl -fsS -X POST "${api}/dashboards/${dashboard_id}/copy" -H "${auth_builder}" | jq -er .summary.id)
curl -fsS "${api}/dashboards/${copy_id}" -H "${auth_builder}" | jq -e '.summary.visibility=="PRIVATE" and .summary.currentVersion==null' >/dev/null || fail "dashboard copy included publication or sharing state"
curl -fsS -X POST "${api}/dashboards/${dashboard_id}/archive" -H "${auth_builder}" | jq -e '.summary.lifecycle=="ARCHIVED"' >/dev/null
archived_code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${api}/dashboard-query-plans/${plan_id}/widgets:batch" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${batch}")
[ "${archived_code}" = 410 ] || fail "archived dashboard query plan remained executable"
curl -fsS -X POST "${api}/dashboards/${dashboard_id}/restore" -H "${auth_builder}" | jq -e '.summary.lifecycle=="PUBLISHED" and .summary.currentVersion==2' >/dev/null || fail "archive restore changed the live version"

result_columns=$(sql "SELECT count(*) FROM information_schema.columns WHERE table_schema='control' AND table_name IN ('dashboards','dashboard_versions','dashboard_drafts','dashboard_query_runs') AND column_name IN ('object_body','object_rows','query_results','sensitive_filter_value')")
[ "${result_columns}" = 0 ] || fail "dashboard control plane contains object results or sensitive filter values"
normalized=$(sql "SELECT (SELECT count(*) FROM control.dashboard_pages WHERE version_id='${v2_id}')||':'||(SELECT count(*) FROM control.dashboard_widgets WHERE version_id='${v2_id}')||':'||(SELECT count(*) FROM control.dashboard_data_sources WHERE version_id='${v2_id}')")
[ "${normalized}" = "2:5:1" ] || fail "published definition was not normalized by immutable version"
audit_count=$(sql "SELECT count(*) FROM control.audit_events WHERE resource_type='DASHBOARD' AND resource_id='${dashboard_id}' AND actor_id='${builder_subject}'")
[ "${audit_count}" -ge 8 ] || fail "dashboard audit evidence was incomplete"

echo "E:dashboards-page passed: ETag draft/lease, normalized immutable v1-v2, live-version safety, permission-scoped query/cache, filters, suppression, drilldown, favorites, copy, archive/restore and audit verified."
