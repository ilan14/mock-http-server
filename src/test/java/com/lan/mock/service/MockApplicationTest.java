package com.lan.mock.service;


import com.lan.mock.MockApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.lan.mock.dto.Scenario;
import com.lan.mock.dto.Settings;
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
        properties = {"mock.stream-timeout-ms=1500"})
class MockApplicationTest {
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private ServletWebServerApplicationContext context;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    private MockSettingsService settings;

    @BeforeEach
    void resetSettings() {
        settings.update(new Settings(Scenario.NORMAL, 0, 0, 0, 3, 2, 503, 1, "你好🌍 mock"));
    }

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
        assertThat(content.toString()).isEqualTo("你好🌍 mock".repeat(3));
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

    @Test
    void managementApiReplacesSettingsAndRejectsInvalidOrPartialUpdates() throws Exception {
        Settings value = new Settings(Scenario.SLOW, 10, 0, 20, 4, 2, 429, 0.5, "configured");
        var updated = putSettings(mapper.writeValueAsString(value));
        assertThat(updated.statusCode()).isEqualTo(200);
        var fetched = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/mock/settings"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mapper.readValue(fetched.body(), Settings.class)).isEqualTo(value);
        assertThat(mapper.readTree(fetched.body()).size()).isEqualTo(9);
        for (String invalid : List.of("{}", "{\"scenario\":\"NORMAL\"}",
                mapper.writeValueAsString(value).replace("\"frames\":4", "\"frames\":0"),
                mapper.writeValueAsString(value).replace("\"errorStatus\":429", "\"errorStatus\":400"),
                mapper.writeValueAsString(value).replace("\"scenario\":\"SLOW\"", "\"scenario\":\"UNKNOWN\""))) {
            assertThat(putSettings(invalid).statusCode()).isEqualTo(400);
            assertThat(settings.current()).isEqualTo(value);
        }
    }

    @Test
    void httpErrorsAreAppliedBeforeStreamingStartsAndProbabilityZeroSucceeds() throws Exception {
        for (int status : List.of(429, 500, 503)) {
            settings.update(new Settings(Scenario.ERROR, 30, 0, 0, 3, 2, status, 1, "ok"));
            for (boolean stream : List.of(false, true)) {
                var response = post(chatBody(stream));
                assertThat(response.statusCode()).isEqualTo(status);
                assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
                assertThat(mapper.readTree(response.body()).at("/error/code").asText()).isEqualTo("" + status);
            }
        }
        settings.update(new Settings(Scenario.ERROR, 0, 0, 0, 1, 0, 503, 0, "ok"));
        assertThat(post(chatBody(false)).statusCode()).isEqualTo(200);
        assertThat(post(chatBody(true)).body()).contains("[DONE]");
    }

    @Test
    void slowResponseAndFramesWaitWithoutUsingUpdatedSettings() throws Exception {
        settings.update(new Settings(Scenario.SLOW, 200, 30, 100, 3, 1, 503, 1, "old"));
        long start = System.nanoTime();
        var response = post(chatBody(true));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(380);
        assertThat(response.body()).contains("[DONE]");
        // 在旧请求等待期间更新：已进入 Service 的请求必须仍使用旧快照。
        var pending = settings.current();
        var result = context.getBean(ChatService.class).respond(
                new com.lan.mock.dto.ChatRequest("mock", List.of(), false, null));
        settings.update(new Settings(Scenario.ERROR, 0, 0, 0, 1, 0, 429, 1, "new"));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(result::hasResult);
        assertThat(((org.springframework.http.ResponseEntity<?>) result.getResult()).getBody().toString())
                .contains("content=" + pending.content());
    }

    @Test
    void ongoingStreamRetainsOldSettingsAfterUpdate() throws Exception {
        settings.update(new Settings(Scenario.NORMAL, 0, 0, 100, 3, 1, 503, 1, "old"));
        var response = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(chatBody(true))).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder events = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                events.append(line).append('\n');
                if (line.contains("\"content\":\"old\"")) {
                    break;
                }
            }
            assertThat(line).isNotNull();
            settings.update(new Settings(Scenario.ERROR, 0, 0, 0, 1, 0, 429, 1, "new"));
            while ((line = reader.readLine()) != null) {
                events.append(line).append('\n');
            }
            assertThat(events.toString()).contains("[DONE]").doesNotContain("new", "\"error\"");
            assertThat(events.toString().lines().filter(event -> event.contains("\"content\":\"old\"")).count())
                    .isEqualTo(3);
        }
        assertThat(post(chatBody(false)).statusCode()).isEqualTo(429);
    }

    @Test
    void streamErrorEmitsConfiguredFramesAndAnErrorWithoutSuccessMarkers() throws Exception {
        for (int count : List.of(0, 2, 3)) {
            settings.update(new Settings(Scenario.STREAM_ERROR, 0, 0, 10, 3, count, 503, 0, "piece"));
            var response = post(chatBody(true));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"error\"").doesNotContain("[DONE]", "\"finish_reason\":\"stop\"");
            long contentFrames = response.body().lines().filter(line -> line.contains("\"content\":\"piece\"")).count();
            assertThat(contentFrames).isEqualTo(count);
        }
        // 流式专用场景不影响普通调用，failureRate 也不影响 STREAM_ERROR。
        assertThat(post(chatBody(false)).statusCode()).isEqualTo(200);
    }

    @Test
    void hangAndStallAreReclaimedByLifetimeWithoutDone() throws Exception {
        settings.update(new Settings(Scenario.HANG, 0, 0, 0, 3, 2, 503, 1, "piece"));
        long start = System.nanoTime();
        assertThat(post(chatBody(false)).statusCode()).isEqualTo(504);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(1400);
        assertThat(post(chatBody(true)).statusCode()).isEqualTo(504);
        for (int count : List.of(0, 2)) {
            settings.update(new Settings(Scenario.STREAM_STALL, 0, 0, 0, 3, count, 503, 1, "piece"));
            start = System.nanoTime();
            var response = post(chatBody(true));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(1400);
            assertThat(response.body()).doesNotContain("[DONE]", "\"finish_reason\":\"stop\"");
            assertThat(response.body().lines().filter(line -> line.contains("\"content\":\"piece\"")).count())
                    .isEqualTo(count);
        }
    }

    private String chatBody(boolean stream) {
        return "{\"model\":\"mock\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":" + stream + "}";
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> putSettings(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/mock/settings"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer mock-key")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
