package com.lan.mock.service;

import com.lan.mock.config.MockProperties;
import com.lan.mock.dto.ChatRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceTest {
    @Test
    void streamReturnsEmitterBeforeQueuedTaskRuns() {
        // 捕获任务而不立即执行，验证发送逻辑委托给执行器而非内联执行。
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ChatService service = new ChatService(new MockProperties("你好🌍", 1, 30000), queued::set);
        ChatRequest request = new ChatRequest("mock", List.of(), true, null);

        var emitter = service.stream(request);

        assertThat(emitter.getTimeout()).isEqualTo(30000L);
        assertThat(queued.get()).isNotNull();
        // emitter 尚未绑定 HTTP 响应时，Spring 可暂存事件；任务可以正常发送并完成。
        queued.get().run();
    }

    @Test
    void completeDoesNotSubmitAnAsyncTask() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ChatService service = new ChatService(new MockProperties("mock reply", 8, 30000), queued::set);

        var response = service.complete(new ChatRequest("mock", List.of(), false, null));

        assertThat(response).containsEntry("object", "chat.completion").containsEntry("model", "mock");
        assertThat(queued.get()).isNull();
    }
}
