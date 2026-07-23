package com.hezhangjian.ontology.core.modeling;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
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
                LEFT JOIN control.ontology_resources r ON r.ontology_id=o.id
                GROUP BY o.id ORDER BY o.display_name
                """, (rs, row) -> new OntologyView(rs.getObject("id", UUID.class), rs.getString("api_name"),
                rs.getString("display_name"), rs.getString("description"), rs.getString("icon"),
                rs.getString("color"), rs.getLong("object_type_count"), rs.getLong("link_type_count"),
                rs.getTimestamp("updated_at").toInstant()));
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
        } catch (DataIntegrityViolationException failure) {
            throw new ResponseStatusException(CONFLICT, "API 名称已被其他本体使用", failure);
        }
        return new OntologyView(id, apiName, displayName, description, "deployment-unit",
                color.toLowerCase(Locale.ROOT), 0, 0, Instant.now());
    }

    public UUID resolve(String value) {
        UUID id;
        try { id = value == null || value.isBlank() ? DEFAULT_ONTOLOGY_ID : UUID.fromString(value); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(NOT_FOUND, "本体不存在"); }
        if (jdbc.queryForObject("SELECT count(*) FROM control.ontologies WHERE id=?", Long.class, id) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "本体不存在");
        }
        return id;
    }

    public record OntologyView(UUID id, String apiName, String displayName, String description,
                               String icon, String color, long objectTypeCount, long linkTypeCount,
                               Instant updatedAt) { }

    public record CreateOntologyRequest(String apiName, String displayName, String description, String color) { }

    private String text(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw invalid(field + "不能为空");
        if (normalized.length() > maxLength) throw invalid(field + "不能超过 " + maxLength + " 个字符");
        return normalized;
    }

    private ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(UNPROCESSABLE_ENTITY, detail);
    }
}
