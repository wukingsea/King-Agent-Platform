package com.king.ai.common.web;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void ignoresClientSuppliedForwardedEntriesAndUsesLastTrustedHop() {
        ClientIpResolver resolver = resolver(1, true);
        MockHttpServletRequest request = request("10.0.0.5",
                "203.0.113.9, 198.51.100.23");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.23");
    }

    @Test
    void walksBackOneEntryPerTrustedProxy() {
        ClientIpResolver resolver = resolver(2, true);
        MockHttpServletRequest request = request("10.0.0.5",
                "203.0.113.9, 198.51.100.23, 10.1.1.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.23");
    }

    @Test
    void fallsBackToRemoteAddressWhenForwardedChainIsShorterThanTrustedHops() {
        ClientIpResolver resolver = resolver(2, true);
        MockHttpServletRequest request = request("10.0.0.5", "203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void fallsBackToRemoteAddressWhenSelectedHopIsNotAnIp() {
        ClientIpResolver resolver = resolver(1, true);
        MockHttpServletRequest request = request("10.0.0.5", "203.0.113.9, attacker");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void ignoresForwardedHeadersWhenTrustIsDisabled() {
        ClientIpResolver resolver = resolver(1, false);
        MockHttpServletRequest request = request("10.0.0.5", "203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    private ClientIpResolver resolver(int trustedProxyHops, boolean trustForwarded) {
        KingAiProperties properties = new KingAiProperties();
        properties.getSecurity().setTrustForwardedHeaders(trustForwarded);
        properties.getSecurity().setTrustedProxyHops(trustedProxyHops);
        return new ClientIpResolver(properties);
    }

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
