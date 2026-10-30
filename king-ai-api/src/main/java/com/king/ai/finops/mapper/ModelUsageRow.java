package com.king.ai.finops.mapper;

import lombok.Data;

@Data
public class ModelUsageRow {

    private String model;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long costMicros;
}
