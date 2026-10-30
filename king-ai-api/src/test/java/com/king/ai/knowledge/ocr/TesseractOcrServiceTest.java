package com.king.ai.knowledge.ocr;

import com.king.ai.common.BizException;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TesseractOcrServiceTest {

    private KingAiProperties properties;
    private TesseractOcrService service;

    @BeforeEach
    void setUp() {
        properties = new KingAiProperties();
        properties.getOcr().setEnabled(true);
        properties.getOcr().setMinTextCharsPerPage(50);
        properties.getOcr().setMaxPages(30);
        service = new TesseractOcrService(properties);
    }

    @Test
    void disabledOcrNeverTriggersAndRejectsImages() {
        properties.getOcr().setEnabled(false);

        assertThat(service.pdfNeedsOcr("", 3)).isFalse();
        assertThatThrownBy(() -> service.recognizeImage(new byte[]{1}))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未启用 OCR");
    }

    @Test
    void thinTextLayerTriggersOcr() {
        assertThat(service.pdfNeedsOcr("short", 2)).isTrue();
        assertThat(service.pdfNeedsOcr("x".repeat(99), 2)).isTrue();
        assertThat(service.pdfNeedsOcr("x".repeat(100), 2)).isFalse();
    }

    @Test
    void keepsTextLayerWhenOcrIsNotNeeded() {
        String textLayer = "plain text layer";

        assertThat(service.extractPdf(new byte[]{1}, textLayer, 1)).isEqualTo(textLayer);
    }

    @Test
    void refusesScannedPdfAbovePageLimit() {
        assertThatThrownBy(() -> service.extractPdf(new byte[]{1}, "", 31))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过 OCR 处理上限");
    }
}
