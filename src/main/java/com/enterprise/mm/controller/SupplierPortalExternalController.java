package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalLoginRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalLoginResponse;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalMe;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalTeamInviteRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalTeamMember;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationResponse;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalRfqItem;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalRfqSummary;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalRfqDetail;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalSubmissionRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionResponse;
import com.enterprise.mm.payload.SupplierMagicLinkDtos.MagicLinkExchangeRequest;
import com.enterprise.mm.payload.SupplierMagicLinkDtos.MagicLinkExchangeResponse;
import com.enterprise.mm.payload.SupplierMagicLinkDtos.MagicLinkRequest;
import com.enterprise.mm.payload.SupplierMagicLinkDtos.MagicLinkRequestResponse;
import com.enterprise.mm.payload.RfqProposalDtos.PortalDeclineRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalDeclineResponse;
import com.enterprise.mm.payload.RfqProposalDtos.PortalProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalReplacementRequest;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalAttachmentView;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalView;
import com.enterprise.mm.service.SupplierMagicLinkService;
import com.enterprise.mm.service.SupplierPortalExternalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import com.enterprise.mm.service.AttachmentService;

@RestController
@RequestMapping("/api/public/supplier-portal")
@RequiredArgsConstructor
public class SupplierPortalExternalController {
    private static final String TOKEN_HEADER = "X-Supplier-Portal-Token";
    private final SupplierPortalExternalService service;
    private final SupplierMagicLinkService magicLinkService;

    @PostMapping("/magic-links/exchange")
    public ApiResponse<MagicLinkExchangeResponse> exchange(
        @Valid @RequestBody MagicLinkExchangeRequest request
    ) {
        return ApiResponse.ok(magicLinkService.exchange(request.token()));
    }

    @PostMapping("/magic-links/request")
    public ApiResponse<MagicLinkRequestResponse> request(
        @Valid @RequestBody MagicLinkRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(magicLinkService.requestLink(
            request.companyCode(), request.email(), servletRequest.getRemoteAddr()
        ));
    }

