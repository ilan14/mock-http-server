package com.lan.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 服务级不可变配置；完整替换，请求通过快照隔离后续更新。 */
public record Settings(
        @JsonProperty(required = true) @NotNull Scenario scenario,
        @JsonProperty(required = true) @Min(0) @Max(60000) long delayMs,
        @JsonProperty(required = true) @Min(0) @Max(60000) long jitterMs,
        @JsonProperty(required = true) @Min(0) @Max(10000) long frameIntervalMs,
        @JsonProperty(required = true) @Min(1) @Max(1000) int frames,
        @JsonProperty(required = true) @Min(0) int faultAfterFrames,
        @JsonProperty(required = true) int errorStatus,
        @JsonProperty(required = true) @DecimalMin("0") @DecimalMax("1") double failureRate,
        @JsonProperty(required = true) @NotNull @Size(max = 4096) String content) {
    @JsonIgnore
    @AssertTrue(message = "faultAfterFrames must not exceed frames")
    public boolean isFaultWithinFrames() {
        return faultAfterFrames <= frames;
    }

    @JsonIgnore
    @AssertTrue(message = "errorStatus must be 429, 500 or 503")
    public boolean isSupportedErrorStatus() {
        return errorStatus == 429 || errorStatus == 500 || errorStatus == 503;
    }

    @JsonIgnore
    @AssertTrue(message = "failureRate must be finite")
    public boolean isFiniteFailureRate() {
        return Double.isFinite(failureRate);
    }

    public static Settings defaults() {
        return new Settings(Scenario.NORMAL, 0, 0, 20, 5, 2, 503, 1, "ok");
    }
}
