package com.king.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAllowlistTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blankConfigurationMeansNoRestriction() {
        assertThat(ToolAllowlist.parse(null, objectMapper)).isEmpty();
        assertThat(ToolAllowlist.parse("  ", objectMapper)).isEmpty();
        assertThat(ToolAllowlist.allows(List.of(), "mcp__github__create_issue", "create_issue")).isTrue();
    }

    @Test
    void malformedConfigurationDeniesEveryTool() {
        List<String> parsed = ToolAllowlist.parse("{not-json", objectMapper);

        assertThat(parsed).containsExactly(ToolAllowlist.DENY_ALL);
        assertThat(ToolAllowlist.allows(parsed, "mcp__github__create_issue", "create_issue")).isFalse();
        assertThat(ToolAllowlist.allows(parsed, "skill_read_file", "skill_read_file")).isFalse();
    }

    @Test
    void nullJsonArrayDeniesEveryTool() {
        assertThat(ToolAllowlist.parse("null", objectMapper)).containsExactly(ToolAllowlist.DENY_ALL);
    }

    @Test
    void trimsPatternsAndDropsBlankEntries() {
        List<String> parsed = ToolAllowlist.parse("[\" mcp__github__* \", \"\", null]", objectMapper);

        assertThat(parsed).containsExactly("mcp__github__*");
    }

    @Test
    void globMatchesFullNameAndRemoteNameIgnoringCase() {
        List<String> allowlist = List.of("MCP__GITHUB__*");

        assertThat(ToolAllowlist.allows(allowlist, "mcp__github__create_issue", "create_issue")).isTrue();
        assertThat(ToolAllowlist.allows(allowlist, "mcp__gitlab__create_issue", "create_issue")).isFalse();
    }

    @Test
    void exactRemoteNamePatternIsSupported() {
        assertThat(ToolAllowlist.allows(List.of("search_issues"), "mcp__github__search_issues", "search_issues"))
                .isTrue();
        assertThat(ToolAllowlist.allows(List.of("search_issues"), "mcp__github__create_issue", "create_issue"))
                .isFalse();
    }

    @Test
    void wildcardOnlyStillRequiresValidConfiguration() {
        assertThat(ToolAllowlist.allows(List.of("*"), "anything", "anything")).isTrue();
    }

    @Test
    void regexMetacharactersAreTreatedLiterally() {
        assertThat(ToolAllowlist.allows(List.of("tool.(x)"), "tool.(x)", "tool.(x)")).isTrue();
        assertThat(ToolAllowlist.allows(List.of("tool.(x)"), "toolax", "toolax")).isFalse();
    }
}
