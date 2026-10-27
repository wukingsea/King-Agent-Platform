package com.king.ai.compliance;

import com.king.ai.compliance.access.CsvWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void escapesRfc4180() {
        String csv = CsvWriter.write(List.of("a", "b"),
                List.of(List.of("1", "x,y"), List.of("2", "say \"hi\"")));

        assertThat(csv).isEqualTo("a,b\n1,\"x,y\"\n2,\"say \"\"hi\"\"\"\n");
    }

    @Test
    void neutralizesFormulaInjection() {
        String csv = CsvWriter.write(List.of("name"),
                List.of(List.of("=HYPERLINK(\"http://evil\")"), List.of("@SUM(A1)")));

        assertThat(csv).isEqualTo("name\n\"'=HYPERLINK(\"\"http://evil\"\")\"\n'@SUM(A1)\n");
    }

    @Test
    void neutralizesEveryFormulaLeadCharacter() {
        String csv = CsvWriter.write(List.of("value"),
                List.of(List.of("=1"), List.of("+1"), List.of("-1"), List.of("@x"),
                        List.of("\tx"), List.of("\rx")));

        assertThat(csv).startsWith("value\n'=1\n'+1\n'-1\n'@x\n'\tx\n");
        assertThat(csv).contains("\"'\rx\"");
    }

    @Test
    void blankAndNullValuesBecomeEmptyCells() {
        String csv = CsvWriter.write(List.of("a", "b"),
                List.of(java.util.Arrays.asList(null, "")));

        assertThat(csv).isEqualTo("a,b\n,\n");
    }

    @Test
    void quotesNewlinesAndCarriageReturns() {
        String csv = CsvWriter.write(List.of("v"),
                List.of(List.of("line1\nline2"), List.of("car\rreturn")));

        assertThat(csv).isEqualTo("v\n\"line1\nline2\"\n\"car\rreturn\"\n");
    }
}
