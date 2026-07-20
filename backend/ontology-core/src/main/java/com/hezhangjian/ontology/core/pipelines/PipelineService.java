package com.hezhangjian.ontology.core.pipelines;

import static com.hezhangjian.ontology.core.pipelines.PipelineModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.connections.ConnectionCrypto;
import com.hezhangjian.ontology.core.connections.ConnectionModels.DataSource;
import com.hezhangjian.ontology.core.connections.ConnectionProblem;
import com.hezhangjian.ontology.core.connections.DataConnectionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineService {
    private static final String PIPELINE_SELECT = """
            SELECT p.*,d.name data_source_name,a.name source_asset_name
            FROM control.pipelines p
            JOIN control.data_sources d ON d.id=p.data_source_id
            LEFT JOIN control.data_source_assets a ON a.id=p.source_asset_id
            """;

    private final ConnectionCrypto crypto;
    private final DataConnectionService connections;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PipelineGraphValidator validator;
    private final PipelineRuntimeCoordinator runtime;

    public PipelineService(ConnectionCrypto crypto, DataConnectionService connections, JdbcTemplate jdbc,
                           ObjectMapper json, PipelineGraphValidator validator,
                           @Lazy PipelineRuntimeCoordinator runtime) {
        this.crypto = crypto;
        this.connections = connections;
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
        this.runtime = runtime;
    }

    public PipelinePage list(int page, int size, String search, String mode, String lifecycle,
                             String runStatus, String owner, String sort) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String pattern = "%" + empty(search).toLowerCase(Locale.ROOT) + "%";
        String order = switch (empty(sort)) {
            case "name" -> "p.name ASC";
            case "lastRun" -> "p.last_run_at DESC NULLS LAST";
            default -> "p.updated_at DESC";
        };
        String where = """
                WHERE (lower(p.name) LIKE ? OR lower(coalesce(p.description,'')) LIKE ?)
                  AND (?='' OR p.mode=?) AND (?='' OR p.lifecycle=?)
                  AND (?='' OR p.run_status=?) AND (?='' OR p.owner_id=?)
                """;
        List<Pipeline> items = jdbc.query(PIPELINE_SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                this::mapPipeline, pattern, pattern, empty(mode), empty(mode), empty(lifecycle), empty(lifecycle),
                empty(runStatus), empty(runStatus), empty(owner), empty(owner), safeSize, safePage * safeSize);
        Long total = jdbc.queryForObject("SELECT count(*) FROM control.pipelines p " + where, Long.class,
                pattern, pattern, empty(mode), empty(mode), empty(lifecycle), empty(lifecycle),
                empty(runStatus), empty(runStatus), empty(owner), empty(owner));
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("SELECT lifecycle,count(*) FROM control.pipelines GROUP BY lifecycle ORDER BY lifecycle",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> counts.put(row.getString(1), row.getInt(2)));
        counts.put("ALL", Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM control.pipelines", Integer.class)));
        return new PipelinePage(items, safePage, safeSize, total == null ? 0 : total, counts,
                Map.of("lifecycle", empty(lifecycle), "mode", empty(mode), "owner", empty(owner),
                        "runStatus", empty(runStatus), "search", empty(search), "sort", empty(sort)));
    }

    @Transactional
    public Pipeline create(CreatePipelineRequest request, Actor actor) {
        requireName(request.name());
        if (request.mode() == null || request.dataSourceId() == null || request.sourceAssetId() == null) {
            throw new ConnectionProblem("PIPELINE_SOURCE_REQUIRED", "管道模式、源连接和源资产不能为空");
        }
        DataSource source = connections.get(request.dataSourceId());
        if (!source.status().name().startsWith("HEALTHY")) throw new ConnectionProblem("CONNECTION_NOT_RUNNABLE", "源连接未处于可运行状态");
        Integer assetCount = jdbc.queryForObject("SELECT count(*) FROM control.data_source_assets WHERE id=? AND data_source_id=? AND status<>'UNAVAILABLE'",
                Integer.class, request.sourceAssetId(), request.dataSourceId());
        if (assetCount == null || assetCount == 0) throw new ConnectionProblem("PIPELINE_ASSET_INVALID", "源资产不属于所选连接或已不可用");
        if (request.mode() == PipelineMode.STREAMING && !List.of("KAFKA", "EXTERNAL_PULSAR").contains(source.type().name())) {
            throw new ConnectionProblem("PIPELINE_MODE_INVALID", "流式管道只支持 Kafka 或外部 Pulsar 源");
        }
        if (request.mode() == PipelineMode.BATCH && List.of("KAFKA", "EXTERNAL_PULSAR").contains(source.type().name())) {
            throw new ConnectionProblem("PIPELINE_MODE_INVALID", "Kafka 或外部 Pulsar 源必须使用流式模式");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String template = normalizeTemplate(request.template());
        PipelineGraph graph = initialGraph(id, request.dataSourceId(), request.sourceAssetId(), source.type().name(), template);
        RuntimeSettings runtimeSettings = new RuntimeSettings(2, request.mode() == PipelineMode.STREAMING ? 60_000 : 0, 3,
                request.mode() == PipelineMode.STREAMING ? "EARLIEST" : "SNAPSHOT", null, 5_000);
        ScheduleSettings schedule = new ScheduleSettings("MANUAL", null, null, "SKIP", false);
        try {
            jdbc.update("""
                    INSERT INTO control.pipelines(id,name,normalized_name,description,template,data_source_id,source_asset_id,
                      mode,status,lifecycle,run_status,target_summary,schedule_summary,owner_id,owner_name,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,'DRAFT','DRAFT','NEVER_RUN',?,'MANUAL',?,?,?,?)
                    """, id, request.name().trim(), normalize(request.name()), safeDescription(request.description()), template,
                    request.dataSourceId(), request.sourceAssetId(), request.mode().name(), targetSummary(graph),
                    valueOr(request.ownerId(), actor.id()), valueOr(request.ownerName(), actor.name()),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException cause) {
            throw new ConnectionProblem("PIPELINE_NAME_CONFLICT", "管道名称已存在（名称不区分大小写）");
        }
        jdbc.update("""
                INSERT INTO control.pipeline_drafts(pipeline_id,graph,runtime,schedule,updated_by)
                VALUES (?,?::jsonb,?::jsonb,?::jsonb,?)
                """, id, writeJson(graph), writeJson(runtimeSettings), writeJson(schedule), actor.id());
        jdbc.update("INSERT INTO control.pipeline_schedules(pipeline_id) VALUES (?)", id);
        audit(actor, "PIPELINE_CREATED", "pipeline", id.toString(), "创建管道草稿“" + request.name().trim() + "”",
                Map.of("dataSourceId", request.dataSourceId(), "mode", request.mode(), "template", template));
        return get(id);
    }

    public Pipeline get(UUID id) {
        List<Pipeline> pipelines = jdbc.query(PIPELINE_SELECT + " WHERE p.id=?", this::mapPipeline, id);
        if (pipelines.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "管道不存在");
        Pipeline value = pipelines.getFirst();
        PipelineDraft draft = jdbc.query("SELECT * FROM control.pipeline_drafts WHERE pipeline_id=?", this::mapDraft, id)
                .stream().findFirst().orElse(null);
        return new Pipeline(value.id(), value.name(), value.description(), value.template(), value.mode(), value.lifecycle(),
                value.runStatus(), value.dataSourceId(), value.dataSourceName(), value.sourceAssetId(), value.sourceAssetName(),
                value.targetSummary(), value.scheduleSummary(), value.ownerId(), value.ownerName(), value.publishedVersion(),
                value.version(), value.lastRunAt(), value.createdAt(), value.updatedAt(), draft);
    }

    @Transactional
    public Pipeline updateDraft(UUID id, long expectedEtag, UpdateDraftRequest request, Actor actor) {
        Pipeline existing = get(id);
        if (existing.lifecycle() == PipelineLifecycle.ARCHIVED) throw new ConnectionProblem("PIPELINE_ARCHIVED", "已归档管道不能编辑");
        if (existing.draft() == null) createDraftFromPublished(existing, actor);
        PipelineDraft current = get(id).draft();
        PipelineGraph graph = request.graph() == null ? current.graph() : request.graph();
        RuntimeSettings runtimeSettings = request.runtime() == null ? current.runtime() : normalizeRuntime(request.runtime(), existing.mode());
        ScheduleSettings schedule = request.schedule() == null ? current.schedule() : normalizeSchedule(request.schedule(), existing.mode());
        String name = request.name() == null ? existing.name() : request.name().trim();
        requireName(name);
        int changed;
        try {
            changed = jdbc.update("""
                    UPDATE control.pipeline_drafts SET graph=?::jsonb,runtime=?::jsonb,schedule=?::jsonb,
                      etag=etag+1,updated_by=?,updated_at=now() WHERE pipeline_id=? AND etag=?
                    """, writeJson(graph), writeJson(runtimeSettings), writeJson(schedule), actor.id(), id, expectedEtag);
            if (changed > 0) jdbc.update("""
                    UPDATE control.pipelines SET name=?,normalized_name=?,description=?,target_summary=?,schedule_summary=?,
                      row_version=row_version+1,updated_at=now() WHERE id=?
                    """, name, normalize(name), request.description() == null ? existing.description() : safeDescription(request.description()),
                    targetSummary(graph), scheduleSummary(schedule), id);
        } catch (DuplicateKeyException cause) {
            throw new ConnectionProblem("PIPELINE_NAME_CONFLICT", "管道名称已存在（名称不区分大小写）");
        }
        if (changed == 0) throw new ConnectionProblem("PIPELINE_VERSION_CONFLICT", "草稿已被其他用户更新，请重新加载后显式合并");
        audit(actor, "PIPELINE_DRAFT_UPDATED", "pipeline", id.toString(), "自动保存管道草稿", Map.of("etag", expectedEtag + 1));
        return get(id);
    }

    public ValidationResult validate(UUID id) {
        Pipeline pipeline = get(id);
        if (pipeline.draft() == null) throw new ConnectionProblem("PIPELINE_DRAFT_MISSING", "管道没有可校验草稿");
        return validator.validate(pipeline.draft().graph(), pipeline.mode(), sourceSchema(pipeline.sourceAssetId()));
    }

    public List<NodeType> nodeTypes() { return validator.nodeTypes(); }

    public PreviewRun preview(UUID id, PreviewRequest request, Actor actor) {
        Pipeline pipeline = get(id);
        String nodeId = request == null || request.nodeId() == null ? lastConnectedNode(pipeline) : request.nodeId();
        int limit = request == null ? 100 : Math.max(1, Math.min(100, request.limit()));
        ValidationResult validation = validate(id);
        if (!validation.valid()) throw new ConnectionProblem("PIPELINE_PREVIEW_INVALID", "修复校验错误后才能预览");
        return runtime.preview(pipeline, validation.normalizedGraph(), nodeId, limit, actor);
    }

    public PreviewRun preview(UUID previewId) { return runtime.preview(previewId); }

    @Transactional
    public Pipeline duplicate(UUID id, Actor actor) {
        Pipeline source = get(id);
        CreatePipelineRequest request = new CreatePipelineRequest(uniqueCopyName(source.name()), source.description(), source.template(),
                source.mode(), source.dataSourceId(), source.sourceAssetId(), actor.id(), actor.name());
        Pipeline copy = create(request, actor);
        if (source.draft() != null) updateDraft(copy.id(), copy.draft().etag(),
                new UpdateDraftRequest(remapGraph(source.draft().graph()), source.draft().runtime(), source.draft().schedule(), null, null), actor);
        audit(actor, "PIPELINE_DUPLICATED", "pipeline", copy.id().toString(), "复制管道“" + source.name() + "”", Map.of("sourcePipelineId", id));
        return get(copy.id());
    }

    @Transactional
    public PipelineProposal propose(UUID id, ProposalRequest request, Actor actor) {
        Pipeline pipeline = get(id);
        ValidationResult validation = validate(id);
        if (!validation.valid()) throw new ConnectionProblem("PIPELINE_VALIDATION_FAILED", "存在阻止提交审核的校验错误");
        jdbc.update("UPDATE control.pipeline_proposals SET status='SUPERSEDED' WHERE pipeline_id=? AND status='OPEN'", id);
        UUID proposalId = UUID.randomUUID();
        String risk = isHighRisk(pipeline) ? "HIGH" : "NORMAL";
        jdbc.update("""
                INSERT INTO control.pipeline_proposals(id,pipeline_id,draft_etag,status,risk_level,title,summary,validation,impact,
                  submitted_by,submitted_by_name) VALUES (?,?,?,'OPEN',?,?,?,?::jsonb,?::jsonb,?,?)
                """, proposalId, id, pipeline.draft().etag(), risk,
                valueOr(request == null ? null : request.title(), "发布“" + pipeline.name() + "”"),
                request == null ? null : safeDescription(request.summary()), writeJson(validation.issues()),
                writeJson(validation.impact()), actor.id(), actor.name());
        jdbc.update("UPDATE control.pipelines SET lifecycle='IN_REVIEW',updated_at=now() WHERE id=?", id);
        audit(actor, "PIPELINE_PROPOSAL_SUBMITTED", "pipeline", id.toString(), "提交管道变更提议",
                Map.of("proposalId", proposalId, "risk", risk));
        return proposal(proposalId);
    }

    @Transactional
    public PipelineProposal decide(UUID id, UUID proposalId, boolean approve, DecisionRequest request, Actor actor) {
        PipelineProposal proposal = proposal(proposalId);
        if (!proposal.pipelineId().equals(id)) throw new ConnectionProblem("PROPOSAL_PIPELINE_MISMATCH", "变更提议不属于该管道");
        if (proposal.status() != ProposalStatus.OPEN) throw new ConnectionProblem("PROPOSAL_CLOSED", "变更提议已结束");
        if (proposal.riskLevel().equals("HIGH") && !actor.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "高风险变更必须由 Admin 审批");
        jdbc.update("""
                UPDATE control.pipeline_proposals SET status=?,decided_by=?,decided_by_name=?,decided_at=now(),decision_comment=? WHERE id=?
                """, approve ? "APPROVED" : "REJECTED", actor.id(), actor.name(), request == null ? null : safeDescription(request.comment()), proposalId);
        if (!approve) jdbc.update("UPDATE control.pipelines SET lifecycle=CASE WHEN published_version IS NULL THEN 'DRAFT' ELSE 'PUBLISHED' END,updated_at=now() WHERE id=?", id);
        audit(actor, approve ? "PIPELINE_PROPOSAL_APPROVED" : "PIPELINE_PROPOSAL_REJECTED", "pipeline", id.toString(),
                approve ? "批准管道变更提议" : "拒绝管道变更提议", Map.of("proposalId", proposalId));
        return proposal(proposalId);
    }

    public List<PipelineProposal> proposals(UUID id) {
        return jdbc.query("SELECT * FROM control.pipeline_proposals WHERE pipeline_id=? ORDER BY submitted_at DESC", this::mapProposal, id);
    }

    public PipelineProposal proposal(UUID proposalId) {
        return jdbc.query("SELECT * FROM control.pipeline_proposals WHERE id=?", this::mapProposal, proposalId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "变更提议不存在"));
    }

    @Transactional
    public PipelineVersion publish(UUID id, PublishRequest request, Actor actor) {
        Pipeline pipeline = get(id);
        if (!actor.admin() && !actor.id().equals(pipeline.ownerId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有负责人或 Admin 可以发布");
        ValidationResult validation = validate(id);
        if (!validation.valid()) throw new ConnectionProblem("PIPELINE_VALIDATION_FAILED", "存在阻止发布的校验错误");
        boolean warnings = validation.issues().stream().anyMatch(issue -> issue.severity().equals("WARNING"));
        if (warnings && (request == null || !request.acknowledgeWarnings())) throw new ConnectionProblem("PIPELINE_WARNINGS_UNACKNOWLEDGED", "确认警告后才能发布");
        if (isHighRisk(pipeline)) {
            if (!actor.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "高风险变更必须由 Admin 发布");
            if (request == null || request.proposalId() == null || proposal(request.proposalId()).status() != ProposalStatus.APPROVED) {
                throw new ConnectionProblem("PIPELINE_APPROVAL_REQUIRED", "高风险变更需要已批准的变更提议");
            }
        }
        int version = pipeline.publishedVersion() == null ? 1 : pipeline.publishedVersion() + 1;
        UUID versionId = UUID.randomUUID();
        Map<String, Object> ir = compileIr(pipeline, validation.normalizedGraph(), version);
        Map<String, Object> jobSpec = Map.of(
                "engine", "FLINK", "jar", "ontology-flink-job-0.1.0-SNAPSHOT.jar",
                "jarSignature", crypto.fingerprint("ontology-flink-job:v1"), "pipelineIrHash", validation.contentHash(),
                "schemaVersion", 1);
        jdbc.update("""
                INSERT INTO control.pipeline_versions(id,pipeline_id,version,graph,pipeline_ir,job_spec,content_hash,validation,
                  published_by,published_by_name) VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?,?)
                """, versionId, id, version, writeJson(validation.normalizedGraph()), writeJson(ir), writeJson(jobSpec),
                validation.contentHash(), writeJson(validation.issues()), actor.id(), actor.name());
        indexDependencies(pipeline, versionId, validation.normalizedGraph());
        jdbc.update("""
                UPDATE control.pipelines SET published_version=?,lifecycle='PUBLISHED',target_summary=?,row_version=row_version+1,updated_at=now() WHERE id=?
                """, version, targetSummary(validation.normalizedGraph()), id);
        jdbc.update("UPDATE control.pipeline_drafts SET base_version=? WHERE pipeline_id=?", version, id);
        activateSchedule(id, pipeline.draft().schedule());
        audit(actor, "PIPELINE_PUBLISHED", "pipeline", id.toString(), "发布不可变管道版本 v" + version,
                Map.of("contentHash", validation.contentHash(), "versionId", versionId));
        PipelineVersion published = version(id, version);
        if (request != null && request.startAfterPublish()) {
            if (pipeline.mode() == PipelineMode.BATCH) runtime.startRun(get(id), "MANUAL", null, actor);
            else runtime.startStream(get(id), actor);
        }
        return published;
    }

    public List<PipelineVersion> versions(UUID id) {
        get(id);
        return jdbc.query("SELECT * FROM control.pipeline_versions WHERE pipeline_id=? ORDER BY version DESC", this::mapVersion, id);
    }

    public PipelineVersion version(UUID id, int version) {
        return jdbc.query("SELECT * FROM control.pipeline_versions WHERE pipeline_id=? AND version=?", this::mapVersion, id, version)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "管道版本不存在"));
    }

    public Map<String, Object> diff(UUID id) {
        Pipeline pipeline = get(id);
        if (pipeline.publishedVersion() == null || pipeline.draft() == null) return Map.of("baseVersion", 0, "changed", true, "highRisk", false);
        PipelineVersion base = version(id, pipeline.publishedVersion());
        ValidationResult current = validate(id);
        return Map.of("baseHash", base.contentHash(), "baseVersion", base.version(), "changed", !base.contentHash().equals(current.contentHash()),
                "currentHash", current.contentHash(), "highRisk", isHighRisk(pipeline), "impact", current.impact());
    }

    @Transactional
    public Pipeline rollback(UUID id, RollbackRequest request, Actor actor) {
        if (request == null || !request.acknowledgeDataNotReverted()) throw new ConnectionProblem("ROLLBACK_IMPACT_UNACKNOWLEDGED", "必须确认回滚不会撤销已写业务数据");
        PipelineVersion target = version(id, request.version());
        jdbc.update("UPDATE control.pipelines SET published_version=?,lifecycle='PUBLISHED',row_version=row_version+1,updated_at=now() WHERE id=?", target.version(), id);
        audit(actor, "PIPELINE_ROLLED_BACK", "pipeline", id.toString(), "将后续运行激活到历史版本 v" + target.version(),
                Map.of("businessDataReverted", false, "versionId", target.id()));
        return get(id);
    }

    public PipelineRun run(UUID id, Actor actor) { return runtime.startRun(get(id), "MANUAL", null, actor); }
    public PipelineRun start(UUID id, Actor actor) { return runtime.startStream(get(id), actor); }
    public PipelineRun stop(UUID id, SavepointRequest request, Actor actor) { return runtime.stopStream(get(id), request == null || request.drain(), actor); }
    public PipelineRun savepoint(UUID id, Actor actor) { return runtime.savepoint(get(id), actor); }
    public Pipeline resetOffsets(UUID id, OffsetResetRequest request, Actor actor) { return runtime.resetOffsets(get(id), request, actor); }
    public PipelineRun cancel(UUID runId, Actor actor) { return runtime.cancel(runId, actor); }
    public PipelineRun retry(UUID runId, Actor actor) { return runtime.retry(runId, actor); }
    public PipelineRun replayDlq(UUID runId, Actor actor) { return runtime.replayDlq(runId, actor); }
    public void cancelPreview(UUID previewId, Actor actor) { runtime.cancelPreview(previewId, actor); }

    @Transactional
    public Pipeline pause(UUID id, Actor actor) {
        Pipeline pipeline = get(id);
        if (pipeline.runStatus() == PipelineRunStatus.LIVE) runtime.stopStream(pipeline, true, actor);
        jdbc.update("UPDATE control.pipelines SET lifecycle='PAUSED',updated_at=now() WHERE id=?", id);
        audit(actor, "PIPELINE_PAUSED", "pipeline", id.toString(), "暂停管道和新运行", Map.of());
        return get(id);
    }

    @Transactional
    public Pipeline resume(UUID id, Actor actor) {
        Pipeline pipeline = get(id);
        if (pipeline.lifecycle() != PipelineLifecycle.PAUSED) throw new ConnectionProblem("PIPELINE_NOT_PAUSED", "管道未处于暂停状态");
        jdbc.update("UPDATE control.pipelines SET lifecycle=CASE WHEN published_version IS NULL THEN 'DRAFT' ELSE 'PUBLISHED' END,updated_at=now() WHERE id=?", id);
        audit(actor, "PIPELINE_RESUMED", "pipeline", id.toString(), "恢复管道运行资格", Map.of());
        return get(id);
    }

    @Transactional
    public Pipeline archive(UUID id, Actor actor) {
        Pipeline pipeline = get(id);
        if (pipeline.runStatus() == PipelineRunStatus.RUNNING || pipeline.runStatus() == PipelineRunStatus.LIVE) {
            throw new ConnectionProblem("PIPELINE_ACTIVE", "活动运行停止后才能归档");
        }
        jdbc.update("UPDATE control.pipelines SET lifecycle='ARCHIVED',archived_at=now(),updated_at=now() WHERE id=?", id);
        audit(actor, "PIPELINE_ARCHIVED", "pipeline", id.toString(), "归档管道，保留版本、运行和血缘", Map.of());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, Actor actor) {
        Pipeline pipeline = get(id);
        Integer runs = jdbc.queryForObject("SELECT count(*) FROM control.pipeline_runs WHERE pipeline_id=?", Integer.class, id);
        if (pipeline.publishedVersion() != null || runs == null || runs > 0) throw new ConnectionProblem("PIPELINE_DELETE_FORBIDDEN", "只有从未发布且没有运行记录的草稿可永久删除");
        audit(actor, "PIPELINE_DELETED", "pipeline", id.toString(), "永久删除未发布管道草稿", Map.of());
        jdbc.update("DELETE FROM control.pipelines WHERE id=?", id);
    }

    public PipelineRunPage runs(UUID id, int page, int size) {
        get(id);
        int safePage = Math.max(0, page); int safeSize = Math.max(1, Math.min(100, size));
        List<PipelineRun> rows = jdbc.query(runSelect() + " WHERE r.pipeline_id=? ORDER BY r.started_at DESC LIMIT ? OFFSET ?",
                this::mapRun, id, safeSize, safePage * safeSize);
        Long total = jdbc.queryForObject("SELECT count(*) FROM control.pipeline_runs WHERE pipeline_id=?", Long.class, id);
        return new PipelineRunPage(rows, safePage, safeSize, total == null ? 0 : total);
    }

    public RunDetail runDetail(UUID runId) {
        PipelineRun run = runById(runId);
        List<RunStage> stages = jdbc.query("SELECT * FROM control.pipeline_run_stages WHERE pipeline_run_id=? ORDER BY stage_order", this::mapStage, runId);
        List<RunEvent> events = events(runId);
        Map<String, Object> metrics = Map.of("readCount", run.readCount(), "rejectedCount", run.rejectedCount(),
                "writtenCount", run.writtenCount(), "projectionStatus", valueOr(run.projectionStatus(), "PENDING"));
        List<Map<String, Object>> logs = events.stream().map(event -> Map.<String, Object>of("level", event.status() == null ? "INFO" : event.status(),
                "message", event.message(), "occurredAt", event.occurredAt())).toList();
        return new RunDetail(run, stages, events, metrics, logs);
    }

    public PipelineRun runById(UUID runId) {
        return jdbc.query(runSelect() + " WHERE r.id=?", this::mapRun, runId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "管道运行不存在"));
    }

    public List<RunEvent> events(UUID runId) {
        return jdbc.query("SELECT * FROM control.pipeline_run_events WHERE pipeline_run_id=? ORDER BY sequence",
                (row, number) -> new RunEvent(row.getLong("sequence"), row.getString("event_type"), row.getString("status"),
                        row.getString("message"), readJson(row.getString("safe_details"), new TypeReference<Map<String, Object>>() { }),
                        instant(row, "occurred_at")), runId);
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void triggerDueSchedules() {
        jdbc.query("""
                SELECT s.pipeline_id,s.concurrency_policy FROM control.pipeline_schedules s
                JOIN control.pipelines p ON p.id=s.pipeline_id
                WHERE s.enabled=true AND s.next_run_at<=now() AND p.mode='BATCH' AND p.lifecycle='PUBLISHED'
                ORDER BY s.next_run_at LIMIT 20
                """, (row, number) -> Map.entry(row.getObject("pipeline_id", UUID.class), row.getString("concurrency_policy")))
                .forEach(schedule -> {
                    try {
                        Pipeline pipeline = get(schedule.getKey());
                        runtime.scheduledRun(pipeline, schedule.getValue(), new Actor("platform-scheduler", "平台调度器", true));
                    } catch (RuntimeException cause) {
                        audit(new Actor("platform-scheduler", "平台调度器", true), "PIPELINE_SCHEDULE_FAILED",
                                "PIPELINE", schedule.getKey().toString(), "调度触发失败",
                                Map.of("errorType", cause.getClass().getSimpleName()));
                    } finally {
                        advanceSchedule(schedule.getKey());
                    }
                });
    }

    private void createDraftFromPublished(Pipeline pipeline, Actor actor) {
        if (pipeline.publishedVersion() == null) throw new ConnectionProblem("PIPELINE_DRAFT_MISSING", "草稿不存在");
        PipelineVersion version = version(pipeline.id(), pipeline.publishedVersion());
        jdbc.update("""
                INSERT INTO control.pipeline_drafts(pipeline_id,base_version,graph,runtime,schedule,updated_by)
                VALUES (?,?,?::jsonb,?::jsonb,?::jsonb,?) ON CONFLICT(pipeline_id) DO NOTHING
                """, pipeline.id(), version.version(), writeJson(version.graph()),
                writeJson(new RuntimeSettings(2, pipeline.mode() == PipelineMode.STREAMING ? 60_000 : 0, 3, "EARLIEST", null, 5_000)),
                writeJson(new ScheduleSettings("MANUAL", null, null, "SKIP", false)), actor.id());
    }

    private PipelineGraph initialGraph(UUID pipelineId, UUID sourceId, UUID assetId, String sourceType, String template) {
        PipelineNode source = new PipelineNode("source-1", "SOURCE", "源资产", new Position(80, 180),
                Map.of("assetId", assetId, "connectionId", sourceId, "sourceType", sourceType), List.of(), List.of(), List.of());
        PipelineNode select = new PipelineNode("select-1", "SELECT", "选择字段", new Position(360, 180), Map.of("fields", List.of()), List.of(), List.of(), List.of());
        PipelineNode output = new PipelineNode("output-1", template.equals("OBJECT_RELATION") ? "ONTOLOGY_RELATION" : "ONTOLOGY_OBJECT",
                template.equals("OBJECT_RELATION") ? "关系输出" : "对象输出", new Position(660, 180), Map.of(), List.of(), List.of(), List.of());
        List<PipelineNode> nodes = template.equals("BLANK") ? List.of(source, output) : List.of(source, select, output);
        List<PipelineEdge> edges = template.equals("BLANK")
                ? List.of(new PipelineEdge("edge-1", source.id(), output.id()))
                : List.of(new PipelineEdge("edge-1", source.id(), select.id()), new PipelineEdge("edge-2", select.id(), output.id()));
        return new PipelineGraph(nodes, edges);
    }

    private List<FieldSchema> sourceSchema(UUID assetId) {
        return jdbc.query("SELECT * FROM control.data_source_asset_fields WHERE asset_id=? ORDER BY ordinal", (row, number) ->
                new FieldSchema(row.getString("name"), row.getString("inferred_type"), row.getBoolean("nullable"),
                        row.getBoolean("sensitive"), "source-1"), assetId);
    }

    private Map<String, Object> compileIr(Pipeline pipeline, PipelineGraph graph, int version) {
        List<Map<String, Object>> sources = graph.nodes().stream().filter(node -> node.type().equals("SOURCE"))
                .map(node -> Map.<String, Object>of("asset_id", pipeline.sourceAssetId(), "connection_id", pipeline.dataSourceId(), "node_id", node.id())).toList();
        List<Map<String, Object>> outputs = graph.nodes().stream().filter(node -> node.type().startsWith("ONTOLOGY_"))
                .map(node -> Map.<String, Object>of("config", node.config(), "node_id", node.id(), "type", node.type())).toList();
        return Map.of("api_version", "ontology.pipeline/v1", "graph", graph, "mode", pipeline.mode(),
                "outputs", outputs, "pipeline_id", pipeline.id(), "runtime", pipeline.draft().runtime(),
                "schedule", pipeline.draft().schedule(), "sources", sources, "version", version);
    }

    private void activateSchedule(UUID pipelineId, ScheduleSettings schedule) {
        Instant next = nextSchedule(schedule, Instant.now());
        jdbc.update("""
                UPDATE control.pipeline_schedules SET enabled=?,schedule_type=?,cron_expression=?,run_at=?,
                  concurrency_policy=?,next_run_at=?,updated_at=now() WHERE pipeline_id=?
                """, schedule.enabled(), schedule.type(), schedule.cronExpression(), schedule.runAt() == null ? null : Timestamp.from(schedule.runAt()),
                schedule.concurrencyPolicy(), next == null ? null : Timestamp.from(next), pipelineId);
    }

    private void advanceSchedule(UUID pipelineId) {
        ScheduleSettings schedule = jdbc.query("SELECT * FROM control.pipeline_schedules WHERE pipeline_id=?", (row, number) ->
                new ScheduleSettings(row.getString("schedule_type"), row.getString("cron_expression"), instant(row, "run_at"),
                        row.getString("concurrency_policy"), row.getBoolean("enabled")), pipelineId).stream().findFirst().orElse(null);
        if (schedule == null) return;
        Instant next = nextSchedule(schedule, Instant.now().plusSeconds(1));
        jdbc.update("UPDATE control.pipeline_schedules SET enabled=?,next_run_at=?,updated_at=now() WHERE pipeline_id=?",
                next != null && schedule.enabled(), next == null ? null : Timestamp.from(next), pipelineId);
    }

    private Instant nextSchedule(ScheduleSettings schedule, Instant after) {
        if (schedule == null || !schedule.enabled()) return null;
        if ("AT".equals(schedule.type())) return schedule.runAt() != null && schedule.runAt().isAfter(after) ? schedule.runAt() : null;
        if ("CRON".equals(schedule.type()) && schedule.cronExpression() != null) {
            return CronExpression.parse(schedule.cronExpression()).next(after.atZone(java.time.ZoneOffset.UTC)).toInstant();
        }
        return null;
    }

    private void indexDependencies(Pipeline pipeline, UUID versionId, PipelineGraph graph) {
        addDependency(versionId, "DATA_SOURCE", pipeline.dataSourceId().toString(), pipeline.dataSourceName(), "source-1", Map.of());
        addDependency(versionId, "DATA_ASSET", pipeline.sourceAssetId().toString(), pipeline.sourceAssetName(), "source-1", Map.of());
        for (PipelineNode node : graph.nodes()) if (node.type().equals("ONTOLOGY_OBJECT")) {
            addDependency(versionId, "ONTOLOGY_OBJECT", String.valueOf(node.config().get("objectTypeId")), node.name(), node.id(), node.config());
        } else if (node.type().equals("ONTOLOGY_RELATION")) {
            addDependency(versionId, "ONTOLOGY_RELATION", String.valueOf(node.config().get("relationTypeId")), node.name(), node.id(), node.config());
        }
    }

    private void addDependency(UUID versionId, String type, String resourceId, String resourceName, String nodeId, Map<String, Object> metadata) {
        jdbc.update("""
                INSERT INTO control.pipeline_dependencies(id,pipeline_version_id,dependency_type,resource_id,resource_name,node_id,metadata)
                VALUES (?,?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), versionId, type, resourceId, resourceName, nodeId, writeJson(metadata));
    }

    private boolean isHighRisk(Pipeline pipeline) {
        if (pipeline.publishedVersion() == null || pipeline.draft() == null) return false;
        PipelineVersion base = version(pipeline.id(), pipeline.publishedVersion());
        String baseOutput = base.graph().nodes().stream().filter(node -> node.type().startsWith("ONTOLOGY_"))
                .map(node -> node.type() + node.config().get("idField") + node.config().get("sourceIdField") + node.config().get("targetIdField")).sorted().toList().toString();
        String draftOutput = pipeline.draft().graph().nodes().stream().filter(node -> node.type().startsWith("ONTOLOGY_"))
                .map(node -> node.type() + node.config().get("idField") + node.config().get("sourceIdField") + node.config().get("targetIdField")).sorted().toList().toString();
        return !baseOutput.equals(draftOutput) || !Objects.equals(pipeline.draft().runtime().offsetPolicy(), "EARLIEST");
    }

    private String lastConnectedNode(Pipeline pipeline) {
        return pipeline.draft().graph().nodes().stream().filter(node -> node.type().startsWith("ONTOLOGY_"))
                .map(PipelineNode::id).findFirst().orElseThrow(() -> new ConnectionProblem("PIPELINE_OUTPUT_MISSING", "管道缺少输出节点"));
    }

    private PipelineGraph remapGraph(PipelineGraph source) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> ids = new LinkedHashMap<>();
        source.nodes().forEach(node -> ids.put(node.id(), node.id() + "-" + suffix));
        List<PipelineNode> nodes = source.nodes().stream().map(node -> new PipelineNode(ids.get(node.id()), node.type(), node.name(),
                node.position(), node.config(), node.inputSchema(), node.outputSchema(), node.invalidReasons())).toList();
        List<PipelineEdge> edges = source.edges().stream().map(edge -> new PipelineEdge(edge.id() + "-" + suffix,
                ids.get(edge.source()), ids.get(edge.target()))).toList();
        return new PipelineGraph(nodes, edges);
    }

    private Pipeline mapPipeline(ResultSet row, int number) throws SQLException {
        return new Pipeline(row.getObject("id", UUID.class), row.getString("name"), row.getString("description"), row.getString("template"),
                PipelineMode.valueOf(row.getString("mode")), PipelineLifecycle.valueOf(row.getString("lifecycle")),
                PipelineRunStatus.valueOf(row.getString("run_status")), row.getObject("data_source_id", UUID.class),
                row.getString("data_source_name"), row.getObject("source_asset_id", UUID.class), row.getString("source_asset_name"),
                row.getString("target_summary"), row.getString("schedule_summary"), row.getString("owner_id"), row.getString("owner_name"),
                nullableInteger(row, "published_version"), row.getLong("row_version"), instant(row, "last_run_at"),
                instant(row, "created_at"), instant(row, "updated_at"), null);
    }

    private PipelineDraft mapDraft(ResultSet row, int number) throws SQLException {
        return new PipelineDraft(readJson(row.getString("graph"), new TypeReference<PipelineGraph>() { }),
                readJson(row.getString("runtime"), new TypeReference<RuntimeSettings>() { }),
                readJson(row.getString("schedule"), new TypeReference<ScheduleSettings>() { }),
                nullableInteger(row, "base_version"), row.getLong("etag"), row.getString("updated_by"), instant(row, "updated_at"));
    }

    private PipelineVersion mapVersion(ResultSet row, int number) throws SQLException {
        return new PipelineVersion(row.getObject("id", UUID.class), row.getObject("pipeline_id", UUID.class), row.getInt("version"),
                readJson(row.getString("graph"), new TypeReference<PipelineGraph>() { }),
                readJson(row.getString("pipeline_ir"), new TypeReference<Map<String, Object>>() { }),
                readJson(row.getString("job_spec"), new TypeReference<Map<String, Object>>() { }), row.getString("content_hash"),
                readJson(row.getString("validation"), new TypeReference<List<ValidationIssue>>() { }),
                row.getString("published_by"), row.getString("published_by_name"), instant(row, "published_at"));
    }

    private PipelineProposal mapProposal(ResultSet row, int number) throws SQLException {
        return new PipelineProposal(row.getObject("id", UUID.class), row.getObject("pipeline_id", UUID.class), row.getLong("draft_etag"),
                ProposalStatus.valueOf(row.getString("status")), row.getString("risk_level"), row.getString("title"), row.getString("summary"),
                readJson(row.getString("validation"), new TypeReference<List<ValidationIssue>>() { }),
                readJson(row.getString("impact"), new TypeReference<Map<String, Object>>() { }), row.getString("submitted_by"),
                row.getString("submitted_by_name"), instant(row, "submitted_at"), row.getString("decided_by_name"),
                instant(row, "decided_at"), row.getString("decision_comment"));
    }

    private String runSelect() {
        return """
                SELECT r.*,p.name pipeline_name,v.version pipeline_version
                FROM control.pipeline_runs r JOIN control.pipelines p ON p.id=r.pipeline_id
                LEFT JOIN control.pipeline_versions v ON v.id=r.pipeline_version_id
                """;
    }

    private PipelineRun mapRun(ResultSet row, int number) throws SQLException {
        return new PipelineRun(row.getObject("id", UUID.class), row.getObject("pipeline_id", UUID.class), row.getString("pipeline_name"),
                row.getObject("pipeline_version_id", UUID.class), nullableInteger(row, "pipeline_version"), row.getObject("retry_of", UUID.class),
                row.getString("trigger_type"), row.getString("status"), row.getString("flink_job_id"), row.getString("correlation_id"),
                row.getLong("read_count"), row.getLong("written_count"), row.getLong("rejected_count"), row.getString("projection_status"),
                row.getString("savepoint_path"), readJson(row.getString("diagnostic"), new TypeReference<Map<String, Object>>() { }),
                row.getString("requested_by_name"), instant(row, "started_at"), instant(row, "completed_at"), instant(row, "updated_at"));
    }

    private RunStage mapStage(ResultSet row, int number) throws SQLException {
        return new RunStage(row.getObject("id", UUID.class), row.getInt("stage_order"), row.getString("stage_type"), row.getString("status"),
                row.getString("correlation_id"), row.getString("flink_job_id"),
                readJson(row.getString("event_position"), new TypeReference<Map<String, Object>>() { }), row.getLong("read_count"),
                row.getLong("written_count"), row.getLong("rejected_count"), instant(row, "started_at"), instant(row, "completed_at"));
    }


    private RuntimeSettings normalizeRuntime(RuntimeSettings value, PipelineMode mode) {
        int parallelism = Math.max(1, Math.min(4, value.parallelism()));
        long checkpoint = mode == PipelineMode.STREAMING ? Math.max(10_000, value.checkpointIntervalMs()) : Math.max(0, value.checkpointIntervalMs());
        return new RuntimeSettings(parallelism, checkpoint, Math.max(0, Math.min(10, value.restartAttempts())),
                valueOr(value.offsetPolicy(), mode == PipelineMode.STREAMING ? "EARLIEST" : "SNAPSHOT"),
                value.eventTimeField(), Math.max(0, value.watermarkDelayMs()));
    }

    private ScheduleSettings normalizeSchedule(ScheduleSettings value, PipelineMode mode) {
        if (mode == PipelineMode.STREAMING && value.enabled()) throw new ConnectionProblem("STREAM_SCHEDULE_INVALID", "流式管道通过启动/停止控制，不使用批处理调度");
        return new ScheduleSettings(valueOr(value.type(), "MANUAL"), value.cronExpression(), value.runAt(),
                valueOr(value.concurrencyPolicy(), "SKIP"), value.enabled());
    }

    private String targetSummary(PipelineGraph graph) {
        return graph.nodes().stream().filter(node -> node.type().startsWith("ONTOLOGY_"))
                .map(node -> valueOr(node.name(), node.type())).sorted().reduce((left, right) -> left + "、" + right).orElse("未配置输出");
    }

    private String scheduleSummary(ScheduleSettings schedule) {
        if (schedule == null || !schedule.enabled()) return "MANUAL";
        if ("CRON".equals(schedule.type())) return "CRON " + valueOr(schedule.cronExpression(), "未配置");
        return schedule.type();
    }

    private String normalizeTemplate(String value) {
        String normalized = valueOr(value, "BLANK").toUpperCase(Locale.ROOT);
        return List.of("BLANK", "DATABASE_BATCH", "FILE_BATCH", "KAFKA_STREAM", "OBJECT_RELATION", "PULSAR_STREAM").contains(normalized) ? normalized : "BLANK";
    }

    private String uniqueCopyName(String base) {
        String candidate = base + " 副本";
        int suffix = 2;
        while (Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM control.pipelines WHERE normalized_name=?", Integer.class, normalize(candidate))) > 0) {
            candidate = base + " 副本 " + suffix++;
        }
        return candidate;
    }

    private void audit(Actor actor, String action, String type, String id, String summary, Map<String, ?> diff) {
        jdbc.update("""
                INSERT INTO control.audit_events(id,actor_id,actor_name,action,resource_type,resource_id,request_id,summary,safe_diff)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), actor.id(), actor.name(), action, type, id, UUID.randomUUID(), summary, writeJson(diff));
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("JSON serialization failed", cause); }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        if (value == null) return null;
        try { return json.readValue(value, type); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("Stored Pipeline JSON is invalid", cause); }
    }

    private String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private String empty(String value) { return value == null ? "" : value.trim(); }
    private String safeDescription(String value) { return value == null ? null : value.trim().substring(0, Math.min(1000, value.trim().length())); }
    private String valueOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private Instant instant(ResultSet row, String column) throws SQLException { Timestamp value = row.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private Integer nullableInteger(ResultSet row, String column) throws SQLException { int value = row.getInt(column); return row.wasNull() ? null : value; }

    private void requireName(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 240) throw new ConnectionProblem("PIPELINE_NAME_INVALID", "管道名称长度必须为 1 到 240 个字符");
    }
}
