package com.king.ai.common.web;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundUrlGuardTest {

    @Test
    void connectionTimeLookupRejectsPrivateAddressesEvenAfterConfigurationWasAccepted() {
        OutboundUrlGuard guard = guard(true, List.of());

        assertThatThrownBy(() -> guard.dns().lookup("localhost"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void explicitlyAllowedHostMayResolveToPrivateAddresses() throws Exception {
        OutboundUrlGuard guard = guard(true, List.of("localhost"));

        var addresses = guard.dns().lookup("localhost");

        assertThat(addresses).isNotEmpty();
    }

    @Test
    void lookupAllowsPrivateAddressesWhenBlockingIsDisabled() throws Exception {
        OutboundUrlGuard guard = guard(false, List.of());

        assertThat(guard.dns().lookup("localhost")).isNotEmpty();
    }

    @Test
    void dnsLookupIsCaseInsensitiveAndToleratesTrailingDot() throws Exception {
        OutboundUrlGuard guard = guard(true, List.of("localhost"));

        assertThat(guard.dns().lookup("LOCALHOST.")).isNotEmpty();
    }

    private OutboundUrlGuard guard(boolean blockPrivate, List<String> allowedHosts) {
        KingAiProperties properties = new KingAiProperties();
        properties.getOutbound().setBlockPrivateNetworks(blockPrivate);
        properties.getOutbound().setAllowedHosts(allowedHosts);
        return new OutboundUrlGuard(properties);
    }
}
