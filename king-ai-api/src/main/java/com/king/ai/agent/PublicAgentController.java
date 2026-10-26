package com.king.ai.agent;

import com.king.ai.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/agents")
@RequiredArgsConstructor
public class PublicAgentController {

    private final AgentService agentService;

    public record PublicAgentView(String publicId, String name, String intro) {
    }

    @GetMapping("/{publicId}")
    public ApiResponse<PublicAgentView> detail(@PathVariable String publicId) {
        AgentRuntime agent = agentService.publishedRuntime(publicId);
        return ApiResponse.ok(new PublicAgentView(agent.publicId(), agent.name(), agent.intro()));
    }
}
