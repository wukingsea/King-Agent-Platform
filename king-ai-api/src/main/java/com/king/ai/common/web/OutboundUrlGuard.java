package com.king.ai.common.web;

import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Dns;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundUrlGuard {

    private static final String BLOCKED_MESSAGE = "目标地址不允许访问，请使用公网地址或联系管理员配置出站白名单";

    private final KingAiProperties properties;

    public String violation(String rawUrl) {
        if (Texts.isBlank(rawUrl)) {
            return "目标地址不能为空";
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            return "目标地址格式不正确";
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return "仅支持 http/https 目标地址";
        }
        if (uri.getRawUserInfo() != null) {
            return "目标地址不允许携带用户名或密码";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "目标地址缺少主机名";
        }
        host = normalizeHost(host);
        if (isAllowedHost(host)) {
            return null;
        }
        if (!properties.getOutbound().isBlockPrivateNetworks()) {
            return null;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            return "无法解析目标主机";
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                return BLOCKED_MESSAGE;
            }
        }
        return null;
    }

    /**
     * DNS implementation for HTTP clients that talk to tenant-configured endpoints. It validates
     * the addresses at connection time, so a hostname that resolved to a public address during
     * configuration cannot be rebound to an internal address later.
     */
    public Dns dns() {
        return hostname -> {
            String host = normalizeHost(hostname);
            List<InetAddress> addresses = Dns.SYSTEM.lookup(host);
            if (!properties.getOutbound().isBlockPrivateNetworks() || isAllowedHost(host)) {
                return addresses;
            }
            for (InetAddress address : addresses) {
                if (isBlocked(address)) {
                    throw new UnknownHostException(BLOCKED_MESSAGE);
                }
            }
            return addresses;
        };
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private boolean isAllowedHost(String host) {
        List<String> allowed = properties.getOutbound().getAllowedHosts();
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        for (String rule : allowed) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String pattern = rule.trim().toLowerCase(Locale.ROOT);
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length == 16) {
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            // IPv4-mapped IPv6 (::ffff:127.0.0.1) must be checked as IPv4.
            int i = 0;
            while (i < 10 && bytes[i] == 0) {
                i++;
            }
            if (i == 10 && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                return isBlockedIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            }
        }
        return false;
    }

    private static boolean isBlockedIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0 || first == 255) {
            return true;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return true;
        }
        if (first == 192 && second == 0 && (bytes[2] & 0xff) == 0) {
            return true;
        }
        return (first == 192 && second == 0 && (bytes[2] & 0xff) == 2)
                || (first == 198 && second == 51 && (bytes[2] & 0xff) == 100)
                || (first == 203 && second == 0 && (bytes[2] & 0xff) == 113);
    }
}
