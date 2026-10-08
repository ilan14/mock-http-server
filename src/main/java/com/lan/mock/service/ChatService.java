package com.lan.mock.service;

import com.lan.mock.config.MockProperties;
import com.lan.mock.dto.ChatRequest;
import com.lan.mock.dto.Scenario;
import com.lan.mock.dto.Settings;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 使用每请求 Settings 快照模拟上游；Servlet 异步请求释放 Tomcat 工作线程。
 * 定时器只触发任务，应用执行器执行可能阻塞的写入，不用 sleep 模拟等待。
 */
@Service
public class ChatService {
    private final MockProperties properties;
    private final MockSettingsService settings;
    private final TaskExecutor executor;
    private final ThreadPoolTaskScheduler scheduler;

    public ChatService(MockProperties properties, MockSettingsService settings,
                       @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                       ThreadPoolTaskScheduler mockScheduler) {
        this.properties = properties;
        this.settings = settings;
        this.executor = executor;
        this.scheduler = mockScheduler;
    }

    public DeferredResult<ResponseEntity<?>> respond(ChatRequest request) {
        Exchange exchange = new Exchange(request, settings.current());
        exchange.start();
        return exchange.result;
    }

    /** 单次请求的调度和关闭状态，运行中只读取 snapshot，不再读取全局配置。 */
    private final class Exchange {
        private final ChatRequest request;
        private final Settings snapshot;
        private final DeferredResult<ResponseEntity<?>> result;
        private final String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        private final long created = Instant.now().getEpochSecond();
        private final long started = System.nanoTime();
        private volatile boolean closed;
        private volatile SseEmitter emitter;
        private ScheduledFuture<?> pending;
        private ScheduledFuture<?> deadline;

        private Exchange(ChatRequest request, Settings snapshot) {
            this.request = request;
            this.snapshot = snapshot;
            result = new DeferredResult<>(properties.streamTimeoutMs());
            result.onTimeout(this::timeout);
            result.onError(error -> close());
            result.onCompletion(this::close);
        }

        private synchronized void start() {
            // 独立截止任务包含初始等待和后续流式阶段，避免切换异步阶段重新计时。
            try {
                deadline = scheduler.schedule(this::timeout,
                        Instant.now().plusMillis(properties.streamTimeoutMs()));
            } catch (RuntimeException rejected) {
                unavailable();
                return;
            }
            long jitter = snapshot.jitterMs() == 0 ? 0
                    : ThreadLocalRandom.current().nextLong(snapshot.jitterMs() + 1);
            schedule(snapshot.delayMs() + jitter, this::begin);
        }

        private synchronized void schedule(long delayMs, Runnable action) {
            if (closed) {
                return;
            }
            try {
                pending = scheduler.schedule(() -> {
                    if (closed) {
                        return;
                    }
                    try {
                        executor.execute(() -> {
                            if (!closed) {
                                action.run();
                            }
                        });
                    } catch (RuntimeException rejected) {
                        unavailable();
                    }
                }, Instant.now().plusMillis(delayMs));
            } catch (RuntimeException rejected) {
                unavailable();
            }
        }

        private void begin() {
            if (snapshot.scenario() == Scenario.HANG) {
                return; // 不写入数据；断连可能无法立即感知，最终由总超时回收。
            }
            if (snapshot.scenario() == Scenario.ERROR
                    && ThreadLocalRandom.current().nextDouble() < snapshot.failureRate()) {
                finishJson(snapshot.errorStatus(), error(snapshot.errorStatus(), "Simulated upstream failure"));
                return;
            }
            if (!request.stream()) {
                Map<String, Object> response = envelope(false);
                response.put("choices", List.of(choice("message",
                        Map.of("role", "assistant", "content", snapshot.content()), "stop")));
                response.put("usage", usage());
                finishJson(200, response);
                return;
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                long remaining = Math.max(1, properties.streamTimeoutMs()
                        - (System.nanoTime() - started) / 1_000_000);
                emitter = new SseEmitter(remaining);
                emitter.onCompletion(this::close);
                emitter.onTimeout(this::timeout);
                emitter.onError(error -> close());
                // 只有确认不是初始 HTTP 错误之后，才交给 MVC 提交 SSE 响应。
                result.setResult(ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                        .header("Cache-Control", "no-cache").body(emitter));
            }
            frame(0);
        }

