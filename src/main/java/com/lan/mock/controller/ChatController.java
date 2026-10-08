package com.lan.mock.controller;

import com.lan.mock.dto.ChatRequest;
import com.lan.mock.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {
    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> complete(@Valid @RequestBody ChatRequest request) {
        if (request.stream()) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-cache").body(service.stream(request));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.complete(request));
    }
}
