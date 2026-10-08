package com.lan.mock.service;

import com.lan.mock.dto.Scenario;
import com.lan.mock.dto.Settings;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockSettingsServiceTest {
    @Test
    void validatesAllBoundsBeforeReplacingSettings() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            MockSettingsService service = new MockSettingsService(factory.getValidator());
            Settings original = service.current();
            Settings[] invalid = {
                new Settings(null, 0, 0, 0, 1, 0, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, -1, 0, 0, 1, 0, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, 60001, 0, 0, 1, 0, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, 0, 60001, 0, 1, 0, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 10001, 1, 0, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 0, 0, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1001, 0, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, -1, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, 2, 503, 1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, 0, 400, 1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, 0, 503, -0.1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, 0, 503, 1.1, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, 0, 503, Double.NaN, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, 0, 503, Double.POSITIVE_INFINITY, "ok"),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, 0, 503, 1, null),
                new Settings(Scenario.NORMAL, 0, 0, 0, 1, 0, 503, 1, "x".repeat(4097))
            };
            for (Settings value : invalid) {
                assertThatThrownBy(() -> service.update(value)).isInstanceOf(ConstraintViolationException.class);
                assertThat(service.current()).isSameAs(original);
            }
            Settings boundary = new Settings(Scenario.SLOW, 60000, 60000, 10000, 1000, 1000, 429, 0, "");
            assertThat(service.update(boundary)).isSameAs(boundary);
            assertThat(service.current()).isSameAs(boundary);
        }
    }
}
