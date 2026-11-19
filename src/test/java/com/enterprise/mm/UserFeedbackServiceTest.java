package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.FeedbackCreateRequest;
import com.enterprise.mm.payload.FeedbackViewport;
import com.enterprise.mm.payload.UserFeedbackSearchRequest;
import com.enterprise.mm.repository.UserFeedbackRepository;
import com.enterprise.mm.service.UserFeedbackService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "feedback-user", roles = "MM_MANAGER")
class UserFeedbackServiceTest {
    @Autowired UserFeedbackService service;
    @Autowired UserFeedbackRepository repository;

    @BeforeEach
    void cleanFeedbacks() {
        repository.deleteAll();
    }

    @Test
    void createsFeedbackWithAnnotatedScreenshotAndContext() {
        FeedbackCreateRequest request = new FeedbackCreateRequest(
            "The quantity field overlaps the table footer",
            "data:image/png;base64,iVBORw0KGgo=",
            "/app/dashboard?tab=stock",
            OffsetDateTime.parse("2026-06-30T12:00:00Z"),
            "Vitest Browser",
            new FeedbackViewport(1440, 900, new BigDecimal("2"))
        );

        var response = service.create(request);

        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo("NEW");
        assertThat(response.comment()).contains("quantity field");
        assertThat(response.route()).isEqualTo("/app/dashboard?tab=stock");

        var saved = repository.findByIdAndIsDeletedFalse(response.id()).orElseThrow();
        assertThat(saved.getScreenshotDataUrl()).startsWith("data:image/png;base64,");
        assertThat(saved.getViewportWidth()).isEqualTo(1440);
        assertThat(saved.getViewportHeight()).isEqualTo(900);
        assertThat(saved.getDevicePixelRatio()).isEqualByComparingTo("2");
    }

    @Test
    void listsFeedbacksForDisplayWithNewestFirstScreenshots() {
        var older = service.create(new FeedbackCreateRequest(
            "Old dashboard comment",
            "data:image/png;base64,OLD=",
            "/app/dashboard",
            OffsetDateTime.parse("2026-06-30T08:00:00Z"),
            "Chrome",
            new FeedbackViewport(1280, 720, BigDecimal.ONE)
        ));
        var newer = service.create(new FeedbackCreateRequest(
            "New stock comment",
            "data:image/png;base64,NEW=",
            "/app/stock-balances",
            OffsetDateTime.parse("2026-06-30T10:00:00Z"),
            "Chrome",
            new FeedbackViewport(1440, 900, new BigDecimal("2"))
        ));

        var page = service.search(UserFeedbackSearchRequest.empty(), PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "clientCreatedAt")));

        assertThat(page.getContent()).extracting("id").containsSubsequence(newer.id(), older.id());
        assertThat(page.getContent().getFirst().screenshotDataUrl()).isEqualTo("data:image/png;base64,NEW=");
        assertThat(page.getContent().getFirst().comment()).contains("New stock");
    }
}
