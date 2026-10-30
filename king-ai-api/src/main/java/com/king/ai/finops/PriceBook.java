package com.king.ai.finops;

import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns token counts into an estimated cost using the configured per-model price list.
 * Prices are expressed per 1M tokens; the ledger stores micros, so one token costs
 * {@code pricePerMillion} micros.
 */
@Component
@RequiredArgsConstructor
public class PriceBook {

    private final KingAiProperties properties;

    public long costMicros(String model, int promptTokens, int completionTokens) {
        KingAiProperties.Finops finops = properties.getFinops();
        KingAiProperties.ModelPrice price = model == null ? null : finops.getPrices().get(model);
        double input = price == null
                ? finops.getDefaultInputPricePerMillion() : price.getInputPerMillion();
        double output = price == null
                ? finops.getDefaultOutputPricePerMillion() : price.getOutputPerMillion();
        double micros = Math.max(0, promptTokens) * input + Math.max(0, completionTokens) * output;
        if (!Double.isFinite(micros) || micros <= 0) {
            return 0;
        }
        return Math.round(micros);
    }

    public String currency() {
        String currency = properties.getFinops().getCurrency();
        return currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase();
    }
}
