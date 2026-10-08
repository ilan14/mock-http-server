package com.lan.mock.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mock")
public record MockProperties(@NotNull String reply, @Min(1) int chunkSize,
                             @Min(1) long streamTimeoutMs) {
}
