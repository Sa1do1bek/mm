package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.UserFeedback;
import com.enterprise.mm.payload.FeedbackCreateRequest;
import com.enterprise.mm.payload.UserFeedbackSearchRequest;
import com.enterprise.mm.payload.UserFeedbackResponse;
import com.enterprise.mm.repository.UserFeedbackRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserFeedbackService {
    private final UserFeedbackRepository repository;
    private final AuditService auditService;

    @Transactional
    public UserFeedbackResponse create(FeedbackCreateRequest request) {
        UserFeedback feedback = new UserFeedback();
        String code = "FEEDBACK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        feedback.setCode(code);
        feedback.setName(nameFor(request));
        feedback.setStatus("NEW");
        feedback.setDescription(request.comment());
        feedback.setCommentText(request.comment());
        feedback.setScreenshotDataUrl(request.screenshotDataUrl());
        feedback.setRoute(request.route());
        feedback.setClientCreatedAt(request.createdAt());
        feedback.setUserAgent(request.userAgent());
        feedback.setViewportWidth(request.viewport().width());
        feedback.setViewportHeight(request.viewport().height());
        feedback.setDevicePixelRatio(request.viewport().devicePixelRatio());

        UserFeedback saved = repository.save(feedback);
        auditService.log("UserFeedback", saved.getId(), "CREATE", saved.getCode(), null, null);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserFeedbackResponse> search(UserFeedbackSearchRequest request, Pageable pageable) {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            pageable
        ).map(this::toResponse);
    }

    private String nameFor(FeedbackCreateRequest request) {
        String route = request.route();
        if (route != null && !route.isBlank()) {
            return "Feedback " + route.substring(0, Math.min(route.length(), 220));
        }
        return "User Feedback";
    }

    private UserFeedbackResponse toResponse(UserFeedback feedback) {
        return new UserFeedbackResponse(
            feedback.getId(),
            feedback.getCode(),
            feedback.getStatus(),
            feedback.getCommentText(),
            feedback.getRoute(),
            feedback.getClientCreatedAt(),
            feedback.getViewportWidth(),
            feedback.getViewportHeight(),
            feedback.getDevicePixelRatio(),
            feedback.getScreenshotDataUrl(),
            feedback.getUserAgent(),
            feedback.getCreatedAt()
        );
    }
}
