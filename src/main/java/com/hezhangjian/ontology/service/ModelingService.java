package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.service.ModelingModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.contracts.projection.MutationEdit;
import com.hezhangjian.ontology.contracts.projection.OntologyMutationBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.exception.ConnectionProblem;
import com.hezhangjian.ontology.instance.ObjectInstanceAuthorityReader;
import com.hezhangjian.ontology.client.ModelingInfrastructureProbe;
import com.hezhangjian.ontology.messaging.ActionMutationPublisher;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import com.hezhangjian.ontology.repo.SqlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.ForeignKeyProjectionCoordinator;

@Service
public class ModelingService {
    private final Map<String, FunctionCacheEntry> functionCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Pattern API_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,159}");
    private final SqlRepository jdbc;
    private final ObjectMapper json;
    private final TaskExecutor tasks;
    private final ModelingInfrastructureProbe infrastructure;
    private final ModelingPolicy policy;
    private final TransactionTemplate transactions;
    private final ResourceDeletionService deletion;
    private final ObjectInstanceAuthorityReader objectInstances;
    private final ActionMutationPublisher actionPublisher;
    private final FunctionRuntimeService functionRuntime;
    private final HugeGraphProjectionClient graph;
    private final OpenSearchProjectionClient search;
    private final ForeignKeyProjectionCoordinator foreignKeys;
    private final ModelingInstanceStatistics instanceStatistics;

    public ModelingService(SqlRepository jdbc, ObjectMapper json,
                           @Qualifier("applicationTaskExecutor") TaskExecutor tasks,
                           ModelingInfrastructureProbe infrastructure,
                           ModelingPolicy policy,
                           PlatformTransactionManager transactionManager,
                           ResourceDeletionService deletion,
                           ObjectInstanceAuthorityReader objectInstances,
                           ActionMutationPublisher actionPublisher,
                           FunctionRuntimeService functionRuntime,
                           HugeGraphProjectionClient graph,
                           OpenSearchProjectionClient search,
                           ForeignKeyProjectionCoordinator foreignKeys,
                           ModelingInstanceStatistics instanceStatistics) {
        this.jdbc = jdbc;
        this.json = json;
        this.tasks = tasks;
        this.infrastructure = infrastructure;
        this.policy = policy;
        this.transactions = new TransactionTemplate(transactionManager);
        this.deletion = deletion;
        this.objectInstances = objectInstances;
        this.actionPublisher = actionPublisher;
        this.functionRuntime = functionRuntime;
        this.graph = graph;
        this.search = search;
        this.foreignKeys = foreignKeys;
        this.instanceStatistics = instanceStatistics;
    }

    public ModelingSummary summary() { return summary(OntologyLookupService.DEFAULT_ONTOLOGY_ID); }

    public ModelingSummary summary(UUID ontologyId) {
        long critical = count(ontologyId, "SELECT count(*) FROM control.ontology_health_issues WHERE ontology_id=? AND status='OPEN' AND severity IN ('ERROR','CRITICAL')");
        long projectionFailures = count(ontologyId, """
                SELECT count(*) FROM control.projection_ledger failed
                WHERE failed.ontology_id=? AND failed.status IN ('DEGRADED','DLQ')
                  AND NOT EXISTS (
                    SELECT 1 FROM control.projection_ledger recovered
                    WHERE recovered.ontology_id=failed.ontology_id
                      AND recovered.entity_key=failed.entity_key
                      AND recovered.projection_sequence>failed.projection_sequence
                      AND recovered.status='PROJECTED')
                """);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ResourceKind kind : ResourceKind.values()) {
            counts.put(kind.name(), Objects.requireNonNullElse(jdbc.queryForObject(
                    "SELECT count(*) FROM control.ontology_resources WHERE ontology_id=? AND kind=?",
                    Long.class, ontologyId, kind.name()), 0L));
        }
        Map<String, Long> objectInstances = instanceStatistics.objectCounts(ontologyId);
        Map<String, Long> relationInstances = instanceStatistics.relationCounts(ontologyId);
        String health = critical > 0 ? "DEGRADED" : "HEALTHY";
        return new ModelingSummary(health, critical, projectionFailures,
                counts, objectInstances, relationInstances,
                list(ontologyId, null, null).stream().limit(12).toList());
    }

    public List<SearchResult> search(String query) { return search(OntologyLookupService.DEFAULT_ONTOLOGY_ID, query); }

    public List<SearchResult> search(UUID ontologyId, String query) {
        String needle = "%" + safe(query).toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                SELECT r.id,r.kind,r.api_name,v.display_name,v.description,v.tags,v.lifecycle
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions v ON v.resource_id=r.id AND v.version=r.latest_version
                WHERE r.ontology_id=? AND (?='%%' OR lower(r.api_name) LIKE ? OR lower(v.display_name) LIKE ?
                  OR lower(v.description) LIKE ? OR EXISTS (
                    SELECT 1 FROM control.properties p JOIN control.property_versions pv ON pv.property_id=p.id
                    WHERE p.object_type_id=r.id AND lower(p.api_name) LIKE ?))
                ORDER BY v.display_name LIMIT 50
                """, (row, n) -> new SearchResult(row.getObject("id", UUID.class), ResourceKind.valueOf(row.getString("kind")),
                row.getString("api_name"), row.getString("display_name"), row.getString("description"),
                strings(row.getArray("tags")), row.getString("lifecycle")),
                ontologyId, needle, needle, needle, needle, needle);
    }

    public List<ResourceView> list(ResourceKind kind, String search) {
        return list(OntologyLookupService.DEFAULT_ONTOLOGY_ID, kind, search);
    }

    public List<ResourceView> list(UUID ontologyId, ResourceKind kind, String search) {
        String sql = """
                SELECT r.*,v.id version_id,v.lifecycle,v.display_name version_display_name,
                       v.description version_description,v.maturity version_maturity,v.promoted version_promoted,
                       v.tags version_tags,v.definition
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions v ON v.resource_id=r.id AND v.version=r.latest_version
                WHERE r.ontology_id=? AND (?::varchar IS NULL OR r.kind=?)
                  AND (?::varchar IS NULL OR lower(r.api_name) LIKE ? OR lower(v.display_name) LIKE ?)
                ORDER BY r.updated_at DESC,r.api_name
                """;
        String needle = search == null || search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
        return jdbc.query(sql, this::resource, ontologyId, kind == null ? null : kind.name(), kind == null ? null : kind.name(), needle, needle, needle);
    }

    public ResourceView get(UUID id) {
        return get(WorkspaceContext.id(), id);
    }

    public ResourceView get(UUID ontologyId, UUID id) {
        String sql = """
                SELECT r.*,v.id version_id,v.lifecycle,v.display_name version_display_name,
                       v.description version_description,v.maturity version_maturity,v.promoted version_promoted,
                       v.tags version_tags,v.definition
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions v ON v.resource_id=r.id AND v.version=r.latest_version
                WHERE r.id=? AND r.ontology_id=?
                """;
        return jdbc.query(sql, this::resource, id, ontologyId).stream().findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "本体资源不存在"));
    }

    public UUID resolveResource(UUID ontologyId, ResourceKind kind, String value) {
        if (value == null || value.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "本体资源不存在");
        }
        return jdbc.query("""
                SELECT id FROM control.ontology_resources
                WHERE ontology_id=? AND kind=? AND api_name=?
                """, (row, number) -> row.getObject(1, UUID.class),
                ontologyId, kind.name(), value).stream().findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "本体资源不存在"));
    }

    @Transactional
    public ResourceView updateIdentity(UUID ontologyId, UUID id, ResourceIdentityRequest request) {
        ResourceView current = get(ontologyId, id);
        if (request == null) throw problem("RESOURCE_IDENTITY_INVALID", "资源信息不能为空");
        String apiName = request.apiName() == null ? current.apiName() : request.apiName().trim();
        requireResourceId(apiName);
        String displayName = request.displayName() == null ? current.displayName() : request.displayName().trim();
        if (displayName.isEmpty() || displayName.length() > 240) {
            throw problem("DISPLAY_NAME_INVALID", "显示名称长度必须为 1 到 240 个字符");
        }
        String description = request.description() == null ? current.description() : request.description().trim();
        Integer duplicate = jdbc.queryForObject("""
                SELECT count(*) FROM control.ontology_resources
                WHERE ontology_id=? AND kind=? AND id<>? AND lower(api_name)=lower(?)
                """, Integer.class, ontologyId, current.kind().name(), id, apiName);
        if (duplicate != null && duplicate > 0) {
            throw problem("RESOURCE_API_NAME_CONFLICT", "资源 ID“" + apiName + "”已被当前本体中的有效资源使用");
        }
        jdbc.update("""
                UPDATE control.ontology_resources
                SET api_name=?,display_name=?,description=?,etag=etag+1,updated_at=now()
                WHERE id=? AND ontology_id=?
                """, apiName, displayName, description, id, ontologyId);
        jdbc.update("""
                UPDATE control.ontology_resource_versions
                SET display_name=?,description=?
                WHERE resource_id=? AND version=?
                """, displayName, description, id, current.version());
        return get(ontologyId, id);
    }

    public ObjectTypeBackingView objectTypeBacking(UUID ontologyId, UUID id) {
        ResourceView resource = get(ontologyId, id);
        if (resource.kind() != ResourceKind.OBJECT_TYPE) {
            throw problem("RESOURCE_KIND_INVALID", "目标资源不是对象类型");
        }
        record Backing(
                UUID versionId,
                String sourceMode,
                UUID pipelineId,
                String pipelineName,
                Integer pipelineVersion,
                String pipelineLifecycle,
                String lastRunStatus,
                String projectionStatus,
                Instant lastRunAt) { }
        Backing backing = jdbc.query("""
                SELECT rv.id version_id,ot.source_mode,ot.primary_pipeline_id,
                       p.name pipeline_name,p.published_version,p.lifecycle pipeline_lifecycle,
                       run.status last_run_status,run.projection_status,run.updated_at last_run_at
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions rv
                  ON rv.resource_id=r.id AND rv.version=r.latest_version
                JOIN control.object_type_versions ot ON ot.version_id=rv.id
                LEFT JOIN control.pipelines p ON p.id=ot.primary_pipeline_id
                LEFT JOIN LATERAL (
                  SELECT status,projection_status,updated_at
                  FROM control.pipeline_runs
                  WHERE pipeline_id=p.id
                  ORDER BY updated_at DESC
                  LIMIT 1
                ) run ON true
                WHERE r.id=? AND r.ontology_id=?
                """, (row, number) -> new Backing(
                row.getObject("version_id", UUID.class),
                row.getString("source_mode"),
                row.getObject("primary_pipeline_id", UUID.class),
                row.getString("pipeline_name"),
                nullableInteger(row, "published_version"),
                row.getString("pipeline_lifecycle"),
                row.getString("last_run_status"),
                row.getString("projection_status"),
                instant(row, "last_run_at")), id, ontologyId).stream().findFirst()
                .orElseThrow(() -> problem("OBJECT_TYPE_BACKING_MISSING", "对象类型缺少来源配置"));
        List<MappingView> mappings = jdbc.query("""
                SELECT m.property_id,p.api_name,pv.display_name,m.source_field,
                       m.sink_node_id,m.transform_path
                FROM control.ontology_mappings m
                JOIN control.properties p ON p.id=m.property_id
                JOIN control.property_versions pv
                  ON pv.property_id=p.id AND pv.object_type_version_id=m.resource_version_id
                WHERE m.resource_version_id=?
                ORDER BY pv.display_name,p.api_name
                """, (row, number) -> new MappingView(
                row.getObject("property_id", UUID.class),
                row.getString("api_name"),
                row.getString("display_name"),
                row.getString("source_field"),
                row.getString("sink_node_id"),
                strings(row.getArray("transform_path"))), backing.versionId());
        String status;
        if ("ACTION".equals(backing.sourceMode())) status = "READY";
        else if (backing.pipelineId() == null) status = "UNBOUND";
        else if (mappings.isEmpty()) status = "UNMAPPED";
        else if (!"PUBLISHED".equals(backing.pipelineLifecycle())) status = "DRAFT";
        else if ("FAILED".equals(backing.lastRunStatus())) status = "FAILED";
        else if ("PROJECTED".equals(backing.projectionStatus())) status = "HEALTHY";
        else status = "READY";
        return new ObjectTypeBackingView(
                backing.sourceMode(), backing.pipelineId(), backing.pipelineName(),
                backing.pipelineVersion(), backing.pipelineLifecycle(), mappings,
                backing.lastRunStatus(), backing.projectionStatus(), backing.lastRunAt(),
                mappings.size(), resource.properties().size(), status);
    }

    @Transactional
    public void delete(UUID id) {
        ResourceView resource = get(id);
        if (resource.kind() == ResourceKind.OBJECT_TYPE) {
            search.deleteObjectType(WorkspaceContext.id(), resource.physicalKey());
            graph.deleteObjectType(WorkspaceContext.id(), resource.physicalKey());
        } else if (resource.kind() == ResourceKind.LINK_TYPE) {
            search.deleteRelationType(WorkspaceContext.id(), resource.physicalKey());
            graph.deleteRelationType(resource.physicalKey());
        }
        deletion.deleteOntologyResource(id);
    }

    public List<PropertyView> properties(UUID objectTypeId) {
        return properties(null, objectTypeId);
    }

    private List<PropertyView> properties(UUID ontologyId, UUID objectTypeId) {
        String condition = (ontologyId == null ? "" : " AND r.ontology_id=?")
                + (objectTypeId == null ? "" : " AND p.object_type_id=?");
        List<Object> arguments = new ArrayList<>();
        if (ontologyId != null) arguments.add(ontologyId);
        if (objectTypeId != null) arguments.add(objectTypeId);
        return jdbc.query("""
                SELECT p.id,p.api_name,p.physical_key,pv.display_name,pv.description,pv.value_type,pv.required,
                       pv.primary_key,pv.title_property,pv.searchable,pv.filterable,pv.sortable,pv.sensitive,
                       pv.action_writable,pv.source_field
                FROM control.properties p
                JOIN control.ontology_resources r ON r.id=p.object_type_id
                JOIN control.object_type_versions otv ON otv.resource_id=r.id
                JOIN control.ontology_resource_versions rv ON rv.id=otv.version_id AND rv.version=r.latest_version
                JOIN control.property_versions pv ON pv.property_id=p.id AND pv.object_type_version_id=otv.version_id
                WHERE true
                """ + condition + " ORDER BY r.display_name,p.api_name",
                this::property, arguments.toArray());
    }

    public List<PropertyView> propertiesForOntology(UUID ontologyId, UUID objectTypeId) {
        if (objectTypeId != null) get(ontologyId, objectTypeId);
        return properties(ontologyId, objectTypeId);
    }

    @Transactional
    public ResourceView create(ResourceKind kind, ResourceDraftRequest request) {
        return create(OntologyLookupService.DEFAULT_ONTOLOGY_ID, kind, request);
    }

    @Transactional
    public ResourceView create(UUID ontologyId, ResourceKind kind, ResourceDraftRequest request) {
        validateCommon(request);
        Integer existing = jdbc.queryForObject("""
                SELECT count(*) FROM control.ontology_resources
                WHERE ontology_id=? AND kind=? AND lower(api_name)=lower(?)
                """, Integer.class, ontologyId, kind.name(), request.apiName().trim());
        if (existing != null && existing > 0) {
            throw problem("RESOURCE_API_NAME_CONFLICT",
                    "API 名称“" + request.apiName().trim() + "”已被当前本体中的有效资源使用；"
                            + "如需重建，请先删除现有资源。已删除资源不会占用该名称");
        }
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String physical = prefix(kind) + id.toString().replace("-", "").substring(0, 12);
        Map<String, Object> definition = definition(request);
        jdbc.update("""
                INSERT INTO control.ontology_resources
                    (id,ontology_id,kind,api_name,display_name,description,physical_key,maturity,promoted,tags)
                VALUES (?,?,?,?,?,?,?,?,?,COALESCE(ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),'{}'::text[]))
                """, id, ontologyId, kind.name(), request.apiName().trim(), request.displayName().trim(), safe(request.description()), physical,
                maturity(request.maturity()), request.promoted(), writeJson(list(request.tags())));
        insertVersion(id, versionId, 1, request, definition);
        insertTyped(kind, id, versionId, request);
        activateDirect(ontologyId, id, 1, kind);
        return get(id);
    }

    @Transactional
    public ResourceView createObjectDraft(UUID resourceId, ResourceDraftRequest request, long expectedEtag) {
        ResourceView current = get(resourceId);
        if (current.kind() != ResourceKind.OBJECT_TYPE) throw problem("RESOURCE_KIND_INVALID", "只有对象类型支持此草稿入口");
        if (current.etag() != expectedEtag) throw problem("ONTOLOGY_ETAG_CONFLICT", "资源已被其他用户修改，请重新加载后合并");
        if (request.apiName() != null && !request.apiName().equals(current.apiName())) throw problem("PUBLISHED_API_NAME_IMMUTABLE", "首次发布后的 API 名称不可直接修改");
        ResourceDraftRequest normalized = withStableApi(request, current.apiName());
        validateCommon(normalized);
        if (current.activeVersion() != null && primaryChanged(resourceId, normalized) && objectCount(resourceId) > 0) {
            throw problem("PRIMARY_KEY_IMMUTABLE", "已有对象的类型不能原地修改主键，请删除后重建对象类型");
        }
        int version = current.version() + 1;
        UUID versionId = UUID.randomUUID();
        insertVersion(resourceId, versionId, version, normalized, definition(normalized));
        insertObjectVersion(resourceId, versionId, normalized);
        jdbc.update("UPDATE control.ontology_resources SET latest_version=?,etag=etag+1,updated_at=now() WHERE id=?", version, resourceId);
        activateDirect(WorkspaceContext.id(), resourceId, version, ResourceKind.OBJECT_TYPE);
        return get(resourceId);
    }

    @Transactional
    public ResourceView createFunctionDraft(UUID resourceId, ResourceDraftRequest request, long expectedEtag) {
        ResourceView current = get(resourceId);
        if (current.kind() != ResourceKind.FUNCTION) throw problem("RESOURCE_KIND_INVALID", "只有 Function 支持此草稿入口");
        if (current.etag() != expectedEtag) throw problem("ONTOLOGY_ETAG_CONFLICT", "资源已被其他用户修改，请重新加载后合并");
        if (request.apiName() != null && !request.apiName().equals(current.apiName())) throw problem("PUBLISHED_API_NAME_IMMUTABLE", "首次发布后的 API 名称不可直接修改");
        ResourceDraftRequest normalized = withStableApi(request, current.apiName());
        validateCommon(normalized);
        policy.validateFunctionDsl(map(normalized.queryDsl()));
        int version = current.version() + 1;
        UUID versionId = UUID.randomUUID();
        insertVersion(resourceId, versionId, version, normalized, definition(normalized));
        insertFunctionVersion(resourceId, versionId, normalized);
        jdbc.update("UPDATE control.ontology_resources SET latest_version=?,etag=etag+1,updated_at=now() WHERE id=?", version, resourceId);
        activateDirect(WorkspaceContext.id(), resourceId, version, ResourceKind.FUNCTION);
        return get(resourceId);
    }

    private void activateDirect(UUID ontologyId, UUID resourceId, int version, ResourceKind kind) {
        jdbc.update("""
                UPDATE control.ontology_resource_versions
                SET lifecycle='PUBLISHED',published_at=now()
                WHERE resource_id=? AND version=?
                """, resourceId, version);
        jdbc.update("""
                UPDATE control.ontology_resources
                SET latest_version=?,active_version=?,updated_at=now()
                WHERE id=? AND ontology_id=?
                """, version, version, resourceId, ontologyId);
        ResourceView resource = get(ontologyId, resourceId);
        if (kind == ResourceKind.OBJECT_TYPE) {
            snapshotObject(ontologyId, resource);
        } else if (kind == ResourceKind.LINK_TYPE) {
            snapshotLink(ontologyId, resource);
        }
        if (kind == ResourceKind.OBJECT_TYPE || kind == ResourceKind.LINK_TYPE) {
            afterCommit(() -> WorkspaceContext.run(
                    ontologyId, () -> graph.reconcileSchema(ontologyId)));
        }
    }


    public List<HealthIssue> health() {
        return jdbc.query("""
                SELECT h.*,r.display_name resource_name FROM control.ontology_health_issues h
                LEFT JOIN control.ontology_resources r ON r.id=h.resource_id
                WHERE h.ontology_id=?
                ORDER BY CASE h.severity WHEN 'CRITICAL' THEN 1 WHEN 'ERROR' THEN 2 WHEN 'WARNING' THEN 3 ELSE 4 END,h.last_seen_at DESC
                """, (row, n) -> new HealthIssue(row.getObject("id", UUID.class), row.getString("severity"), row.getString("category"),
                row.getObject("resource_id", UUID.class), row.getString("resource_name"), row.getString("title"), row.getString("evidence"),
                row.getString("recommendation"), row.getString("status"), instant(row, "first_seen_at"), instant(row, "last_seen_at")),
                WorkspaceContext.id());
    }


    public ActionPreview previewAction(UUID id, ActionPreviewRequest request) {
        request = request == null ? new ActionPreviewRequest(Map.of(), null) : request;
        ResourceView action = get(id);
        if (action.kind() != ResourceKind.ACTION) throw problem("RESOURCE_KIND_INVALID", "目标资源不是 Action");
        String operation = upper(Objects.toString(action.definition().getOrDefault("operation", "UPDATE")));
        List<Map<String, Object>> rules = castList(action.definition().get("rules"));
        policy.validateActionRules(operation, rules);
        if (!"CREATE".equals(operation)
                && (request.objectId() == null || request.objectId().isBlank())) {
            throw problem("ACTION_OBJECT_REQUIRED", "Action Preview 必须指定目标对象");
        }
        UUID targetTypeId = UUID.fromString(Objects.toString(action.definition().get("targetObjectTypeId")));
        ResourceView targetType = get(targetTypeId);
        var currentInstance = "CREATE".equals(operation)
                ? null
                : objectInstances
                        .find(WorkspaceContext.id(), targetTypeId, request.objectId())
                        .orElseThrow(() -> problem(
                                "OBJECT_INSTANCE_NOT_FOUND", "Action 目标对象不存在"));
        JsonNode current = currentInstance == null
                ? null
                : objectInstances.effective(currentInstance);
        Map<String, Object> parameters = request.parameters() == null ? Map.of() : request.parameters();
        validateActionParameters(action, parameters);
        if (!conditionMatches(castMap(action.definition().get("submitCondition")),
                current, parameters)) {
            throw problem("ACTION_SUBMISSION_CONDITION_FAILED", "当前对象或参数不满足 Action 提交条件");
        }
        ObjectNode next =
                current == null ? json.createObjectNode() : ((ObjectNode) current).deepCopy();
        ObjectNode overrides = json.createObjectNode();
        List<Map<String, Object>> visibleDiff = new ArrayList<>();
        List<Map<String, Object>> edits = new ArrayList<>();
        if (List.of("CREATE", "UPDATE").contains(operation)) {
            for (Map<String, Object> rule : rules) {
                UUID propertyId = UUID.fromString(Objects.toString(rule.get("targetPropertyId")));
                PropertyView property = targetType.properties().stream()
                        .filter(value -> value.id().equals(propertyId)).findFirst()
                        .orElseThrow(() -> problem("ACTION_PROPERTY_INVALID", "Action 引用了不存在的目标属性"));
                if (property.primaryKey() || !property.actionWritable()) {
                    throw problem("ACTION_PROPERTY_NOT_WRITABLE", "Action 不能修改主键或未标记为 actionWritable 的属性");
                }
                Object value = actionValue(rule, parameters);
                JsonNode before = next.get(property.physicalKey());
                next.set(property.physicalKey(), json.valueToTree(value));
                overrides.set(property.physicalKey(), json.valueToTree(value));
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("operation", "SET_PROPERTY");
                change.put("propertyId", property.id());
                change.put("property", property.displayName());
                change.put("before", property.sensitive() ? "••••" : jsonValue(before));
                change.put("after", property.sensitive() ? "••••" : value);
                visibleDiff.add(change);
            }
            String objectId = actionObjectId(request, parameters, targetType, next);
            visibleDiff.forEach(change -> {
                change.put("targetTypeId", targetType.id());
                change.put("targetId", objectId);
            });
            if ("CREATE".equals(operation)) {
                visibleDiff.addFirst(new LinkedHashMap<>(Map.of(
                        "operation", "CREATE_OBJECT",
                        "targetTypeId", targetType.id(),
                        "targetId", objectId)));
            }
            Map<String, Object> edit = new LinkedHashMap<>();
            edit.put("operation", "CREATE".equals(operation) ? "object.create" : "object.update");
            edit.put("objectTypeId", targetType.physicalKey());
            edit.put("objectId", objectId);
            edit.put("properties", "CREATE".equals(operation) ? next : overrides);
            edits.add(edit);
        } else if ("DELETE".equals(operation)) {
            edits.add(new LinkedHashMap<>(Map.of(
                    "operation", "object.delete",
                    "objectTypeId", targetType.physicalKey(),
                    "objectId", request.objectId(),
                    "properties", Map.of())));
            visibleDiff.add(Map.of("operation", "DELETE_OBJECT", "targetTypeId", targetType.id(),
                    "targetId", request.objectId()));
        } else if ("CLEAR_OVERRIDES".equals(operation)) {
            edits.add(new LinkedHashMap<>(Map.of(
                    "operation", "object.clear_overrides",
                    "objectTypeId", targetType.physicalKey(),
                    "objectId", request.objectId(),
                    "properties", Map.of())));
            visibleDiff.add(Map.of("operation", "CLEAR_OVERRIDES", "targetTypeId", targetType.id(),
                    "targetId", request.objectId()));
        } else {
            for (Map<String, Object> rule : rules) {
                ResourceView relation = get(UUID.fromString(Objects.toString(rule.get("relationTypeId"))));
                if (relation.kind() != ResourceKind.LINK_TYPE) {
                    throw problem("ACTION_RELATION_INVALID", "Action 关系规则引用的不是关系类型");
                }
                ResourceView sourceType = get(UUID.fromString(
                        Objects.toString(relation.definition().get("leftObjectTypeId"))));
                ResourceView destinationType = get(UUID.fromString(
                        Objects.toString(relation.definition().get("rightObjectTypeId"))));
                String sourceId = actionEndpoint(rule, "source", parameters, request.objectId());
                String targetId = actionEndpoint(rule, "target", parameters, request.objectId());
                String relationId = actionEndpoint(rule, "relation", parameters,
                        fingerprint(relation.physicalKey() + ":" + sourceId + ":" + targetId));
                Map<String, Object> edit = new LinkedHashMap<>();
                edit.put("operation", "LINK".equals(operation) ? "relation.create" : "relation.delete");
                edit.put("relationTypeId", relation.physicalKey());
                edit.put("relationId", relationId);
                edit.put("sourceObjectTypeId", sourceType.physicalKey());
                edit.put("sourceObjectId", sourceId);
                edit.put("targetObjectTypeId", destinationType.physicalKey());
                edit.put("targetObjectId", targetId);
                edit.put("properties", rule.getOrDefault("properties", Map.of()));
                edits.add(edit);
                visibleDiff.add(Map.of("operation", operation, "targetTypeId", relation.id(),
                        "targetId", relationId, "sourceObjectId", sourceId,
                        "targetObjectId", targetId));
            }
        }
        Instant expires = Instant.now().plus(10, ChronoUnit.MINUTES);
        UUID previewId = UUID.randomUUID();
        String token = previewId + "." + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO control.action_previews(
                  id,ontology_id,action_id,action_version,object_id,expected_version,
                  parameters,edits,token_hash,expires_at)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)
                """, previewId, WorkspaceContext.id(), id, action.version(),
                request.objectId(), currentInstance == null ? null : currentInstance.version(),
                writeJson(parameters), writeJson(edits), fingerprint(token), Timestamp.from(expires));
        return new ActionPreview(previewId, id, action.version(), token, expires, visibleDiff);
    }

    @Transactional
    public ActionExecution executeAction(UUID actionId, ActionExecuteRequest request) {
        if (request == null || request.previewToken() == null || request.previewToken().isBlank()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw problem("ACTION_EXECUTION_INVALID", "执行 Action 需要 Preview Token 和 Idempotency-Key");
        }
        ActionExecution existing = jdbc.query("SELECT * FROM control.action_executions WHERE idempotency_key=?",
                (row, number) -> actionExecution(row), request.idempotencyKey()).stream().findFirst().orElse(null);
        if (existing != null) return refreshExecution(existing);
        PreviewRecord preview = jdbc.query("""
                SELECT * FROM control.action_previews WHERE action_id=? AND token_hash=?
                """, (row, number) -> new PreviewRecord(row.getObject("id", UUID.class), row.getInt("action_version"),
                row.getString("edits"), instant(row, "expires_at"), instant(row, "consumed_at")),
                actionId, fingerprint(request.previewToken())).stream().findFirst()
                .orElseThrow(() -> problem("ACTION_PREVIEW_INVALID", "Preview Token 无效"));
        if (preview.consumedAt() != null || preview.expiresAt().isBefore(Instant.now())) {
            throw problem("ACTION_PREVIEW_EXPIRED", "Preview Token 已使用或过期，请重新预览");
        }
        UUID executionId = UUID.randomUUID();
        String correlationId = "action:" + executionId;
        jdbc.update("""
                INSERT INTO control.action_executions(
                  id,ontology_id,preview_id,action_id,action_version,
                  idempotency_key,correlation_id,trace_id,status)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, executionId, WorkspaceContext.id(), preview.id(), actionId, preview.actionVersion(),
                request.idempotencyKey(), correlationId, UUID.randomUUID().toString(), "SUBMITTED");
        jdbc.update("UPDATE control.action_previews SET consumed_at=now() WHERE id=?", preview.id());
        publishMutation(actionId, executionId, preview, request.idempotencyKey());
        return actionExecution(executionId);
    }


    public ActionExecution actionExecution(UUID id) {
        ActionExecution execution = jdbc.query("""
                SELECT e.* FROM control.action_executions e
                WHERE e.id=? AND e.ontology_id=?
                """, (row, number) -> actionExecution(row), id, WorkspaceContext.id()).stream().findFirst()
                .orElseThrow(() -> problem("ACTION_EXECUTION_NOT_FOUND", "Action Execution 不存在"));
        return refreshExecution(execution);
    }

    public List<ActionExecution> actionExecutions(String status) {
        String normalized = upper(status);
        if (!normalized.isBlank() && !List.of(
                "SUBMITTED", "PROJECTING", "SUCCEEDED", "DEGRADED", "FAILED").contains(normalized)) {
            throw problem("ACTION_EXECUTION_STATUS_INVALID", "Action Execution 状态无效");
        }
        List<ActionExecution> executions = jdbc.query("""
                SELECT e.* FROM control.action_executions e
                WHERE e.ontology_id=?
                  AND (?='' OR e.status=?)
                ORDER BY e.submitted_at DESC
                LIMIT 200
                """, (row, number) -> actionExecution(row), WorkspaceContext.id(),
                normalized, normalized);
        return executions.stream().map(this::refreshExecution).toList();
    }

    public FunctionExecution testFunction(UUID id, FunctionTestRequest request) {
        long started = System.nanoTime();
        ResourceView function = get(id);
        if (function.kind() != ResourceKind.FUNCTION) throw problem("RESOURCE_KIND_INVALID", "目标资源不是 Function");
        Map<String, Object> dsl = castMap(function.definition().get("queryDsl"));
        policy.validateFunctionDsl(dsl);
        Map<String, Object> inputs = request == null ? Map.of() : map(request.inputs());
        int ttl = number(function.definition().get("cacheSeconds"), 60);
        String cacheKey = WorkspaceContext.id() + ":" + id + ":"
                + fingerprint(writeJson(inputs)) + ":"
                + fingerprint(writeJson(dsl));
        Instant now = Instant.now();
        functionCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        FunctionCacheEntry cached = ttl <= 0 ? null : functionCache.get(cacheKey);
        Object result;
        List<Map<String, Object>> diagnostics;
        boolean cacheHit = cached != null;
        if (cacheHit) {
            result = cached.result();
            diagnostics = cached.diagnostics();
        } else {
            FunctionRuntimeService.RuntimeResult runtime =
                    functionRuntime.executeDetailed(dsl, inputs);
            result = runtime.result();
            diagnostics = runtime.diagnostics();
            if (ttl > 0) {
                functionCache.put(cacheKey, new FunctionCacheEntry(
                        result, diagnostics, now.plusSeconds(Math.min(ttl, 3600))));
            }
        }
        return new FunctionExecution(UUID.randomUUID(), id, function.version(), result,
                Math.max(1, (System.nanoTime() - started) / 1_000_000), UUID.randomUUID().toString(),
                cacheHit, diagnostics);
    }


    private void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }


    private void snapshotObject(UUID ontologyId, ResourceView resource) {
        jdbc.update("DELETE FROM control.object_properties WHERE ontology_id=? AND type_id=?", ontologyId, resource.physicalKey());
        jdbc.update("DELETE FROM control.object_types WHERE ontology_id=? AND type_id=?", ontologyId, resource.physicalKey());
        jdbc.update("INSERT INTO control.object_types(ontology_id,type_id,display_name,active) VALUES (?,?,?,true)", ontologyId, resource.physicalKey(), resource.displayName());
        for (PropertyView property : resource.properties()) {
            jdbc.update("""
                    INSERT INTO control.object_properties(ontology_id,type_id,property_id,value_type,required,searchable,sensitive)
                    VALUES (?,?,?,?,?,?,?)
                    """, ontologyId, resource.physicalKey(), property.physicalKey(), projectionType(property.valueType()), property.required(),
                    property.searchable() && !property.sensitive(), property.sensitive());
        }
    }

    private void snapshotLink(UUID ontologyId, ResourceView resource) {
        Map<String, Object> definition = resource.definition();
        UUID left = UUID.fromString(Objects.toString(definition.get("leftObjectTypeId")));
        UUID right = UUID.fromString(Objects.toString(definition.get("rightObjectTypeId")));
        jdbc.update("DELETE FROM control.relation_types WHERE ontology_id=? AND type_id=?", ontologyId, resource.physicalKey());
        String sourceMode = Objects.toString(definition.get("sourceMode"), "FOREIGN_KEY");
        String sourceProperty = null;
        if ("FOREIGN_KEY".equals(sourceMode) && definition.get("sourcePropertyId") != null) {
            sourceProperty = jdbc.query("""
                    SELECT property.physical_key
                    FROM control.properties property
                    JOIN control.ontology_resources object_type
                      ON object_type.id=property.object_type_id
                    WHERE property.id=? AND object_type.ontology_id=?
                    """, (row, number) -> row.getString(1),
                    UUID.fromString(Objects.toString(definition.get("sourcePropertyId"))),
                    ontologyId)
                    .stream().findFirst().orElseThrow(() ->
                            problem("FK_PROPERTY_NOT_FOUND", "外键属性不存在"));
        }
        jdbc.update("""
                INSERT INTO control.relation_types(
                  ontology_id,type_id,source_type_id,target_type_id,active,source_mode,source_property_id)
                VALUES (?,?,?,?,true,?,?)
                """, ontologyId, resource.physicalKey(),
                get(ontologyId, left).physicalKey(),
                get(ontologyId, right).physicalKey(),
                sourceMode, sourceProperty);
    }


    private void insertVersion(UUID resourceId, UUID versionId, int version, ResourceDraftRequest request,
                               Map<String, Object> definition) {
        jdbc.update("""
                INSERT INTO control.ontology_resource_versions
                    (id,resource_id,version,lifecycle,display_name,description,maturity,promoted,tags,definition,fingerprint)
                VALUES (?,?,?,'DRAFT',?,?,?,?,COALESCE(ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),'{}'::text[]),?::jsonb,?)
                """, versionId, resourceId, version, request.displayName().trim(), safe(request.description()), maturity(request.maturity()),
                request.promoted(), writeJson(list(request.tags())), writeJson(definition), fingerprint(writeJson(definition)));
    }

    private void insertTyped(ResourceKind kind, UUID resourceId, UUID versionId, ResourceDraftRequest request) {
        switch (kind) {
            case OBJECT_TYPE -> insertObjectVersion(resourceId, versionId, request);
            case LINK_TYPE -> insertLinkVersion(resourceId, versionId, request);
            case INTERFACE -> insertInterfaceVersion(resourceId, versionId, request);
            case ACTION -> insertActionVersion(resourceId, versionId, request);
            case FUNCTION -> insertFunctionVersion(resourceId, versionId, request);
        }
    }

    private void insertObjectVersion(UUID resourceId, UUID versionId, ResourceDraftRequest request) {
        policy.validateProperties(request.properties());
        String sourceMode = upper(value(request.sourceMode(), "ACTION"));
        if (!List.of("ACTION", "DATASET", "PIPELINE").contains(sourceMode)) throw problem("SOURCE_MODE_INVALID", "对象来源必须为 ACTION、DATASET 或 PIPELINE");
        if ("PIPELINE".equals(sourceMode)
                && request.primaryPipelineId() != null
                && count(
                        "SELECT count(*) FROM control.pipelines WHERE id=?",
                        request.primaryPipelineId()) == 0) {
            throw problem("PIPELINE_NOT_FOUND", "主 Pipeline 不存在");
        }
        jdbc.update("INSERT INTO control.object_type_versions(version_id,resource_id,source_mode,primary_pipeline_id) VALUES (?,?,?,?)",
                versionId, resourceId, sourceMode, request.primaryPipelineId());
        UUID primary = null;
        UUID title = null;
        for (PropertyDraft property : list(request.properties())) {
            UUID propertyId = jdbc.query("SELECT id FROM control.properties WHERE object_type_id=? AND api_name=?",
                    (row, n) -> row.getObject("id", UUID.class), resourceId, property.apiName()).stream().findFirst().orElse(UUID.randomUUID());
            if (count(
                    "SELECT count(*) FROM control.properties WHERE id=?",
                    propertyId) == 0) {
                jdbc.update("INSERT INTO control.properties(id,object_type_id,api_name,physical_key) VALUES (?,?,?,?)",
                        propertyId, resourceId, property.apiName(), "p_" + propertyId.toString().replace("-", "").substring(0, 12));
            }
            boolean searchable = property.searchable() && !property.sensitive();
            jdbc.update("""
                    INSERT INTO control.property_versions
                        (id,property_id,object_type_version_id,display_name,description,value_type,required,primary_key,title_property,
                         searchable,filterable,sortable,sensitive,masking_policy,analyzer,source_field,enum_values,action_writable)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,COALESCE(ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),'{}'::text[]),?)
                    """, UUID.randomUUID(), propertyId, versionId, property.displayName(), safe(property.description()), upper(property.valueType()),
                    property.required(), property.primaryKey(), property.titleProperty(), searchable, property.filterable(), property.sortable(),
                    property.sensitive(), property.maskingPolicy(), property.analyzer(), property.sourceField(),
                    writeJson(list(property.enumValues())), !property.primaryKey() && !Boolean.FALSE.equals(property.actionWritable()));
            if (property.primaryKey()) primary = propertyId;
            if (property.titleProperty()) title = propertyId;
        }
        if (title == null) title = primary;
        jdbc.update("UPDATE control.object_type_versions SET primary_property_id=?,title_property_id=? WHERE version_id=?", primary, title, versionId);
    }

    private void insertLinkVersion(UUID resourceId, UUID versionId, ResourceDraftRequest request) {
        ResourceView left = get(WorkspaceContext.id(), Objects.requireNonNull(request.leftObjectTypeId(), "leftObjectTypeId"));
        ResourceView right = get(WorkspaceContext.id(), Objects.requireNonNull(request.rightObjectTypeId(), "rightObjectTypeId"));
        if (left.kind() != ResourceKind.OBJECT_TYPE || right.kind() != ResourceKind.OBJECT_TYPE) throw problem("LINK_ENDPOINT_INVALID", "关系端点必须是对象类型");
        String cardinality = upper(value(request.cardinality(), "N:M"));
        if (!List.of("1:1", "1:N", "N:1", "N:M").contains(cardinality)) throw problem("CARDINALITY_INVALID", "关系基数无效");
        String sourceMode = upper(value(request.sourceMode(), "FOREIGN_KEY"));
        if (!List.of("FOREIGN_KEY", "MANUAL", "PIPELINE").contains(sourceMode)) {
            throw problem("LINK_SOURCE_MODE_INVALID", "关系来源必须为 FOREIGN_KEY、MANUAL 或 PIPELINE");
        }
        if ("FOREIGN_KEY".equals(sourceMode) && request.sourcePropertyId() == null) {
            throw problem("FK_PROPERTY_REQUIRED", "FOREIGN_KEY 关系必须选择起点外键属性");
        }
        if ("FOREIGN_KEY".equals(sourceMode) && request.sourcePropertyId() != null
                && left.properties().stream().noneMatch(property ->
                property.id().equals(request.sourcePropertyId()))) {
            throw problem("FK_PROPERTY_INVALID", "外键属性必须属于起点对象类型");
        }
        jdbc.update("""
                INSERT INTO control.link_type_versions
                    (version_id,resource_id,left_object_type_id,right_object_type_id,cardinality,source_mode,source_property_id,pipeline_id,left_display_name,right_display_name)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, versionId, resourceId, left.id(), right.id(), cardinality, sourceMode, request.sourcePropertyId(), request.primaryPipelineId(),
                value(request.leftDisplayName(), request.displayName()), value(request.rightDisplayName(), request.displayName()));
    }

    private void insertInterfaceVersion(UUID resourceId, UUID versionId, ResourceDraftRequest request) {
        jdbc.update("INSERT INTO control.interface_versions(version_id,resource_id) VALUES (?,?)", versionId, resourceId);
        Map<String, UUID> slots = new LinkedHashMap<>();
        for (Map<String, Object> slot : list(request.slots())) {
            String apiName = Objects.toString(slot.get("apiName"), "");
            requireApiName(apiName);
            UUID id = UUID.randomUUID();
            slots.put(apiName, id);
            jdbc.update("INSERT INTO control.interface_slots(id,interface_version_id,api_name,display_name,value_type,required) VALUES (?,?,?,?,?,?)",
                    id, versionId, apiName, value(Objects.toString(slot.get("displayName"), null), apiName), upper(Objects.toString(slot.get("valueType"), "STRING")), Boolean.TRUE.equals(slot.get("required")));
        }
        for (Map<String, Object> implementation : list(request.implementations())) {
            UUID slot = slots.get(Objects.toString(implementation.get("slotApiName"), ""));
            if (slot == null) throw problem("INTERFACE_MAPPING_INVALID", "Interface 实现引用了不存在的 slot");
            UUID objectTypeId = UUID.fromString(Objects.toString(implementation.get("objectTypeId")));
            UUID propertyId = UUID.fromString(Objects.toString(implementation.get("propertyId")));
            jdbc.update("INSERT INTO control.interface_implementations(id,interface_version_id,object_type_id,slot_id,property_id) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), versionId, objectTypeId, slot, propertyId);
        }
    }

    private void insertActionVersion(UUID resourceId, UUID versionId, ResourceDraftRequest request) {
        ResourceView target = get(WorkspaceContext.id(), Objects.requireNonNull(request.targetObjectTypeId(), "targetObjectTypeId"));
        if (target.kind() != ResourceKind.OBJECT_TYPE) throw problem("ACTION_TARGET_INVALID", "Action 目标必须是对象类型");
        String operation = value(request.operation(), "UPDATE");
        policy.validateActionOperation(operation);
        policy.validateActionRules(operation, list(request.rules()));
        jdbc.update("INSERT INTO control.action_types(resource_id,target_object_type_id) VALUES (?,?)", resourceId, target.id());
        jdbc.update("INSERT INTO control.action_type_versions(version_id,resource_id,operation,rules) VALUES (?,?,?,?::jsonb)",
                versionId, resourceId, upper(value(request.operation(), "UPDATE")),
                writeJson(list(request.rules())));
        insertParameters("action_parameters", "action_version_id", versionId, request.parameters());
    }

    private void insertFunctionVersion(UUID resourceId, UUID versionId, ResourceDraftRequest request) {
        policy.validateFunctionDsl(map(request.queryDsl()));
        validateFunctionDependencies(resourceId, list(request.dependencyIds()));
        jdbc.update("INSERT INTO control.function_types(resource_id) VALUES (?) ON CONFLICT (resource_id) DO NOTHING", resourceId);
        jdbc.update("""
                INSERT INTO control.function_type_versions
                    (version_id,resource_id,output_type,query_dsl,dependency_ids,timeout_ms,max_results,cache_seconds)
                VALUES (?,?,?,?::jsonb,COALESCE(ARRAY(SELECT jsonb_array_elements_text(?::jsonb)::uuid),'{}'::uuid[]),?,?,?)
                """, versionId, resourceId, upper(value(request.outputType(), "TABLE")), writeJson(map(request.queryDsl())),
                writeJson(list(request.dependencyIds()).stream().map(UUID::toString).toList()), number(request.timeoutMs(), 5000), number(request.maxResults(), 1000), number(request.cacheSeconds(), 60));
        insertParameters("function_parameters", "function_version_id", versionId, request.parameters());
    }

    private void insertParameters(String table, String versionColumn, UUID versionId, List<ParameterDraft> parameters) {
        for (ParameterDraft parameter : list(parameters)) {
            requireApiName(parameter.apiName());
            if ("action_parameters".equals(table)) {
                jdbc.update("INSERT INTO control.action_parameters(id,action_version_id,api_name,display_name,value_type,required,sensitive,default_value) VALUES (?,?,?,?,?,?,?,?::jsonb)",
                        UUID.randomUUID(), versionId, parameter.apiName(), parameter.displayName(), upper(parameter.valueType()), parameter.required(), parameter.sensitive(), writeJson(parameter.defaultValue()));
            } else {
                jdbc.update("INSERT INTO control.function_parameters(id,function_version_id,api_name,display_name,value_type,required) VALUES (?,?,?,?,?,?)",
                        UUID.randomUUID(), versionId, parameter.apiName(), parameter.displayName(), upper(parameter.valueType()), parameter.required());
            }
        }
    }


    private void validateFunctionDependencies(UUID functionId, List<UUID> dependencies) {
        Map<UUID, List<UUID>> graph = new LinkedHashMap<>();
        jdbc.query("""
                SELECT r.id,fv.dependency_ids
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions rv
                  ON rv.resource_id=r.id AND rv.version=r.latest_version
                JOIN control.function_type_versions fv ON fv.version_id=rv.id
                WHERE r.ontology_id=? AND r.kind='FUNCTION'
                """, (row, number) -> Map.entry(
                row.getObject("id", UUID.class),
                row.getArray("dependency_ids") == null ? List.<UUID>of()
                        : List.of((UUID[]) row.getArray("dependency_ids").getArray())),
                WorkspaceContext.id()).forEach(entry ->
                graph.put(entry.getKey(), entry.getValue()));
        for (UUID dependency : dependencies) {
            if (!graph.containsKey(dependency) && !dependency.equals(functionId)) {
                throw problem("FUNCTION_DEPENDENCY_NOT_FOUND",
                        "Function 依赖必须引用当前本体中的 Function");
            }
        }
        graph.put(functionId, List.copyOf(dependencies));
        if (hasFunctionCycle(functionId, functionId, graph, new java.util.HashSet<>())) {
            throw problem("FUNCTION_DEPENDENCY_CYCLE", "Function 依赖不能形成循环");
        }
    }

    private boolean hasFunctionCycle(
            UUID origin,
            UUID current,
            Map<UUID, List<UUID>> graph,
            java.util.Set<UUID> visited) {
        if (!visited.add(current)) return false;
        for (UUID dependency : graph.getOrDefault(current, List.of())) {
            if (dependency.equals(origin)
                    || hasFunctionCycle(origin, dependency, graph, visited)) {
                return true;
            }
        }
        return false;
    }

    private void validateCommon(ResourceDraftRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank() || request.displayName().length() > 240) throw problem("DISPLAY_NAME_INVALID", "显示名称长度必须为 1 到 240 个字符");
        requireResourceId(request.apiName());
        if (!List.of("EXPERIMENTAL", "ACTIVE", "DEPRECATED").contains(maturity(request.maturity()))) throw problem("MATURITY_INVALID", "成熟度无效");
    }

    private boolean primaryChanged(UUID resourceId, ResourceDraftRequest request) {
        String active = jdbc.query("""
                SELECT p.api_name FROM control.object_type_versions otv JOIN control.properties p ON p.id=otv.primary_property_id
                JOIN control.ontology_resources r ON r.id=otv.resource_id
                JOIN control.ontology_resource_versions rv ON rv.id=otv.version_id AND rv.version=r.active_version
                WHERE r.id=?
                """, (row, n) -> row.getString(1), resourceId).stream().findFirst().orElse(null);
        String next = list(request.properties()).stream().filter(PropertyDraft::primaryKey).map(PropertyDraft::apiName).findFirst().orElse(null);
        return active != null && !active.equals(next);
    }

    private long objectCount(UUID resourceId) {
        ResourceView resource = get(resourceId);
        return objectInstances.count(WorkspaceContext.id(), resource.id());
    }

    private ResourceView resource(ResultSet row, int number) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        ResourceKind kind = ResourceKind.valueOf(row.getString("kind"));
        return new ResourceView(id, kind, row.getString("api_name"), row.getString("version_display_name"),
                row.getString("version_description"), row.getString("physical_key"),
                row.getString("version_maturity"), row.getBoolean("version_promoted"),
                strings(row.getArray("version_tags")), row.getString("lifecycle"), row.getInt("latest_version"),
                nullableInteger(row, "active_version"), row.getLong("etag"),
                readJson(row.getString("definition"), new TypeReference<Map<String, Object>>() { }),
                kind == ResourceKind.OBJECT_TYPE ? properties(id) : List.of(), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private PropertyView property(ResultSet row, int number) throws SQLException {
        return new PropertyView(row.getObject("id", UUID.class), row.getString("api_name"), row.getString("display_name"),
                row.getString("description"), row.getString("value_type"), row.getBoolean("required"), row.getBoolean("primary_key"),
                row.getBoolean("title_property"), row.getBoolean("searchable"), row.getBoolean("filterable"), row.getBoolean("sortable"),
                row.getBoolean("sensitive"), row.getBoolean("action_writable"), row.getString("physical_key"), row.getString("source_field"));
    }


    private Map<String, Object> definition(ResourceDraftRequest request) {
        Map<String, Object> result = json.convertValue(request, new TypeReference<Map<String, Object>>() { });
        result.values().removeIf(Objects::isNull);
        return result;
    }

    private void validateActionParameters(ResourceView action, Map<String, Object> parameters) {
        Set<String> declared = new java.util.LinkedHashSet<>();
        for (Object item : values(action.definition().get("parameters"))) {
            if (!(item instanceof Map<?, ?> parameter)) continue;
            String name = Objects.toString(parameter.get("apiName"), "");
            declared.add(name);
            if (Boolean.TRUE.equals(parameter.get("required")) && !parameters.containsKey(name)
                    && parameter.get("defaultValue") == null) {
                throw problem("ACTION_PARAMETER_REQUIRED", "缺少 Action 参数：" + name);
            }
            if (parameters.containsKey(name)) {
                validateParameterValue(
                        name,
                        parameters.get(name),
                        upper(Objects.toString(parameter.get("valueType"), "STRING")));
            }
        }
        parameters.keySet().stream()
                .filter(name -> !declared.contains(name))
                .findFirst()
                .ifPresent(name -> {
                    throw problem("ACTION_PARAMETER_UNKNOWN", "未知 Action 参数：" + name);
                });
    }

    private void validateParameterValue(String name, Object value, String type) {
        if (value == null) return;
        boolean valid = switch (type) {
            case "BOOLEAN" -> value instanceof Boolean;
            case "DECIMAL" -> value instanceof Number;
            case "INTEGER", "LONG" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case "INTEGER_ARRAY" -> value instanceof List<?> values
                    && values.stream().allMatch(item -> item instanceof Byte
                    || item instanceof Short || item instanceof Integer || item instanceof Long);
            case "JSON" -> value instanceof Map<?, ?> || value instanceof List<?>;
            case "STRING_ARRAY" -> value instanceof List<?> values
                    && values.stream().allMatch(String.class::isInstance);
            default -> value instanceof String;
        };
        if (!valid) throw problem(
                "ACTION_PARAMETER_TYPE_INVALID",
                "Action 参数“" + name + "”必须是 " + type + " 类型");
    }

    private Object actionValue(Map<String, Object> rule, Map<String, Object> parameters) {
        String parameter = Objects.toString(rule.containsKey("valueFrom")
                ? rule.get("valueFrom") : rule.get("valueFromParameter"), "");
        if (!parameter.isBlank()) {
            if (!parameters.containsKey(parameter)) throw problem("ACTION_PARAMETER_REQUIRED", "缺少 Action 参数：" + parameter);
            return parameters.get(parameter);
        }
        if (rule.containsKey("value")) return rule.get("value");
        if (rule.containsKey("literalValue")) return rule.get("literalValue");
        throw problem("ACTION_RULE_VALUE_REQUIRED", "SET_PROPERTY 规则需要 value 或 valueFrom");
    }

    private Object jsonValue(JsonNode value) {
        return value == null || value.isNull() ? "" : json.convertValue(value, Object.class);
    }

    private String actionObjectId(ActionPreviewRequest request, Map<String, Object> parameters,
                                  ResourceView targetType, ObjectNode payload) {
        if (request != null && request.objectId() != null && !request.objectId().isBlank()) {
            return request.objectId();
        }
        Object explicit = parameters.get("objectId");
        if (explicit != null && !String.valueOf(explicit).isBlank()) return String.valueOf(explicit);
        PropertyView primary = targetType.properties().stream().filter(PropertyView::primaryKey)
                .findFirst().orElseThrow(() -> problem("ACTION_PRIMARY_KEY_MISSING",
                        "CREATE Action 的目标对象类型没有主键"));
        JsonNode value = payload.get(primary.physicalKey());
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw problem("ACTION_OBJECT_ID_REQUIRED", "CREATE Action 必须通过 objectId 或主键规则生成稳定对象 ID");
        }
        return value.asText();
    }

    private String actionEndpoint(Map<String, Object> rule, String endpoint,
                                  Map<String, Object> parameters, String fallback) {
        for (String parameterKey : List.of(endpoint + "ObjectIdFrom", endpoint + "IdFrom",
                endpoint + "IdFromParameter")) {
            String parameter = Objects.toString(rule.get(parameterKey), "");
            if (!parameter.isBlank()) {
                Object value = parameters.get(parameter);
                if (value == null || String.valueOf(value).isBlank()) {
                    throw problem("ACTION_ENDPOINT_REQUIRED", "缺少关系端点参数：" + parameter);
                }
                return String.valueOf(value);
            }
        }
        for (String literalKey : List.of(endpoint + "ObjectId", endpoint + "Id")) {
            String value = Objects.toString(rule.get(literalKey), "");
            if (!value.isBlank()) return value;
        }
        if (fallback != null && !fallback.isBlank()) return fallback;
        throw problem("ACTION_ENDPOINT_REQUIRED", "关系 " + endpoint + " 端点不能为空");
    }


    private boolean conditionMatches(Map<String, Object> condition, JsonNode current,
                                     Map<String, Object> parameters) {
        if (condition == null || condition.isEmpty()) return true;
        if (condition.get("submitWhen") instanceof Map<?, ?> nested) {
            return conditionMatches(castMap(nested), current, parameters);
        }
        if (condition.get("all") instanceof List<?> all) {
            return all.stream().allMatch(value -> conditionMatches(castMap(value), current, parameters));
        }
        if (condition.get("any") instanceof List<?> any) {
            return any.stream().anyMatch(value -> conditionMatches(castMap(value), current, parameters));
        }
        String field = Objects.toString(condition.get("field"), "");
        String parameter = Objects.toString(condition.get("parameter"), "");
        if (field.isBlank() && parameter.isBlank()) return true;
        Object actual = !parameter.isBlank() ? parameters.get(parameter)
                : current == null ? null : jsonValue(current.get(field));
        Object expected = condition.containsKey("valueFromParameter")
                ? parameters.get(Objects.toString(condition.get("valueFromParameter"))) : condition.get("value");
        return switch (upper(Objects.toString(condition.getOrDefault("operator", "EQUALS")))) {
            case "CONTAINS" -> actual != null && String.valueOf(actual).contains(String.valueOf(expected));
            case "GREATER_THAN" -> actionDecimal(actual).compareTo(actionDecimal(expected)) > 0;
            case "IN" -> condition.get("values") instanceof List<?> values
                    && values.stream().anyMatch(value -> Objects.equals(String.valueOf(value), String.valueOf(actual)));
            case "IS_NOT_NULL" -> actual != null;
            case "IS_NULL" -> actual == null;
            case "LESS_THAN" -> actionDecimal(actual).compareTo(actionDecimal(expected)) < 0;
            case "NOT_EQUALS" -> !Objects.equals(String.valueOf(actual), String.valueOf(expected));
            default -> Objects.equals(String.valueOf(actual), String.valueOf(expected));
        };
    }

    private void publishMutation(UUID actionId, UUID executionId, PreviewRecord preview,
                                 String idempotencyKey) {
        List<Map<String, Object>> rawEdits = readJson(preview.edits(), new TypeReference<>() { });
        List<MutationEdit> edits = rawEdits.stream().map(this::mutationEdit).toList();
        String correlationId = jdbc.queryForObject(
                "SELECT correlation_id FROM control.action_executions WHERE id=?",
                String.class, executionId);
        OntologyMutationBatch batch = new OntologyMutationBatch(
                UUID.randomUUID(), WorkspaceContext.id(), actionId.toString(),
                preview.id().toString(), idempotencyKey,
                Instant.now(), correlationId, edits);
        actionPublisher.enqueue(executionId, batch);
        jdbc.update("UPDATE control.action_executions SET status='SUBMITTED' WHERE id=?",
                executionId);
    }

    private BigDecimal actionDecimal(Object value) {
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException failure) {
            throw problem("ACTION_CONDITION_TYPE_INVALID", "Action 条件要求数值参数");
        }
    }

    private MutationEdit mutationEdit(Map<String, Object> edit) {
        return new MutationEdit(
                Objects.toString(edit.get("operation"), null),
                Objects.toString(edit.get("objectTypeId"), null),
                Objects.toString(edit.get("objectId"), null),
                Objects.toString(edit.get("relationTypeId"), null),
                Objects.toString(edit.get("relationId"), null),
                Objects.toString(edit.get("sourceObjectTypeId"), null),
                Objects.toString(edit.get("sourceObjectId"), null),
                Objects.toString(edit.get("targetObjectTypeId"), null),
                Objects.toString(edit.get("targetObjectId"), null),
                json.valueToTree(edit.getOrDefault("properties", Map.of())));
    }

    private ActionExecution refreshExecution(ActionExecution execution) {
        if (!List.of("PROJECTING", "SUBMITTED").contains(execution.status())) return execution;
        String idempotencyKey = jdbc.queryForObject(
                "SELECT idempotency_key FROM control.action_executions WHERE id=?", String.class, execution.id());
        String projectionStatus = jdbc.query("SELECT status FROM control.projection_operations WHERE idempotency_key=?",
                (row, number) -> row.getString(1), idempotencyKey).stream().findFirst().orElse(null);
        if (projectionStatus == null) return execution;
        String status = switch (projectionStatus) {
            case "PROJECTED" -> "SUCCEEDED";
            case "DEGRADED" -> "DEGRADED";
            case "FAILED" -> "FAILED";
            default -> "PROJECTING";
        };
        if (!status.equals(execution.status())) {
            jdbc.update("UPDATE control.action_executions SET status=?,completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN now() ELSE completed_at END WHERE id=?",
                    status, status, execution.id());
        }
        return jdbc.query("SELECT * FROM control.action_executions WHERE id=?",
                (row, number) -> actionExecution(row), execution.id()).getFirst();
    }

    private ActionExecution actionExecution(ResultSet row) throws SQLException {
        return new ActionExecution(row.getObject("id", UUID.class), row.getObject("action_id", UUID.class),
                row.getInt("action_version"), row.getObject("preview_id", UUID.class),
                row.getString("status"), row.getString("correlation_id"),
                row.getString("trace_id"), row.getString("safe_error"),
                instant(row, "submitted_at"), instant(row, "completed_at"));
    }

    private ResourceDraftRequest withStableApi(ResourceDraftRequest value, String apiName) {
        return new ResourceDraftRequest(value.displayName(), apiName, value.description(), value.maturity(),
                value.tags(), value.promoted(), value.sourceMode(), value.primaryPipelineId(),
                value.datasetId(), value.datasetMapping(), value.properties(), value.leftObjectTypeId(),
                value.rightObjectTypeId(), value.targetObjectTypeId(), value.cardinality(), value.leftDisplayName(), value.rightDisplayName(),
                value.sourcePropertyId(), value.operation(), value.parameters(), value.rules(),
                value.slots(), value.implementations(), value.outputType(), value.queryDsl(), value.dependencyIds(), value.timeoutMs(), value.maxResults(), value.cacheSeconds());
    }


    private void requireApiName(String value) {
        if (value == null || !API_NAME.matcher(value).matches()) throw problem("API_NAME_INVALID", "API 名称必须以字母开头，且只能包含字母、数字和下划线");
    }

    private void requireResourceId(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,159}")) {
            throw problem("RESOURCE_ID_INVALID", "资源 ID 必须以英文字母开头，且只能包含字母、数字、下划线和连字符");
        }
    }

    private long count(String sql) { return Objects.requireNonNullElse(jdbc.queryForObject(sql, Long.class), 0L); }
    private long count(String sql, Object... arguments) {
        return Objects.requireNonNullElse(
                jdbc.queryForObject(sql, Long.class, arguments), 0L);
    }
    private long count(UUID ontologyId, String sql) {
        return Objects.requireNonNullElse(jdbc.queryForObject(sql, Long.class, ontologyId), 0L);
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("JSON serialization failed", failure); }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Stored modeling JSON is invalid", failure); }
    }

    private String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
    }

    private String projectionType(String value) {
        return switch (upper(value)) {
            case "BOOLEAN" -> "BOOLEAN";
            case "DATE", "DATETIME" -> "DATE";
            case "DECIMAL" -> "DECIMAL";
            case "INTEGER", "LONG" -> "INTEGER";
            case "JSON" -> "JSON";
            default -> "TEXT";
        };
    }

    private String prefix(ResourceKind kind) { return switch (kind) { case OBJECT_TYPE -> "ot_"; case LINK_TYPE -> "lt_"; case INTERFACE -> "if_"; case ACTION -> "ac_"; case FUNCTION -> "fn_"; }; }
    private String maturity(String value) { return upper(value(value, "EXPERIMENTAL")); }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String safe(String value) { return value == null ? "" : value.trim().substring(0, Math.min(2000, value.trim().length())); }
    private String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private int number(Integer value, int fallback) { return value == null ? fallback : value; }
    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
    private <T> List<T> list(List<T> value) { return value == null ? List.of() : value; }
    private <K, V> Map<K, V> map(Map<K, V> value) { return value == null ? Map.of() : value; }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> castList(Object value) { return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(); }
    @SuppressWarnings("unchecked") private Map<String, Object> castMap(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private List<?> values(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private Instant instant(ResultSet row, String column) throws SQLException { Timestamp value = row.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private Integer nullableInteger(ResultSet row, String column) throws SQLException { int value = row.getInt(column); return row.wasNull() ? null : value; }
    private Long nullableLong(ResultSet row, String column) throws SQLException { long value = row.getLong(column); return row.wasNull() ? null : value; }
    private List<String> strings(Array array) throws SQLException { return array == null ? List.of() : List.of((String[]) array.getArray()); }
    private String safeFailure(Exception failure) { String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); return message.substring(0, Math.min(900, message.length())); }
    private ConnectionProblem problem(String code, String message) { return new ConnectionProblem(code, message); }
    private record PreviewRecord(UUID id, int actionVersion, String edits,
                                 Instant expiresAt, Instant consumedAt) { }
    private record FunctionCacheEntry(
            Object result, List<Map<String, Object>> diagnostics, Instant expiresAt) { }
}
