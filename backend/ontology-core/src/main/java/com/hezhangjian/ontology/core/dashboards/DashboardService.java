package com.hezhangjian.ontology.core.dashboards;

import static com.hezhangjian.ontology.core.dashboards.DashboardModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.explorer.ExplorerModels;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.AggregationBucket;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.FacetRequest;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.FacetResult;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.ObjectSetPage;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.ObjectSetRequest;
import com.hezhangjian.ontology.core.explorer.ExplorerService;
import com.hezhangjian.ontology.core.deletion.ResourceDeletionService;
import com.hezhangjian.ontology.core.datasets.DatasetModels;
import com.hezhangjian.ontology.core.datasets.DatasetService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DashboardService {
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "USERS", "GROUPS", "TEAM", "ORGANIZATION");
    private static final Set<String> REFRESH_POLICIES = Set.of("MANUAL", "OFF", "1_MIN", "5_MIN", "15_MIN", "60_MIN");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final DashboardPolicy policy;
    private final ExplorerService explorer;
    private final DashboardTokenCodec tokens;
    private final DashboardProperties properties;
    private final ResourceDeletionService deletion;
    private final DatasetService datasets;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public DashboardService(JdbcClient jdbc, ObjectMapper objectMapper, DashboardPolicy policy,
                            ExplorerService explorer, DashboardTokenCodec tokens,
                            DashboardProperties properties, ResourceDeletionService deletion,
                            DatasetService datasets) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.explorer = explorer;
        this.tokens = tokens;
        this.properties = properties;
        this.deletion = deletion;
        this.datasets = datasets;
    }

    public List<DashboardSummary> list(String keyword, String lifecycle, boolean favoritesOnly, Actor actor) {
        String search = keyword == null ? "" : keyword.trim().toLowerCase();
        String status = lifecycle == null ? "" : lifecycle.trim().toUpperCase();
        return jdbc.sql("""
                SELECT d.id,d.name,d.description,d.lifecycle,d.owner_name,d.visibility,d.refresh_policy,d.etag,
                       d.updated_at,d.last_published_at,v.version AS current_version,
                       COALESCE(jsonb_array_length(COALESCE(v.definition,dr.definition)->'pages'),0) AS page_count,
                       COALESCE(jsonb_array_length(COALESCE(v.definition,dr.definition)->'widgets'),0) AS widget_count,
                       CASE WHEN EXISTS(SELECT 1 FROM control.dashboard_health_issues h WHERE h.dashboard_id=d.id AND h.status='OPEN' AND h.severity='ERROR') THEN 'ERROR'
                            WHEN EXISTS(SELECT 1 FROM control.dashboard_health_issues h WHERE h.dashboard_id=d.id AND h.status='OPEN') THEN 'WARNING'
                            ELSE COALESCE(v.health_status,'UNKNOWN') END AS health_status,
                       EXISTS(SELECT 1 FROM control.dashboard_favorites f WHERE f.dashboard_id=d.id AND f.user_id=:actor) AS favorite
                FROM control.dashboards d
                LEFT JOIN control.dashboard_versions v ON v.id=d.current_version_id
                LEFT JOIN control.dashboard_drafts dr ON dr.id=d.active_draft_id
                WHERE (:search='' OR lower(d.name) LIKE '%'||:search||'%' OR lower(d.description) LIKE '%'||:search||'%')
                  AND (:status='' OR d.lifecycle=:status)
                  AND (:favorites=false OR EXISTS(SELECT 1 FROM control.dashboard_favorites f WHERE f.dashboard_id=d.id AND f.user_id=:actor))
                  AND (d.owner_id=:actor OR d.visibility IN ('TEAM','ORGANIZATION')
                       OR EXISTS(SELECT 1 FROM control.dashboard_permissions p WHERE p.dashboard_id=d.id AND p.subject_type='USER' AND p.subject_id=:actor))
                ORDER BY d.updated_at DESC,d.id
                """).param("actor", actor.id()).param("search", search).param("status", status)
                .param("favorites", favoritesOnly).query(this::summary).list();
    }

    @Transactional
    public DashboardDetail create(DashboardCreateRequest request, Actor actor) {
        if (!actor.builder()) throw forbidden("只有 Builder 或 Admin 可以创建看板");
        String name = required(request.name(), "看板名称不能为空", 240);
        String visibility = choice(request.visibility(), "PRIVATE", VISIBILITIES, "可见范围无效");
        String refresh = choice(request.refreshPolicy(), "MANUAL", REFRESH_POLICIES, "刷新策略无效");
        UUID dashboardId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        DashboardDefinition definition = blankDefinition();
        jdbc.sql("""
                INSERT INTO control.dashboards(id,name,description,owner_id,owner_name,visibility,lifecycle,refresh_policy,tags,active_draft_id)
                VALUES (:id,:name,:description,:owner,:ownerName,:visibility,'DRAFT',:refresh,CAST(:tags AS text[]),NULL)
                """).param("id", dashboardId).param("name", name).param("description", text(request.description(), 4000))
                .param("owner", actor.id()).param("ownerName", actor.name()).param("visibility", visibility)
                .param("refresh", refresh).param("tags", pgArray(request.tags())).update();
        jdbc.sql("""
                INSERT INTO control.dashboard_drafts(id,dashboard_id,definition,schema_version,status,updated_by)
                VALUES (:id,:dashboard,CAST(:definition AS jsonb),1,'DRAFT',:actor)
                """).param("id", draftId).param("dashboard", dashboardId).param("definition", json(definition))
                .param("actor", actor.id()).update();
        jdbc.sql("UPDATE control.dashboards SET active_draft_id=:draft WHERE id=:id")
                .param("draft", draftId).param("id", dashboardId).update();
        persistDefinition(dashboardId, draftId, false, definition);
        audit(actor, "DASHBOARD_CREATED", dashboardId, "创建分析看板", Map.of("visibility", visibility));
        return get(dashboardId, actor);
    }

    public DashboardDetail get(UUID id, Actor actor) {
        String role = requireAccess(id, actor, "VIEWER");
        DashboardSummary summary = summary(id, actor);
        DashboardVersionView version = summary.currentVersion() == null ? null : currentVersion(id);
        DashboardDraftView draft = canEdit(role) ? activeDraft(id) : null;
        return new DashboardDetail(summary, version, draft, role);
    }

    @Transactional
    public DashboardDetail patch(UUID id, long ifMatch, DashboardPatchRequest request, Actor actor) {
        requireAccess(id, actor, "OWNER");
        DashboardSummary current = summary(id, actor);
        if (current.etag() != ifMatch) throw conflict("看板元数据已被其他用户修改，请重新载入");
        String name = request.name() == null ? current.name() : required(request.name(), "看板名称不能为空", 240);
        String visibility = request.visibility() == null ? current.visibility()
                : choice(request.visibility(), null, VISIBILITIES, "可见范围无效");
        String refresh = request.refreshPolicy() == null ? current.refreshPolicy()
                : choice(request.refreshPolicy(), null, REFRESH_POLICIES, "刷新策略无效");
        jdbc.sql("""
                UPDATE control.dashboards SET name=:name,description=:description,visibility=:visibility,
                       refresh_policy=:refresh,tags=CAST(:tags AS text[]),etag=etag+1,updated_at=now()
                WHERE id=:id AND etag=:etag
                """).param("name", name).param("description", request.description() == null ? current.description() : text(request.description(), 4000))
                .param("visibility", visibility).param("refresh", refresh).param("tags", pgArray(request.tags()))
                .param("id", id).param("etag", ifMatch).update();
        audit(actor, "DASHBOARD_UPDATED", id, "更新看板元数据", Map.of("etag", ifMatch + 1));
        return get(id, actor);
    }

    @Transactional
    public DashboardDetail copy(UUID sourceId, Actor actor) {
        if (!actor.builder()) throw forbidden("只有 Builder 或 Admin 可以复制看板");
        requireAccess(sourceId, actor, "VIEWER");
        DashboardSummary source = summary(sourceId, actor);
        DashboardDefinition definition = source.currentVersion() == null
                ? Objects.requireNonNull(activeDraft(sourceId)).definition() : currentVersion(sourceId).definition();
        DashboardDetail created = create(new DashboardCreateRequest(source.name() + " 副本", source.description(),
                "PRIVATE", source.refreshPolicy(), List.of()), actor);
        DashboardDraftView draft = Objects.requireNonNull(created.activeDraft());
        putDraft(created.summary().id(), draft.etag(), definition, actor, false);
        audit(actor, "DASHBOARD_COPIED", created.summary().id(), "复制分析看板", Map.of("sourceDashboardId", sourceId));
        return get(created.summary().id(), actor);
    }

    @Transactional
    public DashboardDetail archive(UUID id, Actor actor) {
        requireAccess(id, actor, "OWNER");
        jdbc.sql("UPDATE control.dashboards SET lifecycle='ARCHIVED',archived_at=now(),updated_at=now(),etag=etag+1 WHERE id=:id")
                .param("id", id).update();
        cache.entrySet().removeIf(entry -> entry.getKey().startsWith(id + ":"));
        audit(actor, "DASHBOARD_ARCHIVED", id, "归档分析看板", Map.of());
        return get(id, actor);
    }

    @Transactional
    public DashboardDetail restore(UUID id, Actor actor) {
        requireAccess(id, actor, "OWNER");
        DashboardSummary current = summary(id, actor);
        if (!"ARCHIVED".equals(current.lifecycle())) throw conflict("只有已归档看板可以恢复");
        String lifecycle = current.currentVersion() == null ? "DRAFT" : "PUBLISHED";
        jdbc.sql("UPDATE control.dashboards SET lifecycle=:lifecycle,archived_at=NULL,updated_at=now(),etag=etag+1 WHERE id=:id")
                .param("lifecycle", lifecycle).param("id", id).update();
        audit(actor, "DASHBOARD_RESTORED", id, "恢复分析看板", Map.of());
        return get(id, actor);
    }

    @Transactional
    public void delete(UUID id, Actor actor) {
        int count = jdbc.sql("SELECT count(*) FROM control.dashboards WHERE id=:id")
                .param("id", id).query(Integer.class).single();
        if (count == 0) throw notFound("看板不存在");
        audit(actor, "DASHBOARD_DELETED", id, "永久删除看板及关联记录", Map.of());
        cache.entrySet().removeIf(entry -> entry.getKey().startsWith(id + ":"));
        deletion.deleteDashboard(id);
    }

    @Transactional
    public DashboardDraftView draft(UUID id, Actor actor) {
        requireAccess(id, actor, "EDITOR");
        DashboardDraftView current = activeDraft(id);
        if (current != null) return current;
        DashboardVersionView version = currentVersion(id);
        return createDraft(id, version == null ? null : version.id(),
                version == null ? blankDefinition() : version.definition(), actor);
    }

    @Transactional
    public DashboardDraftView putDraft(UUID id, long ifMatch, DashboardDefinition definition,
                                       Actor actor, boolean requireLock) {
        requireAccess(id, actor, "EDITOR");
        if (requireLock) requireEditLock(id, actor);
        DashboardDraftView current = draft(id, actor);
        if (current.etag() != ifMatch) {
            audit(actor, "DASHBOARD_DRAFT_CONFLICT", id, "草稿 ETag 冲突", Map.of("expected", current.etag(), "provided", ifMatch));
            throw conflict("草稿已被其他编辑者修改，请重新载入或复制本地补丁");
        }
        DashboardValidationResult structural = policy.validate(definition);
        List<DashboardValidationIssue> fatal = structural.issues().stream()
                .filter(issue -> Set.of("SCHEMA_VERSION", "PAGE_LIMIT", "WIDGET_LIMIT", "FILTER_LIMIT", "STABLE_ID").contains(issue.code())).toList();
        if (!fatal.isEmpty()) throw invalid(fatal.get(0).message());
        int changed = jdbc.sql("""
                UPDATE control.dashboard_drafts SET definition=CAST(:definition AS jsonb),schema_version=:schema,
                       etag=etag+1,status='DRAFT',updated_by=:actor,updated_at=now()
                WHERE id=:draft AND etag=:etag
                """).param("definition", json(definition)).param("schema", definition.schemaVersion())
                .param("actor", actor.id()).param("draft", current.id()).param("etag", ifMatch).update();
        if (changed != 1) throw conflict("草稿保存冲突，请重新载入");
        persistDefinition(id, current.id(), false, definition);
        jdbc.sql("UPDATE control.dashboards SET lifecycle=CASE WHEN current_version_id IS NULL THEN 'DRAFT' ELSE lifecycle END,updated_at=now() WHERE id=:id")
                .param("id", id).update();
        audit(actor, "DASHBOARD_DRAFT_SAVED", id, "保存看板草稿", Map.of("etag", ifMatch + 1, "definitionHash", policy.fingerprint(definition)));
        return activeDraft(id);
    }

    @Transactional
    public DashboardEditLock acquireLock(UUID id, boolean force, Actor actor) {
        String role = requireAccess(id, actor, "EDITOR");
        DashboardEditLock existing = editLock(id);
        Instant now = Instant.now();
        if (existing != null && existing.expiresAt().isAfter(now) && !existing.holderId().equals(actor.id())) {
            if (!force || !"OWNER".equals(role)) throw conflict("看板正由 " + existing.holderName() + " 编辑，可申请 Owner 接管");
            audit(actor, "DASHBOARD_LOCK_TAKEN_OVER", id, "Owner 强制接管编辑锁", Map.of("previousHolder", existing.holderId()));
        }
        UUID token = existing != null && existing.holderId().equals(actor.id()) ? existing.leaseToken() : UUID.randomUUID();
        Instant expires = now.plus(properties.editLockTtl());
        jdbc.sql("""
                INSERT INTO control.dashboard_edit_locks(dashboard_id,holder_id,holder_name,lease_token,acquired_at,expires_at)
                VALUES (:id,:holder,:name,:token,now(),:expires)
                ON CONFLICT (dashboard_id) DO UPDATE SET holder_id=EXCLUDED.holder_id,holder_name=EXCLUDED.holder_name,
                    lease_token=EXCLUDED.lease_token,acquired_at=now(),expires_at=EXCLUDED.expires_at
                """).param("id", id).param("holder", actor.id()).param("name", actor.name()).param("token", token)
                .param("expires", Timestamp.from(expires)).update();
        audit(actor, "DASHBOARD_LOCK_ACQUIRED", id, "获取看板编辑锁", Map.of("expiresAt", expires));
        return editLock(id);
    }

    @Transactional
    public DashboardEditLock renewLock(UUID id, Actor actor) {
        requireAccess(id, actor, "EDITOR");
        Instant expires = Instant.now().plus(properties.editLockTtl());
        int changed = jdbc.sql("""
                UPDATE control.dashboard_edit_locks SET expires_at=:expires
                WHERE dashboard_id=:id AND holder_id=:actor AND expires_at>now()
                """).param("expires", Timestamp.from(expires)).param("id", id).param("actor", actor.id()).update();
        if (changed != 1) throw conflict("编辑锁已丢失，请重新获取");
        return editLock(id);
    }

    @Transactional
    public void releaseLock(UUID id, Actor actor) {
        String role = requireAccess(id, actor, "EDITOR");
        DashboardEditLock lock = editLock(id);
        if (lock != null && !lock.holderId().equals(actor.id()) && !"OWNER".equals(role)) throw forbidden("不能释放其他编辑者的锁");
        jdbc.sql("DELETE FROM control.dashboard_edit_locks WHERE dashboard_id=:id").param("id", id).update();
    }

    @Transactional
    public DashboardValidationResult validate(UUID id, Actor actor) {
        requireAccess(id, actor, "EDITOR");
        DashboardDraftView draft = draft(id, actor);
        jdbc.sql("UPDATE control.dashboard_drafts SET status='VALIDATING' WHERE id=:id").param("id", draft.id()).update();
        jdbc.sql("UPDATE control.dashboards SET lifecycle='VALIDATING' WHERE id=:id AND current_version_id IS NULL").param("id", id).update();
        DashboardValidationResult result = policy.validate(draft.definition());
        jdbc.sql("UPDATE control.dashboard_drafts SET status=:status WHERE id=:id")
                .param("status", result.status()).param("id", draft.id()).update();
        jdbc.sql("UPDATE control.dashboards SET lifecycle=CASE WHEN current_version_id IS NULL THEN :status ELSE lifecycle END WHERE id=:id")
                .param("status", result.status()).param("id", id).update();
        audit(actor, "DASHBOARD_VALIDATED", id, result.valid() ? "看板验证通过" : "看板验证失败",
                Map.of("valid", result.valid(), "issueCount", result.issues().size(), "definitionHash", result.definitionHash()));
        return result;
    }

    @Transactional
    public DashboardVersionView publish(UUID id, PublishRequest request, Actor actor) {
        requireAccess(id, actor, "OWNER");
        requireEditLock(id, actor);
        DashboardDraftView draft = draft(id, actor);
        DashboardValidationResult validation = policy.validate(draft.definition());
        if (!validation.valid()) throw invalid("看板存在发布阻断问题，请先完成验证");
        long revision = currentRevision();
        validateSample(draft.definition(), actor);
        int versionNumber = jdbc.sql("SELECT COALESCE(max(version),0)+1 FROM control.dashboard_versions WHERE dashboard_id=:id")
                .param("id", id).query(Integer.class).single();
        UUID versionId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        String definitionHash = validation.definitionHash();
        String planHash = policy.fingerprint(Map.of("dashboardId", id, "version", versionNumber,
                "definitionHash", definitionHash, "ontologyRevision", revision, "policyRevision", 1));
        jdbc.sql("UPDATE control.dashboards SET lifecycle='PUBLISHING' WHERE id=:id").param("id", id).update();
        jdbc.sql("""
                INSERT INTO control.dashboard_versions(id,dashboard_id,version,definition,schema_version,ontology_revision,
                    query_plan_hash,published_by,published_by_name,release_notes,health_status)
                VALUES (:id,:dashboard,:version,CAST(:definition AS jsonb),:schema,:revision,:hash,:actor,:name,:notes,'HEALTHY')
                """).param("id", versionId).param("dashboard", id).param("version", versionNumber)
                .param("definition", json(draft.definition())).param("schema", draft.definition().schemaVersion())
                .param("revision", revision).param("hash", planHash).param("actor", actor.id()).param("name", actor.name())
                .param("notes", text(request == null ? "" : request.releaseNotes(), 4000)).update();
        jdbc.sql("""
                INSERT INTO control.dashboard_query_plans(id,dashboard_id,version_id,plan_hash,definition_hash,
                    ontology_revision,policy_revision,estimated_cost)
                VALUES (:id,:dashboard,:version,:hash,:definitionHash,:revision,1,:cost)
                """).param("id", planId).param("dashboard", id).param("version", versionId).param("hash", planHash)
                .param("definitionHash", definitionHash).param("revision", revision).param("cost", validation.estimatedCost()).update();
        persistDefinition(id, versionId, true, draft.definition());
        persistDependencies(id, versionId, draft.definition(), revision);
        jdbc.sql("""
                UPDATE control.dashboards SET lifecycle='PUBLISHED',current_version_id=:version,active_draft_id=NULL,
                    last_published_at=now(),updated_at=now(),etag=etag+1 WHERE id=:id
                """).param("version", versionId).param("id", id).update();
        jdbc.sql("DELETE FROM control.dashboard_edit_locks WHERE dashboard_id=:id").param("id", id).update();
        cache.entrySet().removeIf(entry -> entry.getKey().startsWith(id + ":"));
        audit(actor, "DASHBOARD_PUBLISHED", id, "发布不可变看板版本 v" + versionNumber,
                Map.of("versionId", versionId, "queryPlanHash", planHash, "ontologyRevision", revision));
        return version(id, versionId, actor);
    }

    public List<DashboardVersionView> versions(UUID id, Actor actor) {
        requireAccess(id, actor, "VIEWER");
        return jdbc.sql("""
                SELECT id,dashboard_id,version,definition,schema_version,ontology_revision,query_plan_hash,
                       published_by_name,release_notes,health_status,published_at
                FROM control.dashboard_versions WHERE dashboard_id=:id ORDER BY version DESC
                """).param("id", id).query(this::version).list();
    }

    public DashboardVersionView version(UUID dashboardId, UUID versionId, Actor actor) {
        requireAccess(dashboardId, actor, "VIEWER");
        return jdbc.sql("""
                SELECT id,dashboard_id,version,definition,schema_version,ontology_revision,query_plan_hash,
                       published_by_name,release_notes,health_status,published_at
                FROM control.dashboard_versions WHERE dashboard_id=:dashboard AND id=:version
                """).param("dashboard", dashboardId).param("version", versionId).query(this::version).optional()
                .orElseThrow(() -> notFound("看板版本不存在"));
    }

    public DashboardVersionDiff diff(UUID id, UUID fromId, UUID toId, Actor actor) {
        DashboardVersionView from = version(id, fromId, actor);
        DashboardVersionView to = version(id, toId, actor);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("pages", countChange(from.definition().pages().size(), to.definition().pages().size()));
        changes.put("widgets", countChange(from.definition().widgets().size(), to.definition().widgets().size()));
        changes.put("dataSources", countChange(from.definition().dataSources().size(), to.definition().dataSources().size()));
        changes.put("filters", countChange(from.definition().filters().size(), to.definition().filters().size()));
        changes.put("definitionChanged", !policy.fingerprint(from.definition()).equals(policy.fingerprint(to.definition())));
        return new DashboardVersionDiff(from.version(), to.version(), from.queryPlanHash(), to.queryPlanHash(), changes);
    }

    @Transactional
    public DashboardDraftView createDraftFromVersion(UUID id, UUID versionId, Actor actor) {
        requireAccess(id, actor, "EDITOR");
        DashboardVersionView source = version(id, versionId, actor);
        DashboardDraftView current = activeDraft(id);
        if (current != null) throw conflict("看板已有活动草稿，请先处理现有草稿");
        DashboardDraftView created = createDraft(id, source.id(), source.definition(), actor);
        audit(actor, "DASHBOARD_VERSION_DRAFTED", id, "从历史版本创建新草稿", Map.of("sourceVersion", source.version()));
        return created;
    }

    public List<DashboardPermission> permissions(UUID id, Actor actor) {
        requireAccess(id, actor, "OWNER");
        return jdbc.sql("""
                SELECT subject_type,subject_id,permission_role FROM control.dashboard_permissions
                WHERE dashboard_id=:id ORDER BY subject_type,subject_id
                """).param("id", id).query((rs, row) -> new DashboardPermission(
                        rs.getString("subject_type"), rs.getString("subject_id"), rs.getString("permission_role"))).list();
    }

    @Transactional
    public List<DashboardPermission> putPermissions(UUID id, DashboardPermissionsRequest request, Actor actor) {
        requireAccess(id, actor, "OWNER");
        List<DashboardPermission> values = request == null || request.permissions() == null ? List.of() : request.permissions();
        for (DashboardPermission permission : values) {
            if (!Set.of("USER", "GROUP", "TEAM").contains(permission.subjectType())
                    || !Set.of("VIEWER", "EDITOR", "OWNER").contains(permission.role())
                    || permission.subjectId() == null || permission.subjectId().isBlank()) throw invalid("看板权限条目无效");
        }
        jdbc.sql("DELETE FROM control.dashboard_permissions WHERE dashboard_id=:id").param("id", id).update();
        values.forEach(permission -> jdbc.sql("""
                INSERT INTO control.dashboard_permissions(dashboard_id,subject_type,subject_id,permission_role,granted_by)
                VALUES (:dashboard,:type,:subject,:role,:actor)
                """).param("dashboard", id).param("type", permission.subjectType()).param("subject", permission.subjectId())
                .param("role", permission.role()).param("actor", actor.id()).update());
        cache.entrySet().removeIf(entry -> entry.getKey().startsWith(id + ":"));
        audit(actor, "DASHBOARD_PERMISSIONS_CHANGED", id, "更新看板权限", Map.of("entryCount", values.size()));
        return permissions(id, actor);
    }

    @Transactional
    public void favorite(UUID id, boolean value, Actor actor) {
        requireAccess(id, actor, "VIEWER");
        if (value) jdbc.sql("""
                INSERT INTO control.dashboard_favorites(dashboard_id,user_id) VALUES (:dashboard,:actor)
                ON CONFLICT DO NOTHING
                """).param("dashboard", id).param("actor", actor.id()).update();
        else jdbc.sql("DELETE FROM control.dashboard_favorites WHERE dashboard_id=:dashboard AND user_id=:actor")
                .param("dashboard", id).param("actor", actor.id()).update();
    }

    public DashboardHealth health(UUID id, Actor actor) {
        requireAccess(id, actor, "VIEWER");
        List<Map<String, Object>> issues = jdbc.sql("""
                SELECT severity,issue_code,summary,page_stable_id,widget_stable_id,data_source_stable_id,detected_at
                FROM control.dashboard_health_issues WHERE dashboard_id=:id AND status='OPEN'
                ORDER BY CASE severity WHEN 'ERROR' THEN 1 WHEN 'WARNING' THEN 2 ELSE 3 END,detected_at DESC
                """).param("id", id).query((rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("severity", rs.getString("severity")); item.put("code", rs.getString("issue_code"));
                    item.put("summary", rs.getString("summary")); item.put("pageId", rs.getObject("page_stable_id"));
                    item.put("widgetId", rs.getObject("widget_stable_id")); item.put("dataSourceId", rs.getObject("data_source_stable_id"));
                    item.put("detectedAt", rs.getTimestamp("detected_at").toInstant()); return item;
                }).list();
        String status = issues.stream().anyMatch(issue -> "ERROR".equals(issue.get("severity"))) ? "ERROR"
                : issues.isEmpty() ? "HEALTHY" : "WARNING";
        return new DashboardHealth(status, issues);
    }

    public DashboardUsage usage(UUID id, Actor actor) {
        requireAccess(id, actor, "OWNER");
        return jdbc.sql("""
                SELECT count(*) AS runs,COALESCE(sum(cache_hit_count),0) AS hits,
                       COALESCE(sum(failed_count),0) AS failed,COALESCE(avg(duration_ms),0) AS average
                FROM control.dashboard_query_runs WHERE dashboard_id=:id
                """).param("id", id).query((rs, row) -> new DashboardUsage(0, rs.getLong("runs"), rs.getLong("hits"),
                        rs.getLong("failed"), rs.getDouble("average"))).single();
    }

    public DashboardQueryPlanView queryPlan(UUID id, Actor actor) {
        requireAccess(id, actor, "VIEWER");
        return jdbc.sql("""
                SELECT p.id,p.dashboard_id,p.version_id,p.plan_hash,p.ontology_revision,p.policy_revision,p.estimated_cost,p.created_at
                FROM control.dashboard_query_plans p JOIN control.dashboards d ON d.current_version_id=p.version_id
                WHERE p.dashboard_id=:id
                """).param("id", id).query(this::plan).optional().orElseThrow(() -> conflict("看板尚未发布查询计划"));
    }

    public DashboardBatchResult execute(UUID planId, DashboardExecuteRequest request, Actor actor) {
        PlanRuntime runtime = runtime(planId, actor);
        if (request == null || request.pageId() == null || request.widgetIds() == null || request.widgetIds().isEmpty()) {
            throw invalid("执行请求必须指定页面和 1—20 个组件");
        }
        if (request.widgetIds().size() > 20) throw invalid("单次批量执行最多包含 20 个组件");
        DashboardPage page = runtime.definition().pages().stream().filter(item -> item.id().equals(request.pageId())).findFirst()
                .orElseThrow(() -> invalid("页面不属于当前发布版本"));
        List<DashboardWidget> widgets = runtime.definition().widgets().stream()
                .filter(widget -> request.widgetIds().contains(widget.id()) && widget.pageId().equals(page.id())).toList();
        if (widgets.size() != request.widgetIds().stream().distinct().count()) throw invalid("组件不属于指定页面或包含重复项");

        UUID runId = UUID.randomUUID();
        UUID refreshId = request.refreshId() == null ? UUID.randomUUID() : request.refreshId();
        UUID correlationId = UUID.randomUUID();
        Instant started = Instant.now();
        String filterHash = policy.fingerprint(request.filters() == null ? Map.of() : request.filters());
        String securityHash = policy.fingerprint(Map.of("actor", actor.id(), "roles", actor.roles().stream().sorted().toList()));
        jdbc.sql("""
                INSERT INTO control.dashboard_query_runs(id,plan_id,dashboard_id,version_id,actor_id,page_id,refresh_id,
                    security_context_hash,filter_hash,status,widget_count,correlation_id)
                VALUES (:id,:plan,:dashboard,:version,:actor,:page,:refresh,:security,:filters,'RUNNING',:count,:correlation)
                """).param("id", runId).param("plan", planId).param("dashboard", runtime.plan().dashboardId())
                .param("version", runtime.plan().versionId()).param("actor", actor.id()).param("page", request.pageId())
                .param("refresh", refreshId).param("security", securityHash).param("filters", filterHash)
                .param("count", widgets.size()).param("correlation", correlationId).update();

        List<DashboardWidgetResult> results = new ArrayList<>();
        int cacheHits = 0;
        for (DashboardWidget widget : widgets) {
            String cacheKey = runtime.plan().dashboardId() + ":" + runtime.plan().planHash() + ":" + widget.id()
                    + ":" + filterHash + ":" + securityHash + ":" + runtime.plan().ontologyRevision() + ":" + runtime.plan().policyRevision();
            CacheEntry cached = cache.get(cacheKey);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                DashboardWidgetResult value = cached.value();
                results.add(new DashboardWidgetResult(value.widgetId(), value.status(), value.kind(), value.data(), true,
                        value.suppressed(), Instant.now(), value.watermark(), correlationId.toString(), null));
                cacheHits++;
                continue;
            }
            try {
                DashboardWidgetResult value = executeWidget(runtime.definition(), widget,
                        request.filters() == null ? Map.of() : request.filters(), actor, correlationId);
                results.add(value);
                cache.put(cacheKey, new CacheEntry(value, Instant.now().plus(properties.cacheTtl())));
            } catch (RuntimeException exception) {
                results.add(new DashboardWidgetResult(widget.id(), "FAILED", widget.type(), null, false, false,
                        Instant.now(), Instant.now(), correlationId.toString(), safeError(exception)));
            }
        }
        int failed = (int) results.stream().filter(item -> "FAILED".equals(item.status())).count();
        String status = failed == 0 ? "SUCCEEDED" : failed == results.size() ? "FAILED" : "PARTIAL";
        long duration = Duration.between(started, Instant.now()).toMillis();
        jdbc.sql("""
                UPDATE control.dashboard_query_runs SET status=:status,succeeded_count=:succeeded,failed_count=:failed,
                    cache_hit_count=:hits,duration_ms=:duration,watermark=:watermark,completed_at=now(),safe_error=:error
                WHERE id=:id
                """).param("status", status).param("succeeded", results.size() - failed).param("failed", failed)
                .param("hits", cacheHits).param("duration", duration).param("watermark", Timestamp.from(Instant.now()))
                .param("error", failed == 0 ? null : "一个或多个组件查询失败").param("id", runId).update();
        return new DashboardBatchResult(runId, status, List.copyOf(results), cacheHits, Instant.now(), correlationId.toString());
    }

    public DashboardBatchResult executeSingle(UUID planId, DashboardExecuteRequest request, Actor actor) {
        if (request == null || request.widgetIds() == null || request.widgetIds().size() != 1) {
            throw invalid("单组件执行必须只指定一个组件");
        }
        return execute(planId, request, actor);
    }

    public List<Map<String, Object>> filterOptions(UUID planId, FilterOptionsRequest request, Actor actor) {
        PlanRuntime runtime = runtime(planId, actor);
        DashboardDataSource source = dataSource(runtime.definition(), request.dataSourceId());
        ObjectSetRequest query = dataSourceQuery(source, request.filters() == null ? Map.of() : request.filters(),
                runtime.definition(), actor);
        List<FacetResult> facets = explorer.facets(new FacetRequest(query, List.of(request.propertyId())), explorerActor(actor));
        if (facets.isEmpty()) return List.of();
        String search = request.search() == null ? "" : request.search().toLowerCase();
        return facets.get(0).buckets().stream().filter(bucket -> search.isBlank()
                        || String.valueOf(bucket.value()).toLowerCase().contains(search))
                .map(bucket -> Map.<String, Object>of("value", bucket.value(), "count", bucket.count()))
                .toList();
    }

    public DrilldownToken drilldown(UUID planId, DrilldownRequest request, Actor actor) {
        PlanRuntime runtime = runtime(planId, actor);
        DashboardWidget widget = runtime.definition().widgets().stream().filter(item -> item.id().equals(request.widgetId()))
                .findFirst().orElseThrow(() -> invalid("下钻组件不存在"));
        if (!Boolean.TRUE.equals(widget.interaction() == null ? null : widget.interaction().get("drilldown"))) {
            throw forbidden("该组件未声明下钻能力");
        }
        if (Boolean.TRUE.equals(widget.config() == null ? null : widget.config().get("suppressed"))) {
            throw forbidden("被抑制的数据不能下钻");
        }
        Instant expires = Instant.now().plus(properties.tokenTtl());
        String token = tokens.sign(Map.of("kind", "dashboard-drilldown", "owner", actor.id(),
                "dashboard", runtime.plan().dashboardId(), "version", runtime.plan().versionId(),
                "widget", widget.id(), "valueHash", policy.fingerprint(request.value()),
                "filterHash", policy.fingerprint(request.filters() == null ? Map.of() : request.filters())), expires);
        return new DrilldownToken(token, expires, "OBJECT_EXPLORER");
    }

    private DashboardWidgetResult executeWidget(DashboardDefinition definition, DashboardWidget widget,
                                                Map<String, Object> filters, Actor actor, UUID correlationId) {
        Instant queriedAt = Instant.now();
        if ("MARKDOWN".equals(widget.type()) || "SECTION".equals(widget.type()) || "FILTER".equals(widget.type())) {
            return new DashboardWidgetResult(widget.id(), "SUCCEEDED", widget.type(), widget.config(), false,
                    false, queriedAt, queriedAt, correlationId.toString(), null);
        }
        DashboardDataSource source = dataSource(definition, widget.dataSourceId());
        if ("DATASET".equals(source.kind())) return executeDatasetWidget(source, widget, correlationId, queriedAt);
        ObjectSetRequest query = dataSourceQuery(source, filters, definition, actor);
        ObjectSetPage page = explorer.query(query, explorerActor(actor));
        Object data;
        boolean suppressed = false;
        if ("METRIC".equals(widget.type())) {
            Map<String, Object> measure = firstMeasure(widget);
            String aggregation = String.valueOf(measure.getOrDefault("aggregation", "count"));
            Object field = measure.containsKey("field") ? measure.get("field") : widget.config().get("propertyId");
            data = metric(aggregation, field, page);
        } else if (Set.of("LINE", "AREA", "BAR", "STACKED_BAR", "PIE", "DONUT", "PIVOT").contains(widget.type())) {
            List<UUID> propertyIds = uuids(
                    objectDimensionFields(widget), widget.config().get("dimensionPropertyId"),
                    "图表必须选择分组字段");
            Map<String, Object> measure = firstMeasure(widget);
            String aggregation = String.valueOf(measure.getOrDefault("aggregation", "count"));
            Object measureField = measure.containsKey("field") ? measure.get("field") : widget.config().get("measurePropertyId");
            Object divisorField = measure.containsKey("divisorField") ? measure.get("divisorField") : widget.config().get("divisorPropertyId");
            UUID measurePropertyId = "count".equals(aggregation) ? null
                    : uuid(measureField, "非计数组件必须指定稳定度量属性 ID");
            UUID divisorPropertyId = "sum_per_distinct".equals(aggregation)
                    ? uuid(divisorField, "人均等比值指标必须选择去重计数字段")
                    : null;
            List<AggregationBucket> grouped = explorer.aggregate(
                    query, propertyIds, measurePropertyId, divisorPropertyId, aggregation, explorerActor(actor));
            List<Map<String, Object>> buckets = new ArrayList<>();
            for (AggregationBucket bucket : grouped) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("label", bucketLabel(bucket.value()));
                    item.put("dimensions", bucket.value());
                    if (bucket.count() < properties.suppressionThreshold()) {
                        item.put("value", null); item.put("suppressed", true); suppressed = true;
                    } else {
                        item.put("value", bucket.metric()); item.put("suppressed", false);
                    }
                    buckets.add(item);
            }
            data = Map.of("buckets", buckets, "dimensionPropertyIds", propertyIds, "aggregation", aggregation,
                    "total", page.visibleCount());
        } else if ("SCATTER".equals(widget.type())) {
            String x = String.valueOf(widget.config().get("xProperty"));
            String y = String.valueOf(widget.config().get("yProperty"));
            data = page.items().stream().map(item -> Map.of("objectId", item.objectId(), "title", item.title(),
                    "x", item.properties().get(x), "y", item.properties().get(y))).toList();
        } else {
            data = Map.of("items", page.items(), "visibleCount", page.visibleCount(), "nextCursor", page.nextCursor(),
                    "properties", page.properties(), "queryFingerprint", page.queryFingerprint());
        }
        return new DashboardWidgetResult(widget.id(), "SUCCEEDED", widget.type(), data, false, suppressed,
                queriedAt, page.indexUpdatedAt(), correlationId.toString(), null);
    }

    private DashboardWidgetResult executeDatasetWidget(DashboardDataSource source, DashboardWidget widget,
                                                       UUID correlationId, Instant queriedAt) {
        if ("OBJECT_TABLE".equals(widget.type())) {
            DatasetModels.Preview preview = datasets.preview(source.datasetId(), 50, 0);
            Object data = Map.of("items", preview.rows(), "visibleCount", preview.total(), "columns", preview.columns());
            return new DashboardWidgetResult(widget.id(), "SUCCEEDED", widget.type(), data, false, false,
                    queriedAt, queriedAt, correlationId.toString(), null);
        }
        List<DatasetModels.Dimension> dimensions = datasetDimensions(widget);
        if (!"METRIC".equals(widget.type()) && dimensions.isEmpty()) throw invalid("图表必须选择横轴或分组字段");
        List<DatasetModels.Metric> metrics = datasetMetrics(widget);
        List<DatasetModels.Filter> filters = datasetFilters(widget);
        String firstMetric = metrics.get(0).label();
        DatasetModels.QueryResult query = datasets.query(source.datasetId(), new DatasetModels.QueryRequest(
                List.of(), dimensions, metrics, filters,
                "METRIC".equals(widget.type()) ? firstMetric : dimensions.get(0).label(), "ASC", 2000));
        Object data;
        if ("METRIC".equals(widget.type())) {
            Map<String, Object> values = query.rows().isEmpty() ? Map.of() : query.rows().get(0);
            data = Map.of("value", values.getOrDefault(firstMetric, 0), "values", values,
                    "metricFields", query.metrics());
        } else {
            List<Map<String, Object>> buckets = query.rows().stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                Map<String, Object> values = new LinkedHashMap<>();
                dimensions.forEach(dimension -> values.put(dimension.label(), row.get(dimension.label())));
                item.put("label", values.values().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(" · ")));
                item.put("dimensions", values); item.put("value", row.get(firstMetric));
                item.put("metrics", query.metrics().stream().collect(java.util.stream.Collectors.toMap(
                        field -> field, row::get, (left, right) -> left, LinkedHashMap::new)));
                item.put("suppressed", false);
                return item;
            }).toList();
            data = Map.of("buckets", buckets, "dimensionFields", query.dimensions(),
                    "metricFields", query.metrics(), "rows", query.rows(), "scannedRows", query.scannedRows());
        }
        return new DashboardWidgetResult(widget.id(), "SUCCEEDED", widget.type(), data, false, false,
                queriedAt, queriedAt, correlationId.toString(), null);
    }

    private String requiredField(Object value, String message) {
        String field = value == null ? "" : String.valueOf(value);
        if (field.isBlank()) throw invalid(message);
        return field;
    }

    private List<DatasetModels.Dimension> datasetDimensions(DashboardWidget widget) {
        Map<String, Object> config = widget.config() == null ? Map.of() : widget.config();
        List<DatasetModels.Dimension> values = new ArrayList<>();
        addDatasetDimension(values, config.get("xField"), "x", config.get("xTimeGrain"));
        addDatasetDimension(values, config.get("seriesField"), "series", config.get("seriesTimeGrain"));
        addDatasetDimension(values, config.get("groupField"), "group", config.get("groupTimeGrain"));
        if (!values.isEmpty()) return List.copyOf(values);
        List<String> legacy = stringList(config.get("dimensionPropertyIds"));
        if (legacy.isEmpty() && config.get("dimensionPropertyId") != null) legacy = List.of(String.valueOf(config.get("dimensionPropertyId")));
        for (int index = 0; index < legacy.size(); index++) {
            values.add(new DatasetModels.Dimension(legacy.get(index), index == 0 ? "x" : index == 1 ? "series" : "group", null));
        }
        return List.copyOf(values);
    }

    private void addDatasetDimension(List<DatasetModels.Dimension> values, Object field, String label, Object grain) {
        String value = field == null ? "" : String.valueOf(field).trim();
        if (value.isBlank() || values.stream().anyMatch(item -> item.field().equals(value))) return;
        values.add(new DatasetModels.Dimension(value, label, grain == null ? null : String.valueOf(grain)));
    }

    private List<DatasetModels.Metric> datasetMetrics(DashboardWidget widget) {
        Map<String, Object> config = widget.config() == null ? Map.of() : widget.config();
        List<Map<String, Object>> configured = mapList(config.get("measures"));
        if (configured.isEmpty()) configured = List.of(config);
        List<DatasetModels.Metric> values = new ArrayList<>();
        for (int index = 0; index < configured.size() && index < 4; index++) {
            Map<String, Object> measure = configured.get(index);
            String aggregation = String.valueOf(measure.getOrDefault("aggregation", "count"));
            String operation = switch (aggregation) {
                case "sum" -> "SUM"; case "avg" -> "AVG"; case "min" -> "MIN"; case "max" -> "MAX";
                case "approx_distinct" -> "DISTINCT_COUNT"; case "sum_per_distinct" -> "SUM_PER_DISTINCT";
                default -> "COUNT";
            };
            Object rawField = measure.containsKey("field") ? measure.get("field") : measure.get("measurePropertyId");
            Object rawDivisor = measure.containsKey("divisorField") ? measure.get("divisorField") : measure.get("divisorPropertyId");
            String field = "count".equals(aggregation) ? null : requiredField(rawField, "请选择指标字段");
            String divisor = "sum_per_distinct".equals(aggregation) ? requiredField(rawDivisor, "请选择分母去重字段") : null;
            String id = String.valueOf(measure.getOrDefault("id", index == 0 ? "value" : "value_" + (index + 1)));
            values.add(new DatasetModels.Metric(operation, field, divisor, id));
        }
        if (values.isEmpty()) values.add(new DatasetModels.Metric("COUNT", null, null, "value"));
        return List.copyOf(values);
    }

    private List<DatasetModels.Filter> datasetFilters(DashboardWidget widget) {
        List<DatasetModels.Filter> values = new ArrayList<>();
        for (Map<String, Object> filter : mapList(widget.config() == null ? null : widget.config().get("filters"))) {
            String field = String.valueOf(filter.getOrDefault("field", "")).trim();
            if (field.isBlank()) continue;
            String operator = String.valueOf(filter.getOrDefault("operator", "EQUALS"));
            String comparisonField = String.valueOf(filter.getOrDefault("comparisonField", "")).trim();
            Object rawValues = filter.get("values");
            List<String> filterValues = rawValues instanceof List<?> list ? list.stream().map(String::valueOf).toList()
                    : rawValues == null ? List.of() : List.of(String.valueOf(rawValues));
            values.add(new DatasetModels.Filter(field, operator, filterValues,
                    comparisonField.isBlank() ? null : comparisonField));
        }
        return List.copyOf(values);
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, entry) -> converted.put(String.valueOf(key), entry));
            result.add(converted);
        }
        return List.copyOf(result);
    }

    private Map<String, Object> firstMeasure(DashboardWidget widget) {
        List<Map<String, Object>> measures = mapList(widget.config() == null ? null : widget.config().get("measures"));
        return measures.isEmpty() ? (widget.config() == null ? Map.of() : widget.config()) : measures.get(0);
    }

    private Object objectDimensionFields(DashboardWidget widget) {
        Map<String, Object> config = widget.config() == null ? Map.of() : widget.config();
        List<String> values = new ArrayList<>();
        for (String key : List.of("xField", "seriesField", "groupField")) {
            Object field = config.get(key);
            if (field != null && !String.valueOf(field).isBlank() && !values.contains(String.valueOf(field))) values.add(String.valueOf(field));
        }
        return values.isEmpty() ? config.get("dimensionPropertyIds") : values;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).limit(3).toList();
    }

    private Object metric(String aggregation, Object property, ObjectSetPage page) {
        if ("count".equals(aggregation)) return Map.of("value", page.visibleCount(), "aggregation", "count");
        if (property == null) throw invalid("度量必须指定属性");
        String key = String.valueOf(property);
        List<Double> values = page.items().stream().map(item -> item.properties().get(key))
                .filter(Number.class::isInstance).map(Number.class::cast).map(Number::doubleValue).toList();
        if (values.isEmpty()) return Map.of("value", 0, "aggregation", aggregation, "empty", true);
        double value = switch (aggregation) {
            case "sum" -> values.stream().mapToDouble(Double::doubleValue).sum();
            case "avg" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case "min" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case "max" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "approx_distinct" -> values.stream().distinct().count();
            default -> throw invalid("聚合函数不受支持");
        };
        return Map.of("value", value, "aggregation", aggregation, "sampleSize", values.size());
    }

    private String bucketLabel(Object value) {
        if (value instanceof Map<?, ?> values) {
            return values.values().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(" · "));
        }
        return String.valueOf(value);
    }

    private List<UUID> uuids(Object values, Object legacy, String message) {
        List<UUID> result = new ArrayList<>();
        if (values instanceof List<?> list) {
            for (Object value : list) result.add(uuid(value, message));
        } else if (legacy != null) {
            result.add(uuid(legacy, message));
        }
        if (result.isEmpty() || result.size() > 3) throw invalid(message);
        return List.copyOf(result);
    }

    private ObjectSetRequest dataSourceQuery(DashboardDataSource source, Map<String, Object> filters,
                                             DashboardDefinition definition, Actor actor) {
        ObjectSetRequest base;
        if ("OBJECT_SET".equals(source.kind())) {
            base = objectMapper.convertValue(source.query(), ObjectSetRequest.class);
        } else if ("EXPLORATION".equals(source.kind())) {
            String query = jdbc.sql("""
                    SELECT v.query_ast FROM control.saved_explorations e
                    JOIN control.saved_exploration_versions v ON v.exploration_id=e.id AND v.version=:version
                    WHERE e.id=:id AND (e.owner_id=:actor OR e.visibility='SHARED')
                    """).param("version", source.referenceVersion()).param("id", source.referenceId()).param("actor", actor.id())
                    .query(String.class).optional().orElseThrow(() -> forbidden("无权访问固定 Exploration 版本"));
            base = read(query, ObjectSetRequest.class);
        } else if ("OBJECT_LIST".equals(source.kind())) {
            List<String> ids = jdbc.sql("""
                    SELECT i.object_id FROM control.object_lists l JOIN control.object_list_items i ON i.list_id=l.id
                    WHERE l.id=:id AND (l.owner_id=:actor OR l.visibility='SHARED') ORDER BY i.object_id LIMIT 1000
                    """).param("id", source.referenceId()).param("actor", actor.id()).query(String.class).list();
            if (ids.isEmpty()) throw notFound("固定对象清单为空或不可访问");
            UUID primary = jdbc.sql("""
                    SELECT p.id FROM control.properties p JOIN control.property_versions pv ON pv.property_id=p.id
                    JOIN control.ontology_resources r ON r.id=p.object_type_id
                    JOIN control.ontology_resource_versions rv ON rv.resource_id=r.id AND rv.version=r.active_version
                    WHERE p.object_type_id=:type AND pv.object_type_version_id=rv.id AND pv.primary_key=true
                    """).param("type", source.objectTypeId()).query(UUID.class).single();
            base = new ObjectSetRequest(source.objectTypeId(), Map.of("type", "property", "propertyId", primary,
                    "operator", "in", "value", ids), List.of(), 100, null, List.of());
        } else if ("FUNCTION".equals(source.kind())) {
            String dsl = jdbc.sql("""
                    SELECT fv.query_dsl FROM control.function_type_versions fv
                    JOIN control.ontology_resource_versions rv ON rv.id=fv.version_id
                    WHERE fv.resource_id=:id AND rv.version=:version AND rv.lifecycle='PUBLISHED'
                    """).param("id", source.referenceId()).param("version", source.referenceVersion())
                    .query(String.class).optional().orElseThrow(() -> notFound("固定 Function 版本不可用"));
            Map<String, Object> value = read(dsl, new TypeReference<>() { });
            Object objectSet = value.get("objectSet");
            if (!(objectSet instanceof Map<?, ?>)) throw invalid("Function 不是可执行的只读 Object Set DSL");
            base = objectMapper.convertValue(objectSet, ObjectSetRequest.class);
        } else throw invalid("数据源类型不受支持");

        List<Map<String, Object>> conditions = new ArrayList<>();
        if (base.where() != null && !base.where().isEmpty()) conditions.add(base.where());
        for (DashboardFilterBinding binding : definition.filterBindings()) {
            if (!binding.dataSourceId().equals(source.id())) continue;
            Object value = filters.get(binding.filterId().toString());
            if (value == null) continue;
            conditions.add(Map.of("type", "property", "propertyId", binding.propertyId(),
                    "operator", binding.operator(), "value", value));
        }
        Map<String, Object> where = conditions.isEmpty() ? Map.of() : conditions.size() == 1 ? conditions.get(0)
                : Map.of("type", "and", "children", conditions);
        return new ObjectSetRequest(base.objectTypeId(), where, base.sort(),
                base.pageSize() == null ? 50 : base.pageSize(), null, base.columns());
    }

    private void validateSample(DashboardDefinition definition, Actor actor) {
        for (DashboardDataSource source : definition.dataSources()) {
            if ("DATASET".equals(source.kind())) { datasets.preview(source.datasetId(), 1, 0); continue; }
            ObjectSetRequest query = dataSourceQuery(source, Map.of(), definition, actor);
            explorer.query(new ObjectSetRequest(query.objectTypeId(), query.where(), query.sort(), 25, null, query.columns()), explorerActor(actor));
        }
    }

    private PlanRuntime runtime(UUID planId, Actor actor) {
        DashboardQueryPlanView plan = jdbc.sql("""
                SELECT id,dashboard_id,version_id,plan_hash,ontology_revision,policy_revision,estimated_cost,created_at
                FROM control.dashboard_query_plans WHERE id=:id
                """).param("id", planId).query(this::plan).optional().orElseThrow(() -> notFound("查询计划不存在"));
        requireAccess(plan.dashboardId(), actor, "VIEWER");
        DashboardSummary dashboard = summary(plan.dashboardId(), actor);
        if ("ARCHIVED".equals(dashboard.lifecycle())) throw new ResponseStatusException(HttpStatus.GONE, "看板已归档");
        DashboardDefinition definition = jdbc.sql("SELECT definition FROM control.dashboard_versions WHERE id=:id")
                .param("id", plan.versionId()).query(String.class).optional().map(this::readDefinition)
                .orElseThrow(() -> notFound("发布版本不存在"));
        return new PlanRuntime(plan, definition);
    }

    private DashboardDataSource dataSource(DashboardDefinition definition, UUID id) {
        return definition.dataSources().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> invalid("组件数据源不存在"));
    }

    private DashboardSummary summary(UUID id, Actor actor) {
        return list("", "", false, actor).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> notFound("看板不存在或无权访问"));
    }

    private DashboardSummary summary(ResultSet rs, int row) throws SQLException {
        Integer version = (Integer) rs.getObject("current_version");
        Timestamp published = rs.getTimestamp("last_published_at");
        return new DashboardSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getString("lifecycle"), version, rs.getInt("page_count"), rs.getInt("widget_count"),
                rs.getString("owner_name"), rs.getString("visibility"), rs.getString("refresh_policy"),
                rs.getString("health_status"), rs.getBoolean("favorite"), rs.getLong("etag"),
                rs.getTimestamp("updated_at").toInstant(), published == null ? null : published.toInstant());
    }

    private DashboardVersionView currentVersion(UUID id) {
        return jdbc.sql("""
                SELECT v.id,v.dashboard_id,v.version,v.definition,v.schema_version,v.ontology_revision,v.query_plan_hash,
                       v.published_by_name,v.release_notes,v.health_status,v.published_at
                FROM control.dashboard_versions v JOIN control.dashboards d ON d.current_version_id=v.id WHERE d.id=:id
                """).param("id", id).query(this::version).optional().orElse(null);
    }

    private DashboardVersionView version(ResultSet rs, int row) throws SQLException {
        return new DashboardVersionView(rs.getObject("id", UUID.class), rs.getObject("dashboard_id", UUID.class),
                rs.getInt("version"), readDefinition(rs.getString("definition")), rs.getInt("schema_version"),
                rs.getLong("ontology_revision"), rs.getString("query_plan_hash"), rs.getString("published_by_name"),
                rs.getString("release_notes"), rs.getString("health_status"), rs.getTimestamp("published_at").toInstant());
    }

    private DashboardDraftView activeDraft(UUID id) {
        return jdbc.sql("""
                SELECT dr.id,dr.dashboard_id,dr.base_version_id,dr.definition,dr.etag,dr.status,dr.updated_by,dr.updated_at
                FROM control.dashboard_drafts dr JOIN control.dashboards d ON d.active_draft_id=dr.id WHERE d.id=:id
                """).param("id", id).query(this::draft).optional().orElse(null);
    }

    private DashboardDraftView draft(ResultSet rs, int row) throws SQLException {
        return new DashboardDraftView(rs.getObject("id", UUID.class), rs.getObject("dashboard_id", UUID.class),
                rs.getObject("base_version_id", UUID.class), readDefinition(rs.getString("definition")), rs.getLong("etag"),
                rs.getString("status"), rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant());
    }

    private DashboardQueryPlanView plan(ResultSet rs, int row) throws SQLException {
        return new DashboardQueryPlanView(rs.getObject("id", UUID.class), rs.getObject("dashboard_id", UUID.class),
                rs.getObject("version_id", UUID.class), rs.getString("plan_hash"), rs.getLong("ontology_revision"),
                rs.getLong("policy_revision"), rs.getInt("estimated_cost"), rs.getTimestamp("created_at").toInstant());
    }

    private DashboardEditLock editLock(UUID id) {
        return jdbc.sql("""
                SELECT dashboard_id,holder_id,holder_name,lease_token,acquired_at,expires_at
                FROM control.dashboard_edit_locks WHERE dashboard_id=:id
                """).param("id", id).query((rs, row) -> new DashboardEditLock(rs.getObject("dashboard_id", UUID.class),
                        rs.getString("holder_id"), rs.getString("holder_name"), rs.getObject("lease_token", UUID.class),
                        rs.getTimestamp("acquired_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), true)).optional().orElse(null);
    }

    private void requireEditLock(UUID id, Actor actor) {
        DashboardEditLock lock = editLock(id);
        if (lock == null || lock.expiresAt().isBefore(Instant.now()) || !lock.holderId().equals(actor.id())) {
            throw conflict("需要有效的单编辑者租约才能保存或发布");
        }
    }

    private DashboardDraftView createDraft(UUID id, UUID baseVersion, DashboardDefinition definition, Actor actor) {
        UUID draftId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO control.dashboard_drafts(id,dashboard_id,base_version_id,definition,schema_version,status,updated_by)
                VALUES (:id,:dashboard,CAST(:base AS uuid),CAST(:definition AS jsonb),:schema,'DRAFT',:actor)
                """).param("id", draftId).param("dashboard", id).param("base", baseVersion)
                .param("definition", json(definition)).param("schema", definition.schemaVersion()).param("actor", actor.id()).update();
        jdbc.sql("UPDATE control.dashboards SET active_draft_id=:draft,updated_at=now() WHERE id=:id")
                .param("draft", draftId).param("id", id).update();
        persistDefinition(id, draftId, false, definition);
        return activeDraft(id);
    }

    private void persistDefinition(UUID dashboardId, UUID scopeId, boolean version, DashboardDefinition definition) {
        String scope = version ? "version_id" : "draft_id";
        for (String table : List.of("dashboard_filter_bindings", "dashboard_filter_variables", "dashboard_widgets",
                "dashboard_data_sources", "dashboard_pages")) {
            jdbc.sql("DELETE FROM control." + table + " WHERE " + scope + "=:scope").param("scope", scopeId).update();
        }
        for (DashboardPage page : definition.pages()) {
            jdbc.sql("INSERT INTO control.dashboard_pages(id,dashboard_id," + scope + ",stable_id,name,description,page_order) "
                    + "VALUES (:id,:dashboard,:scope,:stable,:name,:description,:order)")
                    .param("id", UUID.randomUUID()).param("dashboard", dashboardId).param("scope", scopeId)
                    .param("stable", page.id()).param("name", page.name()).param("description", text(page.description(), 4000))
                    .param("order", page.order()).update();
        }
        long revision = currentRevision();
        for (DashboardDataSource source : definition.dataSources()) {
            jdbc.sql("INSERT INTO control.dashboard_data_sources(id,dashboard_id," + scope + ",stable_id,name,source_kind,object_type_id,dataset_id,reference_id,reference_version,query_ast,ontology_revision) "
                    + "VALUES (:id,:dashboard,:scope,:stable,:name,:kind,CAST(:objectType AS uuid),CAST(:dataset AS uuid),CAST(:reference AS uuid),:referenceVersion,CAST(:query AS jsonb),:revision)")
                    .param("id", UUID.randomUUID()).param("dashboard", dashboardId).param("scope", scopeId).param("stable", source.id())
                    .param("name", source.name()).param("kind", source.kind()).param("objectType", source.objectTypeId())
                    .param("dataset", source.datasetId())
                    .param("reference", source.referenceId()).param("referenceVersion", source.referenceVersion())
                    .param("query", json(source.query() == null ? Map.of() : source.query()))
                    .param("revision", source.ontologyRevision() == null ? revision : source.ontologyRevision()).update();
        }
        for (DashboardWidget widget : definition.widgets()) {
            jdbc.sql("INSERT INTO control.dashboard_widgets(id,dashboard_id," + scope + ",stable_id,page_stable_id,data_source_stable_id,widget_type,title,description,layout,config,interaction) "
                    + "VALUES (:id,:dashboard,:scope,:stable,:page,CAST(:source AS uuid),:type,:title,:description,CAST(:layout AS jsonb),CAST(:config AS jsonb),CAST(:interaction AS jsonb))")
                    .param("id", UUID.randomUUID()).param("dashboard", dashboardId).param("scope", scopeId).param("stable", widget.id())
                    .param("page", widget.pageId()).param("source", widget.dataSourceId()).param("type", widget.type())
                    .param("title", widget.title()).param("description", text(widget.description(), 4000))
                    .param("layout", json(widget.layout())).param("config", json(widget.config())).param("interaction", json(widget.interaction())).update();
        }
        for (DashboardFilterVariable filter : definition.filters()) {
            jdbc.sql("INSERT INTO control.dashboard_filter_variables(id,dashboard_id," + scope + ",stable_id,name,value_type,control_type,scope,scope_id,default_value,required,allow_empty,sensitive,apply_mode) "
                    + "VALUES (:id,:dashboard,:scopeId,:stable,:name,:valueType,:controlType,:filterScope,CAST(:target AS uuid),CAST(:defaultValue AS jsonb),:required,:allowEmpty,:sensitive,:applyMode)")
                    .param("id", UUID.randomUUID()).param("dashboard", dashboardId).param("scopeId", scopeId).param("stable", filter.id())
                    .param("name", filter.name()).param("valueType", filter.valueType()).param("controlType", filter.controlType())
                    .param("filterScope", filter.scope()).param("target", filter.scopeId()).param("defaultValue", filter.defaultValue() == null ? null : json(filter.defaultValue()))
                    .param("required", filter.required()).param("allowEmpty", filter.allowEmpty()).param("sensitive", filter.sensitive())
                    .param("applyMode", filter.applyMode()).update();
        }
        for (DashboardFilterBinding binding : definition.filterBindings()) {
            jdbc.sql("INSERT INTO control.dashboard_filter_bindings(id,dashboard_id," + scope + ",filter_stable_id,data_source_stable_id,property_id,operator) "
                    + "VALUES (:id,:dashboard,:scope,:filter,:source,:property,:operator)")
                    .param("id", UUID.randomUUID()).param("dashboard", dashboardId).param("scope", scopeId)
                    .param("filter", binding.filterId()).param("source", binding.dataSourceId())
                    .param("property", binding.propertyId()).param("operator", binding.operator()).update();
        }
    }

    private void persistDependencies(UUID dashboardId, UUID versionId, DashboardDefinition definition, long revision) {
        for (DashboardDataSource source : definition.dataSources()) {
            if (source.datasetId() != null) insertDependency(dashboardId, versionId, "DATASET", source.datasetId(), null, revision);
            if (source.objectTypeId() != null) insertDependency(dashboardId, versionId, "OBJECT_TYPE", source.objectTypeId(), null, revision);
            if (source.referenceId() != null) insertDependency(dashboardId, versionId, source.kind(), source.referenceId(), source.referenceVersion(), revision);
        }
    }

    private void insertDependency(UUID dashboardId, UUID versionId, String kind, UUID resource, Integer resourceVersion, long revision) {
        jdbc.sql("""
                INSERT INTO control.dashboard_dependencies(id,dashboard_id,version_id,dependency_kind,resource_id,resource_version,ontology_revision,health_status)
                VALUES (:id,:dashboard,:version,:kind,:resource,:resourceVersion,:revision,'HEALTHY') ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("dashboard", dashboardId).param("version", versionId)
                .param("kind", kind).param("resource", resource).param("resourceVersion", resourceVersion).param("revision", revision).update();
    }

    private String requireAccess(UUID id, Actor actor, String required) {
        AccessRow row = jdbc.sql("SELECT owner_id,visibility FROM control.dashboards WHERE id=:id")
                .param("id", id).query((rs, index) -> new AccessRow(rs.getString("owner_id"), rs.getString("visibility")))
                .optional().orElseThrow(() -> notFound("看板不存在"));
        String role;
        if (row.ownerId().equals(actor.id())) role = "OWNER";
        else role = jdbc.sql("""
                SELECT permission_role FROM control.dashboard_permissions
                WHERE dashboard_id=:id AND subject_type='USER' AND subject_id=:actor
                """).param("id", id).param("actor", actor.id()).query(String.class).optional()
                .orElse(Set.of("TEAM", "ORGANIZATION").contains(row.visibility()) ? "VIEWER" : null);
        if (role == null || level(role) < level(required)) throw forbidden("没有所需的看板 " + required + " 权限");
        return role;
    }

    private int level(String role) { return "OWNER".equals(role) ? 3 : "EDITOR".equals(role) ? 2 : 1; }
    private boolean canEdit(String role) { return level(role) >= 2; }

    private DashboardDefinition blankDefinition() {
        UUID page = UUID.randomUUID();
        return new DashboardDefinition(1, List.of(new DashboardPage(page, "概览", "", 0)), List.of(), List.of(),
                List.of(), List.of(), Map.of("timezone", "Asia/Shanghai", "weekStart", "MONDAY",
                        "fiscalYearStartMonth", 1, "suppressionThreshold", properties.suppressionThreshold()));
    }

    private DashboardDefinition readDefinition(String value) { return read(value, DashboardDefinition.class); }

    private <T> T read(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Stored dashboard definition is invalid", exception); }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Stored dashboard definition is invalid", exception); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalArgumentException("看板定义无法序列化", exception); }
    }

    private String pgArray(List<String> values) {
        if (values == null || values.isEmpty()) return "{}";
        return "{" + values.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private long currentRevision() {
        return jdbc.sql("SELECT revision FROM control.ontology_revisions WHERE status='ACTIVE'")
                .query(Long.class).optional().orElseThrow(() -> conflict("没有活动本体 revision"));
    }

    private ExplorerModels.Actor explorerActor(Actor actor) {
        return new ExplorerModels.Actor(actor.id(), actor.name(), actor.roles());
    }

    private Map<String, Object> countChange(int from, int to) { return Map.of("from", from, "to", to, "delta", to - from); }

    private UUID uuid(Object value, String message) {
        try { return UUID.fromString(String.valueOf(value)); }
        catch (Exception exception) { throw invalid(message); }
    }

    private String choice(String value, String fallback, Set<String> allowed, String message) {
        String selected = value == null || value.isBlank() ? fallback : value.toUpperCase();
        if (selected == null || !allowed.contains(selected)) throw invalid(message);
        return selected;
    }

    private String required(String value, String message, int max) {
        if (value == null || value.isBlank()) throw invalid(message);
        return text(value, max);
    }

    private String text(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) throw invalid("文本长度超过限制");
        return result;
    }

    private String safeError(RuntimeException exception) {
        return exception instanceof ResponseStatusException status && status.getReason() != null
                ? status.getReason() : "组件查询失败，请使用 correlation ID 联系管理员";
    }

    private void audit(Actor actor, String action, UUID resource, String summary, Map<String, Object> safeDiff) {
        jdbc.sql("""
                INSERT INTO control.audit_events(id,actor_id,actor_name,action,resource_type,resource_id,request_id,summary,safe_diff)
                VALUES (:id,:actor,:name,:action,'DASHBOARD',:resource,:request,:summary,CAST(:diff AS jsonb))
                """).param("id", UUID.randomUUID()).param("actor", actor.id()).param("name", actor.name())
                .param("action", action).param("resource", resource.toString()).param("request", UUID.randomUUID())
                .param("summary", summary).param("diff", json(safeDiff)).update();
    }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    private record AccessRow(String ownerId, String visibility) { }
    private record PlanRuntime(DashboardQueryPlanView plan, DashboardDefinition definition) { }
    private record CacheEntry(DashboardWidgetResult value, Instant expiresAt) { }
}
