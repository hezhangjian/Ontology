package com.hezhangjian.ontology.agent;

import static com.hezhangjian.ontology.agent.AgentModels.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/conversations")
final class ConversationController {
    private final AgentService agents;
    private final ConversationStore conversations;
    private final OntologyToolClient tools;

    ConversationController(AgentService agents, ConversationStore conversations, OntologyToolClient tools) {
        this.agents = agents; this.conversations = conversations; this.tools = tools;
    }

    @GetMapping List<Conversation> list(@RequestHeader("X-Ontology-Id") String ontologyId) {
        return conversations.list(ontologyId);
    }

    @PostMapping ResponseEntity<Conversation> create(@RequestBody(required = false) CreateConversationRequest request,
                                                     @RequestHeader("X-Ontology-Id") String ontologyId) {
        Conversation value = conversations.create(ontologyId, request == null ? null : request.title());
        return ResponseEntity.created(URI.create("/v1/conversations/" + value.id())).body(value);
    }

    @GetMapping("/{id}") Conversation get(@PathVariable UUID id,
                                           @RequestHeader("X-Ontology-Id") String ontologyId) {
        return conversations.get(id, ontologyId);
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<StreamEvent>> send(@PathVariable UUID id, @RequestBody SendMessageRequest request,
                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestHeader("X-Ontology-Id") String ontologyId,
                                            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-cache");
        response.getHeaders().add("X-Accel-Buffering", "no");
        return Flux.<ServerSentEvent<StreamEvent>>create(sink -> Schedulers.boundedElastic().schedule(() -> {
            try {
                agents.stream(id, request.content(), new OntologyToolClient.RequestContext(authorization, ontologyId),
                        event -> sink.next(ServerSentEvent.builder(event).event(event.type()).build()));
            } catch (Exception failure) {
                String detail = failure.getMessage() == null ? "Agent 请求失败" : failure.getMessage();
                sink.next(ServerSentEvent.builder(new StreamEvent("error", Map.of("detail", detail))).event("error").build());
            } finally {
                sink.complete();
            }
        }));
    }

    @PostMapping("/{id}/confirm-action")
    Mono<Object> confirmAction(@PathVariable UUID id, @RequestBody ConfirmActionRequest request,
                               @RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestHeader("X-Ontology-Id") String ontologyId) {
        return Mono.fromCallable(() -> {
            conversations.get(id, ontologyId);
            return tools.confirmAction(Map.of("actionId", request.actionId(), "idempotencyKey", request.idempotencyKey(),
                    "previewToken", request.previewToken()), new OntologyToolClient.RequestContext(authorization, ontologyId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/confirm-rule-transform")
    Mono<Object> confirmRuleTransform(@PathVariable UUID id, @RequestBody Map<String, Object> request,
                                      @RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestHeader("X-Ontology-Id") String ontologyId) {
        return Mono.fromCallable(() -> {
            conversations.get(id, ontologyId);
            return tools.confirmRuleTransform(request, new OntologyToolClient.RequestContext(authorization, ontologyId));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
