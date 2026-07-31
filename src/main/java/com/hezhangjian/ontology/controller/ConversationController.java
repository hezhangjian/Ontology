package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.ConversationApi;
import com.hezhangjian.ontology.model.ConfirmActionRequest;
import com.hezhangjian.ontology.model.Conversation;
import com.hezhangjian.ontology.model.CreateConversationRequest;
import com.hezhangjian.ontology.model.SendMessageRequest;
import com.hezhangjian.ontology.service.ConversationService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RequiredArgsConstructor
@RestController
public class ConversationController implements ConversationApi {
    private final ConversationService conversationService;

    @Override
    public ResponseEntity<Object> confirmAction(
            String idempotencyKey,
            UUID agentId,
            UUID id,
            String ontologyId,
            ConfirmActionRequest confirmActionRequest) {
        return ResponseEntity.ok(conversationService.confirmAction(
                id, confirmActionRequest, idempotencyKey, ontologyId));
    }

    @Override
    public ResponseEntity<Object> confirmRuleTransform(
            UUID agentId,
            UUID id,
            String ontologyId,
            Map<String, Object> requestBody) {
        return ResponseEntity.ok(conversationService.confirmRuleTransform(
                id, requestBody, ontologyId));
    }

    @Override
    public ResponseEntity<Conversation> createConversation(
            UUID agentId,
            String ontologyId,
            CreateConversationRequest createConversationRequest) {
        Conversation conversation =
                conversationService.create(ontologyId, createConversationRequest);
        URI location = URI.create("/v1/ontologies/" + ontologyId + "/agents/" + agentId
                + "/conversations/" + conversation.getId());
        return ResponseEntity.created(location).body(conversation);
    }

    @Override
    public ResponseEntity<Conversation> getConversation(
            UUID agentId, UUID id, String ontologyId) {
        return ResponseEntity.ok(conversationService.get(id, ontologyId));
    }

    @Override
    public ResponseEntity<List<Conversation>> listConversations(
            UUID agentId, String ontologyId) {
        return ResponseEntity.ok(conversationService.list(ontologyId));
    }

    @Override
    public ResponseEntity<SseEmitter> send(
            UUID agentId,
            UUID id,
            String ontologyId,
            SendMessageRequest sendMessageRequest) {
        return ResponseEntity.ok(conversationService.stream(
                id, sendMessageRequest, ontologyId));
    }
}
