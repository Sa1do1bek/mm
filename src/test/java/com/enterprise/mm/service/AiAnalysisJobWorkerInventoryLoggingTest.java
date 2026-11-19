package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.enterprise.mm.service.InventoryAiDraftService.FileSnapshot;
import com.enterprise.mm.service.InventoryAiDraftService.SourceFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AiAnalysisJobWorkerInventoryLoggingTest {
    @Test
    void logsEntireRejectedInventoryResponseInNumberedChunks() throws Exception {
        UUID analysisId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        var snapshot = new FileSnapshot(UUID.randomUUID(), "order.pdf", "application/pdf", "a".repeat(64));
        var request = new AnalysisRequest(UUID.randomUUID(), List.of(snapshot), 1);
        List<SourceFile> files = List.of(new SourceFile(snapshot, new byte[] {1}));
        var raw = mapper.createObjectNode().put("warnings", "x".repeat(16_500));

        AiAnalysisJob job = mock(AiAnalysisJob.class);
        when(job.getId()).thenReturn(analysisId);
        when(job.getTenantId()).thenReturn("tenant-a");
        when(job.getCompanyId()).thenReturn(UUID.randomUUID());
        when(job.getAnalysisType()).thenReturn(AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION);
        when(job.getRequestedBy()).thenReturn("planner");
        when(job.getRequestedLanguage()).thenReturn("uz");
        when(job.getRequestPayload()).thenReturn(mapper.writeValueAsString(request));

        AiAnalysisJobRepository jobs = mock(AiAnalysisJobRepository.class);
        when(jobs.findById(analysisId)).thenReturn(Optional.of(job));
        AiAnalysisJobTransactionService transactions = mock(AiAnalysisJobTransactionService.class);
        when(transactions.markRunning(analysisId)).thenReturn(true);
        InventoryAiDraftService drafts = mock(InventoryAiDraftService.class);
        when(drafts.readSnapshot(eq(request), eq("planner"))).thenReturn(files);
        MmAiClient ai = mock(MmAiClient.class);
        when(ai.extractInventoryDocument(analysisId, files, "uz", 1)).thenReturn(raw);
        InventoryAiResultService results = mock(InventoryAiResultService.class);
        when(results.validate(eq(raw), eq(analysisId), any(AnalysisRequest.class)))
            .thenThrow(new IllegalArgumentException("MM AI inventory extraction warnings must be an array"));

        var worker = new AiAnalysisJobWorker(jobs, transactions, mapper, mock(PriceAiAnalysisService.class),
            ai, mock(MaterialDuplicateCandidateService.class), drafts, results,
            mock(MaterialStockAccountRecommendationService.class));
        Logger logger = (Logger) LoggerFactory.getLogger(AiAnalysisJobWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            worker.run(analysisId);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(message -> assertThat(message)
            .contains("Rejected MM AI inventory response: analysisId=" + analysisId)
            .contains("warningsType=STRING").contains("chunks=3"));
        List<String> parts = messages.stream()
            .filter(message -> message.startsWith("Rejected MM AI inventory response payload:"))
            .toList();
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).contains("part=1/3");
        assertThat(parts.get(2)).contains("part=3/3");
        assertThat(parts.stream().map(part -> part.substring(part.indexOf(" json=") + 6))
            .collect(Collectors.joining())).isEqualTo(raw.toString());
    }
}
