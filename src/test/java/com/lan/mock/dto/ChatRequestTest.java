package com.lan.mock.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void omittedStreamDefaultsToFalse() {
        ChatRequest request = mapper.readValue("""
                {"model":"mock","messages":[{"role":"user","content":"Hi"}]}
                """, ChatRequest.class);

        assertThat(request.stream()).isFalse();
    }

    @Test
    void explicitNullFlagsDefaultToFalse() {
        ChatRequest request = mapper.readValue("""
                {"model":"mock","messages":[{"role":"user","content":"Hi"}],
                 "stream":null,"stream_options":{"include_usage":null}}
                """, ChatRequest.class);

        assertThat(request.stream()).isFalse();
        assertThat(request.streamOptions().includeUsage()).isFalse();
    }

    @Test
    void omittedIncludeUsageDefaultsToFalse() {
        ChatRequest request = mapper.readValue("""
                {"model":"mock","messages":[{"role":"user","content":"Hi"}],
                 "stream":true,"stream_options":{}}
                """, ChatRequest.class);

        assertThat(request.stream()).isTrue();
        assertThat(request.streamOptions().includeUsage()).isFalse();
    }
}
