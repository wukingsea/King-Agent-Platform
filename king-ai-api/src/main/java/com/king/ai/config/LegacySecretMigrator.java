package com.king.ai.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.common.CryptoService;
import com.king.ai.common.Texts;
import com.king.ai.mcp.entity.McpServer;
import com.king.ai.mcp.mapper.McpServerMapper;
import com.king.ai.model.entity.Supplier;
import com.king.ai.model.mapper.SupplierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@Profile("!migrate")
@RequiredArgsConstructor
public class LegacySecretMigrator implements ApplicationRunner {

    private static final int BATCH_SIZE = 200;

    private final SupplierMapper supplierMapper;
    private final McpServerMapper mcpServerMapper;
    private final CryptoService cryptoService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int migrated = encryptSupplierSecrets();
        migrated += encryptMcpHeaders();
        if (migrated > 0) {
            log.info("Encrypted {} legacy plaintext secret field(s)", migrated);
        }
    }

    private int encryptSupplierSecrets() {
        int migrated = 0;
        while (true) {
            List<Supplier> batch = supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                    .and(w -> w.and(a -> a.isNotNull(Supplier::getApiKey).ne(Supplier::getApiKey, "")
                                    .notLikeRight(Supplier::getApiKey, "enc:v"))
                            .or(h -> h.isNotNull(Supplier::getHeaders).ne(Supplier::getHeaders, "")
                                    .notLikeRight(Supplier::getHeaders, "enc:v")))
                    .last("LIMIT " + BATCH_SIZE));
            boolean changed = false;
            for (Supplier supplier : batch) {
                if (needsEncryption(supplier.getApiKey())) {
                    supplier.setApiKey(cryptoService.encrypt(supplier.getApiKey()));
                    changed = true;
                    migrated++;
                }
                if (needsEncryption(supplier.getHeaders())) {
                    supplier.setHeaders(cryptoService.encrypt(supplier.getHeaders()));
                    changed = true;
                    migrated++;
                }
                if (changed) {
                    supplierMapper.updateById(supplier);
                }
                changed = false;
            }
            if (batch.size() < BATCH_SIZE) {
                return migrated;
            }
        }
    }

    private int encryptMcpHeaders() {
        int migrated = 0;
        while (true) {
            List<McpServer> batch = mcpServerMapper.selectList(new LambdaQueryWrapper<McpServer>()
                    .isNotNull(McpServer::getHeaders)
                    .ne(McpServer::getHeaders, "")
                    .notLikeRight(McpServer::getHeaders, "enc:v")
                    .last("LIMIT " + BATCH_SIZE));
            for (McpServer server : batch) {
                if (needsEncryption(server.getHeaders())) {
                    server.setHeaders(cryptoService.encrypt(server.getHeaders()));
                    mcpServerMapper.updateById(server);
                    migrated++;
                }
            }
            if (batch.size() < BATCH_SIZE) {
                return migrated;
            }
        }
    }

    private boolean needsEncryption(String value) {
        return !Texts.isBlank(value) && !cryptoService.isEncrypted(value);
    }
}