        private void frame(int index) {
            if (closed) {
                return;
            }
            boolean fault = snapshot.scenario() == Scenario.STREAM_ERROR
                    || snapshot.scenario() == Scenario.STREAM_STALL;
            int count = fault ? snapshot.faultAfterFrames() : snapshot.frames();
            try {
                if (index < count) {
                    if (index == 0) {
                        send(chunk(Map.of("role", "assistant", "content", ""), null));
                    }
                    send(chunk(Map.of("content", snapshot.content()), null));
                    // 每次只安排下一帧，保持顺序，避免大量定时任务提前排队。
                    if (index + 1 < count) {
                        schedule(snapshot.frameIntervalMs(), () -> frame(index + 1));
                        return;
                    }
                }
                if (snapshot.scenario() == Scenario.STREAM_STALL) {
                    return;
                }
                if (snapshot.scenario() == Scenario.STREAM_ERROR) {
                    // 已提交 HTTP 200，错误通过 SSE data 表达，不能再修改 HTTP 状态。
                    send(error(snapshot.errorStatus(), "Simulated stream failure"));
                } else {
                    send(chunk(Map.of(), "stop"));
                    if (includeUsage()) {
                        Map<String, Object> response = envelope(true);
                        response.put("choices", List.of());
                        response.put("usage", usage());
                        send(response);
                    }
                    if (!closed) {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                    }
                }
                finishStream();
            } catch (IOException exception) {
                // 写入 IO 错误由 Servlet 容器发起错误通知，不再次调用 completeWithError。
                close();
            } catch (IllegalStateException exception) {
                finishStream();
            }
        }

        private void send(Object data) throws IOException {
            if (!closed) {
                emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
            }
        }

        private synchronized void finishJson(int status, Object body) {
            if (!closed) {
                result.setResult(ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body));
                close();
            }
        }

        private synchronized void finishStream() {
            if (!closed) {
                close();
                emitter.complete();
            }
        }

        private synchronized void timeout() {
            if (closed) {
                return;
            }
            if (emitter == null) {
                finishJson(504, error(504, "Mock request lifetime exceeded"));
            } else {
                finishStream(); // 不发送 stop/[DONE]，避免将超时误报为成功。
            }
        }

        private synchronized void unavailable() {
            if (emitter == null) {
                finishJson(503, error(503, "Mock executor unavailable"));
            } else {
                finishStream();
            }
        }

        private synchronized void close() {
            closed = true;
            if (pending != null) {
                pending.cancel(false);
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
        }

        private Map<String, Object> chunk(Map<String, String> delta, String reason) {
            Map<String, Object> response = envelope(true);
            response.put("choices", List.of(choice("delta", delta, reason)));
            if (includeUsage()) {
                response.put("usage", null);
            }
            return response;
        }

        private Map<String, Object> envelope(boolean streaming) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", id);
            response.put("object", streaming ? "chat.completion.chunk" : "chat.completion");
            response.put("created", created);
            response.put("model", request.model());
            return response;
        }

        private boolean includeUsage() {
            return request.streamOptions() != null && request.streamOptions().includeUsage();
        }
    }

    private Map<String, Object> choice(String field, Map<String, String> value, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put(field, value);
        choice.put("logprobs", null);
        choice.put("finish_reason", finishReason);
        return choice;
    }

    private Map<String, Integer> usage() {
        // 合成统计值，不代表真实 tokenizer 计数。
        return Map.of("prompt_tokens", 0, "completion_tokens", 0, "total_tokens", 0);
    }

    private Map<String, Object> error(int status, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", status == 429 ? "rate_limit_error" : "server_error");
        error.put("param", null);
        error.put("code", Integer.toString(status));
        return Map.of("error", error);
    }
}
