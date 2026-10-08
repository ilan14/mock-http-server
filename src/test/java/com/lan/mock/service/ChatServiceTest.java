package com.lan.mock.service;

import com.lan.mock.config.MockProperties;
import com.lan.mock.dto.ChatRequest;
import com.lan.mock.dto.Scenario;
import com.lan.mock.dto.Settings;
import jakarta.validation.Validation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ChatServiceTest {
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final jakarta.validation.ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
    private final MockSettingsService settings = new MockSettingsService(validators.getValidator());

    @BeforeEach
    void startScheduler() {
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
    }

    @AfterEach
    void stopScheduler() {
        scheduler.shutdown();
        validators.close();
    }

    @Test
    void returnsBeforeExecutorRunsAndRetainsSettingsSnapshot() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ChatService service = new ChatService(new MockProperties(2000), settings, queued::set, scheduler);
        var result = service.respond(new ChatRequest("mock", List.of(), false, null));
        await().atMost(Duration.ofSeconds(1)).until(() -> queued.get() != null);
        assertThat(result.hasResult()).isFalse();
        settings.update(new Settings(Scenario.ERROR, 0, 0, 0, 2, 1, 429, 1, "new"));

        queued.get().run();

        ResponseEntity<?> response = (ResponseEntity<?>) result.getResult();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) response.getBody()).get("object")).isEqualTo("chat.completion");
        assertThat(response.getBody().toString()).contains("content=ok");
        assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
    }

    @Test
    void deadlineCancelsPendingDelayBeforeItCanSubmitWork() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        settings.update(new Settings(Scenario.SLOW, 1000, 0, 0, 1, 0, 503, 1, "late"));
        ChatService service = new ChatService(new MockProperties(50), settings, queued::set, scheduler);
        var result = service.respond(new ChatRequest("mock", List.of(), false, null));
        await().atMost(Duration.ofSeconds(1)).until(result::hasResult);
        assertThat(((ResponseEntity<?>) result.getResult()).getStatusCode().value()).isEqualTo(504);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty());
        assertThat(queued.get()).isNull();
    }

    @Test
    void rejectedExecutorReturns503AndCancelsDeadline() {
        ChatService service = new ChatService(new MockProperties(2000), settings,
                task -> { throw new java.util.concurrent.RejectedExecutionException(); }, scheduler);
        var result = service.respond(new ChatRequest("mock", List.of(), false, null));
        await().atMost(Duration.ofSeconds(1)).until(result::hasResult);
        assertThat(((ResponseEntity<?>) result.getResult()).getStatusCode().value()).isEqualTo(503);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty());
    }
}
