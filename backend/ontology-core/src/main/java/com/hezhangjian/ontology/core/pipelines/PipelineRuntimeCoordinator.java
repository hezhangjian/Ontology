package com.hezhangjian.ontology.core.pipelines;

import static com.hezhangjian.ontology.core.pipelines.PipelineModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.connections.ConnectionCrypto;
import com.hezhangjian.ontology.core.connections.ConnectionModels.RuntimeMaterial;
import com.hezhangjian.ontology.core.connections.ConnectionProblem;
import com.hezhangjian.ontology.core.connections.DataConnectionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineRuntimeCoordinator {
    private static final List<String> ACTIVE_STATUSES = List.of("COMPILING", "PROJECTING", "QUEUED", "READING", "RUNNING", "STARTING", "SUBMITTED", "TRANSFORMING", "PUBLISHING");
    private final ConnectionCrypto crypto;
    private final DataConnectionService connections;
    private final FlinkGateway flink;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PipelineProperties properties;
    private final TaskExecutor tasks;
    private PipelineService pipelines;

    public PipelineRuntimeCoordinator(ConnectionCrypto crypto, DataConnectionService connections, FlinkGateway flink,
                                      JdbcTemplate jdbc, ObjectMapper json, PipelineProperties properties,
                                      @Qualifier("applicationTaskExecutor") TaskExecutor tasks) {
        this.crypto = crypto;
        this.connections = connections;
        this.flink = flink;
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.tasks = tasks;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPipelines(@org.springframework.context.annotation.Lazy PipelineService pipelines) {
        this.pipelines = pipelines;
    }

    public PreviewRun preview(Pipeline pipeline, PipelineGraph graph, String nodeId, int limit, Actor actor) {
        UUID previewId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO control.pipeline_preview_runs(id,pipeline_id,draft_etag,node_id,status,graph,runtime,row_limit,
                  requested_by,started_at,expires_at)
                VALUES (?,?,?,?, 'SUBMITTED',?::jsonb,?::jsonb,?,?,?,?)
                """, previewId, pipeline.id(), pipeline.draft().etag(), nodeId, writeJson(graph),
                writeJson(pipeline.draft().runtime()), limit, actor.id(), Timestamp.from(now), Timestamp.from(now.plusSeconds(900)));
        tasks.execute(() -> submitPreview(previewId));
        audit(actor, "PIPELINE_PREVIEW_SUBMITTED", pipeline.id(), "提交有界 Flink 管道预览", Map.of("nodeId", nodeId, "limit", limit));
        return previewById(previewId);
    }

    public PreviewExchangeResponse exchangePreview(PreviewExchangeRequest request, String token) {
        requireServiceToken(token);
        if (request == null || request.previewId() == null || request.jobSignature() == null) {
            throw new ConnectionProblem("PREVIEW_WORKLOAD_REQUEST_INVALID", "预览工作负载请求不完整");
        }
        Map<String, Object> preview = jdbc.query("""
                SELECT pr.*,p.data_source_id,p.source_asset_id,p.mode,a.full_path source_asset_path
                FROM control.pipeline_preview_runs pr JOIN control.pipelines p ON p.id=pr.pipeline_id
                LEFT JOIN control.data_source_assets a ON a.id=p.source_asset_id
                WHERE pr.id=? AND pr.status IN ('SUBMITTED','RUNNING')
                """, (row, number) -> {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("dataSourceId", row.getObject("data_source_id", UUID.class));
            value.put("expiresAt", row.getTimestamp("expires_at").toInstant());
            value.put("graph", readJson(row.getString("graph"), new TypeReference<PipelineGraph>() { }));
            value.put("mode", row.getString("mode"));
            value.put("nodeId", row.getString("node_id"));
            value.put("pipelineId", row.getObject("pipeline_id", UUID.class));
            value.put("rowLimit", row.getInt("row_limit"));
            value.put("runtime", readJson(row.getString("runtime"), new TypeReference<RuntimeSettings>() { }));
            value.put("sourceAssetId", row.getObject("source_asset_id", UUID.class));
            value.put("sourceAssetPath", row.getString("source_asset_path"));
            return value;
        }, request.previewId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "预览授权不存在或已结束"));
        if (!((Instant) preview.get("expiresAt")).isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "预览授权已过期");
        }
        String expectedSignature = crypto.fingerprint("ontology-flink-job:v1");
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                request.jobSignature().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Flink Job 签名不匹配");
        }
        UUID sourceId = (UUID) preview.get("dataSourceId");
        RuntimeMaterial material = connections.runtimeMaterial(sourceId);
        Map<String, Object> config = new java.util.LinkedHashMap<>(material.config());
        config.put("assetId", preview.get("sourceAssetId"));
        config.put("assetPath", preview.get("sourceAssetPath"));
        config.put("connectionId", sourceId);
        config.put("consumerGroup", "ontology-preview-" + request.previewId());
        config.put("offsetPolicy", "EARLIEST");
        config.put("pipelineId", preview.get("pipelineId"));
        config.put("pipelineMode", preview.get("mode"));
        config.put("preview", true);
        config.put("runId", request.previewId());
        config.put("subscription", "ontology-preview-" + request.previewId());
        jdbc.update("UPDATE control.pipeline_preview_runs SET status='RUNNING' WHERE id=?", request.previewId());
        return new PreviewExchangeResponse(request.previewId(), material.type().name(), config, material.credential(),
                (PipelineGraph) preview.get("graph"), (RuntimeSettings) preview.get("runtime"),
                "preview:" + request.previewId(), String.valueOf(preview.get("nodeId")), (int) preview.get("rowLimit"),
                (Instant) preview.get("expiresAt"));
    }

    public void completePreview(UUID previewId, PreviewResultRequest request, String token) {
        requireServiceToken(token);
        PreviewRun preview = previewById(previewId);
        if (!List.of("RUNNING", "SUBMITTED").contains(preview.status())) return;
        Pipeline pipeline = pipelines.get(preview.pipelineId());
        PipelineGraph graph = jdbc.queryForObject("SELECT graph::text FROM control.pipeline_preview_runs WHERE id=?", (row, number) ->
                readJson(row.getString(1), new TypeReference<PipelineGraph>() { }), previewId);
        Set<String> sensitiveFields = new HashSet<>(jdbc.queryForList(
                "SELECT name FROM control.data_source_asset_fields WHERE asset_id=? AND sensitive=true", String.class,
                pipeline.sourceAssetId()));
        if (graph != null) graph.nodes().stream().filter(node -> node.type().equals("ONTOLOGY_OBJECT")).forEach(node -> {
            String objectType = String.valueOf(node.config().getOrDefault("objectTypeId", ""));
            Map<String, Object> mappings = node.config().get("mappings") instanceof Map<?, ?> raw
                    ? raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()), entry -> entry.getValue())) : Map.of();
            jdbc.queryForList("SELECT property_id FROM control.object_properties WHERE revision=(SELECT max(revision) FROM control.ontology_revisions WHERE status='ACTIVE') AND type_id=? AND sensitive=true",
                    String.class, objectType).forEach(property -> {
                        sensitiveFields.add(property);
                        Object mapped = mappings.get(property);
                        if (mapped != null) sensitiveFields.add(String.valueOf(mapped));
                    });
        });
        List<Map<String, Object>> rows = request == null || request.rows() == null ? List.of() : request.rows().stream()
                .limit(100).map(row -> maskPreview(row, sensitiveFields)).toList();
        String serialized = writeJson(rows);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw new ConnectionProblem("PREVIEW_RESULT_TOO_LARGE", "预览结果超过 1 MiB 上限");
        }
        String status = request != null && "FAILED".equals(request.status()) ? "FAILED" : "COMPLETED";
        List<FieldSchema> schema = graph == null ? List.of() : graph.nodes().stream()
                .filter(node -> node.id().equals(preview.nodeId())).flatMap(node -> node.outputSchema().stream()).toList();
        jdbc.update("""
                UPDATE control.pipeline_preview_runs SET status=?,rows=?::jsonb,schema_snapshot=?::jsonb,
                  diagnostic=?::jsonb,row_count=?,size_bytes=?,completed_at=now() WHERE id=?
                """, status, serialized, writeJson(schema), writeJson(request == null ? Map.of() : safeDetails(request.diagnostic())),
                rows.size(), serialized.getBytes(StandardCharsets.UTF_8).length, previewId);
    }

    public PreviewRun preview(UUID previewId) {
        return previewById(previewId);
    }

    @Transactional
    public PipelineRun startRun(Pipeline pipeline, String trigger, UUID retryOf, Actor actor) {
        requirePublished(pipeline);
        if (pipeline.mode() != PipelineMode.BATCH) throw new ConnectionProblem("PIPELINE_MODE_INVALID", "流式管道请使用启动操作");
        if (pipeline.lifecycle() != PipelineLifecycle.PUBLISHED) throw new ConnectionProblem("PIPELINE_NOT_RUNNABLE", "只有已发布且未暂停的管道可运行");
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM control.pipeline_runs
                WHERE pipeline_id=? AND status IN ('COMPILING','PROJECTING','QUEUED','READING','RUNNING',
                  'STARTING','SUBMITTED','TRANSFORMING','PUBLISHING')
                """, Integer.class, pipeline.id());
        if (active != null && active > 0) throw new ConnectionProblem("PIPELINE_RUN_ACTIVE", "已有活动运行，请等待完成或取消");
        UUID runId = createRun(pipeline, trigger, retryOf, actor);
        executeAfterCommit(() -> submit(runId));
        return pipelines.runById(runId);
    }

    @Transactional
    public PipelineRun startStream(Pipeline pipeline, Actor actor) {
        requirePublished(pipeline);
        if (pipeline.mode() != PipelineMode.STREAMING) throw new ConnectionProblem("PIPELINE_MODE_INVALID", "批处理管道请使用立即运行");
        Integer active = jdbc.queryForObject("SELECT count(*) FROM control.pipeline_runs WHERE pipeline_id=? AND status IN ('STARTING','RUNNING')", Integer.class, pipeline.id());
        if (active != null && active > 0) throw new ConnectionProblem("PIPELINE_STREAM_ACTIVE", "流任务已经启动");
        UUID runId = createRun(pipeline, "START", null, actor);
        executeAfterCommit(() -> submit(runId));
        return pipelines.runById(runId);
    }

    public PipelineRun scheduledRun(Pipeline pipeline, String concurrencyPolicy, Actor actor) {
        List<PipelineRun> active = jdbc.query("""
                SELECT r.*,p.name pipeline_name,v.version pipeline_version FROM control.pipeline_runs r
                JOIN control.pipelines p ON p.id=r.pipeline_id LEFT JOIN control.pipeline_versions v ON v.id=r.pipeline_version_id
                WHERE r.pipeline_id=? AND r.status IN ('COMPILING','PROJECTING','QUEUED','READING','RUNNING','STARTING','SUBMITTED','TRANSFORMING','PUBLISHING')
                ORDER BY r.started_at
                """, this::mapRun, pipeline.id());
        String policy = valueOr(concurrencyPolicy, "SKIP").toUpperCase(java.util.Locale.ROOT);
        if (active.isEmpty()) return startRun(pipeline, "SCHEDULE", null, actor);
        if (policy.equals("SKIP")) return null;
        if (policy.equals("CANCEL_PREVIOUS")) {
            active.forEach(run -> cancel(run.id(), actor));
            UUID runId = createRun(pipeline, "SCHEDULE", null, actor);
            executeAfterCommit(() -> submit(runId));
            return pipelines.runById(runId);
        }
        if (policy.equals("QUEUE")) {
            UUID runId = createRun(pipeline, "SCHEDULE", null, actor);
            jdbc.update("UPDATE control.pipeline_runs SET status='QUEUED',updated_at=now() WHERE id=?", runId);
            event(runId, "RUN_QUEUED", "QUEUED", "按调度并发策略等待前一运行结束", Map.of());
            return pipelines.runById(runId);
        }
        throw new ConnectionProblem("SCHEDULE_CONCURRENCY_INVALID", "并发策略必须为 SKIP、QUEUE 或 CANCEL_PREVIOUS");
    }

    public PipelineRun stopStream(Pipeline pipeline, boolean drain, Actor actor) {
        PipelineRun run = activeRun(pipeline.id());
        if (run.flinkJobId() == null) throw new ConnectionProblem("FLINK_JOB_PENDING", "Flink Job 尚未提交，请稍后重试");
        String requestId = flink.stopWithSavepoint(run.flinkJobId(), drain);
        jdbc.update("UPDATE control.pipeline_runs SET status='STOPPING',savepoint_path=?,updated_at=now() WHERE id=?", "pending:" + requestId, run.id());
        event(run.id(), "SAVEPOINT_STOP_REQUESTED", "STOPPING", "已请求 stop-with-savepoint", Map.of("drain", drain, "requestId", requestId));
        audit(actor, "PIPELINE_STREAM_STOPPED", pipeline.id(), "请求流任务 stop-with-savepoint", Map.of("runId", run.id(), "drain", drain));
        return pipelines.runById(run.id());
    }

    public PipelineRun savepoint(Pipeline pipeline, Actor actor) {
        PipelineRun run = activeRun(pipeline.id());
        if (run.flinkJobId() == null) throw new ConnectionProblem("FLINK_JOB_PENDING", "Flink Job 尚未提交，请稍后重试");
        String requestId = flink.triggerSavepoint(run.flinkJobId());
        jdbc.update("INSERT INTO control.pipeline_checkpoints(id,pipeline_run_id,checkpoint_type,external_id,status) VALUES (?,?,'SAVEPOINT',?,'TRIGGERED')",
                UUID.randomUUID(), run.id(), requestId);
        event(run.id(), "SAVEPOINT_REQUESTED", run.status(), "已请求 Flink savepoint", Map.of("requestId", requestId));
        audit(actor, "PIPELINE_SAVEPOINT_REQUESTED", pipeline.id(), "请求流任务 savepoint", Map.of("runId", run.id()));
        return pipelines.runById(run.id());
    }

    @Transactional
    public Pipeline resetOffsets(Pipeline pipeline, OffsetResetRequest request, Actor actor) {
        if (!actor.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "重置消费位置需要 Admin 权限");
        if (pipeline.mode() != PipelineMode.STREAMING) throw new ConnectionProblem("OFFSET_RESET_MODE_INVALID", "只有流式管道可重置消费位置");
        if (request == null || !request.acknowledgeDuplicateOrLossRisk()) {
            throw new ConnectionProblem("OFFSET_RESET_RISK_UNACKNOWLEDGED", "必须确认重置可能产生重复或丢失的数据范围");
        }
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM control.pipeline_runs WHERE pipeline_id=?
                AND status IN ('COMPILING','QUEUED','READING','RUNNING','STARTING','STOPPING','SUBMITTED','TRANSFORMING','PUBLISHING')
                """, Integer.class, pipeline.id());
        if (active != null && active > 0) throw new ConnectionProblem("OFFSET_RESET_REQUIRES_STOP", "先 stop-with-savepoint 停止流任务");
        String savepoint = jdbc.query("""
                SELECT savepoint_path FROM control.pipeline_runs WHERE pipeline_id=? AND status='STOPPED'
                AND savepoint_path IS NOT NULL AND savepoint_path NOT LIKE 'pending:%' ORDER BY completed_at DESC LIMIT 1
                """, (row, number) -> row.getString(1), pipeline.id()).stream().findFirst()
                .orElseThrow(() -> new ConnectionProblem("OFFSET_RESET_SAVEPOINT_REQUIRED", "重置前必须完成 stop-with-savepoint"));
        String position = valueOr(request.position(), "EARLIEST").toUpperCase(java.util.Locale.ROOT);
        if (!List.of("EARLIEST", "LATEST", "SPECIFIC_OFFSETS", "TIMESTAMP").contains(position)) {
            throw new ConnectionProblem("OFFSET_RESET_POSITION_INVALID", "消费位置必须为 EARLIEST、LATEST、TIMESTAMP 或 SPECIFIC_OFFSETS");
        }
        Map<String, Object> impact = new java.util.LinkedHashMap<>();
        impact.put("position", position);
        impact.put("savepoint", savepoint);
        impact.put("specificOffsets", request.specificOffsets() == null ? Map.of() : request.specificOffsets());
        impact.put("timestamp", request.timestamp() == null ? "" : request.timestamp().toString());
        jdbc.update("""
                UPDATE control.pipeline_drafts SET runtime=jsonb_set(runtime,'{offsetPolicy}',to_jsonb(?::text)),
                  etag=etag+1,updated_by=?,updated_at=now() WHERE pipeline_id=?
                """, position, actor.id(), pipeline.id());
        audit(actor, "PIPELINE_OFFSETS_RESET", pipeline.id(), "登记高风险消费位置重置，发布后对后续运行生效", impact);
        return pipelines.get(pipeline.id());
    }

    public PipelineRun cancel(UUID runId, Actor actor) {
        PipelineRun run = pipelines.runById(runId);
        if (!ACTIVE_STATUSES.contains(run.status()) && !run.status().equals("STOPPING")) throw new ConnectionProblem("PIPELINE_RUN_NOT_ACTIVE", "运行已结束，不能取消");
        if (run.flinkJobId() != null) flink.cancel(run.flinkJobId());
        jdbc.update("UPDATE control.pipeline_runs SET status='CANCELLING',updated_at=now() WHERE id=?", runId);
        event(runId, "CANCEL_REQUESTED", "CANCELLING", "已请求取消 Flink Job；已投影对象不会自动删除", Map.of());
        audit(actor, "PIPELINE_RUN_CANCELLED", run.pipelineId(), "请求取消管道运行", Map.of("runId", runId, "businessDataReverted", false));
        return pipelines.runById(runId);
    }

    public PipelineRun retry(UUID runId, Actor actor) {
        PipelineRun failed = pipelines.runById(runId);
        if (!List.of("CANCELLED", "DEGRADED", "FAILED").contains(failed.status())) throw new ConnectionProblem("PIPELINE_RETRY_INVALID", "只有取消、降级或失败运行可重试");
        Pipeline pipeline = pipelines.get(failed.pipelineId());
        return startRun(pipeline, "RETRY", runId, actor);
    }

    public PipelineRun replayDlq(UUID runId, Actor actor) {
        if (!actor.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DLQ 重放需要 Admin 权限");
        PipelineRun source = pipelines.runById(runId);
        UUID replayId = createRun(pipelines.get(source.pipelineId()), "DLQ_REPLAY", runId, actor);
        event(replayId, "DLQ_REPLAY_CREATED", "SUBMITTED", "创建 DLQ 重放运行，保持原发布版本", Map.of("sourceRunId", runId));
        executeAfterCommit(() -> submit(replayId));
        return pipelines.runById(replayId);
    }

    public void cancelPreview(UUID previewId, Actor actor) {
        PreviewRun preview = previewById(previewId);
        if (!preview.status().equals("RUNNING")) return;
        if (preview.flinkJobId() != null) flink.cancel(preview.flinkJobId());
        jdbc.update("UPDATE control.pipeline_preview_runs SET status='CANCELLED',completed_at=now() WHERE id=?", previewId);
        audit(actor, "PIPELINE_PREVIEW_CANCELLED", preview.pipelineId(), "取消管道预览", Map.of("previewId", previewId));
    }

    public WorkloadExchangeResponse exchange(WorkloadExchangeRequest request, String token) {
        requireServiceToken(token);
        if (request == null || request.runId() == null || request.jobSignature() == null) throw new ConnectionProblem("WORKLOAD_REQUEST_INVALID", "工作负载请求不完整");
        Map<String, Object> grant = jdbc.query("""
                SELECT g.*,r.pipeline_id,r.pipeline_version_id,r.correlation_id,r.status run_status,p.data_source_id,p.source_asset_id,
                  p.mode pipeline_mode,a.full_path source_asset_path,v.version pipeline_version,v.graph,v.job_spec,v.pipeline_ir
                FROM control.workload_credential_grants g
                JOIN control.pipeline_runs r ON r.id=g.pipeline_run_id
                JOIN control.pipelines p ON p.id=r.pipeline_id
                JOIN control.pipeline_versions v ON v.id=r.pipeline_version_id
                LEFT JOIN control.data_source_assets a ON a.id=p.source_asset_id
                WHERE g.pipeline_run_id=? AND g.status='ACTIVE'
                """, (row, number) -> grantRow(row), request.runId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "运行授权不存在或已撤销"));
        Instant expiresAt = (Instant) grant.get("expiresAt");
        if (!expiresAt.isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "运行授权已过期");
        if (!MessageDigest.isEqual(String.valueOf(grant.get("jobSignature")).getBytes(StandardCharsets.UTF_8), request.jobSignature().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Flink Job 签名不匹配");
        }
        if (!ACTIVE_STATUSES.contains(String.valueOf(grant.get("status")))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "运行状态不允许凭据交换");
        UUID sourceId = (UUID) grant.get("dataSourceId");
        RuntimeMaterial material = connections.runtimeMaterial(sourceId);
        Map<String, Object> config = new java.util.LinkedHashMap<>(material.config());
        config.put("assetId", grant.get("sourceAssetId"));
        config.put("assetPath", grant.get("sourceAssetPath"));
        config.put("connectionId", sourceId);
        config.put("consumerGroup", "ontology-" + grant.get("pipelineId") + "-" + grant.get("pipelineVersion"));
        Object offsetPolicy = "EARLIEST";
        if (((Map<?, ?>) grant.get("pipelineIr")).get("runtime") instanceof Map<?, ?> runtime && runtime.get("offsetPolicy") != null) {
            offsetPolicy = runtime.get("offsetPolicy");
        }
        config.put("offsetPolicy", offsetPolicy);
        config.put("pipelineId", grant.get("pipelineId"));
        config.put("pipelineMode", grant.get("pipelineMode"));
        config.put("pipelineVersion", grant.get("pipelineVersion"));
        config.put("runId", request.runId());
        config.put("subscription", "ontology-" + grant.get("pipelineId") + "-" + grant.get("pipelineVersion"));
        jdbc.update("UPDATE control.workload_credential_grants SET exchanged_at=now() WHERE id=?", grant.get("grantId"));
        event(request.runId(), "CREDENTIAL_EXCHANGED", "STARTING", "Flink 工作负载已交换短期运行凭据", Map.of("scopeHash", grant.get("scopeHash")));
        @SuppressWarnings("unchecked") Map<String, Object> ir = (Map<String, Object>) grant.get("pipelineIr");
        RuntimeSettings runtime = json.convertValue(ir.get("runtime"), RuntimeSettings.class);
        return new WorkloadExchangeResponse((UUID) grant.get("grantId"), request.runId(), material.type().name(), config,
                material.credential(), (PipelineGraph) grant.get("graph"), runtime, properties.platformTopic(),
                String.valueOf(grant.get("correlationId")), expiresAt);
    }

    public void progress(UUID runId, RuntimeProgressRequest request, String token) {
        requireServiceToken(token);
        PipelineRun run = pipelines.runById(runId);
        if (!ACTIVE_STATUSES.contains(run.status()) && !run.status().equals("STOPPING")) throw new ConnectionProblem("PIPELINE_RUN_NOT_ACTIVE", "运行已结束");
        String phase = allowedPhase(request.phase());
        jdbc.update("""
                UPDATE control.pipeline_runs SET status=?,read_count=GREATEST(read_count,?),written_count=GREATEST(written_count,?),
                  rejected_count=GREATEST(rejected_count,?),updated_at=now() WHERE id=?
                """, phase, Math.max(0, request.readCount()), Math.max(0, request.writtenCount()), Math.max(0, request.rejectedCount()), runId);
        int order = switch (phase) { case "READING" -> 2; case "TRANSFORMING" -> 3; case "PUBLISHING" -> 4; default -> 1; };
        jdbc.update("""
                INSERT INTO control.pipeline_run_stages(id,pipeline_run_id,stage_order,stage_type,status,correlation_id,flink_job_id,
                  read_count,written_count,rejected_count,started_at)
                VALUES (?,?,?,?,?,?,?, ?,?,?,now()) ON CONFLICT(pipeline_run_id,stage_order) DO UPDATE SET
                  stage_type=excluded.stage_type,status=excluded.status,flink_job_id=excluded.flink_job_id,
                  read_count=GREATEST(control.pipeline_run_stages.read_count,excluded.read_count),
                  written_count=GREATEST(control.pipeline_run_stages.written_count,excluded.written_count),
                  rejected_count=GREATEST(control.pipeline_run_stages.rejected_count,excluded.rejected_count)
                """, UUID.randomUUID(), runId, order, phase, "RUNNING", run.correlationId(), run.flinkJobId(),
                Math.max(0, request.readCount()), Math.max(0, request.writtenCount()), Math.max(0, request.rejectedCount()));
        event(runId, "RUNTIME_PROGRESS", phase, valueOr(request.message(), "Flink 运行进度"), safeDetails(request.safeDetails()));
    }

    public void projectionAck(ProjectionAckRequest request, String token) {
        requireServiceToken(token);
        if (request == null || request.runId() == null || request.correlationId() == null) {
            throw new ConnectionProblem("PROJECTION_ACK_INVALID", "Projection ack 请求不完整");
        }
        PipelineRun run = pipelines.runById(request.runId());
        if (!run.correlationId().equals(request.correlationId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Projection ack correlation ID 不匹配");
        }
        jdbc.update("""
                UPDATE control.projection_batches SET acknowledged_events=GREATEST(acknowledged_events,?),
                  failed_events=GREATEST(failed_events,?),status=?,completed_at=CASE WHEN ? IN ('COMPLETED','DEGRADED') THEN now() ELSE completed_at END
                WHERE pipeline_run_id=? AND correlation_id=?
                """, Math.max(0, request.acknowledgedEvents()), Math.max(0, request.failedEvents()),
                valueOr(request.status(), "PENDING"), valueOr(request.status(), "PENDING"), request.runId(), request.correlationId());
        if (run.status().equals("PROJECTING")) synchronizeProjection(run);
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void synchronizeRuns() {
        dispatchQueuedRuns();
        List<PipelineRun> runs = jdbc.query("""
                SELECT r.*,p.name pipeline_name,v.version pipeline_version
                FROM control.pipeline_runs r JOIN control.pipelines p ON p.id=r.pipeline_id
                LEFT JOIN control.pipeline_versions v ON v.id=r.pipeline_version_id
                WHERE r.status IN ('CANCELLING','COMPILING','PROJECTING','PUBLISHING','QUEUED','READING','RUNNING','STARTING','STOPPING','SUBMITTED','TRANSFORMING')
                """, this::mapRun);
        for (PipelineRun run : runs) {
            try { synchronize(run); }
            catch (RuntimeException cause) { fail(run.id(), "FLINK_SYNC_FAILED", safeMessage(cause)); }
        }
        synchronizePreviews();
    }

    private void dispatchQueuedRuns() {
        jdbc.query("""
                SELECT r.id FROM control.pipeline_runs r WHERE r.status='QUEUED' AND r.flink_job_id IS NULL
                AND NOT EXISTS (SELECT 1 FROM control.pipeline_runs active WHERE active.pipeline_id=r.pipeline_id
                  AND active.id<>r.id AND active.started_at<r.started_at
                  AND active.status IN ('COMPILING','PROJECTING','READING','RUNNING','STARTING','SUBMITTED','TRANSFORMING','PUBLISHING'))
                AND r.id=(SELECT oldest.id FROM control.pipeline_runs oldest WHERE oldest.pipeline_id=r.pipeline_id
                  AND oldest.status='QUEUED' ORDER BY oldest.started_at LIMIT 1)
                """, (row, number) -> row.getObject(1, UUID.class)).forEach(runId -> tasks.execute(() -> submit(runId)));
    }

    private void synchronizePreviews() {
        jdbc.query("""
                SELECT id,flink_job_id,status FROM control.pipeline_preview_runs
                WHERE status IN ('SUBMITTED','RUNNING') AND expires_at>now() AND flink_job_id IS NOT NULL
                """, (row, number) -> Map.of("id", row.getObject("id", UUID.class),
                "jobId", row.getString("flink_job_id"), "status", row.getString("status"))).forEach(preview -> {
            try {
                String state = flink.state(String.valueOf(preview.get("jobId")));
                if (state.equals("FAILED") || state.equals("CANCELED")) {
                    jdbc.update("""
                            UPDATE control.pipeline_preview_runs SET status='FAILED',diagnostic=?::jsonb,completed_at=now() WHERE id=?
                            """, writeJson(Map.of("stage", "FLINK_PREVIEW", "reason", "Flink preview " + state,
                            "recoveryAction", "检查节点配置和源资产后重新预览")), preview.get("id"));
                } else if (state.equals("FINISHED") && !"COMPLETED".equals(preview.get("status"))) {
                    jdbc.update("UPDATE control.pipeline_preview_runs SET status='COMPLETED',completed_at=coalesce(completed_at,now()) WHERE id=?",
                            preview.get("id"));
                }
            } catch (RuntimeException ignored) {
                // The next scheduler pass retries transient Flink REST failures without resubmitting the preview.
            }
        });
        jdbc.update("""
                UPDATE control.pipeline_preview_runs SET status='EXPIRED',completed_at=coalesce(completed_at,now())
                WHERE status IN ('SUBMITTED','RUNNING') AND expires_at<=now()
                """);
    }

    private void submitPreview(UUID previewId) {
        try {
            RuntimeSettings runtime = jdbc.queryForObject("SELECT runtime::text FROM control.pipeline_preview_runs WHERE id=?",
                    (row, number) -> readJson(row.getString(1), new TypeReference<RuntimeSettings>() { }), previewId);
            String jobId = flink.submitPreview(previewId, runtime == null ? 1 : runtime.parallelism());
            jdbc.update("UPDATE control.pipeline_preview_runs SET status='RUNNING',flink_job_id=? WHERE id=?", jobId, previewId);
        } catch (RuntimeException cause) {
            jdbc.update("""
                    UPDATE control.pipeline_preview_runs SET status='FAILED',diagnostic=?::jsonb,completed_at=now() WHERE id=?
                    """, writeJson(Map.of("stage", "FLINK_SUBMISSION", "reason", safeMessage(cause),
                    "recoveryAction", "确认 Flink 可用后重新提交预览")), previewId);
        }
    }

    private void submit(UUID runId) {
        try {
            PipelineRun run = pipelines.runById(runId);
            Pipeline pipeline = pipelines.get(run.pipelineId());
            PipelineVersion version = pipelines.version(pipeline.id(), Objects.requireNonNull(run.pipelineVersion()));
            String signature = String.valueOf(version.jobSpec().get("jarSignature"));
            Instant expires = Instant.now().plus(properties.grantTtl());
            jdbc.update("""
                    INSERT INTO control.workload_credential_grants(id,pipeline_run_id,data_source_id,scope_hash,job_signature,expires_at)
                    VALUES (?,?,?,?,?,?)
                    """, UUID.randomUUID(), runId, pipeline.dataSourceId(), crypto.fingerprint(Map.of("asset", pipeline.sourceAssetId(), "connection", pipeline.dataSourceId(), "run", runId)),
                    signature, Timestamp.from(expires));
            jdbc.update("UPDATE control.pipeline_runs SET status='COMPILING',updated_at=now() WHERE id=?", runId);
            event(runId, "FLINK_SUBMISSION_STARTED", "COMPILING", "上传受控签名 JAR 并编译 Pipeline IR", Map.of("version", run.pipelineVersion()));
            RuntimeSettings immutableRuntime = json.convertValue(version.pipelineIr().get("runtime"), RuntimeSettings.class);
            String restorePath = null;
            if (pipeline.mode() == PipelineMode.STREAMING) {
                restorePath = jdbc.query("""
                        SELECT savepoint_path FROM control.pipeline_runs WHERE pipeline_id=? AND status='STOPPED'
                        AND savepoint_path IS NOT NULL AND savepoint_path NOT LIKE 'pending:%'
                        ORDER BY completed_at DESC LIMIT 1
                        """, (row, number) -> row.getString(1), pipeline.id()).stream().findFirst().orElse(null);
            }
            String jobId = flink.submit(runId, immutableRuntime.parallelism(), restorePath);
            jdbc.update("UPDATE control.pipeline_runs SET status='STARTING',flink_job_id=?,updated_at=now() WHERE id=?", jobId, runId);
            jdbc.update("UPDATE control.pipelines SET run_status='RUNNING',last_run_at=now(),updated_at=now() WHERE id=?", pipeline.id());
            event(runId, "FLINK_JOB_SUBMITTED", "STARTING", "Flink Job 已异步提交", Map.of("flinkJobId", jobId));
        } catch (RuntimeException cause) {
            fail(runId, "FLINK_SUBMISSION_FAILED", safeMessage(cause));
        }
    }

    private void synchronize(PipelineRun run) {
        if (run.flinkJobId() == null) return;
        String state = flink.state(run.flinkJobId());
        if (state.equals("RUNNING") && List.of("COMPILING", "QUEUED", "STARTING", "SUBMITTED").contains(run.status())) {
            Pipeline pipeline = pipelines.get(run.pipelineId());
            jdbc.update("UPDATE control.pipeline_runs SET status='RUNNING',updated_at=now() WHERE id=?", run.id());
            jdbc.update("UPDATE control.pipelines SET run_status=?,updated_at=now() WHERE id=?", pipeline.mode() == PipelineMode.STREAMING ? "LIVE" : "RUNNING", pipeline.id());
            event(run.id(), "FLINK_JOB_RUNNING", "RUNNING", "Flink Job 已进入运行态", Map.of());
        } else if (state.equals("FINISHED")) {
            Pipeline pipeline = pipelines.get(run.pipelineId());
            if (pipeline.mode() == PipelineMode.STREAMING || run.status().equals("STOPPING")) {
                completeStopped(run);
            } else {
                beginProjection(run);
            }
        } else if (state.equals("CANCELED")) {
            jdbc.update("UPDATE control.pipeline_runs SET status='CANCELLED',completed_at=now(),updated_at=now() WHERE id=?", run.id());
            jdbc.update("UPDATE control.pipelines SET run_status='FAILED',updated_at=now() WHERE id=?", run.pipelineId());
            event(run.id(), "FLINK_JOB_CANCELLED", "CANCELLED", "Flink Job 已取消；已投影对象保持不变", Map.of());
        } else if (state.equals("FAILED")) {
            fail(run.id(), "FLINK_JOB_FAILED", "Flink Job 执行失败，请打开运行事件和受控日志诊断");
        } else if (run.status().equals("PROJECTING")) {
            synchronizeProjection(run);
        }
    }

    private void beginProjection(PipelineRun run) {
        if (run.status().equals("PROJECTING")) { synchronizeProjection(run); return; }
        jdbc.update("UPDATE control.pipeline_runs SET status='PROJECTING',projection_status='PENDING',updated_at=now() WHERE id=?", run.id());
        jdbc.update("""
                INSERT INTO control.projection_batches(id,pipeline_run_id,correlation_id,expected_events,status)
                VALUES (?,?,?,?,'PENDING') ON CONFLICT(pipeline_run_id,correlation_id) DO NOTHING
                """, UUID.randomUUID(), run.id(), run.correlationId(), run.writtenCount());
        event(run.id(), "PROJECTION_WAIT_STARTED", "PROJECTING", "Flink 发布完成，等待 Projection batch ack", Map.of("expectedEvents", run.writtenCount()));
        synchronizeProjection(pipelines.runById(run.id()));
    }

    private void synchronizeProjection(PipelineRun run) {
        long expected = run.writtenCount();
        Long acknowledged = jdbc.queryForObject("SELECT count(*) FROM control.projection_ledger WHERE correlation_id=? AND status IN ('PROJECTED','STALE')", Long.class, run.correlationId());
        Long failed = jdbc.queryForObject("SELECT count(*) FROM control.projection_ledger WHERE correlation_id=? AND status IN ('DEGRADED','DLQ')", Long.class, run.correlationId());
        long ack = acknowledged == null ? 0 : acknowledged;
        long errors = failed == null ? 0 : failed;
        jdbc.update("UPDATE control.projection_batches SET acknowledged_events=?,failed_events=?,status=? WHERE pipeline_run_id=?",
                ack, errors, errors > 0 ? "DEGRADED" : ack >= expected ? "COMPLETED" : "PENDING", run.id());
        if (errors > 0) {
            jdbc.update("UPDATE control.pipeline_runs SET status='DEGRADED',projection_status='DEGRADED',completed_at=now(),updated_at=now() WHERE id=?", run.id());
            jdbc.update("UPDATE control.pipelines SET run_status='DEGRADED',updated_at=now() WHERE id=?", run.pipelineId());
            event(run.id(), "PROJECTION_DEGRADED", "DEGRADED", "Projection 存在失败；图数据不会因搜索失败回滚", Map.of("failedEvents", errors));
        } else if (ack >= expected) {
            jdbc.update("UPDATE control.pipeline_runs SET status='COMPLETED',projection_status='COMPLETED',completed_at=now(),updated_at=now() WHERE id=?", run.id());
            jdbc.update("UPDATE control.pipelines SET run_status='HEALTHY',updated_at=now() WHERE id=?", run.pipelineId());
            event(run.id(), "PIPELINE_RUN_COMPLETED", "COMPLETED", "Flink 与 Projection 均已完成", Map.of("acknowledgedEvents", ack));
        }
    }

    private void completeStopped(PipelineRun run) {
        String savepoint = resolveSavepoint(run);
        if (savepoint != null && savepoint.startsWith("pending:")) return;
        jdbc.update("UPDATE control.pipeline_runs SET status='STOPPED',completed_at=now(),updated_at=now() WHERE id=?", run.id());
        jdbc.update("UPDATE control.pipelines SET run_status='HEALTHY',updated_at=now() WHERE id=?", run.pipelineId());
        event(run.id(), "STREAM_STOPPED", "STOPPED", "流任务已停止，保留 savepoint 和历史状态", Map.of("savepoint", valueOr(savepoint, "Flink managed")));
    }

    private String resolveSavepoint(PipelineRun run) {
        String path = run.savepointPath();
        if (path == null || run.flinkJobId() == null || !path.startsWith("pending:")) return path;
        String requestId = path.substring("pending:".length());
        try {
            Map<String, Object> response = flink.savepointStatus(run.flinkJobId(), requestId);
            Object operation = response.get("operation");
            if (operation instanceof Map<?, ?> details && details.get("location") != null) {
                path = String.valueOf(details.get("location"));
                jdbc.update("UPDATE control.pipeline_runs SET savepoint_path=? WHERE id=?", path, run.id());
                jdbc.update("""
                        INSERT INTO control.pipeline_checkpoints(id,pipeline_run_id,checkpoint_type,external_id,location,status)
                        VALUES (?,?,'SAVEPOINT',?,?,'COMPLETED')
                        """, UUID.randomUUID(), run.id(), requestId, path);
            }
        } catch (RuntimeException ignored) {
            // Stop completion remains diagnosable; the next explicit savepoint operation can recover it.
        }
        return path;
    }

    private UUID createRun(Pipeline pipeline, String trigger, UUID retryOf, Actor actor) {
        int versionNumber = Objects.requireNonNull(pipeline.publishedVersion());
        PipelineVersion version = pipelines.version(pipeline.id(), versionNumber);
        UUID runId = UUID.randomUUID();
        String correlationId = "pipeline:" + pipeline.id() + ":run:" + runId;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO control.pipeline_runs(id,pipeline_id,pipeline_version_id,retry_of,trigger_type,status,correlation_id,
                  requested_by,requested_by_name,started_at,updated_at) VALUES (?,?,?,?,?,'SUBMITTED',?,?,?,?,?)
                """, runId, pipeline.id(), version.id(), retryOf, trigger, correlationId, actor.id(), actor.name(), Timestamp.from(now), Timestamp.from(now));
        event(runId, "RUN_CREATED", "SUBMITTED", "创建异步管道运行并固定到发布版本 v" + versionNumber,
                Map.of("retryOf", retryOf == null ? "" : retryOf.toString(), "versionId", version.id()));
        audit(actor, "PIPELINE_RUN_CREATED", pipeline.id(), "创建管道运行", Map.of("runId", runId, "trigger", trigger, "version", versionNumber));
        return runId;
    }

    private void fail(UUID runId, String code, String reason) {
        PipelineRun run;
        try { run = pipelines.runById(runId); }
        catch (RuntimeException ignored) { return; }
        if (List.of("CANCELLED", "COMPLETED", "DEGRADED", "FAILED", "STOPPED").contains(run.status())) return;
        jdbc.update("""
                UPDATE control.pipeline_runs SET status='FAILED',diagnostic=?::jsonb,completed_at=now(),updated_at=now() WHERE id=?
                """, writeJson(Map.of("code", code, "reason", reason, "requestId", UUID.randomUUID(),
                "recoveryAction", "修复配置或依赖后使用重试创建新 run ID")), runId);
        jdbc.update("UPDATE control.pipelines SET run_status='FAILED',updated_at=now() WHERE id=?", run.pipelineId());
        event(runId, "RUN_FAILED", "FAILED", reason, Map.of("code", code));
    }

    private Map<String, Object> grantRow(ResultSet row) throws SQLException {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("correlationId", row.getString("correlation_id"));
        value.put("dataSourceId", row.getObject("data_source_id", UUID.class));
        value.put("expiresAt", row.getTimestamp("expires_at").toInstant());
        value.put("grantId", row.getObject("id", UUID.class));
        value.put("graph", readJson(row.getString("graph"), new TypeReference<PipelineGraph>() { }));
        value.put("jobSignature", row.getString("job_signature"));
        value.put("pipelineId", row.getObject("pipeline_id", UUID.class));
        value.put("pipelineIr", readJson(row.getString("pipeline_ir"), new TypeReference<Map<String, Object>>() { }));
        value.put("pipelineMode", row.getString("pipeline_mode"));
        value.put("pipelineVersion", row.getInt("pipeline_version"));
        value.put("scopeHash", row.getString("scope_hash"));
        value.put("sourceAssetId", row.getObject("source_asset_id", UUID.class));
        value.put("sourceAssetPath", row.getString("source_asset_path"));
        value.put("status", row.getString("run_status"));
        return value;
    }

    private PreviewRun previewById(UUID id) {
        return jdbc.query("SELECT * FROM control.pipeline_preview_runs WHERE id=?", (row, number) -> new PreviewRun(
                row.getObject("id", UUID.class), row.getObject("pipeline_id", UUID.class), row.getLong("draft_etag"),
                row.getString("node_id"), row.getString("status"), row.getString("flink_job_id"),
                readJson(row.getString("rows"), new TypeReference<List<Map<String, Object>>>() { }),
                readJson(row.getString("schema_snapshot"), new TypeReference<List<FieldSchema>>() { }),
                readJson(row.getString("diagnostic"), new TypeReference<Map<String, Object>>() { }), instant(row, "started_at"),
                instant(row, "completed_at"), instant(row, "expires_at")), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "预览不存在"));
    }

    private PipelineRun activeRun(UUID pipelineId) {
        return jdbc.query("""
                SELECT r.*,p.name pipeline_name,v.version pipeline_version FROM control.pipeline_runs r
                JOIN control.pipelines p ON p.id=r.pipeline_id LEFT JOIN control.pipeline_versions v ON v.id=r.pipeline_version_id
                WHERE r.pipeline_id=? AND r.status IN ('COMPILING','QUEUED','READING','RUNNING','STARTING','SUBMITTED','TRANSFORMING','PUBLISHING')
                ORDER BY r.started_at DESC LIMIT 1
                """, this::mapRun, pipelineId).stream().findFirst()
                .orElseThrow(() -> new ConnectionProblem("PIPELINE_RUN_NOT_ACTIVE", "没有活动运行"));
    }

    private PipelineRun mapRun(ResultSet row, int number) throws SQLException {
        return new PipelineRun(row.getObject("id", UUID.class), row.getObject("pipeline_id", UUID.class), row.getString("pipeline_name"),
                row.getObject("pipeline_version_id", UUID.class), nullableInteger(row, "pipeline_version"), row.getObject("retry_of", UUID.class),
                row.getString("trigger_type"), row.getString("status"), row.getString("flink_job_id"), row.getString("correlation_id"),
                row.getLong("read_count"), row.getLong("written_count"), row.getLong("rejected_count"), row.getString("projection_status"),
                row.getString("savepoint_path"), readJson(row.getString("diagnostic"), new TypeReference<Map<String, Object>>() { }),
                row.getString("requested_by_name"), instant(row, "started_at"), instant(row, "completed_at"), instant(row, "updated_at"));
    }

    private void event(UUID runId, String type, String status, String message, Map<String, Object> details) {
        Long sequence = jdbc.queryForObject("SELECT coalesce(max(sequence),0)+1 FROM control.pipeline_run_events WHERE pipeline_run_id=?", Long.class, runId);
        try {
            jdbc.update("INSERT INTO control.pipeline_run_events(id,pipeline_run_id,sequence,event_type,status,message,safe_details) VALUES (?,?,?,?,?,?,?::jsonb)",
                    UUID.randomUUID(), runId, sequence == null ? 1 : sequence, type, status, message.substring(0, Math.min(1000, message.length())), writeJson(details));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // A concurrent status synchronizer may win the sequence; the authoritative run state is retained.
        }
    }

    private void audit(Actor actor, String action, UUID pipelineId, String summary, Map<String, ?> diff) {
        jdbc.update("""
                INSERT INTO control.audit_events(id,actor_id,actor_name,action,resource_type,resource_id,request_id,summary,safe_diff)
                VALUES (?,?,?,?, 'pipeline',?,?,?,?::jsonb)
                """, UUID.randomUUID(), actor.id(), actor.name(), action, pipelineId.toString(), UUID.randomUUID(), summary, writeJson(diff));
    }

    private void requirePublished(Pipeline pipeline) {
        if (pipeline.publishedVersion() == null) throw new ConnectionProblem("PIPELINE_NOT_PUBLISHED", "发布不可变版本后才能运行");
    }

    private void requireServiceToken(String token) {
        byte[] expected = properties.workloadToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "工作负载身份无效");
    }

    private String allowedPhase(String phase) {
        String value = phase == null ? "RUNNING" : phase.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PUBLISHING", "READING", "RUNNING", "TRANSFORMING").contains(value)) throw new ConnectionProblem("RUNTIME_PHASE_INVALID", "运行阶段无效");
        return value;
    }

    private Map<String, Object> safeDetails(Map<String, Object> value) {
        if (value == null) return Map.of();
        String serialized = writeJson(value).toLowerCase(java.util.Locale.ROOT);
        if (serialized.matches(".*(password|secret|token|credential|accesskey|privatekey).*")) return Map.of("redacted", true);
        return value;
    }

    private String safeMessage(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return cause.getClass().getSimpleName();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("password") || lower.contains("secret") || lower.contains("token")) return "运行依赖失败，敏感正文已隐藏";
        return message.substring(0, Math.min(600, message.length()));
    }

    private String valueOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> maskPreview(Map<String, Object> row, Set<String> sensitiveFields) {
        Map<String, Object> masked = new LinkedHashMap<>();
        row.forEach((key, value) -> masked.put(key, sensitiveFields.contains(key) ? "••••••" : switch (value) {
            case Map<?, ?> nested -> maskPreview((Map<String, Object>) nested, sensitiveFields);
            case List<?> values -> values.stream().map(item -> item instanceof Map<?, ?> nested
                    ? maskPreview((Map<String, Object>) nested, sensitiveFields) : item).toList();
            default -> value;
        }));
        return masked;
    }

    private void executeAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { tasks.execute(action); }
            });
        } else {
            tasks.execute(action);
        }
    }
    private Instant instant(ResultSet row, String column) throws SQLException { Timestamp value = row.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private Integer nullableInteger(ResultSet row, String column) throws SQLException { int value = row.getInt(column); return row.wasNull() ? null : value; }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("JSON serialization failed", cause); }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        if (value == null) return null;
        try { return json.readValue(value, type); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("Stored runtime JSON is invalid", cause); }
    }
}
