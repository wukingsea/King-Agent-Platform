package com.king.ai.common.web;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorAccessFilterTest {

    private final KingAiProperties properties = new KingAiProperties();
    private final ActuatorAccessFilter filter = new ActuatorAccessFilter(properties);

    @Test
    void healthEndpointsStayOpenWithoutToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/liveness"), response,
                (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void nonHealthEndpointsOpenWhenTokenNotConfigured() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"), response,
                (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
    }

    @Test
    void nonHealthEndpointsRequireTokenWhenConfigured() throws Exception {
        properties.getManagement().setToken("s3cret-token");

        MockHttpServletResponse denied = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"), denied,
                (req, res) -> reached.set(true));

        assertThat(reached).isFalse();
        assertThat(denied.getStatus()).isEqualTo(401);

        MockHttpServletRequest authorized = new MockHttpServletRequest("GET", "/actuator/prometheus");
        authorized.addHeader("X-Actuator-Token", "s3cret-token");
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(authorized, allowed, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(allowed.getStatus()).isEqualTo(200);
    }

    @Test
    void bearerHeaderIsAcceptedAsToken() throws Exception {
        properties.getManagement().setToken("s3cret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/info");
        request.addHeader("Authorization", "Bearer s3cret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
    }

    @Test
    void nonActuatorPathsAreNotFiltered() throws Exception {
        properties.getManagement().setToken("s3cret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/agents"), response,
                (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
    }
}
