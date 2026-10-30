package com.king.ai.model;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.CryptoService;
import com.king.ai.common.UpstreamErrors;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.llm.LlmClientFactory;
import com.king.ai.model.dto.SupplierSaveRequest;
import com.king.ai.model.dto.SupplierTestResult;
import com.king.ai.model.entity.Supplier;
import com.king.ai.model.mapper.SupplierMapper;
import com.king.ai.model.mapper.SupplierModelMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplierServiceTest {

    private static final LocalDateTime ORIGINAL_ROTATED_AT = LocalDateTime.of(2020, 1, 1, 0, 0);

    static {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
    }

    private SupplierMapper supplierMapper;
    private SupplierModelMapper modelMapper;
    private CryptoService cryptoService;
    private LlmClientFactory clientFactory;
    private SupplierService service;

    @BeforeEach
    void setUp() {
        supplierMapper = mock(SupplierMapper.class);
        modelMapper = mock(SupplierModelMapper.class);
        cryptoService = mock(CryptoService.class);
        clientFactory = mock(LlmClientFactory.class);
        service = new SupplierService(supplierMapper, modelMapper, cryptoService,
                mock(OutboundUrlGuard.class), clientFactory, new ObjectMapper());
    }

    @Test
    void createEncryptsSubmittedKeyAndStampsRotationTime() {
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.insert(any(Supplier.class))).thenReturn(1);
        when(cryptoService.encrypt("sk-1")).thenReturn("enc");

        service.create(1L, request("sk-1"));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).insert(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("enc");
        assertThat(captor.getValue().getApiKeyRotatedAt()).isNotNull();
    }

    @Test
    void createWithBlankKeyStoresEmptyKeyAndNoRotationTime() {
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.insert(any(Supplier.class))).thenReturn(1);

        service.create(1L, request("  "));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).insert(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEmpty();
        assertThat(captor.getValue().getApiKeyRotatedAt()).isNull();
        verify(cryptoService, never()).encrypt(any());
    }

    @Test
    void updateWithKeyEncryptsItAndRefreshesRotationTime() {
        Supplier existing = existingSupplier();
        when(supplierMapper.selectById(9L)).thenReturn(existing);
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(cryptoService.encrypt("sk-2")).thenReturn("enc");

        service.update(1L, 9L, request("sk-2"));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("enc");
        assertThat(captor.getValue().getApiKeyRotatedAt()).isAfter(ORIGINAL_ROTATED_AT);
    }

    @Test
    void updateWithBlankKeyClearsRotationTime() {
        Supplier existing = existingSupplier();
        when(supplierMapper.selectById(9L)).thenReturn(existing);
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

        service.update(1L, 9L, request("  "));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEmpty();
        assertThat(captor.getValue().getApiKeyRotatedAt()).isNull();
        verify(cryptoService, never()).encrypt(any());
    }

    @Test
    void rotationTimeMustBeWritableAsNull() throws Exception {
        com.baomidou.mybatisplus.annotation.TableField field = Supplier.class
                .getDeclaredField("apiKeyRotatedAt")
                .getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);

        assertThat(field).isNotNull();
        assertThat(field.updateStrategy())
                .isEqualTo(com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS);
    }

    @Test
    void updateWithNullKeyLeavesKeyAndRotationTimeUntouched() {
        Supplier existing = existingSupplier();
        when(supplierMapper.selectById(9L)).thenReturn(existing);
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

        service.update(1L, 9L, request(null));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("old");
        assertThat(captor.getValue().getApiKeyRotatedAt()).isEqualTo(ORIGINAL_ROTATED_AT);
        verify(cryptoService, never()).encrypt(any());
    }

    @Test
    void connectionTestDoesNotLeakUpstreamErrorText() {
        when(supplierMapper.selectById(9L)).thenReturn(existingSupplier());
        when(clientFactory.clientFor(any(Supplier.class)))
                .thenThrow(new RuntimeException("401 for api key sk-live-secret"));

        SupplierTestResult result = service.test(1L, 9L);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isEqualTo(UpstreamErrors.CONNECTION_TEST_FAILED);
        assertThat(result.message()).doesNotContain("sk-live-secret");
    }

    private Supplier existingSupplier() {
        Supplier supplier = new Supplier();
        supplier.setId(9L);
        supplier.setSpaceId(1L);
        supplier.setName("vendor");
        supplier.setApiKey("old");
        supplier.setApiKeyRotatedAt(ORIGINAL_ROTATED_AT);
        return supplier;
    }

    private SupplierSaveRequest request(String apiKey) {
        return new SupplierSaveRequest("vendor", "OPENAI", "https://api.example.com", apiKey, null, 0, "");
    }
}
