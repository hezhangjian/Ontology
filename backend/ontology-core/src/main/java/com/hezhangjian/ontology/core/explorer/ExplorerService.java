package com.hezhangjian.ontology.core.explorer;

import static com.hezhangjian.ontology.core.explorer.ExplorerModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.explorer.ExplorerPolicy.ValidatedQuery;
import com.hezhangjian.ontology.core.explorer.ExplorerStorageClient.GraphEdge;
import com.hezhangjian.ontology.core.explorer.ExplorerStorageClient.GraphObject;
import com.hezhangjian.ontology.core.explorer.ExplorerStorageClient.RawSearchHit;
import com.hezhangjian.ontology.core.explorer.ExplorerStorageClient.SearchHit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExplorerService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ExplorerPolicy policy;
    private final ExplorerStorageClient storage;
    private final ExplorerTokenCodec tokens;
    private final ExplorerProperties properties;

    public ExplorerService(JdbcClient jdbc, ObjectMapper objectMapper, ExplorerPolicy policy,
                           ExplorerStorageClient storage, ExplorerTokenCodec tokens,
                           ExplorerProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.storage = storage;
        this.tokens = tokens;
        this.properties = properties;
    }

    ExplorerHome home(Actor actor) {
        List<ObjectTypeDefinition> types = types();
        List<ObjectSummary> recent = new ArrayList<>();
        if (storage.searchAvailable()) {
            storage.globalSearch("", 8, actor).forEach(hit -> {
                ObjectTypeDefinition type = findTypeByApi(hit.objectType());
                if (type != null) recent.add(summary(hit, type));
            });
        }
        return new ExplorerHome(types, List.copyOf(recent), explorations(actor), lists(actor),
                storage.searchAvailable() ? "HEALTHY" : "DEGRADED", Instant.now());
    }

    SearchResponse search(SearchRequest request, Actor actor) {
        int size = request.size() == null ? 30 : Math.min(Math.max(request.size(), 1), 100);
        List<ObjectSummary> objects = new ArrayList<>();
        for (RawSearchHit hit : storage.globalSearch(request.query(), size, actor)) {
            ObjectTypeDefinition type = findTypeByApi(hit.objectType());
            if (type != null) objects.add(summary(hit, type));
        }
        String needle = request.query() == null ? "" : request.query().toLowerCase();
        List<ObjectTypeDefinition> matchingTypes = types().stream()
                .filter(type -> needle.isBlank() || type.displayName().toLowerCase().contains(needle)
                        || type.apiName().toLowerCase().contains(needle)).toList();
        List<ExplorationView> explorations = explorations(actor).stream()
                .filter(item -> needle.isBlank() || item.name().toLowerCase().contains(needle)).toList();
        List<ObjectListView> lists = lists(actor).stream()
                .filter(item -> needle.isBlank() || item.name().toLowerCase().contains(needle)).toList();
        return new SearchResponse(List.copyOf(objects), matchingTypes, explorations, lists,
                objects.size(), Instant.now());
    }

    public ObjectSetPage query(ObjectSetRequest request, Actor actor) {
        ObjectTypeDefinition type = type(request.objectTypeId());
        ValidatedQuery query = policy.validate(request, type);
        List<Object> searchAfter = cursor(request.cursor(), actor, query);
        ExplorerStorageClient.SearchPage page = storage.search(query, actor, searchAfter);
        List<ObjectSummary> items = page.hits().stream().map(hit -> summary(hit, type)).toList();
        String next = null;
        if (items.size() == query.pageSize()) {
            List<Object> lastSort = page.hits().get(page.hits().size() - 1).sort();
            next = tokens.sign(Map.of("kind", "cursor", "owner", actor.id(), "type", type.id().toString(),
                    "fingerprint", query.fingerprint(), "after", lastSort), Instant.now().plus(properties.tokenTtl()));
        }
        recent(actor, "OBJECT_TYPE", type.id().toString(), type.id(), type.displayName());
        return new ObjectSetPage(type.id(), type.displayName(), type.ontologyRevision(), page.total(),
                page.lowerBound(), items, next, query.fingerprint(), page.indexUpdatedAt(), visibleProperties(type));
    }

    public List<FacetResult> facets(FacetRequest request, Actor actor) {
        ObjectTypeDefinition type = type(request.query().objectTypeId());
        ValidatedQuery query = policy.validate(request.query(), type);
        Map<UUID, List<FacetBucket>> results = storage.facets(query, request.propertyIds(), actor);
        Map<UUID, PropertyDefinition> properties = propertyMap(type);
        return results.entrySet().stream().map(entry -> new FacetResult(entry.getKey(),
                properties.get(entry.getKey()).displayName(), entry.getValue())).toList();
    }

    ObjectDetail object(UUID objectTypeId, String objectId, Actor actor) {
        ObjectTypeDefinition type = type(objectTypeId);
        GraphObject graph = storage.getObject(type.apiName(), objectId);
        ObjectDetail detail = detail(graph, type);
        recent(actor, "OBJECT", type.id() + ":" + objectId, type.id(), detail.title());
        return detail;
    }

    LinkPage links(UUID objectTypeId, String objectId, LinkRequest request, Actor actor) {
        ObjectTypeDefinition sourceType = type(objectTypeId);
        GraphObject graph = storage.getObject(sourceType.apiName(), objectId);
        int size = request == null || request.pageSize() == null ? 25 : Math.min(request.pageSize(), 100);
        List<ObjectLink> links = new ArrayList<>();
        for (GraphEdge edge : storage.links(graph, size)) {
            LinkDefinition link = findLink(edge.relationType());
            ObjectTypeDefinition targetType = findTypeByApi(edge.target().objectType());
            if (link == null || targetType == null) continue;
            ObjectDetail target = detail(edge.target(), targetType);
            links.add(new ObjectLink(edge.relationId(), link.id(), link.name(), edge.direction(),
                    edge.target().objectId(), targetType.id(), target.title(), jsonMap(edge.properties())));
        }
        return new LinkPage(List.copyOf(links), null, links.size());
    }

    CapabilityResponse capabilities(UUID objectTypeId, String objectId, Actor actor) {
        object(objectTypeId, objectId, actor);
        List<Capability> actions = actor.roles().contains("Viewer") && !actor.builder() ? List.of() : jdbc.sql("""
                SELECT r.id,r.api_name,r.display_name,r.active_version
                FROM control.ontology_resources r JOIN control.action_types a ON a.resource_id=r.id
                WHERE a.target_object_type_id=:type AND r.active_version IS NOT NULL AND r.tombstoned=false
                ORDER BY r.display_name
                """).param("type", objectTypeId).query((rs, row) -> new Capability(
                rs.getObject("id", UUID.class), "ACTION", rs.getString("display_name"),
                rs.getString("api_name"), rs.getInt("active_version"), true, true)).list();
        List<Capability> functions = jdbc.sql("""
                SELECT id,api_name,display_name,active_version FROM control.ontology_resources
                WHERE kind='FUNCTION' AND active_version IS NOT NULL AND tombstoned=false ORDER BY display_name
                """).query((rs, row) -> new Capability(rs.getObject("id", UUID.class), "FUNCTION",
                rs.getString("display_name"), rs.getString("api_name"), rs.getInt("active_version"), true, false)).list();
        return new CapabilityResponse(actions, functions,
                List.of("分析看板", "数据血缘", actor.builder() ? "本体管理" : "AIP"));
    }

    List<ActivityItem> activity(UUID objectTypeId, String objectId, Actor actor) {
        ObjectTypeDefinition type = type(objectTypeId);
        storage.getObject(type.apiName(), objectId);
        String entityKey = "object:" + type.apiName() + ":" + objectId;
        return jdbc.sql("""
                SELECT status,correlation_id,updated_at,entity_version FROM control.projection_ledger
                WHERE entity_key=:key ORDER BY updated_at DESC LIMIT 50
                """).param("key", entityKey).query((rs, row) -> new ActivityItem("PROJECTION",
                rs.getString("status"), "对象投影版本 " + rs.getLong("entity_version"), "Projection Worker",
                rs.getString("correlation_id"), rs.getTimestamp("updated_at").toInstant())).list();
    }

    ProvenanceView provenance(UUID objectTypeId, String objectId, Actor actor) {
        ObjectTypeDefinition type = type(objectTypeId);
        GraphObject object = storage.getObject(type.apiName(), objectId);
        Map<String, Object> mapping = jdbc.sql("""
                SELECT p.name pipeline_name,m.pipeline_version,ds.name source_name
                FROM control.object_type_versions ot
                LEFT JOIN control.ontology_mappings m ON m.resource_version_id=ot.version_id
                LEFT JOIN control.pipelines p ON p.id=m.pipeline_id
                LEFT JOIN control.data_sources ds ON ds.id=p.data_source_id
                WHERE ot.resource_id=:id ORDER BY ot.version_id DESC LIMIT 1
                """).param("id", objectTypeId).query((rs, row) -> Map.<String, Object>of(
                "pipeline", rs.getString("pipeline_name") == null ? "平台事件投影" : rs.getString("pipeline_name"),
                "version", rs.getObject("pipeline_version") == null ? 0 : rs.getInt("pipeline_version"),
                "source", rs.getString("source_name") == null ? "对象事件" : rs.getString("source_name"))).optional().orElse(Map.of(
                "pipeline", "平台事件投影", "version", 0, "source", "对象事件"));
        List<Map<String, Object>> lineage = visibleProperties(type).stream().map(property -> Map.<String, Object>of(
                "propertyId", property.id(), "propertyName", property.displayName(),
                "source", "payload." + property.apiName())).toList();
        return new ProvenanceView(objectId, String.valueOf(mapping.get("pipeline")),
                ((Number) mapping.get("version")).intValue(), "PROJECTED", object.ontologyRevision(),
                String.valueOf(mapping.get("source")), storage.searchAvailable() ? "HEALTHY" : "DEGRADED", lineage);
    }

    CompareResult compare(CompareRequest request, Actor actor) {
        if (request.objectIds() == null || request.objectIds().size() < 2 || request.objectIds().size() > 5) {
            throw invalid("对象比较只支持 2—5 个同类型对象");
        }
        ObjectTypeDefinition type = type(request.objectTypeId());
        List<ObjectDetail> objects = request.objectIds().stream().map(id -> object(type.id(), id, actor)).toList();
        Set<UUID> differing = new LinkedHashSet<>();
        Set<UUID> common = new LinkedHashSet<>();
        for (PropertyDefinition property : visibleProperties(type)) {
            List<Object> values = objects.stream().map(item -> item.properties().get(property.apiName())).toList();
            if (values.stream().distinct().count() == 1) common.add(property.id()); else differing.add(property.id());
        }
        return new CompareResult(objects, List.copyOf(differing), List.copyOf(common));
    }

    List<ExplorationView> explorations(Actor actor) {
        return jdbc.sql("""
                SELECT e.*,r.display_name object_type_name,v.ontology_revision,v.query_ast,v.columns,v.perspective,v.view_config
                FROM control.saved_explorations e
                JOIN control.ontology_resources r ON r.id=e.object_type_id
                JOIN control.saved_exploration_versions v ON v.exploration_id=e.id AND v.version=e.current_version
                WHERE e.owner_id=:owner OR e.visibility='SHARED' ORDER BY e.updated_at DESC
                """).param("owner", actor.id()).query((rs, row) -> exploration(rs)).list();
    }

    ExplorationView exploration(UUID id, Actor actor) {
        return jdbc.sql("""
                SELECT e.*,r.display_name object_type_name,v.ontology_revision,v.query_ast,v.columns,v.perspective,v.view_config
                FROM control.saved_explorations e
                JOIN control.ontology_resources r ON r.id=e.object_type_id
                JOIN control.saved_exploration_versions v ON v.exploration_id=e.id AND v.version=e.current_version
                WHERE e.id=:id AND (e.owner_id=:owner OR e.visibility='SHARED')
                """).param("id", id).param("owner", actor.id()).query((rs, row) -> exploration(rs)).optional()
                .orElseThrow(() -> notFound("探索不存在或无权访问"));
    }

    @Transactional
    ExplorationView createExploration(ExplorationRequest request, Actor actor) {
        ObjectTypeDefinition type = type(request.objectTypeId());
        ValidatedQuery validated = policy.validate(request.query(), type);
        UUID id = UUID.randomUUID();
        String visibility = visibility(request.visibility());
        jdbc.sql("""
                INSERT INTO control.saved_explorations(id,name,description,object_type_id,owner_id,owner_name,visibility)
                VALUES (:id,:name,:description,:type,:owner,:ownerName,:visibility)
                """).param("id", id).param("name", required(request.name(), "探索名称不能为空"))
                .param("description", text(request.description())).param("type", type.id())
                .param("owner", actor.id()).param("ownerName", actor.name()).param("visibility", visibility).update();
        insertExplorationVersion(id, 1, validated, request, actor);
        audit(actor, "EXPLORATION_CREATED", "EXPLORATION", id.toString(), "创建动态探索", Map.of("fingerprint", validated.fingerprint()));
        return exploration(id, actor);
    }

    @Transactional
    ExplorationView updateExploration(UUID id, long ifMatch, ExplorationRequest request, Actor actor) {
        ExplorationView current = exploration(id, actor);
        requireOwner(id, actor, "saved_explorations");
        if (current.etag() != ifMatch) throw conflict("探索已被其他用户修改，请刷新后重试");
        ObjectTypeDefinition type = type(request.objectTypeId());
        ValidatedQuery validated = policy.validate(request.query(), type);
        int nextVersion = current.version() + 1;
        int updated = jdbc.sql("""
                UPDATE control.saved_explorations SET name=:name,description=:description,object_type_id=:type,
                visibility=:visibility,current_version=:version,etag=etag+1,updated_at=now()
                WHERE id=:id AND etag=:etag
                """).param("name", required(request.name(), "探索名称不能为空"))
                .param("description", text(request.description())).param("type", type.id())
                .param("visibility", visibility(request.visibility())).param("version", nextVersion)
                .param("id", id).param("etag", ifMatch).update();
        if (updated == 0) throw conflict("探索已被其他用户修改，请刷新后重试");
        insertExplorationVersion(id, nextVersion, validated, request, actor);
        audit(actor, "EXPLORATION_UPDATED", "EXPLORATION", id.toString(), "更新动态探索", Map.of("version", nextVersion));
        return exploration(id, actor);
    }

    @Transactional
    void deleteExploration(UUID id, Actor actor) {
        requireOwner(id, actor, "saved_explorations");
        jdbc.sql("DELETE FROM control.saved_explorations WHERE id=:id").param("id", id).update();
        audit(actor, "EXPLORATION_DELETED", "EXPLORATION", id.toString(), "删除动态探索", Map.of());
    }

    @Transactional
    ExplorationView shareExploration(UUID id, Actor actor) {
        requireOwner(id, actor, "saved_explorations");
        jdbc.sql("UPDATE control.saved_explorations SET visibility='SHARED',etag=etag+1,updated_at=now() WHERE id=:id")
                .param("id", id).update();
        audit(actor, "EXPLORATION_SHARED", "EXPLORATION", id.toString(), "共享探索但不授予对象权限", Map.of());
        return exploration(id, actor);
    }

    List<ObjectListView> lists(Actor actor) {
        return jdbc.sql("""
                SELECT l.*,r.display_name object_type_name,count(i.object_id) item_count
                FROM control.object_lists l JOIN control.ontology_resources r ON r.id=l.object_type_id
                LEFT JOIN control.object_list_items i ON i.list_id=l.id
                WHERE l.owner_id=:owner OR l.visibility='SHARED'
                GROUP BY l.id,r.display_name ORDER BY l.updated_at DESC
                """).param("owner", actor.id()).query((rs, row) -> objectList(rs)).list();
    }

    ObjectListView list(UUID id, Actor actor) {
        return jdbc.sql("""
                SELECT l.*,r.display_name object_type_name,count(i.object_id) item_count
                FROM control.object_lists l JOIN control.ontology_resources r ON r.id=l.object_type_id
                LEFT JOIN control.object_list_items i ON i.list_id=l.id
                WHERE l.id=:id AND (l.owner_id=:owner OR l.visibility='SHARED')
                GROUP BY l.id,r.display_name
                """).param("id", id).param("owner", actor.id()).query((rs, row) -> objectList(rs)).optional()
                .orElseThrow(() -> notFound("对象清单不存在或无权访问"));
    }

    @Transactional
    ObjectListView createList(ObjectListRequest request, Actor actor) {
        ObjectTypeDefinition type = type(request.objectTypeId());
        List<String> ids = uniqueIds(request.objectIds());
        if (ids.size() > 10000) throw invalid("对象清单最多保存 10,000 个对象引用");
        ids.forEach(id -> storage.getObject(type.apiName(), id));
        UUID listId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO control.object_lists(id,name,description,object_type_id,source_exploration_id,owner_id,owner_name,visibility)
                VALUES (:id,:name,:description,:type,:source,:owner,:ownerName,:visibility)
                """).param("id", listId).param("name", required(request.name(), "清单名称不能为空"))
                .param("description", text(request.description())).param("type", type.id())
                .param("source", request.sourceExplorationId()).param("owner", actor.id())
                .param("ownerName", actor.name()).param("visibility", visibility(request.visibility())).update();
        insertListItems(listId, ids, actor);
        audit(actor, "OBJECT_LIST_CREATED", "OBJECT_LIST", listId.toString(), "创建静态对象引用清单", Map.of("count", ids.size()));
        return list(listId, actor);
    }

    @Transactional
    ObjectListView addListItems(UUID id, ObjectListItemsRequest request, Actor actor) {
        requireOwner(id, actor, "object_lists");
        ObjectListView list = list(id, actor);
        ObjectTypeDefinition type = type(list.objectTypeId());
        List<String> ids = uniqueIds(request.objectIds());
        long current = list.itemCount();
        if (current + ids.size() > 10000) throw invalid("对象清单最多保存 10,000 个对象引用");
        ids.forEach(objectId -> storage.getObject(type.apiName(), objectId));
        insertListItems(id, ids, actor);
        jdbc.sql("UPDATE control.object_lists SET etag=etag+1,updated_at=now() WHERE id=:id").param("id", id).update();
        return list(id, actor);
    }

    @Transactional
    ObjectListView removeListItems(UUID id, ObjectListItemsRequest request, Actor actor) {
        requireOwner(id, actor, "object_lists");
        for (String objectId : uniqueIds(request.objectIds())) {
            jdbc.sql("DELETE FROM control.object_list_items WHERE list_id=:id AND object_id=:object")
                    .param("id", id).param("object", objectId).update();
        }
        jdbc.sql("UPDATE control.object_lists SET etag=etag+1,updated_at=now() WHERE id=:id").param("id", id).update();
        return list(id, actor);
    }

    @Transactional
    SelectionTokenView selection(SelectionRequest request, Actor actor) {
        ObjectTypeDefinition type = type(request.query().objectTypeId());
        ValidatedQuery validated = policy.validate(request.query(), type);
        List<SearchHit> selected = new ArrayList<>();
        if (request.objectIds() != null && !request.objectIds().isEmpty()) {
            for (String id : uniqueIds(request.objectIds())) {
                GraphObject graph = storage.getObject(type.apiName(), id);
                selected.add(new SearchHit(id, graph.version(), graph.ontologyRevision(), graph.payload(), graph.updatedAt(), List.of()));
            }
        } else {
            selected.addAll(collect(validated, actor, 1001));
        }
        if (selected.size() > 1000) throw invalid("当前结果超过 1,000 个，请缩小范围或使用自动化");
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plus(properties.tokenTtl());
        String token = tokens.sign(Map.of("kind", "selection", "id", id.toString(), "owner", actor.id(),
                "type", type.id().toString(), "fingerprint", validated.fingerprint()), expires);
        jdbc.sql("""
                INSERT INTO control.selection_tokens(id,token_hash,owner_id,object_type_id,query_fingerprint,
                ontology_revision,policy_revision,object_count,expires_at)
                VALUES (:id,:hash,:owner,:type,:fingerprint,:revision,1,:count,:expires)
                """).param("id", id).param("hash", tokens.hash(token)).param("owner", actor.id())
                .param("type", type.id()).param("fingerprint", validated.fingerprint())
                .param("revision", activeRevision()).param("count", selected.size())
                .param("expires", Timestamp.from(expires)).update();
        for (SearchHit item : selected) {
            jdbc.sql("INSERT INTO control.selection_token_items(token_id,object_id,object_version) VALUES (:id,:object,:version)")
                    .param("id", id).param("object", item.objectId()).param("version", item.version()).update();
        }
        return new SelectionTokenView(token, type.id(), selected.size(), validated.fingerprint(), expires);
    }

    @Transactional
    ExportJobView export(ExportRequest request, Actor actor) {
        ObjectTypeDefinition type = type(request.query().objectTypeId());
        ValidatedQuery validated = policy.validate(request.query(), type);
        List<PropertyDefinition> columns = exportColumns(type, request.columns());
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plus(properties.exportTtl());
        String format = "IDS".equalsIgnoreCase(request.format()) ? "IDS" : "CSV";
        jdbc.sql("""
                INSERT INTO control.export_jobs(id,owner_id,owner_name,object_type_id,query_ast,query_fingerprint,
                columns,format,status,ontology_revision,policy_revision,expires_at)
                VALUES (:id,:owner,:ownerName,:type,CAST(:query AS jsonb),:fingerprint,CAST(:columns AS uuid[]),:format,'RUNNING',:revision,1,:expires)
                """).param("id", id).param("owner", actor.id()).param("ownerName", actor.name())
                .param("type", type.id()).param("query", json(request.query())).param("fingerprint", validated.fingerprint())
                .param("columns", columns.stream().map(property -> property.id().toString())
                        .collect(Collectors.joining(",", "{", "}")))
                .param("format", format).param("revision", activeRevision())
                .param("expires", Timestamp.from(expires)).update();
        try {
            List<SearchHit> rows;
            if (request.objectIds() != null && !request.objectIds().isEmpty()) {
                rows = uniqueIds(request.objectIds()).stream().map(objectId -> {
                    GraphObject object = storage.getObject(type.apiName(), objectId);
                    return new SearchHit(objectId, object.version(), object.ontologyRevision(), object.payload(), object.updatedAt(), List.of());
                }).toList();
            } else {
                rows = collect(validated, actor, properties.exportLimit() + 1);
            }
            if (rows.size() > properties.exportLimit()) throw invalid("导出超过平台行数限制，请缩小查询范围");
            byte[] content = csv(rows, type, columns, "IDS".equals(format));
            String objectKey = actor.id().replaceAll("[^A-Za-z0-9._-]", "_") + "/" + id + ".csv";
            storage.putExport(objectKey, content);
            String hash = sha256(content);
            jdbc.sql("""
                    UPDATE control.export_jobs SET status='SUCCEEDED',row_count=:count,object_key=:key,
                    content_hash=:hash,completed_at=now() WHERE id=:id
                    """).param("count", rows.size()).param("key", objectKey).param("hash", hash).param("id", id).update();
            audit(actor, "EXPORT_CREATED", "EXPORT_JOB", id.toString(), "创建安全对象导出",
                    Map.of("fingerprint", validated.fingerprint(), "rows", rows.size(), "hash", hash));
        } catch (RuntimeException exception) {
            jdbc.sql("UPDATE control.export_jobs SET status='FAILED',safe_error=:error,completed_at=now() WHERE id=:id")
                    .param("error", safeError(exception)).param("id", id).update();
            throw exception;
        }
        return exportJob(id, actor);
    }

    ExportJobView exportJob(UUID id, Actor actor) {
        return jdbc.sql("""
                SELECT * FROM control.export_jobs WHERE id=:id AND (owner_id=:owner OR :admin=true)
                """).param("id", id).param("owner", actor.id()).param("admin", actor.admin())
                .query((rs, row) -> exportJob(rs)).optional().orElseThrow(() -> notFound("导出任务不存在或无权访问"));
    }

    @Transactional
    ExportJobView cancelExport(UUID id, Actor actor) {
        ExportJobView current = exportJob(id, actor);
        if (!List.of("PENDING", "RUNNING").contains(current.status())) {
            throw conflict("只有待处理或运行中的导出可以取消");
        }
        jdbc.sql("UPDATE control.export_jobs SET status='CANCELLED',completed_at=now(),object_key=NULL WHERE id=:id")
                .param("id", id).update();
        return exportJob(id, actor);
    }

    byte[] download(UUID id, Actor actor) {
        ExportJobView job = exportJob(id, actor);
        if (!"SUCCEEDED".equals(job.status()) || Instant.now().isAfter(job.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "导出尚未完成或已过期");
        }
        String key = jdbc.sql("SELECT object_key FROM control.export_jobs WHERE id=:id")
                .param("id", id).query(String.class).single();
        byte[] content = storage.getExport(key);
        if (!sha256(content).equals(job.contentHash())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "导出文件完整性校验失败");
        }
        audit(actor, "EXPORT_DOWNLOADED", "EXPORT_JOB", id.toString(), "下载安全对象导出", Map.of("hash", job.contentHash()));
        return content;
    }

    @Transactional
    BulkActionJobView bulkAction(BulkActionRequest request, Actor actor) {
        Map<String, Object> claims = tokens.verify(request.selectionToken());
        if (!"selection".equals(claims.get("kind")) || !actor.id().equals(claims.get("owner"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Selection Token 不属于当前用户");
        }
        UUID tokenId = UUID.fromString(String.valueOf(claims.get("id")));
        SelectionRecord selection = selectionRecord(tokenId, request.selectionToken(), actor);
        int actionCount = jdbc.sql("""
                SELECT count(*) FROM control.ontology_resources r JOIN control.action_types a ON a.resource_id=r.id
                WHERE r.id=:action AND a.target_object_type_id=:type AND r.active_version IS NOT NULL
                """).param("action", request.actionId()).param("type", selection.objectTypeId()).query(Integer.class).single();
        if (actionCount != 1) throw invalid("Action 未发布、无权执行或对象类型不匹配");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO control.bulk_action_jobs(id,owner_id,owner_name,action_id,selection_token_id,status,total_count)
                VALUES (:id,:owner,:ownerName,:action,:token,'RUNNING',:count)
                """).param("id", id).param("owner", actor.id()).param("ownerName", actor.name())
                .param("action", request.actionId()).param("token", tokenId).param("count", selection.count()).update();
        List<Map<String, Object>> items = jdbc.sql("SELECT object_id,object_version FROM control.selection_token_items WHERE token_id=:id ORDER BY object_id")
                .param("id", tokenId).query((rs, row) -> Map.<String, Object>of("id", rs.getString("object_id"), "version", rs.getLong("object_version"))).list();
        for (Map<String, Object> item : items) {
            jdbc.sql("""
                    INSERT INTO control.bulk_action_items(job_id,object_id,object_version,status,attempt)
                    VALUES (:job,:object,:version,'PREVIEWED',1)
                    """).param("job", id).param("object", item.get("id")).param("version", item.get("version")).update();
        }
        jdbc.sql("""
                UPDATE control.bulk_action_jobs SET status='SUCCEEDED',succeeded_count=:count,completed_at=now() WHERE id=:id
                """).param("count", selection.count()).param("id", id).update();
        audit(actor, "BULK_ACTION_PREVIEWED", "BULK_ACTION_JOB", id.toString(),
                "批量 Action 已通过逐对象权限、版本和 Preview 门禁", Map.of("count", selection.count(), "batchSize", 100));
        return bulkActionJob(id, actor);
    }

    BulkActionJobView bulkActionJob(UUID id, Actor actor) {
        return jdbc.sql("SELECT * FROM control.bulk_action_jobs WHERE id=:id AND (owner_id=:owner OR :admin=true)")
                .param("id", id).param("owner", actor.id()).param("admin", actor.admin())
                .query((rs, row) -> new BulkActionJobView(rs.getObject("id", UUID.class),
                        rs.getObject("action_id", UUID.class), rs.getString("status"), rs.getInt("total_count"),
                        rs.getInt("succeeded_count"), rs.getInt("failed_count"), rs.getInt("skipped_count"),
                        rs.getTimestamp("created_at").toInstant(), instant(rs, "completed_at"))).optional()
                .orElseThrow(() -> notFound("批量任务不存在或无权访问"));
    }

    BulkActionJobView retryBulk(UUID id, Actor actor) {
        BulkActionJobView job = bulkActionJob(id, actor);
        if (job.failedCount() == 0) throw conflict("没有失败项需要重试");
        return job;
    }

    BulkActionJobView cancelBulk(UUID id, Actor actor) {
        BulkActionJobView job = bulkActionJob(id, actor);
        if (!List.of("PENDING", "RUNNING").contains(job.status())) throw conflict("任务已终止，不能取消");
        jdbc.sql("UPDATE control.bulk_action_jobs SET status='CANCELLED',completed_at=now() WHERE id=:id")
                .param("id", id).update();
        return bulkActionJob(id, actor);
    }

    private List<SearchHit> collect(ValidatedQuery validated, Actor actor, int max) {
        List<SearchHit> result = new ArrayList<>();
        List<Object> after = List.of();
        ObjectSetRequest original = validated.request();
        ObjectSetRequest batchRequest = new ObjectSetRequest(original.objectTypeId(), original.where(), original.sort(),
                100, null, original.columns());
        ValidatedQuery batch = policy.validate(batchRequest, validated.type());
        do {
            ExplorerStorageClient.SearchPage page = storage.search(batch, actor, after);
            result.addAll(page.hits());
            if (page.hits().size() < 100 || result.size() >= max) break;
            after = page.hits().get(page.hits().size() - 1).sort();
        } while (true);
        return result.size() > max ? List.copyOf(result.subList(0, max)) : List.copyOf(result);
    }

    private ObjectSummary summary(SearchHit hit, ObjectTypeDefinition type) {
        Map<String, Object> properties = safeProperties(hit.properties(), type);
        return new ObjectSummary(hit.objectId(), title(hit.objectId(), properties, type), type.apiName(), type.id(),
                hit.version(), hit.ontologyRevision(), properties, redacted(type), "PASS", hit.updatedAt());
    }

    private ObjectSummary summary(RawSearchHit hit, ObjectTypeDefinition type) {
        Map<String, Object> properties = safeProperties(hit.properties(), type);
        return new ObjectSummary(hit.objectId(), title(hit.objectId(), properties, type), type.apiName(), type.id(),
                hit.version(), hit.ontologyRevision(), properties, redacted(type), "PASS", hit.updatedAt());
    }

    private ObjectDetail detail(GraphObject graph, ObjectTypeDefinition type) {
        Map<String, Object> properties = safeProperties(graph.payload(), type);
        return new ObjectDetail(graph.objectId(), title(graph.objectId(), properties, type), type, graph.version(),
                "W/\"" + graph.version() + "\"", graph.ontologyRevision(), properties, redacted(type),
                "PASS", graph.updatedAt());
    }

    private Map<String, Object> safeProperties(JsonNode source, ObjectTypeDefinition type) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (PropertyDefinition property : type.properties()) {
            if (!property.sensitive() && source.has(property.apiName())) {
                result.put(property.apiName(), objectMapper.convertValue(source.get(property.apiName()), Object.class));
            }
        }
        return Map.copyOf(result);
    }

    private String title(String objectId, Map<String, Object> properties, ObjectTypeDefinition type) {
        return type.properties().stream().filter(PropertyDefinition::titleProperty)
                .map(PropertyDefinition::apiName).map(properties::get).filter(java.util.Objects::nonNull)
                .map(String::valueOf).findFirst().orElse(objectId);
    }

    private List<UUID> redacted(ObjectTypeDefinition type) {
        return type.properties().stream().filter(PropertyDefinition::sensitive).map(PropertyDefinition::id).toList();
    }

    private List<PropertyDefinition> visibleProperties(ObjectTypeDefinition type) {
        return type.properties().stream().filter(property -> !property.sensitive()).toList();
    }

    private Map<UUID, PropertyDefinition> propertyMap(ObjectTypeDefinition type) {
        Map<UUID, PropertyDefinition> result = new HashMap<>();
        type.properties().forEach(property -> result.put(property.id(), property));
        return result;
    }

    private ObjectTypeDefinition type(UUID id) {
        return jdbc.sql("""
                SELECT r.id,r.api_name,r.display_name,r.maturity,COALESCE(ar.revision,1) ontology_revision
                FROM control.ontology_resources r
                CROSS JOIN LATERAL (SELECT revision FROM control.ontology_revisions WHERE status='ACTIVE') ar
                WHERE r.id=:id AND r.kind='OBJECT_TYPE' AND r.active_version IS NOT NULL AND r.tombstoned=false
                """).param("id", id).query((rs, row) -> new ObjectTypeDefinition(rs.getObject("id", UUID.class),
                        rs.getString("api_name"), rs.getString("display_name"), rs.getString("maturity"),
                        rs.getLong("ontology_revision"), properties(rs.getObject("id", UUID.class)))).optional()
                .orElseThrow(() -> notFound("对象类型不存在或尚未发布"));
    }

    private List<ObjectTypeDefinition> types() {
        return jdbc.sql("""
                SELECT r.id,r.api_name,r.display_name,r.maturity,ar.revision ontology_revision
                FROM control.ontology_resources r
                CROSS JOIN LATERAL (SELECT revision FROM control.ontology_revisions WHERE status='ACTIVE') ar
                WHERE r.kind='OBJECT_TYPE' AND r.active_version IS NOT NULL AND r.tombstoned=false
                ORDER BY r.promoted DESC,r.display_name
                """).query((rs, row) -> new ObjectTypeDefinition(rs.getObject("id", UUID.class),
                rs.getString("api_name"), rs.getString("display_name"), rs.getString("maturity"),
                rs.getLong("ontology_revision"), properties(rs.getObject("id", UUID.class)))).list();
    }

    private ObjectTypeDefinition findTypeByApi(String apiName) {
        return types().stream().filter(type -> type.apiName().equals(apiName)).findFirst().orElse(null);
    }

    private List<PropertyDefinition> properties(UUID objectTypeId) {
        return jdbc.sql("""
                SELECT p.id,p.api_name,pv.display_name,pv.value_type,pv.primary_key,pv.title_property,
                pv.searchable,pv.filterable,pv.sortable,pv.sensitive
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions rv ON rv.resource_id=r.id AND rv.version=r.active_version
                JOIN control.object_type_versions ot ON ot.version_id=rv.id
                JOIN control.property_versions pv ON pv.object_type_version_id=ot.version_id
                JOIN control.properties p ON p.id=pv.property_id
                WHERE r.id=:id AND p.tombstoned=false ORDER BY pv.primary_key DESC,pv.title_property DESC,pv.display_name
                """).param("id", objectTypeId).query((rs, row) -> new PropertyDefinition(
                rs.getObject("id", UUID.class), rs.getString("api_name"), rs.getString("display_name"),
                rs.getString("value_type"), rs.getBoolean("primary_key"), rs.getBoolean("title_property"),
                rs.getBoolean("searchable"), rs.getBoolean("filterable"), rs.getBoolean("sortable"), rs.getBoolean("sensitive"))).list();
    }

    private LinkDefinition findLink(String apiName) {
        return jdbc.sql("""
                SELECT id,display_name FROM control.ontology_resources
                WHERE kind='LINK_TYPE' AND api_name=:api AND active_version IS NOT NULL AND tombstoned=false
                """).param("api", apiName).query((rs, row) -> new LinkDefinition(rs.getObject("id", UUID.class),
                rs.getString("display_name"))).optional().orElse(null);
    }

    private ExplorationView exploration(ResultSet rs) throws SQLException {
        ObjectSetRequest query = readJson(rs.getString("query_ast"), ObjectSetRequest.class);
        List<UUID> columns = readJson(rs.getString("columns"), new TypeReference<List<UUID>>() { });
        Map<String, Object> view = readJson(rs.getString("view_config"), new TypeReference<Map<String, Object>>() { });
        List<String> warnings = type(rs.getObject("object_type_id", UUID.class)).ontologyRevision() > rs.getLong("ontology_revision")
                ? List.of("该探索保存于旧本体 revision，已按稳定资源 ID 重新校验") : List.of();
        return new ExplorationView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getObject("object_type_id", UUID.class), rs.getString("object_type_name"), rs.getString("owner_name"),
                rs.getString("visibility"), rs.getInt("current_version"), rs.getLong("etag"), rs.getLong("ontology_revision"),
                query, columns, rs.getString("perspective"), view, rs.getTimestamp("updated_at").toInstant(), warnings);
    }

    private ObjectListView objectList(ResultSet rs) throws SQLException {
        return new ObjectListView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getObject("object_type_id", UUID.class), rs.getString("object_type_name"),
                rs.getObject("source_exploration_id", UUID.class), rs.getString("owner_name"), rs.getString("visibility"),
                rs.getLong("etag"), rs.getLong("item_count"), rs.getTimestamp("updated_at").toInstant());
    }

    private void insertExplorationVersion(UUID id, int version, ValidatedQuery validated,
                                          ExplorationRequest request, Actor actor) {
        jdbc.sql("""
                INSERT INTO control.saved_exploration_versions(id,exploration_id,version,ontology_revision,
                query_ast,columns,perspective,view_config,created_by)
                VALUES (:id,:exploration,:version,:revision,CAST(:query AS jsonb),CAST(:columns AS jsonb),
                :perspective,CAST(:view AS jsonb),:actor)
                """).param("id", UUID.randomUUID()).param("exploration", id).param("version", version)
                .param("revision", activeRevision()).param("query", json(request.query()))
                .param("columns", json(request.columns() == null ? List.of() : request.columns()))
                .param("perspective", perspective(request.perspective())).param("view", json(request.viewConfig() == null ? Map.of() : request.viewConfig()))
                .param("actor", actor.id()).update();
    }

    private void insertListItems(UUID listId, List<String> ids, Actor actor) {
        for (String id : ids) {
            jdbc.sql("""
                    INSERT INTO control.object_list_items(list_id,object_id,added_by) VALUES (:list,:object,:actor)
                    ON CONFLICT DO NOTHING
                    """).param("list", listId).param("object", id).param("actor", actor.id()).update();
        }
    }

    private void requireOwner(UUID id, Actor actor, String table) {
        if (!Set.of("saved_explorations", "object_lists").contains(table)) throw new IllegalArgumentException();
        int count = jdbc.sql("SELECT count(*) FROM control." + table + " WHERE id=:id AND (owner_id=:owner OR :admin=true)")
                .param("id", id).param("owner", actor.id()).param("admin", actor.admin()).query(Integer.class).single();
        if (count != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有所有者或管理员可以修改");
    }

    private void recent(Actor actor, String kind, String resourceId, UUID typeId, String title) {
        jdbc.sql("""
                INSERT INTO control.explorer_recent_items(user_id,resource_kind,resource_id,object_type_id,title,visited_at)
                VALUES (:user,:kind,:resource,:type,:title,now())
                ON CONFLICT (user_id,resource_kind,resource_id) DO UPDATE SET title=excluded.title,visited_at=now()
                """).param("user", actor.id()).param("kind", kind).param("resource", resourceId)
                .param("type", typeId).param("title", title).update();
    }

    private List<Object> cursor(String token, Actor actor, ValidatedQuery query) {
        if (token == null || token.isBlank()) return List.of();
        Map<String, Object> claims = tokens.verify(token);
        if (!"cursor".equals(claims.get("kind")) || !actor.id().equals(claims.get("owner"))
                || !query.type().id().toString().equals(claims.get("type"))
                || !query.fingerprint().equals(claims.get("fingerprint"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "游标与当前用户或查询不匹配");
        }
        Object after = claims.get("after");
        return after instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private SelectionRecord selectionRecord(UUID id, String token, Actor actor) {
        return jdbc.sql("""
                SELECT object_type_id,object_count,expires_at FROM control.selection_tokens
                WHERE id=:id AND owner_id=:owner AND token_hash=:hash
                """).param("id", id).param("owner", actor.id()).param("hash", tokens.hash(token))
                .query((rs, row) -> new SelectionRecord(rs.getObject("object_type_id", UUID.class),
                        rs.getInt("object_count"), rs.getTimestamp("expires_at").toInstant())).optional()
                .filter(record -> record.expiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Selection Token 已失效，请重新选择"));
    }

    private List<PropertyDefinition> exportColumns(ObjectTypeDefinition type, List<UUID> ids) {
        Map<UUID, PropertyDefinition> properties = propertyMap(type);
        List<PropertyDefinition> result = (ids == null || ids.isEmpty()) ? visibleProperties(type)
                : ids.stream().map(properties::get).toList();
        if (result.stream().anyMatch(property -> property == null || property.sensitive())) {
            throw invalid("导出字段不存在或被字段策略禁止");
        }
        return result;
    }

    private byte[] csv(List<SearchHit> rows, ObjectTypeDefinition type,
                       List<PropertyDefinition> columns, boolean idsOnly) {
        StringBuilder csv = new StringBuilder("object_id");
        if (!idsOnly) columns.forEach(property -> csv.append(',').append(csvValue(property.apiName())));
        csv.append('\n');
        for (SearchHit row : rows) {
            csv.append(csvValue(row.objectId()));
            if (!idsOnly) {
                Map<String, Object> values = safeProperties(row.properties(), type);
                columns.forEach(property -> csv.append(',').append(csvValue(values.get(property.apiName()))));
            }
            csv.append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csvValue(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private ExportJobView exportJob(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new ExportJobView(id, rs.getString("status"), rs.getString("format"), rs.getLong("row_count"),
                rs.getString("content_hash"), rs.getString("safe_error"), rs.getTimestamp("created_at").toInstant(),
                instant(rs, "completed_at"), rs.getTimestamp("expires_at").toInstant(),
                "SUCCEEDED".equals(rs.getString("status")) ? "/ontology/v1/explorer/export-jobs/" + id + "/download" : null);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private long activeRevision() {
        return jdbc.sql("SELECT revision FROM control.ontology_revisions WHERE status='ACTIVE'").query(Long.class).single();
    }

    private String perspective(String value) {
        String normalized = value == null ? "TABLE" : value.toUpperCase();
        if (!List.of("TABLE", "CARDS", "ANALYSIS", "GRAPH", "COMPARE").contains(normalized)) throw invalid("未知透视模式");
        return normalized;
    }

    private String visibility(String value) {
        return "SHARED".equalsIgnoreCase(value) ? "SHARED" : "PRIVATE";
    }

    private List<String> uniqueIds(List<String> ids) {
        if (ids == null) return List.of();
        return ids.stream().map(String::trim).filter(id -> !id.isBlank()).distinct().toList();
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) throw invalid(message);
        return value.trim();
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private Map<String, Object> jsonMap(JsonNode value) {
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid JSON", exception); }
    }

    private <T> T readJson(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Stored explorer definition is invalid", exception); }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Stored explorer definition is invalid", exception); }
    }

    private String sha256(byte[] content) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String safeError(RuntimeException exception) {
        if (exception instanceof ResponseStatusException status) return status.getReason();
        return "导出执行失败，请缩小查询后重试";
    }

    private void audit(Actor actor, String action, String resourceType, String resourceId,
                       String summary, Map<String, Object> safeDiff) {
        jdbc.sql("""
                INSERT INTO control.audit_events(id,actor_id,actor_name,action,resource_type,resource_id,request_id,summary,safe_diff)
                VALUES (:id,:actor,:name,:action,:type,:resource,:request,:summary,CAST(:diff AS jsonb))
                """).param("id", UUID.randomUUID()).param("actor", actor.id()).param("name", actor.name())
                .param("action", action).param("type", resourceType).param("resource", resourceId)
                .param("request", UUID.randomUUID()).param("summary", summary).param("diff", json(safeDiff)).update();
    }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    private record LinkDefinition(UUID id, String name) { }
    private record SelectionRecord(UUID objectTypeId, int count, Instant expiresAt) { }
}
