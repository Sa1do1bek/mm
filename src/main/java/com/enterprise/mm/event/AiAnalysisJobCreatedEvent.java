package com.enterprise.mm.event;

import com.enterprise.mm.enums.AiAnalysisType;
import java.util.UUID;

public record AiAnalysisJobCreatedEvent(UUID jobId, AiAnalysisType analysisType) {
}
