package com.my.custom.claudepersonalassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableAsync activates the @Async half of Spring Modulith's @ApplicationModuleListener;
// neither Boot nor Modulith enables async annotation processing on their own.
@SpringBootApplication
@EnableAsync
public class ClaudePersonalAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaudePersonalAssistantApplication.class, args);
    }

}
