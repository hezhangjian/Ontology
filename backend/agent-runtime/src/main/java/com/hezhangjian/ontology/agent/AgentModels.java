package com.hezhangjian.ontology.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AgentModels {
    private AgentModels() { }

    record Conversation(UUID id, String ontologyId, String title, Instant createdAt, Instant updatedAt,
                        List<Message> messages) { }
    record Message(UUID id, String role, String content, Instant createdAt, List<ToolTrace> tools) { }
    record ToolTrace(String id, String name, Map<String, Object> arguments, Object result, boolean mutationPreview) { }
    record CreateConversationRequest(String title) { }
    record SendMessageRequest(String content) { }
    record ConfirmActionRequest(UUID actionId, String previewToken, String idempotencyKey) { }
    record AgentReply(Conversation conversation, Message message) { }
    record StreamEvent(String type, Object data) { }
}
