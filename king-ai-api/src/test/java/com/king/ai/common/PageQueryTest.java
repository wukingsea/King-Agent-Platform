package com.king.ai.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void appliesDefaultsForMissingParameters() {
        PageQuery query = PageQuery.of(null, null, 20);

        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(20);
    }

    @Test
    void clampsNonPositiveValues() {
        PageQuery query = PageQuery.of(0, -5, 20);

        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(1);
    }

    @Test
    void capsSizeAtTheMybatisMaxLimit() {
        PageQuery query = PageQuery.of(2, 5000, 20);

        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(PageQuery.MAX_SIZE);
    }
}
