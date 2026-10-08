package com.lan.mock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class MockSchedulingConfig {
    @Bean
    public ThreadPoolTaskScheduler mockScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("mock-timer-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
