package com.king.ai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSecurityChecks implements InitializingBean {

    private static final Set<String> KNOWN_DEFAULT_SECRETS = Set.of(
            "king-ai-local-dev-secret-change-me",
            "please-change-this-production-secret"
    );

    private final KingAiProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        String secret = properties.getAppSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("未配置 KING_AI_SECRET，平台拒绝以缺少签名密钥的状态启动");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("KING_AI_SECRET 长度不能少于 32 个字符");
        }

        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (!production) {
            return;
        }

        String normalized = secret.toLowerCase(Locale.ROOT);
        if (KNOWN_DEFAULT_SECRETS.contains(secret) || normalized.contains("change-me")
                || normalized.contains("please-change")) {
            throw new IllegalStateException("生产环境必须使用独立的 KING_AI_SECRET，禁止沿用示例或默认密钥");
        }
        String encryptionKey = properties.getEncryptionKey();
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("生产环境必须配置 KING_AI_ENCRYPTION_KEY，用于加密供应商密钥与 MCP 请求头");
        }
        if (encryptionKey.trim().length() < 32) {
            throw new IllegalStateException("KING_AI_ENCRYPTION_KEY 长度不能少于 32 个字符");
        }
        if (encryptionKey.trim().equals(secret)) {
            throw new IllegalStateException("KING_AI_ENCRYPTION_KEY 不能与 KING_AI_SECRET 相同，请使用独立密钥");
        }
        if (!"redis".equalsIgnoreCase(properties.getTokenStore())) {
            throw new IllegalStateException("生产环境必须使用 Redis 作为 Token 存储（king-ai.token-store=redis）");
        }
        if (!properties.getOutbound().isBlockPrivateNetworks()) {
            log.warn("生产环境关闭了出站内网地址拦截（king-ai.outbound.block-private-networks=false），SSRF 防护将失效");
        }
        if (!properties.getSecurity().isCookieSecure()) {
            log.warn("生产环境未启用安全 Cookie（king-ai.security.cookie-secure=false），访客身份可能通过明文 HTTP 泄露");
        }
        if (properties.getSecurity().getAllowedOrigins().stream().noneMatch(origin -> origin != null && !origin.isBlank())) {
            log.warn("生产环境未配置 KING_AI_ALLOWED_ORIGINS，Cookie 写操作将按请求 Host 校验来源，跨域部署时可能被拒绝");
        }
        if (properties.getDemo().isEnabled()) {
            log.warn("生产环境启用了演示模块（king-ai.demo.enabled=true），请确认这是预期行为");
        }
        warnWeakTransportSecurity();
    }

    private void warnWeakTransportSecurity() {
        String datasourceUrl = environment.getProperty("spring.datasource.url", "");
        String normalizedUrl = datasourceUrl.toLowerCase(Locale.ROOT);
        if (normalizedUrl.contains("sslmode=disabled") || normalizedUrl.contains("usessl=false")) {
            log.warn("生产环境数据库连接禁用了 TLS（spring.datasource.url），凭据与业务数据可能以明文传输");
        }
        Boolean redisSsl = environment.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false);
        if (!Boolean.TRUE.equals(redisSsl)) {
            log.warn("生产环境 Redis 未启用 TLS（REDIS_SSL_ENABLED=false），会话与限流数据可能在网络中以明文传输");
        }
        String redisPassword = environment.getProperty("spring.data.redis.password", "");
        if (redisPassword == null || redisPassword.isBlank()) {
            log.warn("生产环境 Redis 未设置密码（REDIS_PASSWORD），任何能访问该端口的进程都可读写会话状态");
        }
    }
}
