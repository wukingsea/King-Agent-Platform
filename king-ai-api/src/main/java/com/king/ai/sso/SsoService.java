package com.king.ai.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.config.KingAiProperties;
import com.king.ai.license.LicenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OIDC authorization-code flow. Discovery and JWKS documents are cached; the state
 * parameter carries a nonce and a short expiry, both HMAC-signed with the platform secret
 * so an unsolicited callback cannot start a session.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoService {

    private static final String PROVIDER = "OIDC";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final long STATE_TTL_SECONDS = 600;
    private static final int MAX_DOCUMENT_BYTES = 1_000_000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KingAiProperties properties;
    private final com.king.ai.system.mapper.UserMapper userMapper;
    private final com.king.ai.system.mapper.UserIdentityMapper identityMapper;
    private final com.king.ai.system.mapper.SpaceMapper spaceMapper;
    private final com.king.ai.system.mapper.UserSpaceMapper userSpaceMapper;
    private final PasswordEncoder passwordEncoder;
    private final OidcTokenValidator tokenValidator;
    private final OidcStateSigner stateSigner;
    private final LicenseService licenseService;
    private final SecureRandom random = new SecureRandom();

    private volatile Discovery discovery;
    private volatile long discoveryExpiresAt;
    private volatile Jwks jwks;

    public record SsoConfig(boolean enabled, String loginUrl, String issuer) {
    }

    private record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {
    }

    private record Jwks(Map<String, PublicKey> keys, long expiresAt) {
    }

    public SsoConfig config() {
        boolean enabled = enabled();
        return new SsoConfig(enabled, enabled ? "/api/auth/oidc/authorize" : null, oidc().getIssuer());
    }

    public boolean enabled() {
        KingAiProperties.Oidc oidc = oidc();
        return properties.getSso().isEnabled() && !Texts.isBlank(oidc.getIssuer())
                && !Texts.isBlank(oidc.getClientId()) && !Texts.isBlank(oidc.getRedirectUri());
    }

    public String authorizeUrl() {        requireEnabled();
        Discovery document = discovery();
        String nonce = randomToken(24);
        String state = stateSigner.issue(nonce, Duration.ofSeconds(STATE_TTL_SECONDS));
        HttpUrl url = HttpUrl.parse(document.authorizationEndpoint());
        if (url == null) {
            throw new BizException(502, "SSO 授权地址不合法");
        }
        HttpUrl.Builder builder = url.newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", oidc().getClientId())
                .addQueryParameter("redirect_uri", oidc().getRedirectUri())
                .addQueryParameter("scope", String.join(" ", scopes()))
                .addQueryParameter("state", state)
                .addQueryParameter("nonce", nonce);
        return builder.build().toString();
    }

    /**
     * Resolves the OIDC subject to a local user, creating and binding one on first login.
     * Space roles from the configured claim are only ever raised, never downgraded.
     */
    @Transactional
    public com.king.ai.system.entity.User callback(String code, String state) {
        requireEnabled();
        if (Texts.isBlank(code)) {
            throw BizException.badRequest("SSO 回调缺少授权码");
        }
        String nonce = stateSigner.verify(state);
        String idToken = exchange(code);
        JsonNode claims = validateIdToken(idToken, nonce);
        return resolveUser(claims);
    }

    public String successRedirect() {
        String target = oidc().getSuccessRedirect();
        return Texts.isBlank(target) ? "/#/dashboard" : target.trim();
    }

    public String errorRedirect(String message) {
        return "/#/login?sso_error=" + java.net.URLEncoder.encode(
                Texts.blankToDefault(message, "SSO 登录失败"), StandardCharsets.UTF_8);
    }

    private com.king.ai.system.entity.User resolveUser(JsonNode claims) {
        String subject = text(claims, "sub");
        if (Texts.isBlank(subject)) {
            throw BizException.unauthorized("ID Token 缺少 sub 声明");
        }
        com.king.ai.system.entity.UserIdentity identity = identityMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.king.ai.system.entity.UserIdentity>()
                        .eq(com.king.ai.system.entity.UserIdentity::getProvider, PROVIDER)
                        .eq(com.king.ai.system.entity.UserIdentity::getSubject, subject)
                        .last("LIMIT 1"));
        if (identity != null) {
            com.king.ai.system.entity.User user = userMapper.selectById(identity.getUserId());
            if (user != null) {
                String email = text(claims, "email");
                if (!Texts.isBlank(email) && !email.equalsIgnoreCase(identity.getEmail())) {
                    identity.setEmail(email);
                    identityMapper.updateById(identity);
                }
                applyRoles(user, roles(claims));
                return user;
            }
            identityMapper.deleteById(identity.getId());
        }

        String email = text(claims, "email");
        com.king.ai.system.entity.User user = verifiedEmail(claims) ? findByEmail(email) : null;
        if (user == null) {
            if (!oidc().isAutoCreateUsers()) {
                throw BizException.forbidden("账号未开通，请联系管理员");
            }
            user = createUser(claims, email);
        }
        bindIdentity(user, subject, email);
        applyRoles(user, roles(claims));
        return user;
    }

    private com.king.ai.system.entity.User createUser(JsonNode claims, String email) {
        licenseService.ensureUserCapacity();
        String username = uniqueUsername(claims, email);
        com.king.ai.system.entity.User user = new com.king.ai.system.entity.User();
        user.setUsername(username);
        // SSO accounts keep a random local password so a leaked IdP outage cannot be
        // bypassed with a guessed empty password.
        user.setPassword(passwordEncoder.encode(randomToken(32)));
        String nickname = text(claims, "name");
        user.setNickname(Texts.blankToDefault(Texts.trimTo(nickname, 50), username));
        user.setEmail(Texts.trimTo(email, 100));
        user.setRole("USER");
        user.setStatus(0);
        user.setMustChangePassword(0);
        userMapper.insert(user);
        return user;
    }

    private void bindIdentity(com.king.ai.system.entity.User user, String subject, String email) {
        com.king.ai.system.entity.UserIdentity identity = new com.king.ai.system.entity.UserIdentity();
        identity.setUserId(user.getId());
        identity.setProvider(PROVIDER);
        identity.setSubject(subject);
        identity.setEmail(Texts.trimTo(email, 200));
        try {
            identityMapper.insert(identity);
        } catch (DuplicateKeyException ex) {
            log.info("OIDC identity bound concurrently for subject {}", subject);
        }
    }

    private String uniqueUsername(JsonNode claims, String email) {
        String candidate = text(claims, "preferred_username");
        if (Texts.isBlank(candidate)) {
            candidate = email != null && email.contains("@")
                    ? email.substring(0, email.indexOf('@')) : null;
        }
        if (Texts.isBlank(candidate)) {
            candidate = "sso-" + text(claims, "sub");
        }
        String base = candidate.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-")
                .replaceAll("^-+", "").replaceAll("-+$", "");
        if (base.length() < 3) {
            base = "sso-" + (base.isEmpty() ? "user" : base);
        }
        if (base.length() > 28) {
            base = base.substring(0, 28);
        }
        String candidateName = base;
        for (int suffix = 1; suffix <= 50; suffix++) {
            boolean exists = userMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.king.ai.system.entity.User>()
                            .eq(com.king.ai.system.entity.User::getUsername, candidateName)) > 0;
            if (!exists) {
                return candidateName;
            }
            candidateName = base + "-" + suffix;
        }
        return base + "-" + randomToken(4).toLowerCase(Locale.ROOT);
    }

    private com.king.ai.system.entity.User findByEmail(String email) {
        if (Texts.isBlank(email)) {
            return null;
        }
        List<com.king.ai.system.entity.User> matches = userMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.king.ai.system.entity.User>()
                        .apply("LOWER(email) = {0}", email.trim().toLowerCase(Locale.ROOT))
                        .last("LIMIT 1"));
        return matches.isEmpty() ? null : matches.get(0);
    }

    private boolean verifiedEmail(JsonNode claims) {
        JsonNode value = claims.get("email_verified");
        return value != null && ("true".equalsIgnoreCase(value.asText()) || value.asBoolean(false));
    }

    private void applyRoles(com.king.ai.system.entity.User user, List<String> claims) {
        if ("ADMIN".equals(user.getRole())) {
            return;
        }
        KingAiProperties.Oidc oidc = oidc();
        Long defaultSpaceId = resolveSpaceId(oidc.getDefaultSpaceCode());
        Map<Long, SpaceRole> targets = new LinkedHashMap<>();
        Set<SpaceRole> plainRoles = new LinkedHashSet<>();
        for (String raw : claims) {
            if (Texts.isBlank(raw)) {
                continue;
            }
            String value = raw.trim();
            int separator = value.indexOf(':');
            if (separator <= 0) {
                SpaceRole role = parseRole(value);
                if (role != null) {
                    plainRoles.add(role);
                }
                continue;
            }
            SpaceRole role = parseRole(value.substring(separator + 1));
            Long spaceId = resolveSpaceId(value.substring(0, separator));
            if (role != null && spaceId != null) {
                merge(targets, spaceId, role);
            }
        }
        if (defaultSpaceId != null) {
            SpaceRole highest = plainRoles.stream().reduce(null,
                    (left, right) -> left == null || right.level() > left.level() ? right : left);
            if (highest == null) {
                highest = parseRole(oidc.getDefaultRole());
            }
            if (highest != null) {
                merge(targets, defaultSpaceId, highest);
            }
        }
        for (Map.Entry<Long, SpaceRole> entry : targets.entrySet()) {
            grant(user.getId(), entry.getKey(), entry.getValue());
        }
    }

    private void grant(Long userId, Long spaceId, SpaceRole role) {
        com.king.ai.system.entity.UserSpace link = userSpaceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.king.ai.system.entity.UserSpace>()
                        .eq(com.king.ai.system.entity.UserSpace::getUserId, userId)
                        .eq(com.king.ai.system.entity.UserSpace::getSpaceId, spaceId)
                        .last("LIMIT 1"));
        if (link == null) {
            link = new com.king.ai.system.entity.UserSpace();
            link.setUserId(userId);
            link.setSpaceId(spaceId);
            link.setRole(role.name());
            userSpaceMapper.insert(link);
            return;
        }
        SpaceRole current = SpaceRole.parse(link.getRole());
        if (current.level() >= role.level()) {
            return;
        }
        link.setRole(role.name());
        userSpaceMapper.updateById(link);
    }

    private Long resolveSpaceId(String code) {
        if (Texts.isBlank(code)) {
            return null;
        }
        com.king.ai.system.entity.Space space = spaceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.king.ai.system.entity.Space>()
                        .eq(com.king.ai.system.entity.Space::getCode, code.trim().toLowerCase(Locale.ROOT))
                        .last("LIMIT 1"));
        if (space == null || (space.getStatus() != null && space.getStatus() != 0)) {
            return null;
        }
        return space.getId();
    }

    private static void merge(Map<Long, SpaceRole> targets, Long spaceId, SpaceRole role) {
        SpaceRole current = targets.get(spaceId);
        if (current == null || role.level() > current.level()) {
            targets.put(spaceId, role);
        }
    }

    private static SpaceRole parseRole(String value) {
        if (Texts.isBlank(value)) {
            return null;
        }
        try {
            return SpaceRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private List<String> roles(JsonNode claims) {
        JsonNode node = claims.get(oidc().getRoleClaim());
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>(node.size());
            node.forEach(item -> values.add(item.asText()));
            return values;
        }
        String value = node.asText();
        return Texts.isBlank(value) ? List.of() : List.of(value.split("[,\\s]+"));
    }

    private String exchange(String code) {
        Discovery document = discovery();
        HttpUrl url = HttpUrl.parse(document.tokenEndpoint());
        if (url == null) {
            throw new BizException(502, "SSO Token 地址不合法");
        }
        FormBody form = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", oidc().getRedirectUri())
                .add("client_id", oidc().getClientId())
                .add("client_secret", Texts.blankToDefault(oidc().getClientSecret(), ""))
                .build();
        Request request = new Request.Builder().url(url).post(form)
                .header("Accept", "application/json").build();
        JsonNode body = execute(request, "SSO 换取令牌失败");
        String idToken = text(body, "id_token");
        if (Texts.isBlank(idToken)) {
            throw new BizException(502, "SSO 未返回 ID Token");
        }
        return idToken;
    }

    private JsonNode validateIdToken(String idToken, String expectedNonce) {
        return tokenValidator.validate(idToken, expectedNonce, discovery().issuer(),
                oidc().getClientId(), this::signingKey);
    }

    private PublicKey signingKey(String keyId) {
        Jwks cache = jwks;
        if (cache == null || cache.expiresAt() <= System.currentTimeMillis()) {
            cache = refreshJwks();
        }
        if (keyId != null && cache.keys().containsKey(keyId)) {
            return cache.keys().get(keyId);
        }
        Jwks refreshed = refreshJwks();
        PublicKey key = keyId == null ? firstKey(refreshed) : refreshed.keys().get(keyId);
        if (key == null) {
            throw BizException.unauthorized("ID Token 签名密钥不存在");
        }
        return key;
    }

    private static PublicKey firstKey(Jwks jwks) {
        return jwks.keys().values().stream().findFirst().orElse(null);
    }

    private Jwks refreshJwks() {
        Discovery document = discovery();
        HttpUrl url = HttpUrl.parse(document.jwksUri());
        if (url == null) {
            throw new BizException(502, "SSO JWKS 地址不合法");
        }
        JsonNode body = execute(new Request.Builder().url(url).get()
                .header("Accept", "application/json").build(), "获取 SSO 签名密钥失败");
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        JsonNode entries = body.path("keys");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                if (!"RSA".equals(text(entry, "kty"))) {
                    continue;
                }
                String keyId = text(entry, "kid");
                try {
                    BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(text(entry, "n")));
                    BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(text(entry, "e")));
                    PublicKey key = KeyFactory.getInstance("RSA")
                            .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                    keys.put(keyId == null ? "default" : keyId, key);
                } catch (Exception ex) {
                    log.warn("Skipping invalid JWKS entry: {}", ex.getClass().getSimpleName());
                }
            }
        }
        if (keys.isEmpty()) {
            throw new BizException(502, "SSO 未提供可用的签名密钥");
        }
        Jwks cache = new Jwks(keys, System.currentTimeMillis() + CACHE_TTL.toMillis());
        jwks = cache;
        return cache;
    }

    private Discovery discovery() {
        long now = System.currentTimeMillis();
        Discovery cache = discovery;
        if (cache != null && discoveryExpiresAt > now) {
            return cache;
        }
        String issuer = normalize(oidc().getIssuer());
        HttpUrl url = HttpUrl.parse(issuer + "/.well-known/openid-configuration");
        if (url == null) {
            throw new BizException(502, "SSO Issuer 地址不合法");
        }
        JsonNode body = execute(new Request.Builder().url(url).get()
                .header("Accept", "application/json").build(), "获取 SSO 配置失败");
        String discoveredIssuer = text(body, "issuer");
        if (Texts.isBlank(discoveredIssuer) || !normalize(discoveredIssuer).equals(issuer)) {
            throw new BizException(502, "SSO Issuer 与发现文档不一致");
        }
        String authorizationEndpoint = text(body, "authorization_endpoint");
        String tokenEndpoint = text(body, "token_endpoint");
        String jwksUri = text(body, "jwks_uri");
        if (Texts.isBlank(authorizationEndpoint) || Texts.isBlank(tokenEndpoint)
                || Texts.isBlank(jwksUri)) {
            throw new BizException(502, "SSO 发现文档缺少必要端点");
        }
        Discovery result = new Discovery(issuer, authorizationEndpoint, tokenEndpoint, jwksUri);
        discovery = result;
        discoveryExpiresAt = now + CACHE_TTL.toMillis();
        return result;
    }

    private JsonNode execute(Request request, String failureMessage) {
        try (Response response = httpClient.newBuilder()
                .callTimeout(Duration.ofSeconds(20))
                .build()
                .newCall(request)
                .execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                log.warn("{}: status {}", failureMessage, response.code());
                throw new BizException(502, failureMessage);
            }
            try (InputStream input = body.byteStream()) {
                byte[] payload = input.readNBytes(MAX_DOCUMENT_BYTES);
                if (payload.length == 0) {
                    throw new BizException(502, failureMessage);
                }
                return objectMapper.readTree(payload);
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("{}: {}", failureMessage, ex.getClass().getSimpleName());
            throw new BizException(502, failureMessage);
        }
    }

    private List<String> scopes() {
        List<String> scopes = oidc().getScopes();
        if (scopes == null || scopes.isEmpty()) {
            return List.of("openid", "profile", "email");
        }
        return scopes;
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw BizException.notFound("SSO 未启用");
        }
    }

    private KingAiProperties.Oidc oidc() {
        return properties.getSso().getOidc();
    }

    private static String normalize(String issuer) {
        if (issuer == null) {
            return "";
        }
        String value = issuer.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return Texts.isBlank(text) ? null : text.trim();
    }
}
