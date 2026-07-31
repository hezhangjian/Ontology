package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.repo.ConversationStore;
import com.hezhangjian.ontology.model.ConfirmActionRequest;
import com.hezhangjian.ontology.model.Conversation;
import com.hezhangjian.ontology.model.CreateConversationRequest;
import com.hezhangjian.ontology.model.SendMessageRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

@RequiredArgsConstructor
@Service
public class ConversationService {
    private final AgentService agents;
    private final ConversationStore conversations;
    private final ObjectMapper objectMapper;
    private final OntologyToolClient tools;

    public List<Conversation> list(String ontologyApiName) {
        return conversations.list(ontologyApiName).stream().map(this::toModel).toList();
    }

    public Conversation create(String ontologyApiName, CreateConversationRequest request) {
        String title = request == null ? null : request.getTitle();
        return toModel(conversations.create(ontologyApiName, title));
    }

    public Conversation get(UUID conversationId, String ontologyApiName) {
        return toModel(conversations.get(conversationId, ontologyApiName));
    }

    public SseEmitter stream(
            UUID conversationId,
            SendMessageRequest request,
            String ontologyApiName) {
        SseEmitter emitter = new SseEmitter(0L);
        Schedulers.boundedElastic().schedule(() -> {
            try {
                agents.stream(
                        conversationId,
                        request.getContent(),
                        new OntologyToolClient.RequestContext(ontologyApiName),
                        event -> send(emitter, event));
            } catch (Exception failure) {
                String detail = failure.getMessage() == null
                        ? "Agent request failed"
                        : failure.getMessage();
                send(emitter, new AgentModels.StreamEvent("error", Map.of("detail", detail)));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    public Object confirmAction(
            UUID conversationId,
            ConfirmActionRequest request,
            String idempotencyKey,
            String ontologyApiName) {
        conversations.get(conversationId, ontologyApiName);
        return tools.confirmAction(
                Map.of(
                        "actionId", request.getActionTypeId(),
                        "idempotencyKey", idempotencyKey,
                        "previewToken", request.getPreviewToken()),
                new OntologyToolClient.RequestContext(ontologyApiName));
    }

    public Object confirmRuleTransform(
            UUID conversationId,
            Map<String, Object> request,
            String ontologyApiName) {
        conversations.get(conversationId, ontologyApiName);
        return tools.confirmRuleTransform(
                request,
                new OntologyToolClient.RequestContext(ontologyApiName));
    }

    private void send(SseEmitter emitter, AgentModels.StreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write agent stream event", failure);
        }
    }

    private Conversation toModel(AgentModels.Conversation source) {
        return objectMapper.convertValue(source, Conversation.class);
    }
}
