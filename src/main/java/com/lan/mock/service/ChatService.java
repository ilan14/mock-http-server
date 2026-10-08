package com.lan.mock.service;

import com.lan.mock.config.MockProperties;
import com.lan.mock.dto.ChatRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 生成固定内容的聊天响应；普通请求一次返回，流式请求按 SSE 协议分片发送。
 * 这里只模拟接口格式，不执行模型推理或真实 token 统计。
 */
@Service
public class ChatService {
    private final MockProperties properties;
    // Spring Boot 管理的应用执行器，由 spring.task.execution 配置；与 Tomcat 请求线程池分开。
    // 它是应用级共享执行器，并非 ChatService 私有线程池。
    private final TaskExecutor executor;

    public ChatService(MockProperties properties,
                       @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.properties = properties;
        this.executor = executor;
    }

    /** 普通响应在调用方（通常为 Tomcat 请求线程）同步构建。 */
    public Map<String, Object> complete(ChatRequest request) {
        Map<String, Object> response = envelope(request, newId(), Instant.now().getEpochSecond(), false);
        response.put("choices", List.of(choice("message",
                Map.of("role", "assistant", "content", properties.reply()), "stop")));
        response.put("usage", usage());
        return response;
    }

    /**
     * 提交流式发送任务后返回 emitter，让 Spring MVC 进入 Servlet 异步处理。
     * 请求处理线程可释放，连接保持打开；发送任务负责后续分片和结束通知。
     */
    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeoutMs());
        // 容器回调与发送任务可能在不同线程执行，用原子变量共享结束状态。
        // 这是尽力停止：检查后连接仍可能关闭，因此发送过程还需捕获异常。
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(error -> closed.set(true));
        // 避免未来的分片延迟、等待或慢客户端写入长时间占用 Tomcat 工作线程。
        // SseEmitter 本身不会自动为这段业务代码创建线程；send 仍可能阻塞。
        executor.execute(() -> {
            // 同一次流式响应的所有分片使用相同 id 和创建时间。
            String id = newId();
            long created = Instant.now().getEpochSecond();
            try {
                // 首个分片声明 assistant 角色，后续分片仅携带增量内容。
                send(emitter, closed, chunk(request, id, created,
                        Map.of("role", "assistant", "content", ""), null));
                // 按 Unicode 码点切分，避免将 emoji 等补充字符的 UTF-16 代理对拆开。
                int[] points = properties.reply().codePoints().toArray();
                for (int offset = 0; offset < points.length && !closed.get();) {
                    int size = Math.min(properties.chunkSize(), points.length - offset);
                    send(emitter, closed, chunk(request, id, created,
                            Map.of("content", new String(points, offset, size)), null));
                    offset += size;
                }
                // stop 表示内容生成结束；可选统计分片发送后，再用 [DONE] 结束整个流。
                send(emitter, closed, chunk(request, id, created, Map.of(), "stop"));
                if (includeUsage(request)) {
                    Map<String, Object> response = envelope(request, id, created, true);
                    // include_usage 的最后一个 JSON 分片只返回统计，choices 为空。
                    response.put("choices", List.of());
                    response.put("usage", usage());
                    send(emitter, closed, response);
                }
                if (!closed.get()) {
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException exception) {
                closed.set(true);
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, Object data) throws IOException {
        if (!closed.get()) {
            emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
        }
    }

    private Map<String, Object> chunk(ChatRequest request, String id, long created,
                                      Map<String, String> delta, String finishReason) {
        Map<String, Object> response = envelope(request, id, created, true);
        response.put("choices", List.of(choice("delta", delta, finishReason)));
        if (includeUsage(request)) {
            // 中间分片不提供统计，最终统计单独发送。
            response.put("usage", null);
        }
        return response;
    }

    /** 构建普通响应与流式分片共用的元数据。 */
    private Map<String, Object> envelope(ChatRequest request, String id,
                                         long created, boolean streaming) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("object", streaming ? "chat.completion.chunk" : "chat.completion");
        response.put("created", created);
        response.put("model", request.model());
        return response;
    }

    /** 普通响应使用 message，流式响应使用 delta；生成期间 finish_reason 为 null。 */
    private Map<String, Object> choice(String field, Map<String, String> value, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put(field, value);
        choice.put("logprobs", null);
        choice.put("finish_reason", finishReason);
        return choice;
    }

    private boolean includeUsage(ChatRequest request) {
        return request.streamOptions() != null && request.streamOptions().includeUsage();
    }

    private Map<String, Integer> usage() {
        // 固定 mock 值，不代表真实 tokenizer 的计数。
        return Map.of("prompt_tokens", 0, "completion_tokens", 0, "total_tokens", 0);
    }

    private String newId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
    }
}
