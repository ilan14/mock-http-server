package com.lan.mock.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mock")
public record MockProperties(@Min(1) long streamTimeoutMs) {
}
