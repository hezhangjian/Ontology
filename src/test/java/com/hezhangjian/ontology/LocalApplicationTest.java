package com.hezhangjian.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@DirtiesContext
@SpringBootTest(properties = {"spring.config.import=", "spring.profiles.active="})
class LocalApplicationTest {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mvc;

    @Test
    void defaultProfileSupportsOntologyCrudInMemory() throws Exception {
        assertThat(environment.getDefaultProfiles()).containsExactly("local");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }

        mvc.perform(get("/v1/ontologies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        var created = mvc.perform(post("/v1/ontologies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"local-test","name":"Local test"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("local-test"))
                .andReturn();

        mvc.perform(get("/v1/ontologies/local-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Local test"));

        mvc.perform(patch("/v1/ontologies/local-test")
                        .header("If-Match", created.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated local test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated local test"));

        mvc.perform(delete("/v1/ontologies/local-test"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/ontologies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
