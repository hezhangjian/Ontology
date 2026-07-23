package com.hezhangjian.ontology.agent;

import static com.hezhangjian.ontology.agent.AgentModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ConversationStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Conversation create(String ontologyId, String title) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String value = title == null || title.isBlank() ? "新对话" : title.trim();
        jdbc.update("INSERT INTO control.agent_conversations (id, ontology_id, title, created_at, updated_at) VALUES (?, ?::uuid, ?, ?, ?)",
                id, ontologyId, value, Timestamp.from(now), Timestamp.from(now));
        return new Conversation(id, ontologyId, value, now, now, List.of());
    }

    List<Conversation> list(String ontologyId) {
        return jdbc.query("SELECT id, ontology_id, title, created_at, updated_at FROM control.agent_conversations WHERE ontology_id = ?::uuid ORDER BY updated_at DESC",
                (result, row) -> conversation(result, List.of()), ontologyId);
    }

    Conversation get(UUID id, String ontologyId) {
        List<Conversation> values = jdbc.query("SELECT id, ontology_id, title, created_at, updated_at FROM control.agent_conversations WHERE id = ? AND ontology_id = ?::uuid",
                (result, row) -> conversation(result, List.of()), id, ontologyId);
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前本体中不存在该对话");
        Conversation value = values.getFirst();
        return new Conversation(value.id(), value.ontologyId(), value.title(), value.createdAt(), value.updatedAt(), messages(id));
    }

    @Transactional
    Message append(UUID id, String ontologyId, String role, String content, List<ToolTrace> tools) {
        Conversation conversation = get(id, ontologyId);
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();
        List<ToolTrace> traces = tools == null ? List.of() : List.copyOf(tools);
        jdbc.update("INSERT INTO control.agent_messages (id, conversation_id, role, content, tools, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                messageId, id, role, content, write(traces), Timestamp.from(now));
        String title = conversation.title();
        if ("user".equals(role) && "新对话".equals(title)) {
            title = content.length() > 28 ? content.substring(0, 28) + "…" : content;
        }
        jdbc.update("UPDATE control.agent_conversations SET title = ?, updated_at = ? WHERE id = ?", title, Timestamp.from(now), id);
        return new Message(messageId, role, content, now, traces);
    }

    private List<Message> messages(UUID conversationId) {
        return jdbc.query("SELECT id, role, content, tools, created_at FROM control.agent_messages WHERE conversation_id = ? ORDER BY created_at, id",
                (result, row) -> new Message(result.getObject("id", UUID.class), result.getString("role"),
                        result.getString("content"), result.getTimestamp("created_at").toInstant(), readTools(result.getString("tools"))), conversationId);
    }

    private Conversation conversation(ResultSet result, List<Message> messages) throws SQLException {
        return new Conversation(result.getObject("id", UUID.class), result.getString("ontology_id"), result.getString("title"),
                result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(), messages);
    }

    private List<ToolTrace> readTools(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception failure) { throw new IllegalStateException("无法读取已存储的工具调用", failure); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("无法存储工具调用", failure); }
    }
}
