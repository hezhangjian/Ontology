package com.hezhangjian.ontology.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class ConversationStoreTest {
    private static final String IOT = "00000000-0000-0000-0000-00000000a002";
    private static final String TOKEN = "00000000-0000-0000-0000-00000000a001";

    @Test
    void isolatesConversationsByOntology() {
        ConversationStore store = store();
        AgentModels.Conversation tokenConversation = store.create(TOKEN, "Token analysis");
        AgentModels.Conversation iotConversation = store.create(IOT, "IoT analysis");

        assertEquals(List.of(tokenConversation.id()), store.list(TOKEN).stream().map(AgentModels.Conversation::id).toList());
        assertEquals(List.of(iotConversation.id()), store.list(IOT).stream().map(AgentModels.Conversation::id).toList());
        assertThrows(ResponseStatusException.class, () -> store.get(tokenConversation.id(), IOT));
    }

    @Test
    void persistsMessagesAndToolTraces() {
        ConversationStore store = store();
        AgentModels.Conversation conversation = store.create(TOKEN, null);
        AgentModels.ToolTrace trace = new AgentModels.ToolTrace("call-1", "list_object_types", java.util.Map.of(),
                java.util.Map.of("count", 2), false);

        store.append(conversation.id(), TOKEN, "user", "查看对象类型", List.of());
        store.append(conversation.id(), TOKEN, "assistant", "共 2 个。", List.of(trace));

        AgentModels.Conversation restored = store.get(conversation.id(), TOKEN);
        assertEquals("查看对象类型", restored.title());
        assertEquals(2, restored.messages().size());
        assertEquals(List.of(trace), restored.messages().get(1).tools());
    }

    private ConversationStore store() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA control");
        jdbc.execute("CREATE TABLE control.agent_conversations (id UUID PRIMARY KEY, ontology_id UUID NOT NULL, title VARCHAR(240) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        jdbc.execute("CREATE TABLE control.agent_messages (id UUID PRIMARY KEY, conversation_id UUID NOT NULL, role VARCHAR(16) NOT NULL, content CLOB NOT NULL, tools CLOB NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        return new ConversationStore(jdbc, new ObjectMapper());
    }
}
