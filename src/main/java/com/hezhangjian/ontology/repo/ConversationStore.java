package com.hezhangjian.ontology.repo;

import static com.hezhangjian.ontology.service.AgentModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.service.OntologyLookupService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ConversationStore {
    private final SqlRepository jdbc;
    private final ObjectMapper json;
    private final OntologyLookupService ontologies;

    public ConversationStore(
            SqlRepository jdbc, ObjectMapper json, OntologyLookupService ontologies) {
        this.jdbc = jdbc;
        this.json = json;
        this.ontologies = ontologies;
    }

    public Conversation create(String ontologyId, String title) {
        UUID id = UUID.randomUUID();
        UUID resolvedOntologyId = resolve(ontologyId);
        Instant now = Instant.now();
        String value = title == null || title.isBlank() ? "新对话" : title.trim();
        jdbc.update("INSERT INTO control.agent_conversations (id, ontology_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                id, resolvedOntologyId, value, Timestamp.from(now), Timestamp.from(now));
        return new Conversation(id, ontologyId, value, now, now, List.of());
    }

    public List<Conversation> list(String ontologyId) {
        return jdbc.query("SELECT id, ontology_id, title, created_at, updated_at FROM control.agent_conversations WHERE ontology_id = ? ORDER BY updated_at DESC",
                (result, row) -> conversation(result, ontologyId, List.of()), resolve(ontologyId));
    }

    public Conversation get(UUID id, String ontologyId) {
        List<Conversation> values = jdbc.query("SELECT id, ontology_id, title, created_at, updated_at FROM control.agent_conversations WHERE id = ? AND ontology_id = ?",
                (result, row) -> conversation(result, ontologyId, List.of()), id, resolve(ontologyId));
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前本体中不存在该对话");
        Conversation value = values.getFirst();
        return new Conversation(value.id(), value.ontologyId(), value.title(), value.createdAt(), value.updatedAt(), messages(id));
    }

    @Transactional
    public Message append(
            UUID id, String ontologyId, String role, String content, List<ToolTrace> tools) {
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

    private Conversation conversation(
            ResultSet result, String ontologyApiName, List<Message> messages)
            throws SQLException {
        return new Conversation(result.getObject("id", UUID.class), ontologyApiName, result.getString("title"),
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

    private UUID resolve(String ontologyId) {
        return ontologies.resolve(ontologyId);
    }
}
