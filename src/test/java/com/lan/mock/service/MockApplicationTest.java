package com.lan.mock.service;


import com.lan.mock.MockApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MockApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mock.reply=你好🌍 mock", "mock.chunk-size=1"})
class MockApplicationTest {
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private ServletWebServerApplicationContext context;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void returnsCompletionThroughTomcatAndAcceptsExtraFields() throws Exception {
        assertThat(context.getWebServer()).isInstanceOf(TomcatWebServer.class);
        var response = post("""
                {"model":"mock-model","messages":[{"role":"user","content":"Hi"}],
                 "temperature":0.5,"max_tokens":100}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("id").asText()).startsWith("chatcmpl-");
        assertThat(body.path("object").asText()).isEqualTo("chat.completion");
        assertThat(body.path("model").asText()).isEqualTo("mock-model");
        assertThat(body.at("/choices/0/message/content").asText()).isEqualTo("你好🌍 mock");
        assertThat(body.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(body.at("/usage/total_tokens").asInt()).isZero();
    }

    @Test
    void streamsValidEventsAndOptionalUsage() throws Exception {
        var response = post("""
                {"model":"mock-model","messages":[{"role":"user","content":[
                  {"type":"text","text":"Hi"}]}],"stream":true,
                 "stream_options":{"include_usage":true}}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        List<String> data = response.body().lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).strip()).toList();
        assertThat(data.get(data.size() - 1)).isEqualTo("[DONE]");
        List<JsonNode> chunks = new ArrayList<>();
        for (String event : data.subList(0, data.size() - 1)) {
            chunks.add(mapper.readTree(event));
        }
        assertThat(chunks.get(0).at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        StringBuilder content = new StringBuilder();
        for (JsonNode chunk : chunks) {
            assertThat(chunk.path("id")).isEqualTo(chunks.get(0).path("id"));
            assertThat(chunk.path("created")).isEqualTo(chunks.get(0).path("created"));
            assertThat(chunk.path("object").asText()).isEqualTo("chat.completion.chunk");
            content.append(chunk.at("/choices/0/delta/content").asText(""));
        }
        assertThat(content.toString()).isEqualTo("你好🌍 mock");
        assertThat(chunks.get(chunks.size() - 2).at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(chunks.get(chunks.size() - 1).path("choices")).isEmpty();
        assertThat(chunks.get(chunks.size() - 1).at("/usage/total_tokens").asInt()).isZero();
    }

    @Test
    void streamingWithoutUsageEndsWithStopAndDone() throws Exception {
        var response = post("""
                {"model":"mock","messages":[{"role":"user","content":"Hi"}],"stream":true}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"finish_reason\":\"stop\"").doesNotContain("\"usage\"");
        assertThat(response.body().strip()).endsWith("data:[DONE]");
    }

    @Test
    void rejectsInvalidRequestsWithOpenAiErrorShape() throws Exception {
        for (String request : List.of(
                "{\"model\":\"\",\"messages\":[]}",
                "{\"model\":\"mock\",\"messages\":[null]}",
                "{\"model\":\"mock\",\"messages\":[{\"role\":\"\"}]}",
                "{", "{\"model\":\"mock\",\"messages\":\"invalid\"}")) {
            var response = post(request);
            assertThat(response.statusCode()).isEqualTo(400);
            JsonNode error = mapper.readTree(response.body()).path("error");
            assertThat(error.path("type").asText()).isEqualTo("invalid_request_error");
            assertThat(error.path("message").asText()).isNotBlank();
            assertThat(error.has("param")).isTrue();
            assertThat(error.has("code")).isTrue();
        }
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer mock-key")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
