package com.king.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolDefinition(String name, String description, JsonNode parameters) {
}
