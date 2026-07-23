package com.hezhangjian.ontology.core.modeling;

import static com.hezhangjian.ontology.core.modeling.ModelingModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.contracts.projection.MutationEdit;
import com.hezhangjian.ontology.contracts.projection.OntologyMutationBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.connections.ConnectionProblem;
import com.hezhangjian.ontology.core.deletion.ResourceDeletionService;
import com.hezhangjian.ontology.core.explorer.ExplorerStorageClient;
import com.hezhangjian.ontology.core.security.WorkspaceContext;
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
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ModelingService {
    private static final Pattern API_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,159}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TaskExecutor tasks;
    private final ModelingInfrastructureProbe infrastructure;
    private final ModelingPolicy policy;
    private final TransactionTemplate transactions;
    private final ResourceDeletionService deletion;
    private final ExplorerStorageClient explorerStorage;
    private final ActionMutationPublisher actionPublisher;
    private final FunctionRuntimeService functionRuntime;

    public ModelingService(JdbcTemplate jdbc, ObjectMapper json,
                           @Qualifier("applicationTaskExecutor") TaskExecutor tasks,
                           ModelingInfrastructureProbe infrastructure,
                           ModelingPolicy policy,
                           PlatformTransactionManager transactionManager,
                           ResourceDeletionService deletion,
                           ExplorerStorageClient explorerStorage,
                           ActionMutationPublisher actionPublisher,
                           FunctionRuntimeService functionRuntime) {
        this.jdbc = jdbc;
        this.json = json;
        this.tasks = tasks;
        this.infrastructure = infrastructure;
        this.policy = policy;
        this.transactions = new TransactionTemplate(transactionManager);
        this.deletion = deletion;
        this.explorerStorage = explorerStorage;
        this.actionPublisher = actionPublisher;
        this.functionRuntime = functionRuntime;
    }

    public ModelingSummary summary() { return summary(OntologyCatalogService.DEFAULT_ONTOLOGY_ID); }

    public ModelingSummary summary(UUID ontologyId) {
        long revision = activeRevision(ontologyId);
        Instant activated = jdbc.query("SELECT activated_at FROM control.ontology_revisions WHERE ontology_id=? AND revision=?",
                (row, n) -> instant(row, "activated_at"), ontologyId, revision).stream().findFirst().orElse(null);
        long openProposals = count(ontologyId, "SELECT count(*) FROM control.ontology_proposals WHERE ontology_id=? AND status IN ('DRAFT','VALIDATING','IN_REVIEW','APPROVED','FAILED')");
        long critical = count(ontologyId, "SELECT count(*) FROM control.ontology_health_issues WHERE ontology_id=? AND status='OPEN' AND severity IN ('ERROR','CRITICAL')");
        long projectionFailures = count(ontologyId, "SELECT count(*) FROM control.projection_ledger WHERE ontology_id=? AND status IN ('DEGRADED','DLQ')");
        long reviews = count(ontologyId, "SELECT count(*) FROM control.ontology_proposals WHERE ontology_id=? AND status='IN_REVIEW'");
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ResourceKind kind : ResourceKind.values()) {
            counts.put(kind.name(), Objects.requireNonNullElse(jdbc.queryForObject(
                    "SELECT count(*) FROM control.ontology_resources WHERE ontology_id=? AND kind=? AND NOT tombstoned",
                    Long.class, ontologyId, kind.name()), 0L));
        }
        String health = critical > 0 ? "DEGRADED" : "HEALTHY";
        return new ModelingSummary(revision, activated, health, openProposals, critical, projectionFailures,
                reviews, counts, list(ontologyId, null, null).stream().limit(12).toList());
    }

    public List<SearchResult> search(String query) { return search(OntologyCatalogService.DEFAULT_ONTOLOGY_ID, query); }

    public List<SearchResult> search(UUID ontologyId, String query) {
        String needle = "%" + safe(query).toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                SELECT r.id,r.kind,r.api_name,v.display_name,v.description,v.owner_name,v.tags,v.lifecycle
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions v ON v.resource_id=r.id AND v.version=r.latest_version
                WHERE r.ontology_id=? AND NOT r.tombstoned AND (?='%%' OR lower(r.api_name) LIKE ? OR lower(v.display_name) LIKE ?
                  OR lower(v.description) LIKE ? OR lower(v.owner_name) LIKE ? OR EXISTS (
                    SELECT 1 FROM control.properties p JOIN control.property_versions pv ON pv.property_id=p.id
                    WHERE p.object_type_id=r.id AND lower(p.api_name) LIKE ?))
                ORDER BY v.display_name LIMIT 50
                """, (row, n) -> new SearchResult(row.getObject("id", UUID.class), ResourceKind.valueOf(row.getString("kind")),
                row.getString("api_name"), row.getString("display_name"), row.getString("description"),
                row.getString("owner_name"), strings(row.getArray("tags")), row.getString("lifecycle")),
                ontologyId, needle, needle, needle, needle, needle, needle);
    }

    public List<ResourceView> list(ResourceKind kind, String search) {
        return list(OntologyCatalogService.DEFAULT_ONTOLOGY_ID, kind, search);
    }

    public List<ResourceView> list(UUID ontologyId, ResourceKind kind, String search) {
        String sql = """
                SELECT r.*,v.id version_id,v.lifecycle,v.display_name version_display_name,
                       v.description version_description,v.maturity version_maturity,v.promoted version_promoted,
                       v.owner_id version_owner_id,v.owner_name version_owner_name,v.tags version_tags,v.definition
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions v ON v.resource_id=r.id AND v.version=r.latest_version
                WHERE r.ontology_id=? AND NOT r.tombstoned AND (?::varchar IS NULL OR r.kind=?)
                  AND (?::varchar IS NULL OR lower(r.api_name) LIKE ? OR lower(v.display_name) LIKE ?)
                ORDER BY r.updated_at DESC,r.api_name
                """;
        String needle = search == null || search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
        return jdbc.query(sql, this::resource, ontologyId, kind == null ? null : kind.name(), kind == null ? null : kind.name(), needle, needle, needle);
    }

    public ResourceView get(UUID id) {
        String sql = """
                SELECT r.*,v.id version_id,v.lifecycle,v.display_name version_display_name,
                       v.description version_description,v.maturity version_maturity,v.promoted version_promoted,
                       v.owner_id version_owner_id,v.owner_name version_owner_name,v.tags version_tags,v.definition
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions v ON v.resource_id=r.id AND v.version=r.latest_version
                WHERE r.id=? AND NOT r.tombstoned
                """;
        return jdbc.query(sql, this::resource, id).stream().findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "本体资源不存在"));
    }

    public ResourceView get(UUID ontologyId, UUID id) {
        ResourceView resource = get(id);
        long matches = Objects.requireNonNullElse(jdbc.queryForObject(
                "SELECT count(*) FROM control.ontology_resources WHERE id=? AND ontology_id=?", Long.class, id, ontologyId), 0L);
        if (matches == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "本体资源不存在");
        return resource;
    }

    @Transactional
    public void delete(UUID id, Actor actor) {
        ResourceView resource = get(id);
        audit(actor, "ONTOLOGY_RESOURCE_DELETED", resource.kind().name(), id.toString(),
                "永久删除本体资源“" + resource.displayName() + "”及关联记录", Map.of());
        deletion.deleteOntologyResource(id);
    }

    public List<PropertyView> properties(UUID objectTypeId) {
        String condition = objectTypeId == null ? "" : " AND p.object_type_id=?";
        Object[] args = objectTypeId == null ? new Object[0] : new Object[]{objectTypeId};
        return jdbc.query("""
                SELECT p.id,p.api_name,p.physical_key,pv.display_name,pv.description,pv.value_type,pv.required,
                       pv.primary_key,pv.title_property,pv.searchable,pv.filterable,pv.sortable,pv.sensitive,pv.source_field
                FROM control.properties p
                JOIN control.ontology_resources r ON r.id=p.object_type_id
                JOIN control.object_type_versions otv ON otv.resource_id=r.id
                JOIN control.ontology_resource_versions rv ON rv.id=otv.version_id AND rv.version=r.latest_version
                JOIN control.property_versions pv ON pv.property_id=p.id AND pv.object_type_version_id=otv.version_id
                WHERE NOT p.tombstoned
                """ + condition + " ORDER BY r.display_name,p.api_name", this::property, args);
    }

    public List<PropertyView> propertiesForOntology(UUID ontologyId, UUID objectTypeId) {
        if (objectTypeId != null) get(ontologyId, objectTypeId);
        return properties(objectTypeId).stream().filter(property -> objectTypeId != null ||
                count("SELECT count(*) FROM control.properties p JOIN control.ontology_resources r ON r.id=p.object_type_id " +
                        "WHERE p.id='" + property.id() + "' AND r.ontology_id='" + ontologyId + "'") > 0).toList();
    }

    @Transactional
    public ResourceView create(ResourceKind kind, ResourceDraftRequest request, Actor actor) {
        return create(OntologyCatalogService.DEFAULT_ONTOLOGY_ID, kind, request, actor);
    }

    @Transactional
    public ResourceView create(UUID ontologyId, ResourceKind kind, ResourceDraftRequest request, Actor actor) {
        validateCommon(request);
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String physical = prefix(kind) + id.toString().replace("-", "").substring(0, 12);
        Map<String, Object> definition = definition(request);
        jdbc.update("""
                INSERT INTO control.ontology_resources
                    (id,ontology_id,kind,api_name,display_name,description,physical_key,owner_id,owner_name,maturity,promoted,tags)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,COALESCE(ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),'{}'::text[]))
                """, id, ontologyId, kind.name(), request.apiName().trim(), request.displayName().trim(), safe(request.description()), physical,
                value(request.ownerId(), actor.id()), value(request.ownerName(), actor.name()), maturity(request.maturity()),
                request.promoted(), writeJson(list(request.tags())));
        insertVersion(id, versionId, 1, request, definition, actor);
        insertTyped(kind, id, versionId, request);
        audit(actor, "ONTOLOGY_DRAFT_CREATED", kind.name(), id.toString(), "创建本体资源草稿", Map.of("apiName", request.apiName(), "version", 1));
        return get(id);
    }

    @Transactional
    public ResourceView createObjectDraft(UUID resourceId, ResourceDraftRequest request, long expectedEtag, Actor actor) {
        ResourceView current = get(resourceId);
        if (current.kind() != ResourceKind.OBJECT_TYPE) throw problem("RESOURCE_KIND_INVALID", "只有对象类型支持此草稿入口");
        if (current.etag() != expectedEtag) throw problem("ONTOLOGY_VERSION_CONFLICT", "资源已被其他用户修改，请重新加载后合并");
        if (request.apiName() != null && !request.apiName().equals(current.apiName())) throw problem("PUBLISHED_API_NAME_IMMUTABLE", "首次发布后的 API 名称不可直接修改");
        ResourceDraftRequest normalized = withStableApi(request, current.apiName());
        validateCommon(normalized);
        if (current.activeVersion() != null && primaryChanged(resourceId, normalized) && objectCount(resourceId) > 0) {
            throw problem("PRIMARY_KEY_IMMUTABLE", "已有对象的类型不能原地修改主键，请创建迁移 Proposal");
        }
        int version = current.version() + 1;
        UUID versionId = UUID.randomUUID();
        insertVersion(resourceId, versionId, version, normalized, definition(normalized), actor);
        insertObjectVersion(resourceId, versionId, normalized);
        jdbc.update("UPDATE control.ontology_resources SET latest_version=?,etag=etag+1,updated_at=now() WHERE id=?", version, resourceId);
        audit(actor, "ONTOLOGY_DRAFT_CREATED", "OBJECT_TYPE", resourceId.toString(), "创建对象类型新版本草稿", Map.of("version", version));
        return get(resourceId);
    }

    @Transactional
    public ResourceView createFunctionDraft(UUID resourceId, ResourceDraftRequest request, long expectedEtag, Actor actor) {
        ResourceView current = get(resourceId);
        if (current.kind() != ResourceKind.FUNCTION) throw problem("RESOURCE_KIND_INVALID", "只有 Function 支持此草稿入口");
        if (current.etag() != expectedEtag) throw problem("ONTOLOGY_VERSION_CONFLICT", "资源已被其他用户修改，请重新加载后合并");
        if (request.apiName() != null && !request.apiName().equals(current.apiName())) throw problem("PUBLISHED_API_NAME_IMMUTABLE", "首次发布后的 API 名称不可直接修改");
        ResourceDraftRequest normalized = withStableApi(request, current.apiName());
        validateCommon(normalized);
        policy.validateFunctionDsl(map(normalized.queryDsl()));
        int version = current.version() + 1;
        UUID versionId = UUID.randomUUID();
        insertVersion(resourceId, versionId, version, normalized, definition(normalized), actor);
        insertFunctionVersion(resourceId, versionId, normalized);
        jdbc.update("UPDATE control.ontology_resources SET latest_version=?,etag=etag+1,updated_at=now() WHERE id=?", version, resourceId);
        audit(actor, "ONTOLOGY_DRAFT_CREATED", "FUNCTION", resourceId.toString(), "创建 Function 新版本草稿", Map.of("version", version));
        return get(resourceId);
    }

    @Transactional
    public ProposalView createProposal(ProposalRequest request, Actor actor) {
        return createProposal(OntologyCatalogService.DEFAULT_ONTOLOGY_ID, request, actor);
    }

    @Transactional
    public ProposalView createProposal(UUID ontologyId, ProposalRequest request, Actor actor) {
        if (request.title() == null || request.title().isBlank() || list(request.resourceIds()).isEmpty()) {
            throw problem("PROPOSAL_INVALID", "Proposal 标题和至少一个资源为必填项");
        }
        UUID id = UUID.randomUUID();
        long baseline = activeRevision(ontologyId);
        jdbc.update("""
                INSERT INTO control.ontology_proposals(id,ontology_id,title,description,status,baseline_revision,created_by,created_by_name)
                VALUES (?,?,?,?,'DRAFT',?,?,?)
                """, id, ontologyId, request.title().trim(), safe(request.description()), baseline, actor.id(), actor.name());
        for (UUID resourceId : request.resourceIds().stream().distinct().toList()) {
            ResourceView resource = get(ontologyId, resourceId);
            if (!"DRAFT".equals(resource.lifecycle())) throw problem("RESOURCE_NOT_DRAFT", resource.displayName() + " 没有可提交的草稿版本");
            UUID versionId = jdbc.queryForObject("SELECT id FROM control.ontology_resource_versions WHERE resource_id=? AND version=?", UUID.class, resourceId, resource.version());
            jdbc.update("INSERT INTO control.ontology_proposal_tasks(id,proposal_id,resource_id,resource_version_id) VALUES (?,?,?,?)",
                    UUID.randomUUID(), id, resourceId, versionId);
        }
        audit(actor, "ONTOLOGY_PROPOSAL_CREATED", "ONTOLOGY_PROPOSAL", id.toString(), "创建多资源变更提议", Map.of("baselineRevision", baseline));
        return proposal(id);
    }

    public List<ProposalView> proposals() {
        return jdbc.query("SELECT id FROM control.ontology_proposals ORDER BY updated_at DESC",
                (row, n) -> proposal(row.getObject("id", UUID.class)));
    }

    public List<ProposalView> proposals(UUID ontologyId) {
        return jdbc.query("SELECT id FROM control.ontology_proposals WHERE ontology_id=? ORDER BY updated_at DESC",
                (row, n) -> proposal(row.getObject("id", UUID.class)), ontologyId);
    }

    public ProposalView proposal(UUID id) {
        return jdbc.query("SELECT * FROM control.ontology_proposals WHERE id=?", (row, n) -> new ProposalView(
                row.getObject("id", UUID.class), row.getString("title"), row.getString("description"), row.getString("status"),
                row.getLong("baseline_revision"), row.getString("risk_level"),
                readJson(row.getString("validation"), new TypeReference<List<ValidationIssue>>() { }),
                readJson(row.getString("impact"), new TypeReference<Map<String, Object>>() { }),
                proposalResources(row.getObject("id", UUID.class)), row.getString("created_by_name"),
                instant(row, "created_at"), instant(row, "updated_at"), instant(row, "submitted_at"), nullableLong(row, "published_revision")), id)
                .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Proposal 不存在"));
    }

    @Transactional
    public ProposalView validate(UUID id, Actor actor) {
        ProposalView proposal = proposal(id);
        requireStatus(proposal, "DRAFT", "CHANGES_REQUESTED", "FAILED");
        jdbc.update("UPDATE control.ontology_proposals SET status='VALIDATING',updated_at=now() WHERE id=?", id);
        List<ValidationIssue> issues = validateProposal(proposal);
        String risk = issues.stream().anyMatch(issue -> "ERROR".equals(issue.severity())) ? "BLOCKED"
                : proposal.resources().stream().anyMatch(resource -> resource.activeVersion() != null) ? "MEDIUM" : "LOW";
        Map<String, Object> impact = Map.of(
                "resourceCount", proposal.resources().size(),
                "publishedConsumers", proposal.resources().stream().filter(resource -> resource.activeVersion() != null).count(),
                "targetRevision", activeRevision() + 1,
                "requiresIndexMigration", proposal.resources().stream().anyMatch(resource -> resource.kind() == ResourceKind.OBJECT_TYPE));
        jdbc.update("UPDATE control.ontology_proposals SET status='DRAFT',risk_level=?,validation=?::jsonb,impact=?::jsonb,updated_at=now() WHERE id=?",
                risk, writeJson(issues), writeJson(impact), id);
        audit(actor, "ONTOLOGY_PROPOSAL_VALIDATED", "ONTOLOGY_PROPOSAL", id.toString(), "校验本体变更提议", Map.of("issueCount", issues.size(), "risk", risk));
        return proposal(id);
    }

    @Transactional
    public ProposalView submit(UUID id, Actor actor) {
        ProposalView validated = validate(id, actor);
        if (validated.validation().stream().anyMatch(issue -> "ERROR".equals(issue.severity()))) {
            throw problem("PROPOSAL_VALIDATION_FAILED", "Proposal 仍有阻断问题，不能提交审核");
        }
        jdbc.update("UPDATE control.ontology_proposals SET status='IN_REVIEW',submitted_at=now(),updated_at=now() WHERE id=?", id);
        jdbc.update("UPDATE control.ontology_resource_versions SET lifecycle='IN_REVIEW' WHERE id IN (SELECT resource_version_id FROM control.ontology_proposal_tasks WHERE proposal_id=?)", id);
        audit(actor, "ONTOLOGY_PROPOSAL_SUBMITTED", "ONTOLOGY_PROPOSAL", id.toString(), "提交本体变更提议审核", Map.of());
        return proposal(id);
    }

    @Transactional
    public ProposalView review(UUID id, ReviewRequest request, Actor actor) {
        ProposalView proposal = proposal(id);
        requireStatus(proposal, "IN_REVIEW");
        String decision = request == null ? "APPROVED" : value(request.decision(), "APPROVED").toUpperCase(Locale.ROOT);
        if (!List.of("APPROVED", "CHANGES_REQUESTED", "REJECTED").contains(decision)) throw problem("REVIEW_INVALID", "审核决定无效");
        String next = decision;
        jdbc.update("INSERT INTO control.ontology_reviews(id,proposal_id,decision,comment,reviewer_id,reviewer_name) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), id, decision, request == null ? "" : safe(request.comment()), actor.id(), actor.name());
        jdbc.update("UPDATE control.ontology_proposals SET status=?,updated_at=now() WHERE id=?", next, id);
        String lifecycle = "APPROVED".equals(decision) ? "APPROVED" : "REJECTED";
        jdbc.update("UPDATE control.ontology_resource_versions SET lifecycle=? WHERE id IN (SELECT resource_version_id FROM control.ontology_proposal_tasks WHERE proposal_id=?)", lifecycle, id);
        audit(actor, "ONTOLOGY_PROPOSAL_" + decision, "ONTOLOGY_PROPOSAL", id.toString(), "记录本体审核决定", Map.of("decision", decision));
        return proposal(id);
    }

    @Transactional
    public DeploymentView publish(UUID id, Actor actor) {
        ProposalView proposal = proposal(id);
        requireStatus(proposal, "APPROVED");
        if (proposal.baselineRevision() != activeRevision()) throw problem("PROPOSAL_BASELINE_CONFLICT", "主本体 revision 已变化，请重新校验并人工解决冲突");
        jdbc.execute("SELECT pg_advisory_xact_lock(700021)");
        long target = Objects.requireNonNull(jdbc.queryForObject("SELECT coalesce(max(revision),0)+1 FROM control.ontology_revisions", Long.class));
        UUID ontologyId = jdbc.queryForObject(
                "SELECT ontology_id FROM control.ontology_proposals WHERE id=?", UUID.class, id);
        jdbc.update("INSERT INTO control.ontology_revisions(revision,ontology_id,status) VALUES (?,?,'DRAFT')",
                target, ontologyId);
        UUID deploymentId = UUID.randomUUID();
        jdbc.update("INSERT INTO control.ontology_deployments(id,ontology_id,proposal_id,target_revision,status,current_step) VALUES (?,?,?,?,'PENDING','LOCK_PROPOSAL')",
                deploymentId, ontologyId, id, target);
        List<String> steps = List.of("LOCK_PROPOSAL", "BUILD_CONTRACT", "DEPLOY_HUGEGRAPH", "MIGRATE_OPENSEARCH", "ACTIVATE_REVISION", "PUBLISH_AUDIT");
        for (int i = 0; i < steps.size(); i++) jdbc.update("INSERT INTO control.ontology_deployment_steps(id,deployment_id,step_order,step_name,status) VALUES (?,?,?,?,'PENDING')",
                UUID.randomUUID(), deploymentId, i + 1, steps.get(i));
        jdbc.update("UPDATE control.ontology_proposals SET status='PUBLISHING',updated_at=now() WHERE id=?", id);
        audit(actor, "ONTOLOGY_PUBLISH_REQUESTED", "ONTOLOGY_PROPOSAL", id.toString(), "请求发布本体 revision", Map.of("targetRevision", target));
        afterCommit(() -> dispatchDeployment(deploymentId, ontologyId, actor));
        return deployment(deploymentId);
    }

    public DeploymentView retry(UUID proposalId, Actor actor) {
        ProposalView proposal = proposal(proposalId);
        requireStatus(proposal, "FAILED");
        jdbc.update("UPDATE control.ontology_proposals SET status='APPROVED',updated_at=now() WHERE id=?", proposalId);
        return publish(proposalId, actor);
    }

    @Transactional
    public ProposalView close(UUID id, Actor actor) {
        ProposalView proposal = proposal(id);
        if (List.of("PUBLISHING", "PUBLISHED").contains(proposal.status())) throw problem("PROPOSAL_CLOSED", "发布中或已发布 Proposal 不能关闭");
        jdbc.update("UPDATE control.ontology_proposals SET status='CLOSED',updated_at=now() WHERE id=?", id);
        audit(actor, "ONTOLOGY_PROPOSAL_CLOSED", "ONTOLOGY_PROPOSAL", id.toString(), "关闭本体变更提议", Map.of());
        return proposal(id);
    }

    public DeploymentView deployment(UUID id) {
        return jdbc.query("SELECT * FROM control.ontology_deployments WHERE id=?", (row, n) -> new DeploymentView(
                row.getObject("id", UUID.class), row.getObject("proposal_id", UUID.class), row.getLong("target_revision"),
                row.getString("status"), row.getString("current_step"), row.getInt("attempt"), row.getString("safe_error"),
                instant(row, "created_at"), instant(row, "started_at"), instant(row, "completed_at"), deploymentSteps(id)), id)
                .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Deployment 不存在"));
    }

    public List<HealthIssue> health() {
        return jdbc.query("""
                SELECT h.*,r.display_name resource_name FROM control.ontology_health_issues h
                LEFT JOIN control.ontology_resources r ON r.id=h.resource_id
                WHERE h.ontology_id=?
                ORDER BY CASE h.severity WHEN 'CRITICAL' THEN 1 WHEN 'ERROR' THEN 2 WHEN 'WARNING' THEN 3 ELSE 4 END,h.last_seen_at DESC
                """, (row, n) -> new HealthIssue(row.getObject("id", UUID.class), row.getString("severity"), row.getString("category"),
                row.getObject("resource_id", UUID.class), row.getString("resource_name"), row.getString("title"), row.getString("evidence"),
                row.getString("recommendation"), row.getString("owner_name"), row.getString("status"), instant(row, "first_seen_at"), instant(row, "last_seen_at")),
                WorkspaceContext.id());
    }

    public List<HistoryEntry> history() {
        return jdbc.query("""
                SELECT rev.revision,rev.status,rev.activated_at,rev.created_at,
                       (SELECT count(*) FROM control.object_types ot WHERE ot.revision=rev.revision) resource_count,
                       p.id proposal_id,p.title proposal_title
                FROM control.ontology_revisions rev
                LEFT JOIN control.ontology_proposals p ON p.published_revision=rev.revision AND p.ontology_id=rev.ontology_id
                WHERE rev.ontology_id=?
                ORDER BY rev.revision DESC
                """, (row, n) -> new HistoryEntry(row.getLong("revision"), row.getString("status"), instant(row, "activated_at"),
                instant(row, "created_at"), row.getLong("resource_count"), row.getObject("proposal_id", UUID.class), row.getString("proposal_title")),
                WorkspaceContext.id());
    }

    public ActionPreview previewAction(UUID id, ActionPreviewRequest request, Actor actor) {
        request = request == null ? new ActionPreviewRequest(Map.of(), null, null) : request;
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
        var current = "CREATE".equals(operation) ? null
                : explorerStorage.getObject(targetType.physicalKey(), request.objectId());
        if (current != null && request.objectVersion() != null && request.objectVersion() != current.version()) {
            throw problem("ACTION_OBJECT_VERSION_CONFLICT", "目标对象已发生变化，请重新预览");
        }
        Map<String, Object> parameters = request.parameters() == null ? Map.of() : request.parameters();
        validateActionParameters(action, parameters);
        if (!conditionMatches(castMap(action.definition().get("submitCondition")),
                current == null ? null : current.payload(), parameters)) {
            throw problem("ACTION_SUBMISSION_CONDITION_FAILED", "当前对象或参数不满足 Action 提交条件");
        }
        ObjectNode next = current == null ? json.createObjectNode() : current.payload().deepCopy();
        List<Map<String, Object>> visibleDiff = new ArrayList<>();
        List<Map<String, Object>> edits = new ArrayList<>();
        if (List.of("CREATE", "UPDATE").contains(operation)) {
            for (Map<String, Object> rule : rules) {
                UUID propertyId = UUID.fromString(Objects.toString(rule.get("targetPropertyId")));
                PropertyView property = targetType.properties().stream()
                        .filter(value -> value.id().equals(propertyId)).findFirst()
                        .orElseThrow(() -> problem("ACTION_PROPERTY_INVALID", "Action 引用了不存在的目标属性"));
                Object value = actionValue(rule, parameters);
                JsonNode before = next.get(property.physicalKey());
                next.set(property.physicalKey(), json.valueToTree(value));
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
            edit.put("expectedVersion", current == null ? null : current.version());
            edit.put("properties", next);
            edits.add(edit);
        } else if ("RETIRE".equals(operation)) {
            edits.add(new LinkedHashMap<>(Map.of(
                    "operation", "object.delete",
                    "objectTypeId", targetType.physicalKey(),
                    "objectId", request.objectId(),
                    "expectedVersion", current.version(),
                    "properties", Map.of())));
            visibleDiff.add(Map.of("operation", "RETIRE_OBJECT", "targetTypeId", targetType.id(),
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
                edit.put("expectedVersion", request.objectVersion());
                edit.put("properties", rule.getOrDefault("properties", Map.of()));
                edits.add(edit);
                visibleDiff.add(Map.of("operation", operation, "targetTypeId", relation.id(),
                        "targetId", relationId, "sourceObjectId", sourceId,
                        "targetObjectId", targetId));
            }
        }
        boolean approvalRequired = approvalRequired(action, current == null ? null : current.payload(), parameters);
        Instant expires = Instant.now().plus(10, ChronoUnit.MINUTES);
        UUID previewId = UUID.randomUUID();
        String token = previewId + "." + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO control.action_previews(
                  id,ontology_id,action_id,action_version,actor_id,object_id,expected_version,
                  parameters,edits,token_hash,expires_at,approval_required)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)
                """, previewId, WorkspaceContext.id(), id, action.version(), actor.id(),
                request == null ? null : request.objectId(), current == null ? null : current.version(),
                writeJson(parameters), writeJson(edits), fingerprint(token), Timestamp.from(expires),
                approvalRequired);
        return new ActionPreview(previewId, id, action.version(), token, expires, visibleDiff,
                approvalRequired);
    }

    @Transactional
    public ActionExecution executeAction(UUID actionId, ActionExecuteRequest request, Actor actor) {
        if (request == null || request.previewToken() == null || request.previewToken().isBlank()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw problem("ACTION_EXECUTION_INVALID", "执行 Action 需要 Preview Token 和 Idempotency-Key");
        }
        ActionExecution existing = jdbc.query("SELECT * FROM control.action_executions WHERE idempotency_key=?",
                (row, number) -> actionExecution(row), request.idempotencyKey()).stream().findFirst().orElse(null);
        if (existing != null) return refreshExecution(existing);
        PreviewRecord preview = jdbc.query("""
                SELECT * FROM control.action_previews WHERE action_id=? AND actor_id=? AND token_hash=?
                """, (row, number) -> new PreviewRecord(row.getObject("id", UUID.class), row.getInt("action_version"),
                row.getString("edits"), row.getBoolean("approval_required"),
                instant(row, "expires_at"), instant(row, "consumed_at")),
                actionId, actor.id(), fingerprint(request.previewToken())).stream().findFirst()
                .orElseThrow(() -> problem("ACTION_PREVIEW_INVALID", "Preview Token 无效或不属于当前用户"));
        if (preview.consumedAt() != null || preview.expiresAt().isBefore(Instant.now())) {
            throw problem("ACTION_PREVIEW_EXPIRED", "Preview Token 已使用或过期，请重新预览");
        }
        List<Map<String, Object>> rawEdits = readJson(preview.edits(), new TypeReference<>() { });
        UUID executionId = UUID.randomUUID();
        String correlationId = "action:" + executionId;
        String initialStatus = preview.approvalRequired() ? "PENDING_APPROVAL" : "SUBMITTED";
        jdbc.update("""
                INSERT INTO control.action_executions(
                  id,ontology_id,preview_id,action_id,action_version,actor_id,actor_name,
                  idempotency_key,correlation_id,trace_id,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, executionId, WorkspaceContext.id(), preview.id(), actionId, preview.actionVersion(),
                actor.id(), actor.name(), request.idempotencyKey(), correlationId,
                UUID.randomUUID().toString(), initialStatus);
        jdbc.update("UPDATE control.action_previews SET consumed_at=now() WHERE id=?", preview.id());
        if (!preview.approvalRequired()) {
            publishMutation(actionId, executionId, preview, request.idempotencyKey(), actor.id());
            audit(actor, "ACTION_EXECUTED", "ACTION", actionId.toString(), "提交本体 Action Mutation",
                    Map.of("executionId", executionId, "editCount", rawEdits.size()));
        } else {
            audit(actor, "ACTION_APPROVAL_REQUESTED", "ACTION", actionId.toString(),
                    "Action 等待 Resource Owner 审批", Map.of("executionId", executionId));
        }
        return actionExecution(executionId, actor);
    }

    @Transactional
    public ActionExecution reviewAction(UUID executionId, ActionReviewRequest request, Actor actor) {
        String decision = switch (upper(request == null ? null : request.decision())) {
            case "APPROVE", "APPROVED" -> "APPROVED";
            case "REJECT", "REJECTED" -> "REJECTED";
            default -> "";
        };
        if (!List.of("APPROVED", "REJECTED").contains(decision)) {
            throw problem("ACTION_REVIEW_INVALID", "审批决定必须为 APPROVED 或 REJECTED");
        }
        String comment = request == null ? "" : Objects.toString(request.comment(), "").trim();
        if (comment.isBlank() || comment.length() > 4000) {
            throw problem("ACTION_REVIEW_COMMENT_INVALID", "审批意见必须为 1—4000 个字符");
        }
        ActionExecution execution = jdbc.query(
                "SELECT * FROM control.action_executions WHERE id=? AND ontology_id=? FOR UPDATE",
                (row, number) -> actionExecution(row), executionId, WorkspaceContext.id()).stream().findFirst()
                .orElseThrow(() -> problem("ACTION_EXECUTION_NOT_FOUND", "Action Execution 不存在"));
        if (!"PENDING_APPROVAL".equals(execution.status())) {
            String existingDecision = jdbc.query("""
                    SELECT decision FROM control.action_execution_reviews
                    WHERE execution_id=? AND reviewer_id=?
                    """, (row, number) -> row.getString(1), executionId, actor.id())
                    .stream().findFirst().orElse(null);
            if (decision.equals(existingDecision)) return actionExecution(executionId, actor);
            throw problem("ACTION_REVIEW_STATE_INVALID", "只有等待审批的 Action 才能审核");
        }
        ResourceView action = get(execution.actionTypeId());
        if (!actor.admin() && !actor.id().equals(action.ownerId())) {
            throw problem("ACTION_REVIEW_FORBIDDEN", "只有本体管理员或 Action Resource Owner 可以审批");
        }
        if (actor.id().equals(execution.submittedBy())) {
            throw problem("ACTION_SELF_APPROVAL_FORBIDDEN", "Action 提交者不能审批自己的请求");
        }
        jdbc.update("""
                INSERT INTO control.action_execution_reviews(
                  id,execution_id,decision,comment,reviewer_id,reviewer_name)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), executionId, decision, comment, actor.id(), actor.name());
        if ("REJECTED".equals(decision)) {
            jdbc.update("""
                    UPDATE control.action_executions
                    SET status='REJECTED',completed_at=now() WHERE id=?
                    """, executionId);
            audit(actor, "ACTION_REJECTED", "ACTION", action.id().toString(),
                    "拒绝 Action Execution", Map.of("executionId", executionId));
            return actionExecution(executionId, actor);
        }
        PreviewRecord preview = jdbc.query("""
                SELECT * FROM control.action_previews WHERE id=?
                """, (row, number) -> new PreviewRecord(row.getObject("id", UUID.class),
                row.getInt("action_version"), row.getString("edits"),
                row.getBoolean("approval_required"), instant(row, "expires_at"),
                instant(row, "consumed_at")), execution.previewId()).getFirst();
        String idempotencyKey = jdbc.queryForObject(
                "SELECT idempotency_key FROM control.action_executions WHERE id=?",
                String.class, executionId);
        publishMutation(action.id(), executionId, preview, idempotencyKey, execution.submittedBy());
        audit(actor, "ACTION_APPROVED", "ACTION", action.id().toString(),
                "批准并提交 Action Mutation", Map.of("executionId", executionId));
        return actionExecution(executionId, actor);
    }

    public ActionExecution actionExecution(UUID id, Actor actor) {
        ActionExecution execution = jdbc.query("""
                SELECT e.* FROM control.action_executions e
                JOIN control.ontology_resources a ON a.id=e.action_id
                WHERE e.id=? AND e.ontology_id=?
                  AND (e.actor_id=? OR ? OR a.owner_id=?)
                """, (row, number) -> actionExecution(row), id, WorkspaceContext.id(),
                actor.id(), actor.admin(), actor.id()).stream().findFirst()
                .orElseThrow(() -> problem("ACTION_EXECUTION_NOT_FOUND", "Action Execution 不存在或无权访问"));
        return refreshExecution(execution);
    }

    public List<ActionExecution> actionExecutions(String status, Actor actor) {
        String normalized = upper(status);
        if (!normalized.isBlank() && !List.of(
                "PENDING_APPROVAL", "SUBMITTED", "PROJECTING", "SUCCEEDED",
                "DEGRADED", "FAILED", "REJECTED").contains(normalized)) {
            throw problem("ACTION_EXECUTION_STATUS_INVALID", "Action Execution 状态无效");
        }
        List<ActionExecution> executions = jdbc.query("""
                SELECT e.* FROM control.action_executions e
                JOIN control.ontology_resources a ON a.id=e.action_id
                WHERE e.ontology_id=?
                  AND (?='' OR e.status=?)
                  AND (e.actor_id=? OR ? OR a.owner_id=?)
                ORDER BY e.submitted_at DESC
                LIMIT 200
                """, (row, number) -> actionExecution(row), WorkspaceContext.id(),
                normalized, normalized, actor.id(), actor.admin(), actor.id());
        return executions.stream().map(this::refreshExecution).toList();
    }

    public FunctionExecution testFunction(UUID id, FunctionTestRequest request, Actor actor) {
        long started = System.nanoTime();
        ResourceView function = get(id);
        if (function.kind() != ResourceKind.FUNCTION) throw problem("RESOURCE_KIND_INVALID", "目标资源不是 Function");
        Map<String, Object> dsl = castMap(function.definition().get("queryDsl"));
        policy.validateFunctionDsl(dsl);
        Object result = functionRuntime.execute(dsl, request == null ? Map.of() : map(request.inputs()), actor);
        return new FunctionExecution(UUID.randomUUID(), id, function.version(), activeRevision(), result,
                Math.max(1, (System.nanoTime() - started) / 1_000_000), UUID.randomUUID().toString());
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void recoverPendingDeployments() {
        jdbc.query("""
                SELECT id,ontology_id FROM control.ontology_deployments
                WHERE status='PENDING' ORDER BY created_at LIMIT 20
                """, (row, number) -> Map.entry(
                row.getObject("id", UUID.class), row.getObject("ontology_id", UUID.class)))
                .forEach(deployment -> dispatchDeployment(deployment.getKey(), deployment.getValue(),
                        new Actor("platform-recovery", "Platform Recovery", true)));
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

    private void dispatchDeployment(UUID deploymentId, UUID ontologyId, Actor actor) {
        tasks.execute(() -> WorkspaceContext.run(ontologyId, () -> deploy(deploymentId, actor)));
    }

    private void deploy(UUID deploymentId, Actor actor) {
        int claimed = jdbc.update("""
                UPDATE control.ontology_deployments
                SET status='RUNNING',started_at=coalesce(started_at,now())
                WHERE id=? AND status='PENDING'
                """, deploymentId);
        if (claimed == 0) return;
        DeploymentView deployment = deployment(deploymentId);
        try {
            completeStep(deploymentId, 1, () -> {
                ProposalView proposal = proposal(deployment.proposalId());
                if (!"PUBLISHING".equals(proposal.status())) throw new IllegalStateException("Proposal lock was lost");
                return "proposal:" + deployment.proposalId();
            });
            completeStep(deploymentId, 2, () -> { buildSnapshot(deployment); return "ontology-revision:" + deployment.targetRevision(); });
            completeStep(deploymentId, 3, infrastructure::verifyHugeGraph);
            completeStep(deploymentId, 4, infrastructure::verifyOpenSearch);
            completeStep(deploymentId, 5, () -> { activate(deployment); return "ontology-revision:" + deployment.targetRevision(); });
            completeStep(deploymentId, 6, () -> {
                audit(actor, "ONTOLOGY_PUBLISHED", "ONTOLOGY_PROPOSAL", deployment.proposalId().toString(),
                        "发布并激活本体 revision", Map.of("revision", deployment.targetRevision()));
                return "audit:ontology-published";
            });
            jdbc.update("UPDATE control.ontology_deployments SET status='SUCCEEDED',current_step='COMPLETED',completed_at=now() WHERE id=?", deploymentId);
        } catch (Exception failure) {
            String safeError = safeFailure(failure);
            jdbc.update("UPDATE control.ontology_deployments SET status='FAILED',safe_error=?,completed_at=now() WHERE id=?", safeError, deploymentId);
            jdbc.update("UPDATE control.ontology_proposals SET status='FAILED',updated_at=now() WHERE id=?", deployment.proposalId());
            jdbc.update("""
                    INSERT INTO control.ontology_health_issues(id,ontology_id,issue_key,severity,category,title,evidence,recommendation,owner_name)
                    VALUES (?,?,?,'CRITICAL','DEPLOYMENT','本体发布失败',?,'修复外部依赖后从 Proposal 重试','Platform Admin')
                    ON CONFLICT (ontology_id,issue_key) DO UPDATE SET evidence=excluded.evidence,last_seen_at=now(),status='OPEN'
                    """, UUID.randomUUID(), WorkspaceContext.id(), "deployment:" + deployment.proposalId(), safeError);
        }
    }

    private void completeStep(UUID deploymentId, int order, StepWork work) {
        String name = jdbc.queryForObject("SELECT step_name FROM control.ontology_deployment_steps WHERE deployment_id=? AND step_order=?", String.class, deploymentId, order);
        jdbc.update("UPDATE control.ontology_deployments SET current_step=? WHERE id=?", name, deploymentId);
        jdbc.update("UPDATE control.ontology_deployment_steps SET status='RUNNING',started_at=now() WHERE deployment_id=? AND step_order=?", deploymentId, order);
        try {
            String external = work.run();
            jdbc.update("UPDATE control.ontology_deployment_steps SET status='SUCCEEDED',external_resource=?,completed_at=now() WHERE deployment_id=? AND step_order=?", external, deploymentId, order);
        } catch (Exception failure) {
            jdbc.update("UPDATE control.ontology_deployment_steps SET status='FAILED',safe_error=?,completed_at=now() WHERE deployment_id=? AND step_order=?", safeFailure(failure), deploymentId, order);
            throw failure;
        }
    }

    @FunctionalInterface
    private interface StepWork { String run(); }

    private void buildSnapshot(DeploymentView deployment) {
        transactions.executeWithoutResult(status -> {
            long active = activeRevision();
            jdbc.update("INSERT INTO control.object_types(revision,type_id,display_name,active) SELECT ?,type_id,display_name,active FROM control.object_types WHERE revision=?", deployment.targetRevision(), active);
            jdbc.update("INSERT INTO control.object_properties(revision,type_id,property_id,value_type,required,searchable,sensitive) SELECT ?,type_id,property_id,value_type,required,searchable,sensitive FROM control.object_properties WHERE revision=?", deployment.targetRevision(), active);
            jdbc.update("INSERT INTO control.relation_types(revision,type_id,source_type_id,target_type_id,active) SELECT ?,type_id,source_type_id,target_type_id,active FROM control.relation_types WHERE revision=?", deployment.targetRevision(), active);
            List<ResourceView> resources = proposalResources(deployment.proposalId());
            resources.stream().filter(resource -> resource.kind() == ResourceKind.OBJECT_TYPE)
                    .forEach(resource -> snapshotObject(deployment.targetRevision(), resource));
            resources.stream().filter(resource -> resource.kind() == ResourceKind.LINK_TYPE)
                    .forEach(resource -> snapshotLink(deployment.targetRevision(), resource));
        });
    }

    private void snapshotObject(long revision, ResourceView resource) {
        jdbc.update("DELETE FROM control.object_properties WHERE revision=? AND type_id=?", revision, resource.physicalKey());
        jdbc.update("DELETE FROM control.object_types WHERE revision=? AND type_id=?", revision, resource.physicalKey());
        jdbc.update("INSERT INTO control.object_types(revision,type_id,display_name,active) VALUES (?,?,?,true)", revision, resource.physicalKey(), resource.displayName());
        for (PropertyView property : resource.properties()) {
            jdbc.update("""
                    INSERT INTO control.object_properties(revision,type_id,property_id,value_type,required,searchable,sensitive)
                    VALUES (?,?,?,?,?,?,?)
                    """, revision, resource.physicalKey(), property.physicalKey(), projectionType(property.valueType()), property.required(),
                    property.searchable() && !property.sensitive(), property.sensitive());
        }
    }

    private void snapshotLink(long revision, ResourceView resource) {
        Map<String, Object> definition = resource.definition();
        UUID left = UUID.fromString(Objects.toString(definition.get("leftObjectTypeId")));
        UUID right = UUID.fromString(Objects.toString(definition.get("rightObjectTypeId")));
        jdbc.update("DELETE FROM control.relation_types WHERE revision=? AND type_id=?", revision, resource.physicalKey());
        jdbc.update("INSERT INTO control.relation_types(revision,type_id,source_type_id,target_type_id,active) VALUES (?,?,?,?,true)",
                revision, resource.physicalKey(), get(left).physicalKey(), get(right).physicalKey());
    }

    private void activate(DeploymentView deployment) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(700022)");
            if (activeRevision() != proposal(deployment.proposalId()).baselineRevision()) throw new IllegalStateException("Ontology revision changed during deployment");
            jdbc.update("UPDATE control.ontology_revisions SET status='RETIRED' WHERE ontology_id=? AND status='ACTIVE'",
                    WorkspaceContext.id());
            jdbc.update("UPDATE control.ontology_revisions SET status='ACTIVE',activated_at=now() WHERE ontology_id=? AND revision=?",
                    WorkspaceContext.id(), deployment.targetRevision());
            jdbc.update("""
                    UPDATE control.ontology_resource_versions v SET lifecycle='PUBLISHED',published_revision=?,published_at=now()
                    FROM control.ontology_proposal_tasks t WHERE t.proposal_id=? AND t.resource_version_id=v.id
                    """, deployment.targetRevision(), deployment.proposalId());
            jdbc.update("""
                    UPDATE control.ontology_resources r SET active_version=v.version,published_revision=?,
                        display_name=v.display_name,description=v.description,owner_id=v.owner_id,owner_name=v.owner_name,
                        maturity=v.maturity,promoted=v.promoted,tags=v.tags,updated_at=now()
                    FROM control.ontology_proposal_tasks t JOIN control.ontology_resource_versions v ON v.id=t.resource_version_id
                    WHERE t.proposal_id=? AND r.id=t.resource_id
                    """, deployment.targetRevision(), deployment.proposalId());
            jdbc.update("UPDATE control.ontology_proposal_tasks SET status='PUBLISHED' WHERE proposal_id=?", deployment.proposalId());
            jdbc.update("UPDATE control.ontology_proposals SET status='PUBLISHED',published_revision=?,updated_at=now() WHERE id=?", deployment.targetRevision(), deployment.proposalId());
            jdbc.update("UPDATE control.ontology_health_issues SET status='RESOLVED',last_seen_at=now() WHERE ontology_id=? AND issue_key=?",
                    WorkspaceContext.id(), "deployment:" + deployment.proposalId());
        });
    }

    private void insertVersion(UUID resourceId, UUID versionId, int version, ResourceDraftRequest request,
                               Map<String, Object> definition, Actor actor) {
        jdbc.update("""
                INSERT INTO control.ontology_resource_versions
                    (id,resource_id,version,lifecycle,display_name,description,maturity,promoted,owner_id,owner_name,tags,definition,fingerprint,created_by,created_by_name)
                VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,COALESCE(ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),'{}'::text[]),?::jsonb,?,?,?)
                """, versionId, resourceId, version, request.displayName().trim(), safe(request.description()), maturity(request.maturity()),
                request.promoted(), value(request.ownerId(), actor.id()), value(request.ownerName(), actor.name()), writeJson(list(request.tags())),
                writeJson(definition), fingerprint(writeJson(definition)), actor.id(), actor.name());
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
        if (!List.of("PIPELINE", "ACTION").contains(sourceMode)) throw problem("SOURCE_MODE_INVALID", "对象来源必须为 PIPELINE 或 ACTION");
        if ("PIPELINE".equals(sourceMode) && request.primaryPipelineId() != null && count("SELECT count(*) FROM control.pipelines WHERE id='" + request.primaryPipelineId() + "'") == 0) {
            throw problem("PIPELINE_NOT_FOUND", "主 Pipeline 不存在");
        }
        jdbc.update("INSERT INTO control.object_type_versions(version_id,resource_id,source_mode,primary_pipeline_id) VALUES (?,?,?,?)",
                versionId, resourceId, sourceMode, request.primaryPipelineId());
        UUID primary = null;
        UUID title = null;
        for (PropertyDraft property : list(request.properties())) {
            UUID propertyId = jdbc.query("SELECT id FROM control.properties WHERE object_type_id=? AND api_name=?",
                    (row, n) -> row.getObject("id", UUID.class), resourceId, property.apiName()).stream().findFirst().orElse(UUID.randomUUID());
            if (count("SELECT count(*) FROM control.properties WHERE id='" + propertyId + "'") == 0) {
                jdbc.update("INSERT INTO control.properties(id,object_type_id,api_name,physical_key) VALUES (?,?,?,?)",
                        propertyId, resourceId, property.apiName(), "p_" + propertyId.toString().replace("-", "").substring(0, 12));
            }
            boolean searchable = property.searchable() && !property.sensitive();
            jdbc.update("""
                    INSERT INTO control.property_versions
                        (id,property_id,object_type_version_id,display_name,description,value_type,required,primary_key,title_property,
                         searchable,filterable,sortable,sensitive,masking_policy,analyzer,source_field,enum_values)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,COALESCE(ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),'{}'::text[]))
                    """, UUID.randomUUID(), propertyId, versionId, property.displayName(), safe(property.description()), upper(property.valueType()),
                    property.required(), property.primaryKey(), property.titleProperty(), searchable, property.filterable(), property.sortable(),
                    property.sensitive(), property.maskingPolicy(), property.analyzer(), property.sourceField(), writeJson(list(property.enumValues())));
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
        String approvalPolicy = value(request.approvalPolicy(), "NONE");
        policy.validateActionContract(operation, approvalPolicy);
        policy.validateActionRules(operation, list(request.rules()));
        Map<String, Object> submitCondition = map(request.submitCondition());
        if ("CONDITIONAL".equals(upper(approvalPolicy))
                && !submitCondition.containsKey("approvalWhen")
                && !submitCondition.containsKey("approvalCondition")) {
            throw problem("ACTION_APPROVAL_CONDITION_REQUIRED",
                    "CONDITIONAL 审批策略必须声明 approvalWhen 条件");
        }
        jdbc.update("INSERT INTO control.action_types(resource_id,target_object_type_id) VALUES (?,?)", resourceId, target.id());
        jdbc.update("INSERT INTO control.action_type_versions(version_id,resource_id,operation,approval_policy,rules,submit_condition) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                versionId, resourceId, upper(value(request.operation(), "UPDATE")), upper(approvalPolicy),
                writeJson(list(request.rules())), writeJson(submitCondition));
        insertParameters("action_parameters", "action_version_id", versionId, request.parameters());
    }

    private void insertFunctionVersion(UUID resourceId, UUID versionId, ResourceDraftRequest request) {
        policy.validateFunctionDsl(map(request.queryDsl()));
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

    private List<ValidationIssue> validateProposal(ProposalView proposal) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (ResourceView resource : proposal.resources()) {
            if (resource.publishedRevision() != null && resource.publishedRevision() > proposal.baselineRevision()) {
                issues.add(new ValidationIssue("BASELINE_CONFLICT", "ERROR", resource.id(), "version", "同一资源已在基线后发布", "选择保留草稿、接受已发布版本或手工解决"));
            }
            if (resource.kind() == ResourceKind.OBJECT_TYPE) {
                long primary = resource.properties().stream().filter(PropertyView::primaryKey).count();
                if (primary != 1) issues.add(new ValidationIssue("PRIMARY_KEY_REQUIRED", "ERROR", resource.id(), "properties", "已发布对象类型必须恰好有一个主键", "选择一个 string/integer/long 必填属性"));
                if (resource.properties().stream().noneMatch(PropertyView::titleProperty)) issues.add(new ValidationIssue("TITLE_FALLBACK", "WARNING", resource.id(), "properties", "未指定标题属性，将回退到主键", "显式选择便于识别的标题属性"));
                if ("PIPELINE".equals(resource.definition().get("sourceMode")) && resource.definition().get("primaryPipelineId") == null) {
                    issues.add(new ValidationIssue("PIPELINE_UNBOUND", "WARNING", resource.id(), "primaryPipelineId", "Pipeline 来源尚未绑定主 Pipeline", "选择一个已发布 Pipeline Sink"));
                }
            }
        }
        return issues;
    }

    private void validateCommon(ResourceDraftRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank() || request.displayName().length() > 240) throw problem("DISPLAY_NAME_INVALID", "显示名称长度必须为 1 到 240 个字符");
        requireApiName(request.apiName());
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
        return jdbc.query("""
                SELECT otv.object_count FROM control.object_type_versions otv JOIN control.ontology_resources r ON r.id=otv.resource_id
                JOIN control.ontology_resource_versions rv ON rv.id=otv.version_id AND rv.version=r.active_version WHERE r.id=?
                """, (row, n) -> row.getLong(1), resourceId).stream().findFirst().orElse(0L);
    }

    private ResourceView resource(ResultSet row, int number) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        ResourceKind kind = ResourceKind.valueOf(row.getString("kind"));
        return new ResourceView(id, kind, row.getString("api_name"), row.getString("version_display_name"),
                row.getString("version_description"), row.getString("physical_key"), row.getString("version_owner_id"),
                row.getString("version_owner_name"), row.getString("version_maturity"), row.getBoolean("version_promoted"),
                strings(row.getArray("version_tags")), row.getString("lifecycle"), row.getInt("latest_version"),
                nullableInteger(row, "active_version"), nullableLong(row, "published_revision"), row.getLong("etag"),
                readJson(row.getString("definition"), new TypeReference<Map<String, Object>>() { }),
                kind == ResourceKind.OBJECT_TYPE ? properties(id) : List.of(), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private PropertyView property(ResultSet row, int number) throws SQLException {
        return new PropertyView(row.getObject("id", UUID.class), row.getString("api_name"), row.getString("display_name"),
                row.getString("description"), row.getString("value_type"), row.getBoolean("required"), row.getBoolean("primary_key"),
                row.getBoolean("title_property"), row.getBoolean("searchable"), row.getBoolean("filterable"), row.getBoolean("sortable"),
                row.getBoolean("sensitive"), row.getString("physical_key"), row.getString("source_field"));
    }

    private List<ResourceView> proposalResources(UUID proposalId) {
        return jdbc.query("""
                SELECT r.*,v.id version_id,v.lifecycle,v.display_name version_display_name,
                       v.description version_description,v.maturity version_maturity,v.promoted version_promoted,
                       v.owner_id version_owner_id,v.owner_name version_owner_name,v.tags version_tags,v.definition
                FROM control.ontology_proposal_tasks t JOIN control.ontology_resources r ON r.id=t.resource_id
                JOIN control.ontology_resource_versions v ON v.id=t.resource_version_id WHERE t.proposal_id=? ORDER BY r.kind,r.api_name
                """, this::resource, proposalId);
    }

    private List<DeploymentStep> deploymentSteps(UUID deploymentId) {
        return jdbc.query("SELECT * FROM control.ontology_deployment_steps WHERE deployment_id=? ORDER BY step_order", (row, n) -> new DeploymentStep(
                row.getInt("step_order"), row.getString("step_name"), row.getString("status"), row.getString("external_resource"),
                row.getString("safe_error"), instant(row, "started_at"), instant(row, "completed_at")), deploymentId);
    }

    private Map<String, Object> definition(ResourceDraftRequest request) {
        Map<String, Object> result = json.convertValue(request, new TypeReference<Map<String, Object>>() { });
        result.values().removeIf(Objects::isNull);
        return result;
    }

    private void validateActionParameters(ResourceView action, Map<String, Object> parameters) {
        for (Object item : values(action.definition().get("parameters"))) {
            if (!(item instanceof Map<?, ?> parameter)) continue;
            String name = Objects.toString(parameter.get("apiName"), "");
            if (Boolean.TRUE.equals(parameter.get("required")) && !parameters.containsKey(name)
                    && parameter.get("defaultValue") == null) {
                throw problem("ACTION_PARAMETER_REQUIRED", "缺少 Action 参数：" + name);
            }
        }
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

    private boolean approvalRequired(ResourceView action, JsonNode current,
                                     Map<String, Object> parameters) {
        String policyName = upper(Objects.toString(
                action.definition().getOrDefault("approvalPolicy", "NONE")));
        if ("ALWAYS".equals(policyName)) return true;
        if (!"CONDITIONAL".equals(policyName)) return false;
        Map<String, Object> submitCondition = castMap(action.definition().get("submitCondition"));
        Object condition = submitCondition.containsKey("approvalWhen")
                ? submitCondition.get("approvalWhen") : submitCondition.get("approvalCondition");
        return conditionMatches(castMap(condition), current, parameters);
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
                                 String idempotencyKey, String requestedBy) {
        List<Map<String, Object>> rawEdits = readJson(preview.edits(), new TypeReference<>() { });
        List<MutationEdit> edits = rawEdits.stream().map(this::mutationEdit).toList();
        String correlationId = jdbc.queryForObject(
                "SELECT correlation_id FROM control.action_executions WHERE id=?",
                String.class, executionId);
        OntologyMutationBatch batch = new OntologyMutationBatch(
                UUID.randomUUID(), WorkspaceContext.id(), activeRevision(), actionId.toString(),
                (long) preview.actionVersion(), preview.id().toString(), idempotencyKey,
                requestedBy, Instant.now(), correlationId, edits);
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
        Object expected = edit.get("expectedVersion");
        return new MutationEdit(
                Objects.toString(edit.get("operation"), null),
                Objects.toString(edit.get("objectTypeId"), null),
                Objects.toString(edit.get("objectId"), null),
                expected instanceof Number number ? number.longValue() : null,
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
                row.getString("status"), row.getString("actor_id"), row.getString("correlation_id"),
                row.getString("trace_id"), row.getString("safe_error"),
                instant(row, "submitted_at"), instant(row, "completed_at"));
    }

    private ResourceDraftRequest withStableApi(ResourceDraftRequest value, String apiName) {
        return new ResourceDraftRequest(value.displayName(), apiName, value.description(), value.maturity(), value.ownerId(), value.ownerName(),
                value.tags(), value.promoted(), value.sourceMode(), value.primaryPipelineId(), value.properties(), value.leftObjectTypeId(),
                value.rightObjectTypeId(), value.targetObjectTypeId(), value.cardinality(), value.leftDisplayName(), value.rightDisplayName(),
                value.sourcePropertyId(), value.operation(), value.approvalPolicy(), value.parameters(), value.rules(), value.submitCondition(),
                value.slots(), value.implementations(), value.outputType(), value.queryDsl(), value.dependencyIds(), value.timeoutMs(), value.maxResults(), value.cacheSeconds());
    }

    private void requireStatus(ProposalView proposal, String... allowed) {
        if (!List.of(allowed).contains(proposal.status())) throw problem("PROPOSAL_STATE_INVALID", "Proposal 当前状态不允许此操作：" + proposal.status());
    }

    private void requireApiName(String value) {
        if (value == null || !API_NAME.matcher(value).matches()) throw problem("API_NAME_INVALID", "API 名称必须以字母开头，且只能包含字母、数字和下划线");
    }

    private long activeRevision() {
        return activeRevision(WorkspaceContext.id());
    }

    private long activeRevision(UUID ontologyId) {
        Long value = jdbc.queryForObject(
                "SELECT max(revision) FROM control.ontology_revisions WHERE ontology_id=? AND status='ACTIVE'",
                Long.class, ontologyId);
        if (value == null) throw new IllegalStateException("Active ontology revision is missing");
        return value;
    }

    private long count(String sql) { return Objects.requireNonNullElse(jdbc.queryForObject(sql, Long.class), 0L); }
    private long count(UUID ontologyId, String sql) {
        return Objects.requireNonNullElse(jdbc.queryForObject(sql, Long.class, ontologyId), 0L);
    }

    private void audit(Actor actor, String action, String type, String id, String summary, Map<String, ?> diff) {
        jdbc.update("""
                INSERT INTO control.audit_events(id,actor_id,actor_name,action,resource_type,resource_id,request_id,summary,safe_diff)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), actor.id(), actor.name(), action, type, id, UUID.randomUUID(), summary, writeJson(diff));
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
    private record PreviewRecord(UUID id, int actionVersion, String edits, boolean approvalRequired,
                                 Instant expiresAt, Instant consumedAt) { }
}
