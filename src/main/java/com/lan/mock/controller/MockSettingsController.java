package com.lan.mock.controller;

import com.lan.mock.dto.Settings;
import com.lan.mock.service.MockSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MockSettingsController {
    private final MockSettingsService service;

    public MockSettingsController(MockSettingsService service) {
        this.service = service;
    }

    @GetMapping("/mock/settings")
    public Settings current() {
        return service.current();
    }

    @PutMapping("/mock/settings")
    public Settings update(@Valid @RequestBody Settings settings) {
        return service.update(settings);
    }
}
