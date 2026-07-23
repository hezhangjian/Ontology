package com.hezhangjian.ontology.core.modeling;

import com.hezhangjian.ontology.core.security.WorkspaceContext;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@Service
public class OntologyCatalogService {
    public static final UUID DEFAULT_ONTOLOGY_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final Pattern API_NAME = Pattern.compile("[a-z][a-z0-9_]{1,159}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private final JdbcTemplate jdbc;

    public OntologyCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OntologyView> list() {
        return jdbc.query("""
                SELECT o.*,
                       count(r.id) FILTER (WHERE r.kind='OBJECT_TYPE' AND NOT r.tombstoned) object_type_count,
                       count(r.id) FILTER (WHERE r.kind='LINK_TYPE' AND NOT r.tombstoned) link_type_count
                FROM control.ontologies o
                JOIN control.ontology_members m ON m.ontology_id=o.id AND m.member_id=?
                LEFT JOIN control.ontology_resources r ON r.ontology_id=o.id
                GROUP BY o.id ORDER BY o.display_name
                """, (rs, row) -> new OntologyView(rs.getObject("id", UUID.class), rs.getString("api_name"),
                rs.getString("display_name"), rs.getString("description"), rs.getString("icon"),
                rs.getString("color"), rs.getLong("object_type_count"), rs.getLong("link_type_count"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Transactional
    public OntologyView create(CreateOntologyRequest request) {
        if (request == null) throw invalid("本体信息不能为空");
        String displayName = text(request.displayName(), 240, "本体名称");
        String apiName = text(request.apiName(), 160, "API 名称").toLowerCase(Locale.ROOT);
        String description = request.description() == null ? "" : request.description().trim();
        String color = request.color() == null || request.color().isBlank() ? "#3157d5" : request.color().trim();
        if (!API_NAME.matcher(apiName).matches()) throw invalid("API 名称必须以小写字母开头，且只能包含小写字母、数字和下划线");
        if (description.length() > 1000) throw invalid("场景说明不能超过 1000 个字符");
        if (!COLOR.matcher(color).matches()) throw invalid("标识颜色必须是六位十六进制颜色");
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO control.ontologies(id,api_name,display_name,description,icon,color)
                    VALUES (?,?,?,?,?,?)
                    """, id, apiName, displayName, description, "deployment-unit", color.toLowerCase(Locale.ROOT));
            jdbc.execute("SELECT pg_advisory_xact_lock(700021)");
            long revision = jdbc.queryForObject(
                    "SELECT coalesce(max(revision),0)+1 FROM control.ontology_revisions", Long.class);
            jdbc.update("""
                    INSERT INTO control.ontology_revisions(revision,ontology_id,status,activated_at)
                    VALUES (?,?,'ACTIVE',now())
                    """, revision, id);
            String creator = SecurityContextHolder.getContext().getAuthentication().getName();
            jdbc.update("""
                    INSERT INTO control.ontology_members(ontology_id,member_id,member_type,display_name,role)
                    VALUES (?,?,'USER',?,'OWNER')
                    """, id, creator, creator);
        } catch (DataIntegrityViolationException failure) {
            throw new ResponseStatusException(CONFLICT, "API 名称已被其他本体使用", failure);
        }
        return new OntologyView(id, apiName, displayName, description, "deployment-unit",
                color.toLowerCase(Locale.ROOT), 0, 0, Instant.now(), Instant.now());
    }

    public UUID resolve(String value) {
        UUID id;
        try { id = value == null || value.isBlank() ? WorkspaceContext.id() : UUID.fromString(value); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(NOT_FOUND, "本体不存在"); }
        if (jdbc.queryForObject("SELECT count(*) FROM control.ontologies WHERE id=?", Long.class, id) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "本体不存在");
        }
        return id;
    }

    public record OntologyView(UUID id, String apiName, String displayName, String description,
                               String icon, String color, long objectTypeCount, long linkTypeCount,
                               Instant createdAt, Instant updatedAt) { }

    public record CreateOntologyRequest(String apiName, String displayName, String description, String color) { }
    public record UpdateOntologyRequest(String displayName, String description) { }

    public OntologyView get(UUID id) {
        return list().stream().filter(ontology -> ontology.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "本体不存在"));
    }

    public long activeRevision() {
        return activeRevision(WorkspaceContext.id());
    }

    public long activeRevision(UUID ontologyId) {
        Long revision = jdbc.queryForObject(
                "SELECT coalesce(max(revision),0) FROM control.ontology_revisions WHERE ontology_id=? AND status='ACTIVE'",
                Long.class, ontologyId);
        return revision == null ? 0 : revision;
    }

    public List<OntologyMember> members(UUID ontologyId) {
        return jdbc.query("""
                SELECT member_id,member_type,display_name,role,created_at
                FROM control.ontology_members WHERE ontology_id=? ORDER BY display_name,member_id
                """, (rs, row) -> new OntologyMember(rs.getString("member_id"), rs.getString("member_type"),
                rs.getString("display_name"), rs.getString("role"), rs.getTimestamp("created_at").toInstant()),
                ontologyId);
    }

    @Transactional
    public OntologyMember putMember(UUID ontologyId, PutOntologyMemberRequest request) {
        String memberId = text(request.memberId(), 240, "成员 ID");
        String memberType = enumValue(request.memberType(), List.of("GROUP", "SERVICE", "USER"), "成员类型");
        String role = enumValue(request.role(),
                List.of("ADMINISTRATOR", "BUILDER", "OPERATOR", "OWNER", "VIEWER"), "成员角色");
        jdbc.update("""
                INSERT INTO control.ontology_members(ontology_id,member_id,member_type,display_name,role)
                VALUES (?,?,?,?,?)
                ON CONFLICT (ontology_id,member_id) DO UPDATE
                SET member_type=excluded.member_type,display_name=excluded.display_name,
                    role=excluded.role,updated_at=now()
                """, ontologyId, memberId, memberType, memberId, role);
        return members(ontologyId).stream().filter(member -> member.memberId().equals(memberId)).findFirst().orElseThrow();
    }

    @Transactional
    public void removeMember(UUID ontologyId, String memberId) {
        String role = jdbc.queryForObject("""
                SELECT role FROM control.ontology_members WHERE ontology_id=? AND member_id=?
                """, String.class, ontologyId, memberId);
        if ("OWNER".equals(role)) {
            long owners = jdbc.queryForObject("""
                    SELECT count(*) FROM control.ontology_members WHERE ontology_id=? AND role='OWNER'
                    """, Long.class, ontologyId);
            if (owners <= 1) throw new ResponseStatusException(CONFLICT, "不能移除本体的最后一位 Owner");
        }
        jdbc.update("DELETE FROM control.ontology_members WHERE ontology_id=? AND member_id=?",
                ontologyId, memberId);
    }

    public record OntologyMember(String memberId, String memberType, String displayName,
                                 String role, Instant addedAt) { }
    public record PutOntologyMemberRequest(String memberId, String memberType, String role) { }

    @Transactional
    public OntologyView update(UUID id, UpdateOntologyRequest request, long expectedEtag) {
        if (request == null || (request.displayName() == null && request.description() == null)) {
            throw invalid("至少需要更新一个字段");
        }
        OntologyView current = get(id);
        if (current.updatedAt().toEpochMilli() != expectedEtag) {
            throw new ResponseStatusException(CONFLICT, "本体已被其他用户更新");
        }
        String displayName = request.displayName() == null
                ? current.displayName() : text(request.displayName(), 240, "本体名称");
        String description = request.description() == null ? current.description() : request.description().trim();
        if (description.length() > 4000) throw invalid("场景说明不能超过 4000 个字符");
        jdbc.update("""
                UPDATE control.ontologies
                SET display_name=?,description=?,updated_at=date_trunc('milliseconds',now())
                WHERE id=?
                """, displayName, description, id);
        return get(id);
    }

    private String text(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw invalid(field + "不能为空");
        if (normalized.length() > maxLength) throw invalid(field + "不能超过 " + maxLength + " 个字符");
        return normalized;
    }

    private String enumValue(String value, List<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid(field + "无效");
        return normalized;
    }

    private ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(UNPROCESSABLE_ENTITY, detail);
    }
}
