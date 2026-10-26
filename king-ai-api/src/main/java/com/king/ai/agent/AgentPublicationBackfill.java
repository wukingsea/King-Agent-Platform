package com.king.ai.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Captures an immutable snapshot for agents that were published before snapshots existed.
 * Runs before the application starts accepting traffic so public chat never serves the
 * editable configuration of a legacy published agent.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AgentPublicationBackfill implements ApplicationRunner {

    private final AgentService agentService;

    @Override
    public void run(ApplicationArguments args) {
        List<Long> agentIds = agentService.legacyPublicationAgentIds();
        if (agentIds.isEmpty()) {
            return;
        }
        int migrated = 0;
        for (Long agentId : agentIds) {
            try {
                if (agentService.backfillLegacyPublication(agentId)) {
                    migrated++;
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to backfill publication snapshot for agent {}: {}", agentId, ex.getMessage());
            }
        }
        log.info("Backfilled immutable publication snapshots for {} legacy published agent(s)", migrated);
    }
}
