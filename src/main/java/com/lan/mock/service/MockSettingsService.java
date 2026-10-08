package com.lan.mock.service;

import com.lan.mock.dto.Settings;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

@Service
public class MockSettingsService {
    private volatile Settings settings = Settings.defaults();
    private final Validator validator;

    public MockSettingsService(Validator validator) {
        this.validator = validator;
    }

    public Settings current() {
        return settings;
    }

    public Settings update(Settings value) {
        var violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        // 先验证后整体替换，其他请求不会看到一半新配置、一半旧配置。
        settings = value;
        return value;
    }
}
