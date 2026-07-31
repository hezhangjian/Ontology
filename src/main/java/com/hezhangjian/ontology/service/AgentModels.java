package com.hezhangjian.ontology.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentModels {
    private AgentModels() { }

    public record Conversation(UUID id, String ontologyId, String title, Instant createdAt, Instant updatedAt,
                               List<Message> messages) { }
    public record Message(UUID id, String role, String content, Instant createdAt, List<ToolTrace> tools) { }
    public record ToolTrace(
            String id, String name, Map<String, Object> arguments, Object result, boolean mutationPreview) { }
    public record CreateConversationRequest(String title) { }
    public record SendMessageRequest(String content) { }
    public record ConfirmActionRequest(UUID actionTypeId, String previewToken) { }
    public record AgentReply(Conversation conversation, Message message) { }
    public record StreamEvent(String type, Object data) { }
}
