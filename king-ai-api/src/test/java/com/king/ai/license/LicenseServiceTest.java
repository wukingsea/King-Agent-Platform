package com.king.ai.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.BizException;
import com.king.ai.config.KingAiProperties;
import com.king.ai.license.dto.LicenseStatusView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LicenseServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KeyPair keyPair = generateKeyPair();

    @Test
    void acceptsSignedLicenseAndReportsUsage(@TempDir Path dir) throws Exception {
        Path file = writeLicense(dir, claims(Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS), 5, 10, 20), null);

        LicenseService service = service(file, rawPublicKey(keyPair), 2L, 3L, 4L);
        service.load();

        assertThat(service.enabled()).isTrue();
        LicenseStatusView status = service.status();
        assertThat(status.enabled()).isTrue();
        assertThat(status.status()).isEqualTo("ACTIVE");
        assertThat(status.customer()).isEqualTo("ACME");
        assertThat(status.edition()).isEqualTo("enterprise");
        assertThat(status.limits()).isEqualTo(new LicenseStatusView.Limits(5, 10, 20));
        assertThat(status.usage()).isEqualTo(new LicenseStatusView.Usage(2, 3, 4));
        assertThat(status.features()).containsExactly("sso", "ocr");
    }

    @Test
    void acceptsPemEncodedPublicKey(@TempDir Path dir) throws Exception {
        Path file = writeLicense(dir, claims(Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS), 5, 10, 20), null);

        LicenseService service = service(file, pemPublicKey(keyPair), 0L, 0L, 0L);
        service.load();

        assertThat(service.enabled()).isTrue();
    }

    @Test
    void rejectsTamperedPayload(@TempDir Path dir) throws Exception {
        Map<String, Object> signed = claims(Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS), 5, 10, 20);
        Map<String, Object> tampered = new LinkedHashMap<>(signed);
        tampered.put("maxAgents", 9_999);
        Path file = writeLicense(dir, tampered, signed);

        assertThatThrownBy(() -> service(file, rawPublicKey(keyPair), 0L, 0L, 0L).load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动")
                .hasMessageContaining("签名校验失败");
    }

    @Test
    void rejectsExpiredLicense(@TempDir Path dir) throws Exception {
        Path file = writeLicense(dir, claims(Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS), 5, 10, 20), null);

        assertThatThrownBy(() -> service(file, rawPublicKey(keyPair), 0L, 0L, 0L).load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void rejectsMissingFile(@TempDir Path dir) {
        LicenseService service = service(dir.resolve("absent.json"), rawPublicKey(keyPair), 0L, 0L, 0L);

        assertThatThrownBy(service::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("许可证文件不存在");
    }

    @Test
    void enforcesCapacityOnceUsageReachesLimit(@TempDir Path dir) throws Exception {
        Path file = writeLicense(dir, claims(Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS), 5, 10, 20), null);
        LicenseService service = service(file, rawPublicKey(keyPair), 2L, 3L, 20L);
        service.load();

        assertThatCode(service::ensureSpaceCapacity).doesNotThrowAnyException();
        assertThatCode(service::ensureUserCapacity).doesNotThrowAnyException();
        assertThatThrownBy(service::ensureAgentCapacity)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("智能体")
                .hasMessageContaining("20");
    }

    @Test
    void nonPositiveLimitMeansUnlimited(@TempDir Path dir) throws Exception {
        Path file = writeLicense(dir, claims(Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS), 0, 0, 0), null);
        LicenseService service = service(file, rawPublicKey(keyPair), 1_000L, 1_000L, 1_000L);
        service.load();

        assertThatCode(service::ensureSpaceCapacity).doesNotThrowAnyException();
        assertThatCode(service::ensureUserCapacity).doesNotThrowAnyException();
        assertThatCode(service::ensureAgentCapacity).doesNotThrowAnyException();
    }

    @Test
    void disabledLicenseDoesNotEnforceCapacity(@TempDir Path dir) {
        LicenseService service = service(dir.resolve("absent.json"), "", 1_000L, 1_000L, 1_000L, false);
        service.load();

        assertThat(service.enabled()).isFalse();
        assertThatCode(service::ensureAgentCapacity).doesNotThrowAnyException();
        assertThat(service.status().status()).isEqualTo("DISABLED");
    }

    private LicenseService service(Path file, String publicKey, long spaces, long users, long agents) {
        return service(file, publicKey, spaces, users, agents, true);
    }

    private LicenseService service(Path file, String publicKey, long spaces, long users, long agents,
                                   boolean enabled) {
        KingAiProperties properties = new KingAiProperties();
        properties.getLicense().setEnabled(enabled);
        properties.getLicense().setFile(file.toString());
        properties.getLicense().setPublicKey(publicKey);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("king_ai_space"), eq(Long.class))).thenReturn(spaces);
        when(jdbcTemplate.queryForObject(contains("king_ai_user"), eq(Long.class))).thenReturn(users);
        when(jdbcTemplate.queryForObject(contains("king_ai_agent"), eq(Long.class))).thenReturn(agents);
        return new LicenseService(properties, jdbcTemplate, objectMapper);
    }

    private Path writeLicense(Path dir, Map<String, Object> payload,
                              Map<String, Object> signedPayload) throws Exception {
        byte[] signed = objectMapper.writeValueAsBytes(signedPayload == null ? payload : signedPayload);
        byte[] wire = objectMapper.writeValueAsBytes(payload);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(signed);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("payload", base64Url(wire));
        envelope.put("signature", base64Url(signer.sign()));
        Path file = dir.resolve("license.json");
        Files.write(file, objectMapper.writeValueAsBytes(envelope));
        return file;
    }

    private static Map<String, Object> claims(Instant issuedAt, Instant expiresAt,
                                              int maxSpaces, int maxUsers, int maxAgents) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("licenseId", "lic-test-1");
        claims.put("customer", "ACME");
        claims.put("edition", "enterprise");
        claims.put("issuedAt", issuedAt.toString());
        claims.put("expiresAt", expiresAt.toString());
        claims.put("maxSpaces", maxSpaces);
        claims.put("maxUsers", maxUsers);
        claims.put("maxAgents", maxAgents);
        claims.put("features", List.of("sso", "ocr"));
        return claims;
    }

    private static String rawPublicKey(KeyPair pair) {
        byte[] encoded = pair.getPublic().getEncoded();
        return base64Url(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    private static String pemPublicKey(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
