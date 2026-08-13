package com.my.custom.claudepersonalassistant;

import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static org.assertj.core.api.Assertions.assertThat;

class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(ClaudePersonalAssistantApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeDocumentation();
    }

    @Test
    void hasExactlyTheExpectedModules() {
        // verify() only checks structural rules (no illegal internal-package reach, no
        // cycles, allowed-dependency violations); it doesn't pin the module set itself, so a
        // stray top-level package would silently become an extra module with no test signal.
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("assistant", "audit", "chat", "mcp");
    }
}
