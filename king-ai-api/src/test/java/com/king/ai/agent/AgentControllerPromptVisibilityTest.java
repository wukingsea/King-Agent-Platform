package com.king.ai.agent;

import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentControllerPromptVisibilityTest {

    private final AgentService agentService = mock(AgentService.class);
    private final AgentController controller = new AgentController(agentService);

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void viewerCannotReadSystemPromptInList() {
        RequestContext.set(new UserPrincipal(1L, "viewer", "Viewer", "USER", 2L, "VIEWER", false));

        controller.list(null, 1, 20);

        verify(agentService).list(2L, null, com.king.ai.common.PageQuery.of(1, 20, 20), false);
    }

    @Test
    void viewerCannotReadSystemPromptInDetail() {
        RequestContext.set(new UserPrincipal(1L, "viewer", "Viewer", "USER", 2L, "VIEWER", false));

        controller.detail(9L);

        verify(agentService).detail(2L, 9L, false);
    }

    @Test
    void editorCanReadSystemPrompt() {
        RequestContext.set(new UserPrincipal(1L, "editor", "Editor", "USER", 2L, "EDITOR", false));

        controller.detail(9L);

        verify(agentService).detail(2L, 9L, true);
    }

    @Test
    void globalAdminCanReadSystemPrompt() {
        RequestContext.set(new UserPrincipal(1L, "admin", "Admin", "ADMIN", 2L, "VIEWER", false));

        controller.detail(9L);

        verify(agentService).detail(2L, 9L, true);
    }
}
