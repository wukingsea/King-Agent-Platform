package com.king.ai.finops.mapper;

import lombok.Data;

@Data
public class UsageTotals {

    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long costMicros;
}
