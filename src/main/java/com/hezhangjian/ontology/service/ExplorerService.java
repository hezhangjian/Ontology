package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.service.ExplorerModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.client.ExplorerStorageClient;
import com.hezhangjian.ontology.config.ExplorerProperties;
import com.hezhangjian.ontology.instance.ObjectInstanceAuthorityReader;
import com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance;
import com.hezhangjian.ontology.service.ExplorerPolicy.ValidatedQuery;
import com.hezhangjian.ontology.client.ExplorerStorageClient.GraphEdge;
import com.hezhangjian.ontology.client.ExplorerStorageClient.GraphObject;
import com.hezhangjian.ontology.client.ExplorerStorageClient.RawSearchHit;
import com.hezhangjian.ontology.client.ExplorerStorageClient.SearchHit;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExplorerService {
    private final SqlClientRepository jdbc;
    private final ObjectMapper objectMapper;
    private final ExplorerPolicy policy;
    private final ObjectInstanceAuthorityReader authority;
    private final ExplorerStorageClient storage;
    private final ExplorerTokenCodec tokens;
    private final ExplorerProperties properties;

    public ExplorerService(SqlClientRepository jdbc, ObjectMapper objectMapper, ExplorerPolicy policy,
                           ObjectInstanceAuthorityReader authority,
                           ExplorerStorageClient storage, ExplorerTokenCodec tokens,
                           ExplorerProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.authority = authority;
        this.storage = storage;
        this.tokens = tokens;
        this.properties = properties;
    }

    public ExplorerHome home(Actor actor) {
        List<ObjectTypeDefinition> types = types();
        Map<UUID, Long> counts = new LinkedHashMap<>();
        types.forEach(type -> counts.put(
                type.id(),
                (long) authority.list(WorkspaceContext.id(), type.physicalKey()).size()));
        return new ExplorerHome(types, Map.copyOf(counts),
                storage.searchAvailable() ? "HEALTHY" : "DEGRADED", Instant.now());
    }

    public SearchResponse search(SearchRequest request, Actor actor) {
        int size = request.size() == null ? 30 : Math.min(Math.max(request.size(), 1), 100);
        List<ObjectSummary> objects = new ArrayList<>();
        for (RawSearchHit hit : storage.globalSearch(request.query(), size, actor)) {
            ObjectTypeDefinition type = findTypeByStorageKey(hit.objectType());
            if (type != null) objects.add(summary(hit, type));
        }
        String needle = request.query() == null ? "" : request.query().toLowerCase();
        List<ObjectTypeDefinition> matchingTypes = types().stream()
                .filter(type -> needle.isBlank() || type.displayName().toLowerCase().contains(needle)
                        || type.apiName().toLowerCase().contains(needle)).toList();
        return new SearchResponse(
                List.copyOf(objects), matchingTypes, objects.size(), Instant.now());
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
        return new ObjectSetPage(type.id(), type.displayName(), page.total(),
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

    public List<AggregationBucket> aggregate(ObjectSetRequest request, UUID dimensionPropertyId,
                                             UUID measurePropertyId, String aggregation, Actor actor) {
        return aggregate(request, List.of(dimensionPropertyId), measurePropertyId, null, aggregation, actor);
    }

    public List<AggregationBucket> aggregate(ObjectSetRequest request, List<UUID> dimensionPropertyIds,
                                             UUID measurePropertyId, UUID divisorPropertyId,
                                             String aggregation, Actor actor) {
        ObjectTypeDefinition type = type(request.objectTypeId());
        ValidatedQuery query = policy.validate(request, type);
        return storage.aggregate(
                query, dimensionPropertyIds, measurePropertyId, divisorPropertyId, aggregation, actor);
    }

    public AggregateResponse aggregate(AggregateRequest request, Actor actor) {
        if (request == null || request.query() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "聚合查询不能为空");
        }
        ObjectTypeDefinition type = type(request.query().objectTypeId());
        ValidatedQuery query = policy.validate(request.query(), type);
        String aggregation = request.aggregation() == null
                ? "count" : request.aggregation().trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("approx_distinct", "avg", "count", "max", "min", "sum", "sum_per_distinct")
                .contains(aggregation)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的聚合操作");
        }
        List<AggregationBucket> buckets = storage.aggregate(query, request.dimensionPropertyIds(),
                request.measurePropertyId(), request.divisorPropertyId(), aggregation, actor);
        return new AggregateResponse(type.id(), query.fingerprint(), Instant.now(), buckets);
    }

    public double metric(ObjectSetRequest request, UUID measurePropertyId, String aggregation, Actor actor) {
        ObjectTypeDefinition type = type(request.objectTypeId());
        ValidatedQuery query = policy.validate(request, type);
        return storage.metric(query, measurePropertyId, aggregation, actor);
    }

    public ObjectDetail object(UUID objectTypeId, String objectId, Actor actor) {
        ObjectTypeDefinition type = type(objectTypeId);
        return detail(objectId, type);
    }

    public LinkPage links(UUID objectTypeId, String objectId, LinkRequest request, Actor actor) {
        ObjectTypeDefinition sourceType = type(objectTypeId);
        detail(objectId, sourceType);
        GraphObject graph = storage.getObject(sourceType.physicalKey(), objectId);
        int size = request == null || request.pageSize() == null ? 25 : Math.min(request.pageSize(), 100);
        List<ObjectLink> links = new ArrayList<>();
        String direction = request == null || request.direction() == null ? "BOTH" : request.direction().toUpperCase();
        Set<UUID> requestedTypes = request == null || request.linkTypeIds() == null
                ? Set.of() : Set.copyOf(request.linkTypeIds());
        for (GraphEdge edge : storage.links(graph, size)) {
            LinkDefinition link = findLink(edge.relationType());
            ObjectTypeDefinition targetType = findTypeByStorageKey(edge.target().objectType());
            if (link == null || targetType == null
                    || (!"BOTH".equals(direction) && !direction.equals(edge.direction()))
                    || (!requestedTypes.isEmpty() && !requestedTypes.contains(link.id()))) continue;
            ObjectDetail target = detail(edge.target().objectId(), targetType);
            links.add(new ObjectLink(edge.relationId(), link.id(), link.name(), edge.direction(),
                    edge.target().objectId(), targetType.id(), target.title(), jsonMap(edge.properties())));
        }
        return new LinkPage(List.copyOf(links), null, links.size());
    }

    public RelationInstancePage relationInstances(String objectTypeId, String objectId,
                                                  String linkTypeId, String direction,
                                                  Integer pageSize, Actor actor) {
        ObjectTypeDefinition currentType = type(resolveResource("OBJECT_TYPE", objectTypeId));
        String normalizedDirection = direction == null ? "BOTH" : direction.toUpperCase(Locale.ROOT);
        if (!Set.of("BOTH", "IN", "OUT").contains(normalizedDirection)) {
            throw invalid("关系方向必须为 BOTH、IN 或 OUT");
        }
        ObjectDetail currentObject = object(currentType.id(), objectId, actor);
        List<UUID> linkTypes = linkTypeId == null || linkTypeId.isBlank()
                ? List.of() : List.of(resolveResource("LINK_TYPE", linkTypeId));
        LinkPage page = links(currentType.id(), objectId,
                new LinkRequest(normalizedDirection, linkTypes, pageSize, null), actor);
        List<RelationInstance> items = page.items().stream().map(link -> {
            ObjectTypeDefinition otherType = type(link.targetObjectTypeId());
            LinkDefinition relationType = linkType(link.linkTypeId());
            InstanceReference current = new InstanceReference(currentType.apiName(),
                    objectId, currentObject.title());
            InstanceReference other = new InstanceReference(otherType.apiName(),
                    link.targetObjectId(), link.targetTitle());
            InstanceReference source = "IN".equals(link.direction()) ? other : current;
            InstanceReference target = "IN".equals(link.direction()) ? current : other;
            return new RelationInstance(link.relationId(), relationType.apiName(),
                    source, target, link.edgeProperties());
        }).toList();
        return new RelationInstancePage(page.visibleCount(), items, page.nextCursor());
    }

    public RelationInstancePage relationInstances(RelationInstanceQueryRequest request, Actor actor) {
        if (request == null || request.source() == null) {
            throw invalid("关系查询必须指定 source");
        }
        return relationInstances(request.source().type(), request.source().id(),
                request.type(), request.direction(), request.pageSize(), actor);
    }

    public InterfaceQueryPage interfaceQuery(UUID interfaceId, InterfaceQueryRequest request, Actor actor) {
        int pageSize = Math.max(1, Math.min(100, request == null || request.pageSize() == null
                ? 50 : request.pageSize()));
        record Mapping(UUID objectTypeId, String slotName, String propertyName) { }
        List<Mapping> mappings = jdbc.sql("""
                SELECT implementation.object_type_id,slot.api_name slot_name,property.api_name property_name
                FROM control.ontology_resources resource
                JOIN control.ontology_resource_versions version
                  ON version.resource_id=resource.id AND version.version=resource.active_version
                JOIN control.interface_versions interface_version ON interface_version.version_id=version.id
                JOIN control.interface_implementations implementation
                  ON implementation.interface_version_id=interface_version.version_id
                JOIN control.interface_slots slot ON slot.id=implementation.slot_id
                JOIN control.properties property ON property.id=implementation.property_id
                WHERE resource.id=:interfaceId AND resource.ontology_id=:ontology
                  AND resource.kind='INTERFACE'
                ORDER BY implementation.object_type_id,slot.api_name
                """).param("interfaceId", interfaceId).param("ontology", WorkspaceContext.id())
                .query((row, number) -> new Mapping(row.getObject("object_type_id", UUID.class),
                        row.getString("slot_name"), row.getString("property_name"))).list();
        if (mappings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Interface 不存在、未发布或没有实现映射");
        }
        List<InterfaceObject> items = new ArrayList<>();
        boolean truncated = false;
        for (Map.Entry<UUID, List<Mapping>> implementation : mappings.stream()
                .collect(Collectors.groupingBy(Mapping::objectTypeId, LinkedHashMap::new, Collectors.toList()))
                .entrySet()) {
            if (items.size() >= pageSize) {
                truncated = true;
                break;
            }
            ObjectTypeDefinition objectType = type(implementation.getKey());
            ObjectSetPage page = query(new ObjectSetRequest(objectType.id(), Map.of(), List.of(),
                    100, null, List.of()), actor);
            for (ObjectSummary object : page.items()) {
                if (items.size() >= pageSize) {
                    truncated = true;
                    break;
                }
                Map<String, Object> slots = new LinkedHashMap<>();
                implementation.getValue().forEach(mapping ->
                        slots.put(mapping.slotName(), object.properties().get(mapping.propertyName())));
                items.add(new InterfaceObject(object.objectId(), object.title(), object.objectTypeApiName(),
                        object.objectTypeId(), Map.copyOf(slots)));
            }
            truncated = truncated || page.nextCursor() != null;
        }
        return new InterfaceQueryPage(interfaceId, List.copyOf(items), truncated);
    }

    public CapabilityResponse capabilities(UUID objectTypeId, String objectId, Actor actor) {
        object(objectTypeId, objectId, actor);
        List<Capability> actions = actor.roles().contains("Viewer") && !actor.builder() ? List.of() : jdbc.sql("""
                SELECT r.id,r.api_name,r.display_name
                FROM control.ontology_resources r JOIN control.action_types a ON a.resource_id=r.id
                WHERE a.target_object_type_id=:type AND r.ontology_id=:ontology
                  AND r.active_version IS NOT NULL
                ORDER BY r.display_name
                """).param("type", objectTypeId).param("ontology", WorkspaceContext.id()).query((rs, row) -> new Capability(
                rs.getObject("id", UUID.class), "ACTION", rs.getString("display_name"),
                rs.getString("api_name"), true, true)).list();
        List<Capability> functions = jdbc.sql("""
                SELECT id,api_name,display_name FROM control.ontology_resources
                WHERE ontology_id=:ontology AND kind='FUNCTION' AND active_version IS NOT NULL ORDER BY display_name
                """).param("ontology", WorkspaceContext.id()).query((rs, row) -> new Capability(rs.getObject("id", UUID.class), "FUNCTION",
                rs.getString("display_name"), rs.getString("api_name"), true, false)).list();
        return new CapabilityResponse(actions, functions,
                List.of("分析看板", "数据血缘", actor.builder() ? "本体管理" : "AIP"));
    }

    public List<ActivityItem> activity(UUID objectTypeId, String objectId, Actor actor) {
        ObjectTypeDefinition type = type(objectTypeId);
        stored(objectId, type);
        return jdbc.sql("""
                SELECT status,payload->>'correlationId' correlation_id,
                       COALESCE(published_at,created_at) occurred_at,version
                FROM control.object_instance_outbox
                WHERE ontology_id=:ontology AND object_type_id=:objectType
                  AND object_id=:objectId
                ORDER BY created_at DESC LIMIT 50
                """).param("ontology", WorkspaceContext.id())
                .param("objectType", objectTypeId)
                .param("objectId", objectId)
                .query((rs, row) -> new ActivityItem(
                        "OBJECT_MUTATION",
                        rs.getString("status"),
                        "对象版本 " + rs.getLong("version"),
                        "PostgreSQL Outbox",
                        rs.getString("correlation_id"),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    public ProvenanceView provenance(UUID objectTypeId, String objectId, Actor actor) {
        ObjectTypeDefinition type = type(objectTypeId);
        stored(objectId, type);
        Map<String, Object> mapping = jdbc.sql("""
                SELECT p.name pipeline_name,m.pipeline_version,ds.name source_name
                FROM control.object_type_versions ot
                LEFT JOIN control.ontology_mappings m ON m.resource_version_id=ot.version_id
                LEFT JOIN control.pipelines p ON p.id=m.pipeline_id
                LEFT JOIN control.data_sources ds ON ds.id=p.data_source_id
                WHERE ot.resource_id=:id ORDER BY ot.version_id DESC LIMIT 1
                """).param("id", objectTypeId).query((rs, row) -> Map.<String, Object>of(
                "pipeline", rs.getString("pipeline_name") == null ? "PostgreSQL 对象服务" : rs.getString("pipeline_name"),
                "version", rs.getObject("pipeline_version") == null ? 0 : rs.getInt("pipeline_version"),
                "source", rs.getString("source_name") == null ? "对象实例主存" : rs.getString("source_name"))).optional().orElse(Map.of(
                "pipeline", "PostgreSQL 对象服务", "version", 0, "source", "对象实例主存"));
        List<Map<String, Object>> lineage = visibleProperties(type).stream().map(property -> Map.<String, Object>of(
                "propertyId", property.id(), "propertyName", property.displayName(),
                "source", "instance." + property.apiName())).toList();
        boolean projected = jdbc.sql("""
                SELECT count(*)=0
                FROM control.object_instance_projection_state
                WHERE ontology_id=:ontology AND object_type_id=:objectType
                  AND object_id=:objectId AND status<>'PROJECTED'
                """).param("ontology", WorkspaceContext.id())
                .param("objectType", objectTypeId)
                .param("objectId", objectId)
                .query(Boolean.class)
                .single();
        return new ProvenanceView(objectId, String.valueOf(mapping.get("pipeline")),
                ((Number) mapping.get("version")).intValue(), projected ? "PROJECTED" : "DEGRADED",
                String.valueOf(mapping.get("source")), projected ? "HEALTHY" : "DEGRADED", lineage);
    }

    private ObjectSummary summary(SearchHit hit, ObjectTypeDefinition type) {
        return summary(hit.objectId(), type);
    }

    private ObjectSummary summary(RawSearchHit hit, ObjectTypeDefinition type) {
        return summary(hit.objectId(), type);
    }

    private ObjectSummary summary(String objectId, ObjectTypeDefinition type) {
        StoredInstance value = stored(objectId, type);
        Map<String, Object> properties =
                safeProperties(authority.effective(value), type);
        return new ObjectSummary(
                objectId,
                value.title(),
                type.apiName(),
                type.id(),
                properties,
                redacted(type),
                "PASS",
                value.updatedAt());
    }

    private ObjectDetail detail(String objectId, ObjectTypeDefinition type) {
        StoredInstance value = stored(objectId, type);
        Map<String, Object> properties =
                safeProperties(authority.effective(value), type);
        return new ObjectDetail(
                objectId,
                value.title(),
                type,
                "\"" + value.version() + "\"",
                properties,
                redacted(type),
                "PASS",
                value.updatedAt());
    }

    private StoredInstance stored(String objectId, ObjectTypeDefinition type) {
        return authority.find(WorkspaceContext.id(), type.id(), objectId)
                .orElseThrow(() -> notFound("对象不存在"));
    }

    private Map<String, Object> safeProperties(JsonNode source, ObjectTypeDefinition type) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (PropertyDefinition property : type.properties()) {
            if (!property.sensitive() && source.has(property.physicalKey())) {
                result.put(
                        property.displayName(),
                        objectMapper.convertValue(source.get(property.physicalKey()), Object.class));
            }
        }
        return Map.copyOf(result);
    }

    private String title(String objectId, Map<String, Object> properties, ObjectTypeDefinition type) {
        return type.properties().stream().filter(PropertyDefinition::titleProperty)
                .map(PropertyDefinition::displayName)
                .map(properties::get)
                .filter(java.util.Objects::nonNull)
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
                SELECT r.id,r.api_name,r.physical_key,r.display_name,r.maturity
                FROM control.ontology_resources r
                WHERE r.id=:id AND r.ontology_id=:workspace AND r.kind='OBJECT_TYPE' AND r.active_version IS NOT NULL
                """).param("id", id).param("workspace", WorkspaceContext.id()).query((rs, row) -> new ObjectTypeDefinition(rs.getObject("id", UUID.class),
                        rs.getString("api_name"), rs.getString("physical_key"), rs.getString("display_name"), rs.getString("maturity"),
                        properties(rs.getObject("id", UUID.class)))).optional()
                .orElseThrow(() -> notFound("对象类型不存在或尚未发布"));
    }

    private List<ObjectTypeDefinition> types() {
        return jdbc.sql("""
                SELECT r.id,r.api_name,r.physical_key,r.display_name,r.maturity
                FROM control.ontology_resources r
                WHERE r.ontology_id=:workspace AND r.kind='OBJECT_TYPE' AND r.active_version IS NOT NULL
                ORDER BY r.promoted DESC,r.display_name
                """).param("workspace", WorkspaceContext.id()).query((rs, row) -> new ObjectTypeDefinition(rs.getObject("id", UUID.class),
                rs.getString("api_name"), rs.getString("physical_key"), rs.getString("display_name"), rs.getString("maturity"),
                properties(rs.getObject("id", UUID.class)))).list();
    }

    private ObjectTypeDefinition findTypeByStorageKey(String physicalKey) {
        return types().stream().filter(type -> type.physicalKey().equals(physicalKey)).findFirst().orElse(null);
    }

    private List<PropertyDefinition> properties(UUID objectTypeId) {
        return jdbc.sql("""
                SELECT p.id,p.api_name,p.physical_key,pv.display_name,pv.value_type,pv.primary_key,pv.title_property,
                pv.searchable,pv.filterable,pv.sortable,pv.sensitive
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions rv ON rv.resource_id=r.id AND rv.version=r.active_version
                JOIN control.object_type_versions ot ON ot.version_id=rv.id
                JOIN control.property_versions pv ON pv.object_type_version_id=ot.version_id
                JOIN control.properties p ON p.id=pv.property_id
                WHERE r.id=:id ORDER BY pv.primary_key DESC,pv.title_property DESC,pv.display_name
                """).param("id", objectTypeId).query((rs, row) -> new PropertyDefinition(
                        rs.getObject("id", UUID.class), rs.getString("api_name"), rs.getString("physical_key"), rs.getString("display_name"),
                rs.getString("value_type"), rs.getBoolean("primary_key"), rs.getBoolean("title_property"),
                rs.getBoolean("searchable"), rs.getBoolean("filterable"), rs.getBoolean("sortable"), rs.getBoolean("sensitive"))).list();
    }

    private LinkDefinition findLink(String physicalKey) {
        return jdbc.sql("""
                SELECT id,api_name,display_name FROM control.ontology_resources
                WHERE ontology_id=:workspace AND kind='LINK_TYPE' AND physical_key=:physicalKey
                  AND active_version IS NOT NULL
                """).param("workspace", WorkspaceContext.id()).param("physicalKey", physicalKey)
                .query((rs, row) -> new LinkDefinition(rs.getObject("id", UUID.class),
                rs.getString("api_name"), rs.getString("display_name"))).optional().orElse(null);
    }

    private LinkDefinition linkType(UUID id) {
        return jdbc.sql("""
                SELECT id,api_name,display_name FROM control.ontology_resources
                WHERE ontology_id=:ontology AND kind='LINK_TYPE' AND id=:id
                  AND active_version IS NOT NULL
                """).param("ontology", WorkspaceContext.id()).param("id", id)
                .query((rs, row) -> new LinkDefinition(rs.getObject("id", UUID.class),
                        rs.getString("api_name"), rs.getString("display_name")))
                .optional().orElseThrow(() -> notFound("关系类型不存在"));
    }

    private UUID resolveResource(String kind, String value) {
        if (value == null || value.isBlank()) throw notFound("本体资源不存在");
        return jdbc.sql("""
                SELECT id FROM control.ontology_resources
                WHERE ontology_id=:ontology AND kind=:kind AND api_name=:value
                  AND active_version IS NOT NULL
                """).param("ontology", WorkspaceContext.id()).param("kind", kind)
                .param("value", value).query(UUID.class).optional()
                .orElseThrow(() -> notFound("本体资源不存在"));
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

    private Map<String, Object> jsonMap(JsonNode value) {
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    private record LinkDefinition(UUID id, String apiName, String name) { }
}
