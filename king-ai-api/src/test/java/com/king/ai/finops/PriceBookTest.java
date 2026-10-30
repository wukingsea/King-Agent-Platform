package com.king.ai.finops;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceBookTest {

    private final KingAiProperties properties = new KingAiProperties();

    @Test
    void appliesModelSpecificPricesPerMillionTokens() {
        properties.getFinops().getPrices().put("gpt-4o", price(5.0, 15.0));
        PriceBook book = new PriceBook(properties);

        // 1000 prompt tokens at $5/1M + 500 completion tokens at $15/1M = 12500 micros.
        assertThat(book.costMicros("gpt-4o", 1000, 500)).isEqualTo(12_500);
    }

    @Test
    void fallsBackToDefaultPricesForUnknownModels() {
        properties.getFinops().setDefaultInputPricePerMillion(2.0);
        properties.getFinops().setDefaultOutputPricePerMillion(4.0);
        PriceBook book = new PriceBook(properties);

        assertThat(book.costMicros("unknown-model", 1_000_000, 250_000)).isEqualTo(3_000_000);
    }

    @Test
    void returnsZeroWithoutConfiguredPrices() {
        PriceBook book = new PriceBook(properties);

        assertThat(book.costMicros("gpt-4o", 1000, 1000)).isZero();
    }

    @Test
    void ignoresNegativeTokenCounts() {
        properties.getFinops().setDefaultInputPricePerMillion(3.0);
        properties.getFinops().setDefaultOutputPricePerMillion(3.0);
        PriceBook book = new PriceBook(properties);

        assertThat(book.costMicros(null, -100, 100)).isEqualTo(300);
    }

    @Test
    void currencyDefaultsToUsdAndIsNormalized() {
        PriceBook book = new PriceBook(properties);
        assertThat(book.currency()).isEqualTo("USD");

        properties.getFinops().setCurrency(" cny ");
        assertThat(book.currency()).isEqualTo("CNY");
    }

    private static KingAiProperties.ModelPrice price(double input, double output) {
        KingAiProperties.ModelPrice price = new KingAiProperties.ModelPrice();
        price.setInputPerMillion(input);
        price.setOutputPerMillion(output);
        return price;
    }
}
