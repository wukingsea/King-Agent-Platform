package com.king.ai.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.king.ai.common.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcTokenValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ISSUER = "https://idp.example.com";
    private static final String CLIENT_ID = "king-ai";
    private static final String NONCE = "nonce-1";
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private final OidcTokenValidator validator = new OidcTokenValidator(MAPPER);

    @Test
    void acceptsValidIdToken() throws Exception {
        String token = sign(claims(Instant.now().getEpochSecond() + 300),
                new String[]{"another-client", CLIENT_ID}, NONCE, ISSUER);

        JsonNode claims = validator.validate(token, NONCE, ISSUER, CLIENT_ID,
                keyId -> keyPair.getPublic());

        assertThat(claims.path("sub").asText()).isEqualTo("user-1");
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        String token = sign(claims(Instant.now().getEpochSecond() + 300),
                new String[]{CLIENT_ID}, NONCE, ISSUER);
        String[] parts = token.split("\\.");
        ObjectNode forged = (ObjectNode) MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        forged.put("sub", "attacker");
        String payload = encode(MAPPER.writeValueAsString(forged));
        String tampered = parts[0] + "." + payload + "." + parts[2];

        assertThatThrownBy(() -> validator.validate(tampered, NONCE, ISSUER, CLIENT_ID,
                keyId -> keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("签名校验失败");
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = sign(claims(Instant.now().getEpochSecond() - 120),
                new String[]{CLIENT_ID}, NONCE, ISSUER);

        assertThatThrownBy(() -> validator.validate(token, NONCE, ISSUER, CLIENT_ID,
                keyId -> keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已过期");
    }

    @Test
    void rejectsTokenThatIsNotYetValid() throws Exception {
        ObjectNode claims = claims(Instant.now().getEpochSecond() + 300);
        claims.put("nbf", Instant.now().getEpochSecond() + 300);
        String token = sign(claims, new String[]{CLIENT_ID}, NONCE, ISSUER);

        assertThatThrownBy(() -> validator.validate(token, NONCE, ISSUER, CLIENT_ID,
                keyId -> keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未生效");
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        String token = sign(claims(Instant.now().getEpochSecond() + 300),
                new String[]{"another-client"}, NONCE, ISSUER);

        assertThatThrownBy(() -> validator.validate(token, NONCE, ISSUER, CLIENT_ID,
                keyId -> keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("受众不匹配");
    }

    @Test
    void rejectsWrongNonce() throws Exception {
        String token = sign(claims(Instant.now().getEpochSecond() + 300),
                new String[]{CLIENT_ID}, "another-nonce", ISSUER);

        assertThatThrownBy(() -> validator.validate(token, NONCE, ISSUER, CLIENT_ID,
                keyId -> keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("随机数不匹配");
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        String token = sign(claims(Instant.now().getEpochSecond() + 300),
                new String[]{CLIENT_ID}, NONCE, "https://evil.example.com");

        assertThatThrownBy(() -> validator.validate(token, NONCE, ISSUER, CLIENT_ID,
                keyId -> keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("签发方不匹配");
    }

    @Test
    void rejectsUnknownKey() throws Exception {
        String token = sign(claims(Instant.now().getEpochSecond() + 300),
                new String[]{CLIENT_ID}, NONCE, ISSUER);

        assertThatThrownBy(() -> validator.validate(token, NONCE, ISSUER, CLIENT_ID, keyId -> null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("签名密钥不存在");
    }

    private ObjectNode claims(long expiresAt) {
        ObjectNode claims = MAPPER.createObjectNode();
        claims.put("sub", "user-1");
        claims.put("iss", ISSUER);
        claims.put("nonce", NONCE);
        claims.put("exp", expiresAt);
        return claims;
    }

    private String sign(ObjectNode claims, String[] audience, String nonce, String issuer)
            throws Exception {
        claims.put("nonce", nonce);
        claims.put("iss", issuer);
        ArrayNode audienceNode = claims.putArray("aud");
        for (String value : audience) {
            audienceNode.add(value);
        }
        ObjectNode header = MAPPER.createObjectNode();
        header.put("alg", "RS256");
        header.put("kid", "test-key");
        header.put("typ", "JWT");
        String signingInput = encode(MAPPER.writeValueAsString(header)) + "." + encode(
                MAPPER.writeValueAsString(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature.sign());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
