package com.king.ai.compliance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRuleModeratorTest {

    private final LocalRuleModerator moderator = new LocalRuleModerator();

    @Test
    void blocksConfiguredTerm() {
        ModerationScan scan = moderator.scan("这里有违禁词出现", List.of("违禁词"));

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
        assertThat(scan.decision().category()).isEqualTo("BLOCKLIST");
        assertThat(scan.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.start()).isEqualTo(3));
    }

    @Test
    void detectsTermSeparatedByZeroWidthAndPunctuation() {
        ModerationScan scan = moderator.scan("违\u200B禁-词", List.of("违禁词"));

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
        assertThat(scan.hits()).hasSize(1);
    }

    @Test
    void detectsTermSeparatedByWhitespace() {
        assertThat(moderator.scan("违 禁\n词", List.of("违禁词")).decision().verdict())
                .isEqualTo(ModerationVerdict.BLOCK);
    }

    @Test
    void maskStaysAlignedWhenNfkcWouldChangeLength() {
        // ㍿ 的 NFKC 结果是 4 个字符；若用归一化后的下标去改原串，会越界并漏掉违禁词。
        ModerationScan scan = moderator.scan("㍿违禁词", List.of("违禁词"));

        assertThat(moderator.mask("㍿违禁词", scan)).isEqualTo("㍿[已屏蔽]");
    }

    @Test
    void emptyTextOrEmptyBlocklistAllows() {
        assertThat(moderator.scan("", List.of("违禁词")).decision().verdict())
                .isEqualTo(ModerationVerdict.ALLOW);
        assertThat(moderator.scan("普通内容", List.of()).decision().verdict())
                .isEqualTo(ModerationVerdict.ALLOW);
    }

    @Test
    void excessiveLinksAreReview() {
        ModerationScan scan = moderator.scan(
                "https://a.com/1 https://a.com/2 https://a.com/3 "
                        + "https://a.com/4 https://a.com/5 https://a.com/6", List.of());

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(scan.decision().category()).isEqualTo("SPAM");
    }

    @Test
    void maskReplacesHitWithoutLeakingTerm() {
        ModerationScan scan = moderator.scan("前违禁词后", List.of("违禁词"));

        assertThat(moderator.mask("前违禁词后", scan)).isEqualTo("前[已屏蔽]后");
    }

    @Test
    void maskMergesContainedHitsWhenShorterTermListedFirst() {
        ModerationScan scan = moderator.scan("违禁词", List.of("违禁", "违禁词"));

        assertThat(scan.hits()).hasSize(2);
        assertThat(moderator.mask("违禁词", scan)).isEqualTo("[已屏蔽]");
    }

    @Test
    void maskMergesContainedHitsWhenLongerTermListedFirst() {
        ModerationScan scan = moderator.scan("违禁词", List.of("违禁词", "违禁"));

        assertThat(scan.hits()).hasSize(2);
        assertThat(moderator.mask("违禁词", scan)).isEqualTo("[已屏蔽]");
    }

    @Test
    void maskMergesContainedHitsWithoutLeakingTail() {
        ModerationScan scan = moderator.scan("违禁词句", List.of("违禁", "违禁词句"));

        assertThat(scan.hits()).hasSize(2);
        assertThat(moderator.mask("违禁词句", scan))
                .isEqualTo("[已屏蔽]")
                .doesNotContain("词句");
    }
}
