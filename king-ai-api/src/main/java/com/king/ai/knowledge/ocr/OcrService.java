package com.king.ai.knowledge.ocr;

/**
 * Document OCR port. Implementations must degrade gracefully: a failed page keeps the
 * PDF text layer, and a disabled OCR pipeline behaves exactly like the pre-OCR upload path.
 */
public interface OcrService {

    boolean enabled();

    /**
     * A PDF needs OCR when its text layer is too thin for the configured per-page minimum.
     */
    boolean pdfNeedsOcr(String textLayer, int pages);

    /**
     * Renders the pages and returns recognized text. Falls back to the supplied text layer
     * when OCR is unavailable or every page fails.
     */
    String extractPdf(byte[] pdf, String textLayer, int pages);

    String recognizeImage(byte[] image);
}
