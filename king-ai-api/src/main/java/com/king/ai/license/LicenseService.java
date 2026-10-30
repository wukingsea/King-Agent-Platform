package com.king.ai.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import com.king.ai.license.dto.LicenseStatusView;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Verifies the offline license (Ed25519 over a base64url payload) and enforces the
 * capacity claims. When {@code king-ai.license.enabled} is true the application refuses
 * to start with a missing, tampered or expired license.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {

    /** DER prefix of an X.509 SubjectPublicKeyInfo wrapping a raw 32-byte Ed25519 key. */
    private static final byte[] ED25519_X509_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final KingAiProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private volatile LicenseClaims claims;

    @PostConstruct
    public void load() {
        KingAiProperties.License config = properties.getLicense();
        if (config == null || !config.isEnabled()) {
            claims = null;
            return;
        }
        try {
            claims = read(config);
            log.info("License accepted: id={} customer={} edition={} spaces={} users={} agents={} expiresAt={}",
                    claims.licenseId(), claims.customer(), claims.edition(), claims.maxSpaces(),
                    claims.maxUsers(), claims.maxAgents(), claims.expiresAt());
        } catch (RuntimeException ex) {
            claims = null;
            throw new IllegalStateException("许可证无效，拒绝启动：" + ex.getMessage(), ex);
        }
    }

    public boolean enabled() {
        return claims != null;
    }

    public void ensureSpaceCapacity() {
        LicenseClaims active = requireActive();
        if (active != null) {
            enforceLimit("业务空间", active.maxSpaces(), countSpaces());
        }
    }

    public void ensureUserCapacity() {
        LicenseClaims active = requireActive();
        if (active != null) {
            enforceLimit("用户", active.maxUsers(), countUsers());
        }
    }

    public void ensureAgentCapacity() {
        LicenseClaims active = requireActive();
        if (active != null) {
            enforceLimit("智能体", active.maxAgents(), countAgents());
        }
    }

    public LicenseStatusView status() {
        LicenseClaims active = claims;
        LicenseStatusView.Usage usage = new LicenseStatusView.Usage(
                countSpaces(), countUsers(), countAgents());
        if (active == null) {
            return new LicenseStatusView(false, "DISABLED", null, null, null,
                    null, null, null, usage, List.of());
        }
        String state = Instant.now().isAfter(active.expiresAt()) ? "EXPIRED" : "ACTIVE";
        return new LicenseStatusView(true, state, active.licenseId(), active.customer(), active.edition(),
                active.issuedAt(), active.expiresAt(),
                new LicenseStatusView.Limits(active.maxSpaces(), active.maxUsers(), active.maxAgents()),
                usage, active.features());
    }

    LicenseClaims claims() {
        return claims;
    }

    private LicenseClaims requireActive() {
        LicenseClaims active = claims;
        if (active == null) {
            return null;
        }
        if (Instant.now().isAfter(active.expiresAt())) {
            throw BizException.forbidden("许可证已过期，请联系供应商续期");
        }
        return active;
    }

    private void enforceLimit(String label, int max, long usage) {
        if (max <= 0) {
            return;
        }
        if (usage >= max) {
            throw BizException.forbidden("已达到许可证允许的" + label + "数量上限（" + max + "），请升级许可证");
        }
    }

    private long countSpaces() {
        return count("SELECT COUNT(*) FROM king_ai_space WHERE deleted_at IS NULL");
    }

    private long countUsers() {
        return count("SELECT COUNT(*) FROM king_ai_user");
    }

    private long countAgents() {
        return count("SELECT COUNT(*) FROM king_ai_agent WHERE deleted_at IS NULL");
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private LicenseClaims read(KingAiProperties.License config) {
        if (Texts.isBlank(config.getFile())) {
            throw new IllegalStateException("未配置许可证文件（king-ai.license.file）");
        }
        Path path = Path.of(config.getFile().trim());
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("许可证文件不存在：" + path);
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw new IllegalStateException("许可证文件无法解析：" + ex.getMessage(), ex);
        }
        if (envelope == null || !envelope.isObject()) {
            throw new IllegalStateException("许可证文件格式不正确");
        }
        byte[] payload = decodeBase64(text(envelope, "payload"), "payload");
        byte[] signature = decodeBase64(text(envelope, "signature"), "signature");
        verify(publicKey(config.getPublicKey()), payload, signature);

        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("许可证 payload 不是合法 JSON", ex);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("许可证 payload 不是 JSON 对象");
        }
        String licenseId = requiredText(node, "licenseId");
        String customer = requiredText(node, "customer");
        Instant issuedAt = instant(node, "issuedAt", false);
        Instant expiresAt = instant(node, "expiresAt", true);
        Instant now = Instant.now();
        if (expiresAt.isBefore(now)) {
            throw new IllegalStateException("许可证已于 " + expiresAt + " 过期");
        }
        if (issuedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalStateException("许可证签发时间在未来（" + issuedAt + "）");
        }
        return new LicenseClaims(licenseId, customer, defaultEdition(node),
                issuedAt, expiresAt,
                intClaim(node, "maxSpaces"), intClaim(node, "maxUsers"), intClaim(node, "maxAgents"),
                features(node));
    }

    private PublicKey publicKey(String value) {
        if (Texts.isBlank(value)) {
            throw new IllegalStateException("未配置许可证公钥（king-ai.license.public-key）");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            String trimmed = value.trim();
            if (trimmed.contains("BEGIN PUBLIC KEY")) {
                String base64 = trimmed.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
                return factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
            }
            byte[] decoded = decodeBase64(trimmed, "public-key");
            if (decoded.length == 32) {
                byte[] encoded = new byte[ED25519_X509_PREFIX.length + decoded.length];
                System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
                System.arraycopy(decoded, 0, encoded, ED25519_X509_PREFIX.length, decoded.length);
                return factory.generatePublic(new X509EncodedKeySpec(encoded));
            }
            return factory.generatePublic(new X509EncodedKeySpec(decoded));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("许可证公钥无效：" + ex.getMessage(), ex);
        }
    }

    private void verify(PublicKey key, byte[] payload, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw new IllegalStateException("许可证签名校验失败");
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("许可证签名校验失败：" + ex.getMessage(), ex);
        }
    }

    private static String defaultEdition(JsonNode node) {
        String edition = text(node, "edition");
        return Texts.isBlank(edition) ? "enterprise" : edition.trim();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (Texts.isBlank(value)) {
            throw new IllegalStateException("许可证缺少 " + field);
        }
        return value.trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int intClaim(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return 0;
        }
        return value.isNumber() ? value.asInt() : Integer.parseInt(value.asText().trim());
    }

    private static Instant instant(JsonNode node, String field, boolean endOfDay) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("许可证缺少 " + field);
        }
        if (value.isNumber()) {
            return Instant.ofEpochSecond(value.asLong());
        }
        String raw = value.asText().trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException("许可证缺少 " + field);
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return Instant.ofEpochSecond(Long.parseLong(raw));
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            // Fall through to the more forgiving formats below.
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            // Local date-times are interpreted as UTC.
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // A date-only expiry covers the whole day.
        }
        try {
            LocalDate date = LocalDate.parse(raw);
            return (endOfDay ? date.atTime(java.time.LocalTime.MAX) : date.atStartOfDay())
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException("许可证字段 " + field + " 不是合法时间：" + raw);
        }
    }

    private static List<String> features(JsonNode node) {
        JsonNode value = node.get("features");
        List<String> result = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(item -> {
                if (item != null && !item.isNull() && !item.asText().isBlank()) {
                    result.add(item.asText().trim());
                }
            });
        }
        return List.copyOf(result);
    }

    private static byte[] decodeBase64(String value, String field) {
        if (Texts.isBlank(value)) {
            throw new IllegalStateException("许可证缺少 " + field);
        }
        String normalized = value.trim().replaceAll("\\s", "");
        try {
            return Base64.getUrlDecoder().decode(normalized);
        } catch (IllegalArgumentException ignored) {
            try {
                return Base64.getDecoder().decode(normalized);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("许可证 " + field + " 不是合法 Base64", ex);
            }
        }
    }
}
