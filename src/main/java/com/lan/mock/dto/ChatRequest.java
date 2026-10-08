package com.lan.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(@NotBlank String model,
                          @NotEmpty List<@NotNull @Valid Message> messages,
                          // 可选布尔字段缺失或为 null 时按 false 处理，兼容 Jackson 3 的严格基本类型校验。
                          @JsonSetter(nulls = Nulls.AS_EMPTY) boolean stream,
                          @JsonProperty("stream_options") StreamOptions streamOptions) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(@NotBlank String role, JsonNode content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamOptions(@JsonProperty("include_usage")
                                @JsonSetter(nulls = Nulls.AS_EMPTY) boolean includeUsage) {
    }
}
