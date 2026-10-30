package com.king.ai.knowledge.ocr;

import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Tesseract adapter. Pages are rendered at the configured DPI, written to a temporary PNG
 * and passed to {@code tesseract ... stdout}. Every failure is contained per page so a
 * single bad page cannot discard the rest of the document.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TesseractOcrService implements OcrService {

    private static final int MAX_OUTPUT_BYTES = 4_000_000;

    private final KingAiProperties properties;

    @Override
    public boolean enabled() {
        return properties.getOcr().isEnabled();
    }

    @Override
    public boolean pdfNeedsOcr(String textLayer, int pages) {
        if (!enabled() || pages <= 0) {
            return false;
        }
        int threshold = Math.max(0, properties.getOcr().getMinTextCharsPerPage()) * pages;
        return (textLayer == null ? 0 : textLayer.trim().length()) < threshold;
    }

    @Override
    public String extractPdf(byte[] pdf, String textLayer, int pages) {
        if (!pdfNeedsOcr(textLayer, pages)) {
            return textLayer;
        }
        int maxPages = properties.getOcr().getMaxPages();
        if (maxPages > 0 && pages > maxPages) {
            throw BizException.badRequest("PDF 页数为 " + pages + " 页，超过 OCR 处理上限 "
                    + maxPages + " 页，请拆分后上传");
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int dpi = Math.max(72, properties.getOcr().getRenderDpi());
            StringBuilder recognized = new StringBuilder();
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                try {
                    BufferedImage image = renderer.renderImageWithDPI(page, dpi);
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", png);
                    String text = recognizeImage(png.toByteArray());
                    if (!text.isBlank()) {
                        if (recognized.length() > 0) {
                            recognized.append('\n');
                        }
                        recognized.append(text.trim());
                    }
                } catch (Exception ex) {
                    log.warn("OCR failed for page {}: {}", page + 1, ex.getMessage());
                }
            }
            if (recognized.length() == 0) {
                log.warn("OCR produced no text; keeping the PDF text layer");
                return textLayer;
            }
            return recognized.toString();
        } catch (IOException ex) {
            log.warn("OCR rendering failed: {}", ex.getMessage());
            return textLayer;
        }
    }

    @Override
    public String recognizeImage(byte[] image) {
        if (!enabled()) {
            throw BizException.badRequest("当前未启用 OCR，无法解析图片文档");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("king-ai-ocr-", ".png");
            Files.write(temporary, image);
            return runTesseract(temporary);
        } catch (BizException ex) {
            throw ex;
        } catch (IOException ex) {
            throw BizException.badRequest("OCR 临时文件处理失败");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    private String runTesseract(Path image) {
        List<String> command = new ArrayList<>();
        command.add(properties.getOcr().getCommand());
        command.add(image.toAbsolutePath().toString());
        command.add("stdout");
        command.add("-l");
        command.add(Texts.blankToDefault(properties.getOcr().getLanguages(), "eng"));
        command.add("--psm");
        command.add("6");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            Process started = process;
            FutureTask<byte[]> reader = new FutureTask<>(
                    () -> started.getInputStream().readNBytes(MAX_OUTPUT_BYTES));
            Thread readerThread = new Thread(reader, "king-ai-ocr-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            int timeout = Math.max(1, properties.getOcr().getTimeoutSeconds());
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.cancel(true);
                throw BizException.badRequest("OCR 处理超时，请稍后重试");
            }
            byte[] output;
            try {
                output = reader.get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw BizException.badRequest("OCR 输出读取失败");
            }
            if (process.exitValue() != 0) {
                log.warn("tesseract exited with {}", process.exitValue());
                throw BizException.badRequest("OCR 识别失败，请确认服务器已安装 tesseract 及语言包");
            }
            return new String(output, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (BizException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw BizException.badRequest("OCR 处理被中断");
        } catch (IOException ex) {
            throw BizException.badRequest("OCR 引擎不可用，请确认服务器已安装 tesseract");
        }
    }
}
