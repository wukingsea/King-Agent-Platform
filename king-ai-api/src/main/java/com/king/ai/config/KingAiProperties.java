package com.king.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "king-ai")
public class KingAiProperties {

    private String appSecret = "king-ai-local-dev-secret-change-me";
    private String encryptionKey = "";
    private List<String> previousEncryptionKeys = new ArrayList<>();
    private Duration sessionTtl = Duration.ofDays(7);
    private String tokenStore = "redis";
    private String vectorStore = "milvus";
    private String dbType = "mysql";
    private Chat chat = new Chat();
    private Rag rag = new Rag();
    private Index index = new Index();
    private Milvus milvus = new Milvus();
    private Demo demo = new Demo();
    private PublicChat publicChat = new PublicChat();
    private Security security = new Security();
    private Outbound outbound = new Outbound();
    private Admin admin = new Admin();
    private Audit audit = new Audit();
    private Compliance compliance = new Compliance();

    @Data
    public static class Chat {
        private int maxHistoryMessages = 20;
        private int maxHistoryChars = 24000;
        private int maxInputChars = 10000;
        private int maxToolRounds = 6;
        private int maxToolResultChars = 8000;
        private int llmTimeoutSeconds = 180;
        private int llmMaxRetries = 2;
        /**
         * Maximum concurrently streaming chats per space. Protects the shared executor from a
         * single noisy tenant; 0 or a negative value disables the limit.
         */
        private int maxConcurrentPerSpace = 4;
        /**
         * Maximum SSE connections accepted by this instance. 0 or a negative value disables admission control.
         */
        private int sseCapacity = 200;
    }

    @Data
    public static class Rag {
        private int maxContextChars = 8000;
        private int embeddingBatchSize = 16;
        private double minScore = 0.1;
    }

    @Data
    public static class Index {
        private boolean enabled = true;
        private long pollIntervalMs = 15000;
        private long reconcileIntervalMs = 300000;
        private int batchSize = 10;
        private int maxAttempts = 5;
        private int retryDelaySeconds = 30;
        private int staleMinutes = 30;
    }

    @Data
    public static class Milvus {
        private String baseUrl = "http://localhost:19530";
        private String token = "";
        private int timeoutSeconds = 30;
    }

    @Data
    public static class Demo {
        private boolean enabled = false;
    }

    @Data
    public static class PublicChat {
        private int rateLimitPerMinute = 20;
        private Duration visitorTokenTtl = Duration.ofDays(30);
        private String visitorCookieName = "king_visitor";
    }

    @Data
    public static class Security {
        private boolean trustForwardedHeaders = false;
        /**
         * Number of trusted reverse proxies in front of the app. The client IP is read from the
         * right-hand side of X-Forwarded-For, so client-supplied entries on the left cannot spoof it.
         */
        private int trustedProxyHops = 1;
        private boolean cookieSecure = false;
        private String sessionCookieName = "king_session";
        private List<String> allowedOrigins = new ArrayList<>();
    }

    @Data
    public static class Outbound {
        private boolean blockPrivateNetworks = true;
        private List<String> allowedHosts = new ArrayList<>();
    }

    @Data
    public static class Admin {
        private String username = "";
        private String password = "";
        private String nickname = "平台管理员";
        private String spaceName = "默认空间";
        private String spaceCode = "default";
    }

    @Data
    public static class Audit {
        private boolean enabled = true;
        private int retentionDays = 180;
    }

    @Data
    public static class Compliance {
        private Pii pii = new Pii();
        private Moderation moderation = new Moderation();
        private Retention retention = new Retention();
        private Export export = new Export();
        private AccessReview accessReview = new AccessReview();
    }

    @Data
    public static class Pii {
        private boolean defaultEnabled = true;
        private int maxCustomRules = 20;
        private int streamHoldbackChars = 128;
    }

    @Data
    public static class Moderation {
        private com.king.ai.compliance.ModerationMode defaultMode =
                com.king.ai.compliance.ModerationMode.OFF;
        private boolean failClosedOnError = true;
        private int externalTimeoutMs = 2000;
        private String externalUrl = "";
        private String externalToken = "";
    }

    @Data
    public static class Retention {
        private boolean enabled = false;
        private boolean dryRun = true;
        private String cron = "0 0 3 * * *";
        private int batchSize = 200;
        private int maxBatches = 100;
    }

    @Data
    public static class Export {
        private String rootDir = "./data/exports";
        private long pollIntervalMs = 10000;
        private int ttlHours = 24;
        private long maxRows = 1_000_000;
        private int batchSize = 500;
    }

    @Data
    public static class AccessReview {
        private String cron = "0 0 4 1 1,4,7,10 *";
        private int staleDays = 90;
    }
}
