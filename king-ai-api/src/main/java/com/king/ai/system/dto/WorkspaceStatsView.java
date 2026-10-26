package com.king.ai.system.dto;

public record WorkspaceStatsView(long agents,
                                 long publishedAgents,
                                 long knowledgeBases,
                                 long documents,
                                 long chunks,
                                 long sessions,
                                 long mcpServers,
                                 long skills,
                                 long suppliers,
                                 long models) {
}