    @PostMapping("/auth/login")
    @Deprecated(forRemoval = false)
    public ApiResponse<SupplierPortalLoginResponse> login(@Valid @RequestBody SupplierPortalLoginRequest request) {
        return ApiResponse.ok(service.login(request));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(@RequestHeader(TOKEN_HEADER) String token) {
        service.logout(token);
        return ApiResponse.message("logged out");
    }

    @GetMapping("/me")
    public ApiResponse<SupplierPortalMe> me(@RequestHeader(TOKEN_HEADER) String token) {
        return ApiResponse.ok(service.me(token));
    }

    @PostMapping("/terms/accept")
    public ApiResponse<SupplierPortalMe> acceptTerms(@RequestHeader(TOKEN_HEADER) String token) {
        return ApiResponse.ok(service.acceptTerms(token));
    }

    @GetMapping("/team")
    public ApiResponse<List<SupplierPortalTeamMember>> team(@RequestHeader(TOKEN_HEADER) String token) {
        return ApiResponse.ok(service.listTeam(token));
    }

    @PostMapping("/team")
    public ApiResponse<SupplierPortalTeamMember> inviteTeamMember(
        @RequestHeader(TOKEN_HEADER) String token,
        @Valid @RequestBody SupplierPortalTeamInviteRequest request
    ) {
        return ApiResponse.created(service.inviteTeamMember(token, request));
    }

    @GetMapping("/rfq")
    public ApiResponse<PageResponse<SupplierPortalRfqSummary>> rfqs(@RequestHeader(TOKEN_HEADER) String token) {
        return ApiResponse.ok(PageResponse.from(service.listRfqs(token)));
    }

    @GetMapping("/rfq/{rfqId}")
    public ApiResponse<SupplierPortalRfqDetail> rfqDetail(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId
    ) {
        return ApiResponse.ok(service.rfqDetail(token, rfqId));
    }

    @GetMapping("/rfq/{rfqId}/artifacts/{attachmentId}/download")
    public ResponseEntity<InputStreamResource> downloadRfqArtifact(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @PathVariable UUID attachmentId
    ) {
        AttachmentService.AttachmentDownload download = service.downloadRfqArtifact(
            token, rfqId, attachmentId
        );
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.metadata().getContentType()))
            .contentLength(download.metadata().getSizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(download.metadata().getOriginalFileName())
                .build()
                .toString())
            .body(download.resource());
    }

    @GetMapping("/rfq/{rfqId}/items")
    public ApiResponse<PageResponse<SupplierPortalRfqItem>> rfqItems(@RequestHeader(TOKEN_HEADER) String token, @PathVariable UUID rfqId) {
        return ApiResponse.ok(PageResponse.from(service.listRfqItems(token, rfqId)));
    }

    @PostMapping("/rfq/{rfqId}/quotations")
    public ApiResponse<SupplierPortalQuotationResponse> createQuotation(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @Valid @RequestBody SupplierPortalQuotationRequest request
    ) {
        return ApiResponse.created(service.createQuotation(token, rfqId, request));
    }

    @PostMapping("/rfq/{rfqId}/quotations/drafts")
    public ApiResponse<ProposalView> createProposalDraft(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @Valid @RequestBody PortalProposalRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.createProposalDraft(token, rfqId, request, idempotencyKey));
    }

    @PutMapping("/rfq/{rfqId}/quotations/drafts/{revisionId}")
    public ApiResponse<ProposalView> updateProposalDraft(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @PathVariable UUID revisionId,
        @Valid @RequestBody PortalProposalRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.updateProposalDraft(
            token, rfqId, revisionId, request, idempotencyKey
        ));
    }

    @PostMapping("/rfq/{rfqId}/quotations/{revisionId}/submit")
    public ApiResponse<ProposalView> submitProposal(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @PathVariable UUID revisionId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.submitProposal(token, rfqId, revisionId, idempotencyKey));
    }

    @PostMapping("/rfq/{rfqId}/quotations/{revisionId}/replace")
    public ApiResponse<ProposalView> replaceProposal(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @PathVariable UUID revisionId,
        @Valid @RequestBody PortalReplacementRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.replaceProposal(
            token, rfqId, revisionId, request, idempotencyKey
        ));
    }

    @PostMapping(
        value = "/rfq/{rfqId}/quotations/{revisionId}/attachments",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ApiResponse<ProposalAttachmentView> addProposalAttachment(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @PathVariable UUID revisionId,
        @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.created(service.addProposalAttachment(token, rfqId, revisionId, file));
    }

    @DeleteMapping("/rfq/{rfqId}/quotations/{revisionId}/attachments/{attachmentId}")
    public ApiResponse<Void> deleteProposalAttachment(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @PathVariable UUID revisionId,
        @PathVariable UUID attachmentId
    ) {
        service.deleteProposalAttachment(token, rfqId, revisionId, attachmentId);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/rfq/{rfqId}/decline")
    public ApiResponse<PortalDeclineResponse> decline(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID rfqId,
        @Valid @RequestBody PortalDeclineRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.decline(token, rfqId, request, idempotencyKey));
    }

    @GetMapping("/submissions")
    public ApiResponse<PageResponse<SupplierPortalSubmissionResponse>> submissions(@RequestHeader(TOKEN_HEADER) String token) {
        return ApiResponse.ok(PageResponse.from(service.listSubmissions(token)));
    }

    @PostMapping("/submissions")
    public ApiResponse<SupplierPortalSubmissionResponse> createSubmission(@RequestHeader(TOKEN_HEADER) String token, @Valid @RequestBody SupplierPortalSubmissionRequest request) {
        return ApiResponse.created(service.createSubmission(token, request));
    }

    @PostMapping("/submissions/{id}/submit")
    public ApiResponse<SupplierPortalSubmissionResponse> submitSubmission(@RequestHeader(TOKEN_HEADER) String token, @PathVariable UUID id) {
        return ApiResponse.ok(service.submitSubmission(token, id));
    }
}
