package com.enterprise.mm.payload;

public record RunStatsDto(
    Long totalRuns,
    Long completedRuns,
    Long failedRuns
) {}
