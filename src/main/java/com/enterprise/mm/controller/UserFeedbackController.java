package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.FeedbackCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.UserFeedbackSearchRequest;
import com.enterprise.mm.payload.UserFeedbackResponse;
import com.enterprise.mm.service.UserFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class UserFeedbackController {
    private final UserFeedbackService service;

    @GetMapping
    public ApiResponse<PageResponse<UserFeedbackResponse>> search(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(PageResponse.from(service.search(UserFeedbackSearchRequest.empty(), pageable)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserFeedbackResponse> create(@Valid @RequestBody FeedbackCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }
}
