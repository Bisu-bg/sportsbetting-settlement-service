package com.sportsbetting.settlement.api;

import tools.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.messaging-enabled=false", "spring.datasource.url=jdbc:h2:mem:openapi;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class TestOpenApiIntegration {
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper objectMapper;

    @Test
    void generatedContractShowsRoutesAndSchemasAndSwaggerUiLoads() throws Exception {
        var response = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse();
        var document = objectMapper.readTree(response.getContentAsString());

        assertThat(document.path("openapi").asText()).startsWith("3.");
        var paths = new HashSet<String>();
        document.path("paths").properties().forEach(entry -> paths.add(entry.getKey()));
        assertThat(paths).containsExactlyInAnyOrder("/api/bets", "/api/bets/{id}", "/api/event-outcomes");

        var placeBet = document.path("paths").path("/api/bets").path("post");
        assertThat(placeBet.path("responses").has("201")).isTrue();
        assertThat(placeBet.path("responses").path("201").path("content").has("application/json")).isTrue();
        assertThat(placeBet.path("requestBody").path("content").path("application/json")
                .path("schema").path("$ref").asText()).endsWith("/PlaceBet");
        assertThat(document.path("components").path("schemas").path("PlaceBet")
                .path("properties").has("betAmount")).isTrue();
        assertThat(document.path("components").path("schemas").path("PlaceBet")
                .path("properties").path("betAmount").has("example")).isFalse();

        var publish = document.path("paths").path("/api/event-outcomes").path("post");
        assertThat(publish.path("responses").has("202")).isTrue();
        assertThat(document.path("paths").path("/api/bets/{id}").path("get")
                .path("responses").has("200")).isTrue();

        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void invalidRequestsUseDocumentedProblemDetails() throws Exception {
        mockMvc.perform(post("/api/bets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")));
    }
}
