package com.my.custom.claudepersonalassistant;

import org.springframework.boot.SpringApplication;

public class TestClaudePersonalAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.from(ClaudePersonalAssistantApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
