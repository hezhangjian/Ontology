package com.hezhangjian.ontology.core.connections;

import static com.hezhangjian.ontology.core.connections.ConnectionModels.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.deletion.ResourceDeletionService;
import com.hezhangjian.ontology.core.security.WorkspaceContext;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DataConnectionService {
    private static final String DATA_SOURCE_SELECT = """
            SELECT d.*, c.name credential_name, c.provider credential_provider,
                   c.credential_type, c.created_at credential_created_at, c.rotated_at credential_rotated_at,
                   (SELECT count(*) FROM control.data_sources x WHERE x.secret_ref=c.id AND x.deleted_at IS NULL) credential_refs,
                   (SELECT count(*) FROM control.pipelines p WHERE p.data_source_id=d.id) pipeline_refs,
                   (SELECT count(*) FROM control.pipeline_runs r JOIN control.pipelines p ON p.id=r.pipeline_id
                       WHERE p.data_source_id=d.id AND r.status IN ('RUNNING','STARTING')) active_runs
              FROM control.data_sources d
              JOIN control.connection_secrets c ON c.id=d.secret_ref
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConnectionCrypto crypto;
    private final ConnectionPolicy policy;
    private final ConnectionProbe probe;
    private final ConnectionProperties properties;
    private final ResourceDeletionService deletion;
    private final Map<String, RateWindow> testRates = new ConcurrentHashMap<>();

    public DataConnectionService(JdbcTemplate jdbc, ObjectMapper json, ConnectionCrypto crypto,
                                 ConnectionPolicy policy, ConnectionProbe probe, ConnectionProperties properties,
                                 ResourceDeletionService deletion) {
        this.jdbc = jdbc;
        this.json = json;
        this.crypto = crypto;
        this.policy = policy;
        this.probe = probe;
        this.properties = properties;
        this.deletion = deletion;
    }

    public TestResult test(TestRequest request, Actor actor) {
        if (request == null || request.type() == null || request.credential() == null) {
            throw new ConnectionProblem("REQUEST_INVALID", "连接类型、配置和凭据不能为空");
        }
        Map<String, Object> config = policy.validate(request.type(), request.config());
        enforceRate(actor.id(), request.type() + ":" + target(config));
        Map<String, String> credential = resolveTransientCredential(request.credential(), actor);
        ConnectionProbe.ProbeOutcome outcome = probe.probe(request.type(), config, credential);
        UUID requestId = UUID.randomUUID();
        String fingerprint = fingerprint(request.type(), config, credential);
        Instant expiresAt = Instant.now().plus(properties.testTokenTtl());
        String token = outcome.status() == ConnectionStatus.ERROR ? null
                : crypto.issueTestToken(actor.id(), request.type().name(), fingerprint, outcome.status().name(), expiresAt);
        return new TestResult(requestId, outcome.status(), outcome.stages(), outcome.assets().size(), fingerprint,
                token, expiresAt, outcome.assets());
    }

    @Transactional
    public DataSource create(CreateRequest request, Actor actor) {
        requireName(request.name());
        if (request.type() == null || request.credential() == null) {
            throw new ConnectionProblem("REQUEST_INVALID", "连接类型和凭据不能为空");
        }
        Map<String, Object> config = policy.validate(request.type(), request.config());
        Map<String, String> credentialValues = resolveTransientCredential(request.credential(), actor);
        ConnectionCrypto.TestToken token = crypto.verifyTestToken(request.testToken());
        String fingerprint = fingerprint(request.type(), config, credentialValues);
        if (!token.subject().equals(actor.id())) throw new ConnectionProblem("TEST_TOKEN_SUBJECT_MISMATCH", "登录身份已变化，请重新测试");
        if (!token.type().equals(request.type().name())) throw new ConnectionProblem("TEST_TOKEN_TYPE_MISMATCH", "连接类型已变化，请重新测试");
        if (!token.fingerprint().equals(fingerprint)) throw new ConnectionProblem("TEST_TOKEN_CONFIG_MISMATCH", "连接配置已变化，请重新测试");
        if (token.status().equals(ConnectionStatus.ERROR.name())) throw new ConnectionProblem("TEST_TOKEN_FAILED", "失败的测试结果不能用于创建连接");
        ConnectionProbe.ProbeOutcome outcome = probe.probe(request.type(), config, credentialValues);
        if (outcome.status() == ConnectionStatus.ERROR) {
            throw new ConnectionProblem("CREATE_RETEST_FAILED", "创建前复核失败，当前配置未保存");
        }
        UUID secretId = createOrReferenceCredential(request.credential(), request.type(), actor);
        return persistDataSource(request.name(), request.description(), request.type(), request.ownerId(), request.ownerName(),
                request.tags(), config, secretId, fingerprint, outcome, actor);
    }

    @Transactional
    public DataSource importLocalCsv(String name, String description, List<String> tags, List<MultipartFile> files, Actor actor) {
        requireName(name);
        List<LocalCsvFile> accepted = validateLocalCsvFiles(files);
        UUID id = UUID.randomUUID();
        String prefix = "local-csv/" + id + "/";
        MinioClient storage = localCsvStorage();
        List<String> uploaded = new ArrayList<>();
        try {
            ensureLocalCsvBucket(storage);
            for (LocalCsvFile file : accepted) {
                String object = prefix + file.objectName();
                storage.putObject(PutObjectArgs.builder().bucket(properties.localCsvBucket()).object(object)
                        .stream(file.file().getInputStream(), file.file().getSize(), -1)
                        .contentType("text/csv").build());
                uploaded.add(object);
            }
            Map<String, Object> config = Map.of(
                    "endpoint", properties.localCsvMinioUrl().toString(),
                    "region", "us-east-1",
                    "bucket", properties.localCsvBucket(),
                    "prefix", prefix,
                    "pathStyle", true,
                    "timeoutSeconds", 15);
            Map<String, String> credential = Map.of(
                    "accessKey", properties.localCsvAccessKey(),
                    "secretKey", properties.localCsvSecretKey());
            ConnectionProbe.ProbeOutcome outcome = probe.probe(DataSourceType.S3_CSV, config, credential);
            if (outcome.status() == ConnectionStatus.ERROR) {
                throw new ConnectionProblem("LOCAL_CSV_DISCOVERY_FAILED", "文件已上传，但平台未能识别 CSV；请检查文件编码和内容");
            }
            UUID secretId = createOrReferenceCredential(
                    new CredentialInput("MANAGED", "平台托管本地 CSV 存储", null, null, credential),
                    DataSourceType.S3_CSV, actor);
            return persistDataSource(name, description, DataSourceType.S3_CSV, null, null, tags, config, secretId,
                    fingerprint(DataSourceType.S3_CSV, config, credential), outcome, actor);
        } catch (ConnectionProblem cause) {
            deleteUploaded(storage, uploaded);
            throw cause;
        } catch (Exception cause) {
            deleteUploaded(storage, uploaded);
            throw new ConnectionProblem("LOCAL_CSV_UPLOAD_FAILED", "无法上传所选 CSV 文件夹，请稍后重试");
        }
    }

    private DataSource persistDataSource(String name, String description, DataSourceType type, String requestedOwnerId,
                                         String requestedOwnerName, List<String> tags, Map<String, Object> config,
                                         UUID secretId, String fingerprint, ConnectionProbe.ProbeOutcome outcome, Actor actor) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    INSERT INTO control.data_sources
                      (id,ontology_id,name,normalized_name,description,source_type,owner_id,owner_name,tags,config,secret_ref,
                       connection_status,asset_count,last_checked_at,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,string_to_array(?, E'\\u001f'),?::jsonb,?,?,?,?,?,?,?)
                    """, id, WorkspaceContext.id(), name.trim(), normalizeName(name), safeDescription(description),
                    type.name(), ownerId(requestedOwnerId, actor), ownerName(requestedOwnerName, actor), joinTags(tags),
                    writeJson(config), secretId, outcome.status().name(), outcome.assets().size(), Timestamp.from(now),
                    actor.id(), Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException cause) {
            throw new ConnectionProblem("NAME_CONFLICT", "连接名称已存在（名称不区分大小写）");
        }
        saveTest(id, actor, UUID.randomUUID(), fingerprint, outcome);
        replaceAssets(id, outcome.assets());
        audit(actor, "DATA_SOURCE_CREATED", "data_source", id.toString(), "创建数据连接“" + name.trim() + "”",
                Map.of("type", type.name(), "credential", "configured"));
        return get(id);
    }

    public DataSourcePage list(int page, int size, String search, String type, String status, String owner) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String pattern = "%" + (search == null ? "" : search.trim().toLowerCase()) + "%";
        String query = DATA_SOURCE_SELECT + """
             WHERE d.ontology_id=? AND d.deleted_at IS NULL
               AND (lower(d.name) LIKE ? OR lower(coalesce(d.description,'')) LIKE ?)
               AND (? = '' OR d.source_type = ?)
               AND (? = '' OR d.connection_status = ?)
               AND (? = '' OR d.owner_id = ?)
             ORDER BY CASE d.connection_status WHEN 'ERROR' THEN 0 WHEN 'UNTESTED' THEN 1 WHEN 'TESTING' THEN 2 ELSE 3 END,
                      d.updated_at DESC
             LIMIT ? OFFSET ?
            """;
        List<DataSource> items = jdbc.query(query, this::mapDataSource, WorkspaceContext.id(), pattern, pattern,
                empty(type), empty(type), empty(status), empty(status), empty(owner), empty(owner), safeSize, safePage * safeSize);
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM control.data_sources d WHERE d.ontology_id=? AND d.deleted_at IS NULL
                 AND (lower(d.name) LIKE ? OR lower(coalesce(d.description,'')) LIKE ?)
                 AND (? = '' OR d.source_type = ?) AND (? = '' OR d.connection_status = ?) AND (? = '' OR d.owner_id = ?)
                """, Long.class, WorkspaceContext.id(), pattern, pattern, empty(type), empty(type), empty(status), empty(status), empty(owner), empty(owner));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("all", Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM control.data_sources WHERE ontology_id=? AND deleted_at IS NULL", Integer.class, WorkspaceContext.id())));
        counts.put("healthy", countStatus("HEALTHY") + countStatus("HEALTHY_RESTRICTED"));
        counts.put("error", countStatus("ERROR"));
        counts.put("untested", countStatus("UNTESTED"));
        return new DataSourcePage(items, safePage, safeSize, total == null ? 0 : total, counts,
                Map.of("search", empty(search), "type", empty(type), "status", empty(status), "owner", empty(owner), "sort", "status,updatedAt"));
    }

    public DataSource get(UUID id) {
        List<DataSource> rows = jdbc.query(DATA_SOURCE_SELECT + " WHERE d.id=? AND d.ontology_id=? AND d.deleted_at IS NULL", this::mapDataSource, id, WorkspaceContext.id());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据连接不存在");
        return rows.getFirst();
    }

    public Overview overview(UUID id) {
        DataSource source = get(id);
        List<TestStage> health = jdbc.query("""
                SELECT stages FROM control.data_source_test_results WHERE data_source_id=? ORDER BY tested_at DESC LIMIT 1
                """, (rs, row) -> readJson(rs.getString(1), new TypeReference<List<TestStage>>() { }), id)
                .stream().findFirst().orElse(List.of());
        Map<String, Integer> assetSummary = new LinkedHashMap<>();
        jdbc.query("SELECT asset_type,count(*) FROM control.data_source_assets WHERE data_source_id=? AND status<>'UNAVAILABLE' GROUP BY asset_type",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> assetSummary.put(rs.getString(1), rs.getInt(2)), id);
        Map<String, Integer> pipelineSummary = new LinkedHashMap<>();
        jdbc.query("SELECT status,count(*) FROM control.pipelines WHERE data_source_id=? GROUP BY status",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> pipelineSummary.put(rs.getString(1), rs.getInt(2)), id);
        return new Overview(source, health, assetSummary, pipelineSummary, runs(id).stream().limit(5).toList(),
                activities(id), Map.of());
    }

    @Transactional
    public DataSource update(UUID id, long expectedVersion, UpdateRequest request, Actor actor) {
        DataSource existing = get(id);
        String name = request.name() == null ? existing.name() : request.name().trim();
        requireName(name);
        Map<String, Object> config = request.config() == null ? existing.config() : policy.validate(existing.type(), request.config());
        boolean connectionChanged = !crypto.fingerprint(existing.config()).equals(crypto.fingerprint(config));
        ConnectionProbe.ProbeOutcome outcome = null;
        if (connectionChanged) {
            enforceRate(actor.id(), existing.type() + ":" + target(config));
            outcome = probe.probe(existing.type(), config, credentialValues(existing.credential().id(), actor, true));
            if (outcome.status() == ConnectionStatus.ERROR) {
                throw new ConnectionProblem("UPDATE_TEST_FAILED", "新连接配置测试失败，当前有效配置未修改");
            }
        }
        int changed;
        try {
            changed = jdbc.update("""
                    UPDATE control.data_sources SET name=?, normalized_name=?, description=?, owner_id=?, owner_name=?,
                      tags=string_to_array(?, E'\\u001f'), config=?::jsonb,
                      connection_status=CASE WHEN ? THEN ? ELSE connection_status END,
                      asset_count=CASE WHEN ? THEN ? ELSE asset_count END,
                      last_checked_at=CASE WHEN ? THEN now() ELSE last_checked_at END,
                      last_error=CASE WHEN ? THEN NULL ELSE last_error END,
                      version=version+1, updated_at=now()
                    WHERE id=? AND version=? AND deleted_at IS NULL
                    """, name, normalizeName(name), request.description() == null ? existing.description() : safeDescription(request.description()),
                    request.ownerId() == null ? existing.ownerId() : request.ownerId(),
                    request.ownerName() == null ? existing.ownerName() : request.ownerName(),
                    joinTags(request.tags() == null ? existing.tags() : request.tags()), writeJson(config),
                    connectionChanged, connectionChanged ? outcome.status().name() : existing.status().name(),
                    connectionChanged, connectionChanged ? outcome.assets().size() : existing.assetCount(),
                    connectionChanged, connectionChanged, id, expectedVersion);
        } catch (DuplicateKeyException cause) {
            throw new ConnectionProblem("NAME_CONFLICT", "连接名称已存在（名称不区分大小写）");
        }
        if (changed == 0) throw new ConnectionProblem("VERSION_CONFLICT", "连接已被其他用户更新，请重新加载");
        if (connectionChanged) {
            UUID requestId = UUID.randomUUID();
            replaceAssets(id, outcome.assets());
            saveTest(id, actor, requestId, fingerprint(existing.type(), config, credentialValues(existing.credential().id(), actor, true)), outcome);
        }
        audit(actor, "DATA_SOURCE_UPDATED", "data_source", id.toString(), connectionChanged ? "测试并更新数据连接配置" : "更新数据连接基本信息",
                Map.of("connectionConfig", connectionChanged ? "tested-and-updated" : "unchanged", "credential", "unchanged"));
        return get(id);
    }

    @Transactional
    public TestResult retest(UUID id, Actor actor) {
        DataSource source = get(id);
        if (source.status() == ConnectionStatus.DISABLED) throw new ConnectionProblem("CONNECTION_DISABLED", "已停用连接不能测试，请先恢复");
        jdbc.update("UPDATE control.data_sources SET connection_status='TESTING',updated_at=now() WHERE id=?", id);
        Map<String, String> credentials = credentialValues(source.credential().id(), actor, true);
        ConnectionProbe.ProbeOutcome outcome = probe.probe(source.type(), source.config(), credentials);
        UUID requestId = UUID.randomUUID();
        Diagnostic diagnostic = outcome.safeError() == null ? null : diagnostic("AUTHENTICATION", requestId, outcome.safeError());
        jdbc.update("UPDATE control.data_sources SET connection_status=?,asset_count=?,last_checked_at=now(),last_error=?::jsonb,updated_at=now(),version=version+1 WHERE id=?",
                outcome.status().name(), outcome.assets().size(), writeJson(diagnostic), id);
        saveTest(id, actor, requestId, fingerprint(source.type(), source.config(), credentials), outcome);
        if (outcome.status() != ConnectionStatus.ERROR) replaceAssets(id, outcome.assets());
        audit(actor, "DATA_SOURCE_TESTED", "data_source", id.toString(), "测试数据连接：" + outcome.status().name(), Map.of("status", outcome.status().name()));
        Instant expiresAt = Instant.now().plus(properties.testTokenTtl());
        return new TestResult(requestId, outcome.status(), outcome.stages(), outcome.assets().size(),
                fingerprint(source.type(), source.config(), credentials), null, expiresAt, outcome.assets());
    }

    @Transactional
    public DataSource disable(UUID id, Actor actor) {
        DataSource source = get(id);
        int activeStreams = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM control.pipeline_runs r JOIN control.pipelines p ON p.id=r.pipeline_id
                 WHERE p.data_source_id=? AND p.mode='STREAMING' AND r.status IN ('RUNNING','STARTING')
                """, Integer.class, id));
        if (activeStreams > 0) throw new ConnectionProblem("SAVEPOINT_REQUIRED", "存在活动流任务，必须先在管道页完成 savepoint 停止");
        if (source.status() != ConnectionStatus.DISABLED) {
            jdbc.update("UPDATE control.data_sources SET status_before_disable=connection_status,connection_status='DISABLED',version=version+1,updated_at=now() WHERE id=?", id);
            audit(actor, "DATA_SOURCE_DISABLED", "data_source", id.toString(), "停用数据连接；历史运行、血缘和对象保留", Map.of());
        }
        return get(id);
    }

    @Transactional
    public DataSource enable(UUID id, Actor actor) {
        DataSource source = get(id);
        if (source.status() == ConnectionStatus.DISABLED) {
            jdbc.update("UPDATE control.data_sources SET connection_status='UNTESTED',status_before_disable=NULL,version=version+1,updated_at=now() WHERE id=?", id);
            audit(actor, "DATA_SOURCE_ENABLED", "data_source", id.toString(), "恢复数据连接，需重新测试", Map.of());
        }
        return get(id);
    }

    @Transactional
    public void delete(UUID id, Actor actor) {
        DataSource source = get(id);
        audit(actor, "DATA_SOURCE_DELETED", "data_source", id.toString(), "永久删除数据连接“" + source.name() + "”及关联记录", Map.of());
        deletion.deleteDataSource(id);
    }

    @Transactional
    public CredentialSummary rotate(UUID id, CredentialInput input, Actor actor) {
        DataSource source = get(id);
        Map<String, String> values = resolveTransientCredential(input, actor);
        ConnectionProbe.ProbeOutcome outcome = probe.probe(source.type(), source.config(), values);
        if (outcome.status() == ConnectionStatus.ERROR) throw new ConnectionProblem("ROTATION_TEST_FAILED", "新凭据测试失败，旧凭据继续有效");
        UUID oldSecret = source.credential().id();
        UUID newSecret = createOrReferenceCredential(input, source.type(), actor);
        jdbc.update("UPDATE control.data_sources SET secret_ref=?,connection_status=?,last_checked_at=now(),last_error=NULL,version=version+1,updated_at=now() WHERE id=?",
                newSecret, outcome.status().name(), id);
        jdbc.update("DELETE FROM control.connection_secrets c WHERE c.id=? AND NOT EXISTS (SELECT 1 FROM control.data_sources d WHERE d.secret_ref=c.id)", oldSecret);
        audit(actor, "CREDENTIAL_ROTATED", "data_source", id.toString(), "测试并轮换连接凭据", Map.of("credential", "rotated"));
        return get(id).credential();
    }

    public List<CredentialSummary> credentials(Actor actor) {
        String where = actor.admin() ? "" : " WHERE c.created_by=?";
        Object[] args = actor.admin() ? new Object[]{} : new Object[]{actor.id()};
        return jdbc.query("""
                SELECT c.*,(SELECT count(*) FROM control.data_sources d WHERE d.secret_ref=c.id AND d.deleted_at IS NULL) refs
                FROM control.connection_secrets c
                """ + where + " ORDER BY c.created_at DESC", this::mapCredential, args);
    }

    public RuntimeMaterial runtimeMaterial(UUID sourceId) {
        DataSource source = get(sourceId);
        if (source.status() != ConnectionStatus.HEALTHY && source.status() != ConnectionStatus.HEALTHY_RESTRICTED) {
            throw new ConnectionProblem("CONNECTION_NOT_RUNNABLE", "连接未处于可运行状态");
        }
        return new RuntimeMaterial(source.type(), source.config(),
                credentialValues(source.credential().id(), new Actor("pipeline-runtime", "Pipeline Runtime", true), true),
                source.status());
    }

    public AssetPage assets(UUID sourceId, int page, int size, String search) {
        get(sourceId);
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(100, size));
        String pattern = "%" + empty(search).toLowerCase() + "%";
        List<DataSourceAsset> items = jdbc.query("""
                SELECT a.*,(SELECT count(*) FROM control.data_source_asset_fields f WHERE f.asset_id=a.id) field_count,
                       EXISTS(SELECT 1 FROM control.pipelines p WHERE p.source_asset_id=a.id) used
                FROM control.data_source_assets a WHERE a.data_source_id=? AND lower(a.full_path) LIKE ?
                ORDER BY CASE a.status WHEN 'NEW' THEN 0 WHEN 'AVAILABLE' THEN 1 ELSE 2 END,a.full_path LIMIT ? OFFSET ?
                """, this::mapAsset, sourceId, pattern, safeSize, safePage * safeSize);
        Long total = jdbc.queryForObject("SELECT count(*) FROM control.data_source_assets WHERE data_source_id=? AND lower(full_path) LIKE ?",
                Long.class, sourceId, pattern);
        return new AssetPage(items, safePage, safeSize, total == null ? 0 : total);
    }

    public DataSourceAsset asset(UUID sourceId, UUID assetId) {
        get(sourceId);
        List<DataSourceAsset> rows = jdbc.query("""
                SELECT a.*,(SELECT count(*) FROM control.data_source_asset_fields f WHERE f.asset_id=a.id) field_count,
                       EXISTS(SELECT 1 FROM control.pipelines p WHERE p.source_asset_id=a.id) used
                FROM control.data_source_assets a WHERE a.data_source_id=? AND a.id=?
                """, this::mapAsset, sourceId, assetId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资产不存在");
        DataSourceAsset raw = rows.getFirst();
        List<AssetField> fields = jdbc.query("SELECT * FROM control.data_source_asset_fields WHERE asset_id=? ORDER BY ordinal",
                (rs, row) -> new AssetField(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("inferred_type"),
                        rs.getString("original_type"), rs.getBoolean("nullable"), rs.getBoolean("sensitive"),
                        rs.getBoolean("primary_key_candidate"), rs.getString("sample_value_masked")), assetId);
        return new DataSourceAsset(raw.id(), raw.name(), raw.fullPath(), raw.parentPath(), raw.assetType(), raw.status(),
                raw.schemaStatus(), raw.schemaHash(), raw.schemaVersion(), fields.size(), raw.sizeBytes(), raw.estimatedRows(),
                raw.partitionCount(), raw.permissionStatus(), raw.usedByPipeline(), raw.discoveredAt(), fields);
    }

    @Transactional
    public DiscoveryRun discover(UUID id, Actor actor) {
        DataSource source = get(id);
        if (source.status() == ConnectionStatus.DISABLED) throw new ConnectionProblem("CONNECTION_DISABLED", "已停用连接不能刷新资产");
        UUID taskId = UUID.randomUUID();
        Instant started = Instant.now();
        jdbc.update("INSERT INTO control.data_source_discovery_runs(id,data_source_id,status,requested_by,started_at) VALUES (?,?,?,?,?)",
                taskId, id, "RUNNING", actor.id(), Timestamp.from(started));
        ConnectionProbe.ProbeOutcome outcome = probe.probe(source.type(), source.config(), credentialValues(source.credential().id(), actor, true));
        String runStatus = outcome.status() == ConnectionStatus.ERROR ? "FAILED"
                : outcome.status() == ConnectionStatus.HEALTHY_RESTRICTED ? "PARTIAL" : "SUCCEEDED";
        Diagnostic diagnostic = outcome.safeError() == null ? null : diagnostic("DISCOVERY", taskId, outcome.safeError());
        if (outcome.status() != ConnectionStatus.ERROR) replaceAssets(id, outcome.assets());
        jdbc.update("UPDATE control.data_source_discovery_runs SET status=?,discovered_count=?,diagnostic=?::jsonb,completed_at=now() WHERE id=?",
                runStatus, outcome.assets().size(), writeJson(diagnostic), taskId);
        jdbc.update("UPDATE control.data_sources SET asset_count=(SELECT count(*) FROM control.data_source_assets WHERE data_source_id=? AND status<>'UNAVAILABLE'),updated_at=now() WHERE id=?", id, id);
        audit(actor, "ASSETS_DISCOVERED", "data_source", id.toString(), "刷新连接资产：" + outcome.assets().size() + " 个", Map.of("taskId", taskId));
        return new DiscoveryRun(taskId, runStatus, outcome.assets().size(), started, Instant.now(), diagnostic);
    }

    @Transactional
    public DiscoveryRun inferSchema(UUID sourceId, UUID assetId, Actor actor) {
        DataSourceAsset asset = asset(sourceId, assetId);
        UUID taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO control.data_source_discovery_runs(id,data_source_id,status,discovered_count,requested_by,started_at,completed_at) VALUES (?,?, 'SUCCEEDED',1,?,now(),now())",
                taskId, sourceId, actor.id());
        jdbc.update("UPDATE control.data_source_assets SET schema_status=CASE WHEN schema_version>0 THEN 'CHANGED' ELSE 'READY' END,schema_version=schema_version+1,discovered_at=now() WHERE id=?", assetId);
        audit(actor, "ASSET_SCHEMA_INFERRED", "data_source_asset", assetId.toString(), "重新推断资产 Schema", Map.of("dataSourceId", sourceId));
        return new DiscoveryRun(taskId, "SUCCEEDED", 1, Instant.now(), Instant.now(), null);
    }

    public AssetPreview preview(UUID sourceId, UUID assetId, int limit, Actor actor) {
        DataSource source = get(sourceId);
        if (source.status() == ConnectionStatus.DISABLED) throw new ConnectionProblem("CONNECTION_DISABLED", "已停用连接不能预览资产");
        DataSourceAsset asset = asset(sourceId, assetId);
        AssetPreview result = probe.preview(source.type(), source.config(), credentialValues(source.credential().id(), actor, true), asset, limit);
        audit(actor, "ASSET_PREVIEWED", "data_source_asset", assetId.toString(), "预览资产（最多 " + limit + " 行）", Map.of("dataSourceId", sourceId));
        return result;
    }

    public AssetPreview runtimePreview(UUID sourceId, UUID assetId, int limit) {
        DataSource source = get(sourceId);
        if (source.status() != ConnectionStatus.HEALTHY && source.status() != ConnectionStatus.HEALTHY_RESTRICTED) {
            throw new ConnectionProblem("CONNECTION_NOT_RUNNABLE", "辅助连接未处于可运行状态");
        }
        DataSourceAsset asset = asset(sourceId, assetId);
        if (!"READABLE".equals(asset.permissionStatus())) {
            throw new ConnectionProblem("LOOKUP_ASSET_NOT_READABLE", "辅助资产不可读取");
        }
        return probe.preview(source.type(), source.config(),
                credentialValues(source.credential().id(), new Actor("pipeline-runtime", "Pipeline Runtime", true), true),
                asset, Math.max(1, Math.min(1000, limit)));
    }

    public AssetUsage usage(UUID sourceId, UUID assetId) {
        asset(sourceId, assetId);
        return new AssetUsage(pipelines(sourceId).stream().filter(p -> Objects.equals(p.sourceAsset(), assetId.toString())).toList(), 0);
    }

    public List<PipelineSummary> pipelines(UUID sourceId) {
        get(sourceId);
        return jdbc.query("""
                SELECT p.*,a.full_path source_asset,(SELECT max(started_at) FROM control.pipeline_runs r WHERE r.pipeline_id=p.id) recent_run
                FROM control.pipelines p LEFT JOIN control.data_source_assets a ON a.id=p.source_asset_id
                WHERE p.data_source_id=? ORDER BY p.updated_at DESC
                """, (rs, row) -> new PipelineSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("source_asset"),
                        rs.getString("mode"), rs.getString("status"), rs.getString("owner_name"), instant(rs, "recent_run")), sourceId);
    }

    public List<PipelineRunSummary> runs(UUID sourceId) {
        get(sourceId);
        return jdbc.query("""
                SELECT r.*,p.name pipeline_name,a.full_path source_asset,
                       extract(epoch from (coalesce(r.completed_at,now())-r.started_at))*1000 duration_ms
                FROM control.pipeline_runs r JOIN control.pipelines p ON p.id=r.pipeline_id
                LEFT JOIN control.data_source_assets a ON a.id=p.source_asset_id
                WHERE p.data_source_id=? ORDER BY r.started_at DESC LIMIT 100
                """, (rs, row) -> new PipelineRunSummary(rs.getObject("id", UUID.class), rs.getString("pipeline_name"),
                        rs.getString("source_asset"), rs.getString("trigger_type"), rs.getString("status"), instant(rs, "started_at"),
                        rs.getLong("duration_ms"), rs.getLong("read_count"), rs.getLong("written_count"), rs.getLong("rejected_count"),
                        rs.getString("flink_job_id")), sourceId);
    }

    private List<AuditEvent> activities(UUID sourceId) {
        return jdbc.query("""
                SELECT id,action,actor_name,occurred_at,summary FROM control.audit_events
                WHERE (resource_type='data_source' AND resource_id=?) OR safe_diff->>'dataSourceId'=?
                ORDER BY occurred_at DESC LIMIT 20
                """, (rs, row) -> new AuditEvent(rs.getObject("id", UUID.class), rs.getString("action"), rs.getString("actor_name"),
                        instant(rs, "occurred_at"), rs.getString("summary")), sourceId.toString(), sourceId.toString());
    }

    private UUID createOrReferenceCredential(CredentialInput input, DataSourceType type, Actor actor) {
        String mode = input.mode() == null ? "" : input.mode().toUpperCase();
        if (mode.equals("EXISTING")) {
            if (input.existingSecretRef() == null) throw new ConnectionProblem("CREDENTIAL_REQUIRED", "请选择已有凭据");
            credentialValues(input.existingSecretRef(), actor, false);
            return input.existingSecretRef();
        }
        UUID id = UUID.randomUUID();
        String name = input.name() == null || input.name().isBlank() ? type.name() + " credential" : input.name().trim();
        if (mode.equals("FILE")) {
            validateFileRefs(input.safeFileRefs());
            jdbc.update("""
                    INSERT INTO control.connection_secrets(id,name,provider,file_references,credential_type,created_by)
                    VALUES (?,?, 'FILE',?::jsonb,?,?)
                    """, id, name, writeJson(input.safeFileRefs()), type.name(), actor.id());
        } else if (mode.equals("MANAGED")) {
            if (input.safeValues().isEmpty()) throw new ConnectionProblem("CREDENTIAL_REQUIRED", "请输入连接凭据");
            ConnectionCrypto.Encrypted encrypted = crypto.encrypt(input.safeValues());
            jdbc.update("""
                    INSERT INTO control.connection_secrets(id,name,provider,ciphertext,nonce,key_version,credential_type,created_by)
                    VALUES (?,?, 'MANAGED',?,?,?,?,?)
                    """, id, name, encrypted.ciphertext(), encrypted.nonce(), properties.keyVersion(), type.name(), actor.id());
        } else {
            throw new ConnectionProblem("CREDENTIAL_MODE_INVALID", "凭据模式无效");
        }
        audit(actor, "CREDENTIAL_CREATED", "connection_secret", id.toString(), "创建连接凭据（正文已加密或由文件提供）", Map.of("provider", mode));
        return id;
    }

    private Map<String, String> resolveTransientCredential(CredentialInput input, Actor actor) {
        String mode = input.mode() == null ? "" : input.mode().toUpperCase();
        return switch (mode) {
            case "MANAGED" -> input.safeValues();
            case "FILE" -> readFileRefs(input.safeFileRefs());
            case "EXISTING" -> {
                if (input.existingSecretRef() == null) throw new ConnectionProblem("CREDENTIAL_REQUIRED", "请选择已有凭据");
                yield credentialValues(input.existingSecretRef(), actor, false);
            }
            default -> throw new ConnectionProblem("CREDENTIAL_MODE_INVALID", "凭据模式无效");
        };
    }

    private Map<String, String> credentialValues(UUID id, Actor actor, boolean referencedAccess) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM control.connection_secrets WHERE id=? AND revoked_at IS NULL", (rs, row) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("provider", rs.getString("provider")); value.put("createdBy", rs.getString("created_by"));
            value.put("ciphertext", rs.getBytes("ciphertext")); value.put("nonce", rs.getBytes("nonce"));
            value.put("fileRefs", rs.getString("file_references")); return value;
        }, id);
        if (rows.isEmpty()) throw new ConnectionProblem("CREDENTIAL_NOT_FOUND", "凭据不存在或已撤销");
        Map<String, Object> row = rows.getFirst();
        if (!actor.admin() && !referencedAccess && !actor.id().equals(row.get("createdBy"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用此凭据");
        }
        if ("FILE".equals(row.get("provider"))) {
            return readFileRefs(readJson((String) row.get("fileRefs"), new TypeReference<Map<String, String>>() { }));
        }
        return crypto.decrypt((byte[]) row.get("ciphertext"), (byte[]) row.get("nonce"));
    }

    private Map<String, String> readFileRefs(Map<String, String> refs) {
        validateFileRefs(refs);
        Map<String, String> result = new LinkedHashMap<>();
        refs.forEach((key, value) -> {
            try {
                result.put(key, Files.readString(Path.of("/run/secrets", value.substring("file://".length())), StandardCharsets.UTF_8).trim());
            } catch (java.io.IOException cause) {
                throw new ConnectionProblem("SECRET_FILE_UNAVAILABLE", "预置凭据文件不可用");
            }
        });
        return result;
    }

    private void validateFileRefs(Map<String, String> refs) {
        if (refs.isEmpty()) throw new ConnectionProblem("CREDENTIAL_REQUIRED", "请输入预置 Secret 引用");
        refs.values().forEach(value -> {
            if (value == null || !value.matches("file://[A-Za-z0-9_.-]+")) {
                throw new ConnectionProblem("SECRET_REF_INVALID", "Secret 引用必须是安全的 file:// 名称");
            }
        });
    }

    private void saveTest(UUID dataSourceId, Actor actor, UUID requestId, String fingerprint, ConnectionProbe.ProbeOutcome outcome) {
        jdbc.update("""
                INSERT INTO control.data_source_test_results
                  (id,data_source_id,request_id,tested_by,config_fingerprint,status,stages,discovered_summary)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)
                """, UUID.randomUUID(), dataSourceId, requestId, actor.id(), fingerprint, outcome.status().name(),
                writeJson(outcome.stages()), writeJson(Map.of("assetCount", outcome.assets().size())));
    }

    private void replaceAssets(UUID sourceId, List<DiscoveredAsset> discovered) {
        jdbc.update("UPDATE control.data_source_assets SET status='UNAVAILABLE',unavailable_at=now() WHERE data_source_id=?", sourceId);
        for (DiscoveredAsset asset : discovered) {
            UUID id = UUID.nameUUIDFromBytes((sourceId + ":" + asset.stableKey()).getBytes(StandardCharsets.UTF_8));
            String hash = crypto.fingerprint(asset.fields());
            jdbc.update("""
                    INSERT INTO control.data_source_assets
                      (id,data_source_id,stable_key,name,full_path,parent_path,asset_type,status,schema_status,schema_hash,schema_version,
                       size_bytes,estimated_rows,partition_count,permission_status,discovered_at)
                    VALUES (?,?,?,?,?,?,?,'NEW',?,?,1,?,?,?,?,now())
                    ON CONFLICT(data_source_id,stable_key) DO UPDATE SET
                      name=excluded.name,full_path=excluded.full_path,parent_path=excluded.parent_path,asset_type=excluded.asset_type,
                      status='AVAILABLE',schema_status=CASE WHEN control.data_source_assets.schema_hash IS DISTINCT FROM excluded.schema_hash
                        THEN 'CHANGED' ELSE excluded.schema_status END,
                      schema_hash=excluded.schema_hash,schema_version=CASE WHEN control.data_source_assets.schema_hash IS DISTINCT FROM excluded.schema_hash
                        THEN control.data_source_assets.schema_version+1 ELSE control.data_source_assets.schema_version END,
                      size_bytes=excluded.size_bytes,estimated_rows=excluded.estimated_rows,partition_count=excluded.partition_count,
                      permission_status=excluded.permission_status,discovered_at=now(),unavailable_at=NULL
                    """, id, sourceId, asset.stableKey(), asset.name(), asset.fullPath(), asset.parentPath(), asset.assetType(),
                    asset.fields().isEmpty() ? "UNKNOWN" : "READY", hash, asset.sizeBytes(), asset.estimatedRows(), asset.partitionCount(), asset.permissionStatus());
            jdbc.update("DELETE FROM control.data_source_asset_fields WHERE asset_id=?", id);
            int ordinal = 0;
            for (DiscoveredField field : asset.fields()) {
                jdbc.update("""
                        INSERT INTO control.data_source_asset_fields
                          (id,asset_id,ordinal,name,inferred_type,original_type,nullable,sensitive,primary_key_candidate,sample_value_masked)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """, UUID.nameUUIDFromBytes((id + ":" + field.name()).getBytes(StandardCharsets.UTF_8)), id, ordinal++,
                        field.name(), field.inferredType(), field.originalType(), field.nullable(), false,
                        field.primaryKeyCandidate(), mask(field.sampleValue()));
            }
        }
    }

    private DataSource mapDataSource(ResultSet rs, int row) throws SQLException {
        CredentialSummary credential = new CredentialSummary(rs.getObject("secret_ref", UUID.class), rs.getString("credential_name"),
                rs.getString("credential_provider"), rs.getString("credential_type"), "CONFIGURED", rs.getInt("credential_refs"),
                instant(rs, "credential_created_at"), instant(rs, "credential_rotated_at"));
        return new DataSource(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                DataSourceType.valueOf(rs.getString("source_type")), rs.getString("owner_id"), rs.getString("owner_name"),
                array(rs, "tags"), readJson(rs.getString("config"), new TypeReference<Map<String, Object>>() { }), credential,
                ConnectionStatus.valueOf(rs.getString("connection_status")), SyncStatus.valueOf(rs.getString("sync_status")),
                rs.getInt("asset_count"), instant(rs, "last_checked_at"), readJson(rs.getString("last_error"), new TypeReference<Diagnostic>() { }),
                rs.getLong("version"), rs.getInt("pipeline_refs"), rs.getInt("active_runs"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private CredentialSummary mapCredential(ResultSet rs, int row) throws SQLException {
        return new CredentialSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("provider"),
                rs.getString("credential_type"), rs.getTimestamp("revoked_at") == null ? "CONFIGURED" : "REVOKED",
                rs.getInt("refs"), instant(rs, "created_at"), instant(rs, "rotated_at"));
    }

    private DataSourceAsset mapAsset(ResultSet rs, int row) throws SQLException {
        return new DataSourceAsset(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("full_path"),
                rs.getString("parent_path"), rs.getString("asset_type"), rs.getString("status"), rs.getString("schema_status"),
                rs.getString("schema_hash"), rs.getInt("schema_version"), rs.getInt("field_count"), nullableLong(rs, "size_bytes"),
                nullableLong(rs, "estimated_rows"), nullableInt(rs, "partition_count"), rs.getString("permission_status"),
                rs.getBoolean("used"), instant(rs, "discovered_at"), List.of());
    }

    private void audit(Actor actor, String action, String resourceType, String resourceId, String summary, Map<String, ?> safeDiff) {
        jdbc.update("""
                INSERT INTO control.audit_events(id,actor_id,actor_name,action,resource_type,resource_id,request_id,summary,safe_diff)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), actor.id(), actor.name(), action, resourceType, resourceId, UUID.randomUUID(), summary, writeJson(safeDiff));
    }

    private Diagnostic diagnostic(String stage, UUID requestId, String reason) {
        return new Diagnostic(stage, Instant.now(), reason, requestId, "检查目标地址、网络策略和凭据后重新测试", "依赖错误正文已隐藏");
    }

    private void enforceRate(String actor, String target) {
        String key = actor + ":" + target;
        RateWindow current = testRates.compute(key, (ignored, prior) -> prior == null || prior.started().isBefore(Instant.now().minusSeconds(60))
                ? new RateWindow(Instant.now(), 1) : new RateWindow(prior.started(), prior.count() + 1));
        if (current.count() > 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "连接测试请求过于频繁，请稍后再试");
    }

    private String target(Map<String, Object> config) {
        for (String key : List.of("endpoint", "host", "bootstrapServers", "serviceUrl")) if (config.containsKey(key)) return String.valueOf(config.get(key));
        return "unknown";
    }

    private String fingerprint(DataSourceType type, Map<String, Object> config, Map<String, String> credential) {
        return crypto.fingerprint(Map.of("type", type.name(), "config", config, "credential", credential));
    }

    private int countStatus(String status) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM control.data_sources WHERE ontology_id=? AND deleted_at IS NULL AND connection_status=?", Integer.class, WorkspaceContext.id(), status));
    }

    private List<LocalCsvFile> validateLocalCsvFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw new ConnectionProblem("LOCAL_CSV_FILES_REQUIRED", "请选择至少一个 CSV 文件");
        if (files.size() > properties.localCsvMaxFiles()) {
            throw new ConnectionProblem("LOCAL_CSV_FILE_COUNT_EXCEEDED", "一次最多可导入 " + properties.localCsvMaxFiles() + " 个 CSV 文件");
        }
        long total = 0;
        Map<String, MultipartFile> unique = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) throw new ConnectionProblem("LOCAL_CSV_FILE_EMPTY", "不能导入空文件");
            String objectName = localCsvObjectName(file.getOriginalFilename());
            if (!objectName.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
                throw new ConnectionProblem("LOCAL_CSV_FILE_TYPE_INVALID", "所选文件夹中只能包含 CSV 文件");
            }
            if (file.getSize() > properties.localCsvMaxFileBytes()) {
                throw new ConnectionProblem("LOCAL_CSV_FILE_TOO_LARGE", "单个 CSV 文件不能超过 " + bytesLabel(properties.localCsvMaxFileBytes()));
            }
            total += file.getSize();
            if (total > properties.localCsvMaxTotalBytes()) {
                throw new ConnectionProblem("LOCAL_CSV_TOTAL_TOO_LARGE", "所选 CSV 文件总大小不能超过 " + bytesLabel(properties.localCsvMaxTotalBytes()));
            }
            if (unique.putIfAbsent(objectName, file) != null) {
                throw new ConnectionProblem("LOCAL_CSV_FILE_DUPLICATE", "文件夹中存在同名 CSV 文件，请重命名后再导入");
            }
        }
        return unique.entrySet().stream().map(entry -> new LocalCsvFile(entry.getKey(), entry.getValue())).toList();
    }

    private String localCsvObjectName(String originalName) {
        if (originalName == null || originalName.isBlank()) throw new ConnectionProblem("LOCAL_CSV_NAME_INVALID", "CSV 文件名无效");
        String candidate = originalName.replace('\\', '/').replaceAll("^/+", "");
        if (candidate.contains("../") || candidate.equals("..")) {
            throw new ConnectionProblem("LOCAL_CSV_NAME_INVALID", "CSV 文件名无效");
        }
        String[] segments = candidate.split("/");
        List<String> safe = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) continue;
            String normalized = segment.replaceAll("[^A-Za-z0-9._-]", "_");
            if (normalized.isBlank()) throw new ConnectionProblem("LOCAL_CSV_NAME_INVALID", "CSV 文件名无效");
            safe.add(normalized);
        }
        if (safe.isEmpty()) throw new ConnectionProblem("LOCAL_CSV_NAME_INVALID", "CSV 文件名无效");
        return String.join("/", safe);
    }

    private MinioClient localCsvStorage() {
        return MinioClient.builder().endpoint(properties.localCsvMinioUrl().toString())
                .credentials(properties.localCsvAccessKey(), properties.localCsvSecretKey()).build();
    }

    private void ensureLocalCsvBucket(MinioClient storage) throws Exception {
        if (!storage.bucketExists(BucketExistsArgs.builder().bucket(properties.localCsvBucket()).build())) {
            storage.makeBucket(MakeBucketArgs.builder().bucket(properties.localCsvBucket()).build());
        }
    }

    private void deleteUploaded(MinioClient storage, List<String> objects) {
        for (String object : objects) {
            try {
                storage.removeObject(RemoveObjectArgs.builder().bucket(properties.localCsvBucket()).object(object).build());
            } catch (Exception ignored) {
                // Best effort cleanup; storage may be temporarily unavailable.
            }
        }
    }

    private String bytesLabel(long bytes) { return (bytes / (1024 * 1024)) + " MB"; }
    private String ownerId(String requested, Actor actor) { return requested == null || requested.isBlank() ? actor.id() : requested; }
    private String ownerName(String requested, Actor actor) { return requested == null || requested.isBlank() ? actor.name() : requested; }
    private String normalizeName(String value) { return value.trim().toLowerCase(java.util.Locale.ROOT); }
    private String safeDescription(String value) { return value == null ? null : value.trim().substring(0, Math.min(1000, value.trim().length())); }
    private String empty(String value) { return value == null ? "" : value.trim(); }
    private String joinTags(List<String> tags) { return tags == null ? "" : tags.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).limit(20).map(v -> v.substring(0, Math.min(40, v.length()))).collect(java.util.stream.Collectors.joining("\u001f")); }
    private String mask(String value) { return value == null ? null : value.length() <= 2 ? "**" : value.charAt(0) + "***" + value.charAt(value.length() - 1); }

    private void requireName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 160) throw new ConnectionProblem("NAME_INVALID", "连接名称长度必须为 1 到 160 个字符");
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("JSON serialization failed", cause); }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        if (value == null) return null;
        try { return json.readValue(value, type); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("Stored JSON is invalid", cause); }
    }

    private List<String> array(ResultSet rs, String column) throws SQLException {
        java.sql.Array value = rs.getArray(column);
        return value == null ? List.of() : List.of((String[]) value.getArray());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private Long nullableLong(ResultSet rs, String column) throws SQLException { long value = rs.getLong(column); return rs.wasNull() ? null : value; }
    private Integer nullableInt(ResultSet rs, String column) throws SQLException { int value = rs.getInt(column); return rs.wasNull() ? null : value; }
    private record LocalCsvFile(String objectName, MultipartFile file) { }
    private record RateWindow(Instant started, int count) { }
}
