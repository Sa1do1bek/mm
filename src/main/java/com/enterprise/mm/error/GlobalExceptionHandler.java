package com.enterprise.mm.error;

import com.fasterxml.jackson.databind.JsonMappingException.Reference;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.enterprise.mm.payload.ApiErrorResponse;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.MmAiServiceException;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessBlockedException;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern ENUM_PARSE_ERROR = Pattern.compile("No enum constant\\s+([\\w.$]+)\\.(.+)");
    private final MessageSource messageSource;

    public GlobalExceptionHandler() {
        this.messageSource = null;
    }

    @Autowired
    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(NotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(localizedMessage(ex, request), errorCode(ex)));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiResponse<Void>> badRequest(BadRequestException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(localizedMessage(ex, request), errorCode(ex)));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiResponse<Void>> missingRoute(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error(message(request, "error.resource.not_found", "Resource not found"), errorCode(ex)));
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ApiErrorResponse> insufficientStock(InsufficientStockException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiErrorResponse.insufficientStock(ex.details()));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<?>> business(BusinessException ex, HttpServletRequest request) {
        String message = localizedMessage(ex, request);
        String code = errorCode(ex);
        // UX-LIVE-01: when the exception carries structured context (e.g. purchaseOrderId,
        // supplierId), surface it in `data` so the client can keep the originating document
        // visible instead of losing it when the action fails.
        Map<String, Object> context = ex.getContext();
        if (context != null && !context.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(message, code, context));
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(message, code));
    }

    @ExceptionHandler(PasswordSecurityException.class)
    ResponseEntity<ApiResponse<Void>> passwordSecurity(PasswordSecurityException ex) {
        return ResponseEntity.status(ex.getStatus())
            .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(MmAiServiceException.class)
    ResponseEntity<ApiResponse<Map<String, Object>>> mmAiService(MmAiServiceException ex) {
        HttpStatus status = ex.httpStatus() == HttpStatus.UNAUTHORIZED.value()
            ? HttpStatus.BAD_GATEWAY
            : Optional.ofNullable(HttpStatus.resolve(ex.httpStatus())).orElse(HttpStatus.BAD_GATEWAY);
        Map<String, Object> context = ex.upstreamRequestId() == null || ex.upstreamRequestId().isBlank()
            ? Map.of()
            : Map.of("upstreamRequestId", ex.upstreamRequestId());
        return ResponseEntity.status(status)
            .body(ApiResponse.error(ex.getMessage(), ex.httpStatus() == HttpStatus.UNAUTHORIZED.value()
                ? "MM_AI_UPSTREAM_UNAUTHORIZED" : ex.errorCode(), context));
    }

    @ExceptionHandler(SupplierReadinessBlockedException.class)
    ResponseEntity<ApiResponse<ReadinessBlocker>> readinessBlocked(
        SupplierReadinessBlockedException ex
    ) {
        return ResponseEntity.unprocessableEntity().body(new ApiResponse<>(
            ex.blocker(),
            ex.getMessage(),
            ex.blocker().code(),
            java.time.LocalDateTime.now()
        ));
    }

    @ExceptionHandler(InventoryCountConflictException.class)
    ResponseEntity<ApiResponse<com.enterprise.mm.payload.InventoryCountBatchDtos.RowResult>> inventoryCountConflict(InventoryCountConflictException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(localizedMessage(ex, request), errorCode(ex), ex.result()));
    }

    @ExceptionHandler(TransferReceiptConflictException.class)
    ResponseEntity<ApiResponse<?>> receiptConflict(TransferReceiptConflictException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error(localizedMessage(ex, request), errorCode(ex), ex.getContext()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiResponse<Void>> conflict(ConflictException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(localizedMessage(ex, request), errorCode(ex)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error(accessDeniedMessage(ex, request), errorCode(ex)));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiResponse<Void>> authentication(AuthenticationException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(safeMessage(ex), errorCode(ex)));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse<Void>> optimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        String code = ex.getMessage() != null && ex.getMessage().contains("PROCUREMENT_ASSIGNMENT_CONFLICT")
            ? "PROCUREMENT_ASSIGNMENT_CONFLICT"
            : errorCode(ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error(message(request, "error.optimistic_lock", "Record was changed by another transaction"), code));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> dataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String message = ex.getMostSpecificCause() == null ? ex.getMessage() : ex.getMostSpecificCause().getMessage();
        String normalized = message == null ? "" : message.toLowerCase();
        if (normalized.contains("unique") || normalized.contains("duplicate") || normalized.contains("uk_")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(duplicateMessage(normalized, request), duplicateErrorCode(normalized, ex)));
        }
        if (normalized.contains("not null") || normalized.contains("null value") || normalized.contains("значение null")) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error(message(request, "error.data.required", "Required database field is missing"), errorCode(ex)));
        }
        if (normalized.contains("foreign key") || normalized.contains("violates foreign key") || normalized.contains("нарушает ограничение внешнего ключа")) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error(message(request, "error.data.reference", "Referenced record does not exist or is still in use"), errorCode(ex)));
        }
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(message(request, "error.data.invalid", "Invalid data violates database constraint"), errorCode(ex)));
    }

    private String duplicateMessage(String normalizedError, HttpServletRequest request) {
        String key = duplicateMessageKey(normalizedError);
        if (key == null) {
            return message(request, "error.data.duplicate", "Duplicate value violates unique constraint");
        }
        return message(request, key, duplicateFallback(key));
    }

    private String duplicateMessageKey(String normalizedError) {
        if (containsAny(normalizedError, "uk_materials_active_normalized_company_code")) {
            return "material.code.duplicate";
        }
        if (containsAny(normalizedError, "uk_rfq_pr_source_active_item")) {
            return "rfq.aggregation.already_allocated";
        }
        if (containsAny(normalizedError, "uk_contract_active_source_rfq", "uk_contract_active_source_quotation")) {
            return "rfq.award.contract_exists";
        }
        if (containsAny(normalizedError, "username", "uk_users_tenant_company_username", "uk_users_tenant_username")) {
            return "error.data.duplicate.username";
        }
        if (containsAny(normalizedError, "login_email", "email", "uq_supplier_portal_accounts_email")) {
            return "error.data.duplicate.email";
        }
        if (containsAny(normalizedError, "role_name", "uk_roles_tenant_company_role_name", "uk_roles_tenant_role_name")) {
            return "error.data.duplicate.role_name";
        }
        if (containsAny(normalizedError, "tax_id", "inn")) {
            return "error.data.duplicate.tax_id";
        }
        if (containsAny(normalizedError, "barcode")) {
            return "error.data.duplicate.barcode";
        }
        if (containsAny(normalizedError, "qr_code")) {
            return "error.data.duplicate.qr_code";
        }
        if (containsAny(normalizedError, "rfid_tag", "tag_value", "uk_rfid_tags_value")) {
            return "error.data.duplicate.rfid_tag";
        }
        if (containsAny(normalizedError, "bank_account", "bank_account_hash")) {
            return "error.data.duplicate.bank_account";
        }
        if (containsAny(normalizedError, "license_number")) {
            return "error.data.duplicate.license_number";
        }
        if (containsAny(normalizedError, "account_code")) {
            return "error.data.duplicate.account_code";
        }
        if (containsAny(normalizedError, "system_code")) {
            return "error.data.duplicate.system_code";
        }
        if (containsAny(normalizedError, "idempotency_key")) {
            return "error.data.duplicate.idempotency_key";
        }
        if (containsAny(normalizedError, "code", "_code", "tenant_code")) {
            return "error.data.duplicate.code";
        }
        if (containsAny(normalizedError, "name", "_name")) {
            return "error.data.duplicate.name";
        }
        if (containsAny(normalizedError, "number", "_number")) {
            return "error.data.duplicate.number";
        }
        return null;
    }

    private boolean containsAny(String value, String... tokens) {
        return Arrays.stream(tokens).anyMatch(value::contains);
    }

    private String duplicateFallback(String key) {
        return switch (key) {
            case "rfq.aggregation.already_allocated" -> "Purchase request line is already allocated to an RFQ";
            case "rfq.award.contract_exists" -> "RFQ already has a source contract";
            case "error.data.duplicate.username" -> "Username already exists";
            case "error.data.duplicate.email" -> "Email already exists";
            case "error.data.duplicate.role_name" -> "Role name already exists";
            case "error.data.duplicate.tax_id" -> "Tax ID already exists";
            case "error.data.duplicate.barcode" -> "Barcode already exists";
            case "error.data.duplicate.qr_code" -> "QR code already exists";
            case "error.data.duplicate.rfid_tag" -> "RFID tag already exists";
            case "error.data.duplicate.bank_account" -> "Bank account already exists";
            case "error.data.duplicate.license_number" -> "License number already exists";
            case "error.data.duplicate.account_code" -> "Account code already exists";
            case "error.data.duplicate.system_code" -> "System code already exists";
            case "error.data.duplicate.idempotency_key" -> "Idempotency key already exists";
            case "error.data.duplicate.code" -> "Code already exists";
            case "error.data.duplicate.name" -> "Name already exists";
            case "error.data.duplicate.number" -> "Number already exists";
            default -> "Duplicate value violates unique constraint";
        };
    }

    private String duplicateErrorCode(String normalizedError, Throwable throwable) {
        String key = duplicateMessageKey(normalizedError);
        return key != null && (key.startsWith("rfq.") || key.equals("material.code.duplicate"))
            ? key : errorCode(throwable);
    }

    @ExceptionHandler({PropertyReferenceException.class, InvalidDataAccessApiUsageException.class})
    ResponseEntity<ApiResponse<Void>> invalidQuery(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(resolveErrorMessage(ex, request), errorCode(ex)));
    }

    ResponseEntity<ApiResponse<Void>> invalidQuery(Exception ex) {
        return invalidQuery(ex, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> malformedRequest(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            resolveErrorMessage(ex, request, message(request, "error.request.malformed_json", "Malformed JSON request")),
            isSupplyWorkspaceRequest(request) ? "SUPPLY_REQUEST_INVALID" : errorCode(ex)
        ));
    }

    ResponseEntity<ApiResponse<Void>> malformedRequest(HttpMessageNotReadableException ex) {
        return malformedRequest(ex, null);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiResponse<Void>> validation(Exception ex, HttpServletRequest request) {
        String message = ex instanceof MethodArgumentNotValidException manve
            ? manve.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "))
            : ex.getMessage();
        return ResponseEntity.badRequest().body(ApiResponse.error(
            safeMessage(message),
            supplyBindingErrorCode(ex, request).orElseGet(() -> errorCode(ex))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse<Void>> missingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            message(request, "error.request.parameter.missing", "Required request parameter ''{0}'' is missing", ex.getParameterName()),
            errorCode(ex)
        ));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiResponse<Void>> missingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            message(request, "error.request.header.missing", "Required request header ''{0}'' is missing", ex.getHeaderName()),
            errorCode(ex)
        ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String expected = ex.getRequiredType() == null ? "valid value" : ex.getRequiredType().getSimpleName();
        return ResponseEntity.badRequest().body(ApiResponse.error(
            message(request, "error.request.parameter.invalid", "Invalid parameter ''{0}'': expected {1}", ex.getName(), expected),
            supplyParameterErrorCode(ex.getName(), request).orElseGet(() -> errorCode(ex))
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> illegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            resolveErrorMessage(ex, request),
            errorCode(ex)
        ));
    }

    // A NullPointerException here is always a programmer error (a missed null check
    // somewhere in our own code), never something the caller can fix by changing their
    // request. It must not be reported as a 400 "bad request" -- that tells the client
    // their input was wrong, which is false, and it must not leak the exception's class
    // name or raw message to the client, since NPE messages (JEP 358 "helpful NPE
    // messages") can describe internal field/method names. The full exception and stack
    // trace are preserved server-side via log.error below for debugging; the client only
    // ever sees a generic, localized, non-identifying message and a stable errorCode.
    @ExceptionHandler(NullPointerException.class)
    ResponseEntity<ApiResponse<Void>> nullPointer(NullPointerException ex, HttpServletRequest request) {
        log.error(
            "Unhandled null reference (programmer error) at {} {}",
            request == null ? "UNKNOWN" : request.getMethod(),
            request == null ? "UNKNOWN" : request.getRequestURI(),
            ex
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(
            message(request, "error.internal.unexpected", "An unexpected server error occurred. Please try again or contact support."),
            "INTERNAL_SERVER_ERROR"
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(
            message(request, "error.internal", "An unexpected error occurred"),
            "INTERNAL_SERVER_ERROR"
        ));
    }

    private String resolveErrorMessage(Throwable throwable, HttpServletRequest request) {
        return resolveErrorMessage(throwable, request, safeMessage(throwable));
    }

    private String resolveErrorMessage(Throwable throwable, HttpServletRequest request, String fallback) {
        return invalidFormatMessage(throwable, request)
            .or(() -> enumParseMessage(throwable, request))
            .orElse(fallback);
    }

    private Optional<String> invalidFormatMessage(Throwable throwable, HttpServletRequest request) {
        InvalidFormatException invalidFormat = findCause(throwable, InvalidFormatException.class);
        if (invalidFormat == null || invalidFormat.getTargetType() == null) {
            return Optional.empty();
        }
        String field = invalidFormat.getPath().stream()
            .map(Reference::getFieldName)
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.joining("."));
        String fieldName = field.isBlank() ? "value" : field;
        if (invalidFormat.getTargetType() == UUID.class) {
            return Optional.of(message(
                request,
                "error.uuid.invalid",
                "Invalid {0} ''{1}''. Expected a UUID or omit the field",
                fieldName,
                invalidFormat.getValue()
            ));
        }
        if (!invalidFormat.getTargetType().isEnum()) {
            return Optional.empty();
        }
        return Optional.of(enumMessage(
            fieldName,
            invalidFormat.getValue(),
            invalidFormat.getTargetType(),
            request
        ));
    }

    private Optional<String> enumParseMessage(Throwable throwable, HttpServletRequest request) {
        IllegalArgumentException cause = findCause(throwable, IllegalArgumentException.class);
        if (cause == null || cause.getMessage() == null) {
            return Optional.empty();
        }
        Matcher matcher = ENUM_PARSE_ERROR.matcher(cause.getMessage().trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(matcher.group(1));
            if (!type.isEnum()) {
                return Optional.empty();
            }
            return Optional.of(enumMessage("value", matcher.group(2), type, request));
        } catch (ClassNotFoundException ignored) {
            return Optional.empty();
        }
    }

    private String enumMessage(String field, Object value, Class<?> enumType, HttpServletRequest request) {
        String allowedValues = Arrays.stream(enumType.getEnumConstants())
            .map(constant -> ((Enum<?>) constant).name())
            .collect(Collectors.joining(", "));
        return message(
            request,
            "error.enum.invalid",
            "Invalid {0} ''{1}''. Allowed values: {2}",
            field,
            value,
            allowedValues
        );
    }

    private String message(HttpServletRequest request, String key, String fallback, Object... args) {
        if (messageSource == null) {
            return java.text.MessageFormat.format(fallback, args);
        }
        return messageSource.getMessage(key, args, fallback, resolveLocale(request));
    }

    private Locale resolveLocale(HttpServletRequest request) {
        String language = request == null ? null : request.getHeader("Accept-Language");
        if (language == null || language.isBlank()) {
            return Locale.ENGLISH;
        }
        String normalized = language.split(",", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ru")) {
            return Locale.forLanguageTag("ru");
        }
        if (normalized.startsWith("en")) {
            return Locale.ENGLISH;
        }
        return Locale.forLanguageTag("uz");
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "No exception message available";
        }
        return throwable.getMessage();
    }

    private String localizedMessage(Throwable throwable, HttpServletRequest request) {
        if (throwable instanceof LocalizedException localized && localized.getMessageKey() != null) {
            return message(request, localized.getMessageKey(), safeMessage(throwable), localized.getMessageArgs());
        }
        return safeMessage(throwable);
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "No exception message available";
        }
        return message;
    }

    private String errorCode(Throwable throwable) {
        if (throwable instanceof LocalizedException localized
            && localized.getMessageKey() != null) {
            String messageKey = localized.getMessageKey();
            if ("stockPicking.insufficient".equals(messageKey)) return "STOCK_PICKING_INSUFFICIENT";
            if ("stockPicking.unavailable".equals(messageKey)) return "STOCK_PICKING_UNAVAILABLE";
            if (messageKey.startsWith("transfers.receipt.")) return messageKey;
            Object[] args = localized.getMessageArgs();
            if (messageKey.startsWith("supply.") && args.length > 0
                && args[0] instanceof String code && code.matches("SUPPLY_[A-Z0-9_]+")) {
                return code;
            }
            if ("supply.workspace.commitment.notFound".equals(messageKey)) {
                return "SUPPLY_COMMITMENT_NOT_FOUND";
            }
            if ("supply.workspace.assignment.ownerNotFound".equals(messageKey)) {
                return "SUPPLY_ASSIGNMENT_OWNER_NOT_FOUND";
            }
            if (messageKey.startsWith("material360.")
                || messageKey.startsWith("MATERIAL_ATTRIBUTE_")
                || messageKey.startsWith("ACCREDITATION_")) {
                return messageKey;
            }
            if (messageKey.startsWith("rfq.")) {
                return messageKey;
            }
            if (messageKey.startsWith("goodsIncome.")) {
                return switch (messageKey) {
                    case "goodsIncome.po.notFound" -> "GOODS_INCOME_PO_NOT_FOUND";
                    case "goodsIncome.po.notGoods" -> "GOODS_INCOME_PO_NOT_GOODS";
                    case "goodsIncome.po.companyAccessDenied" -> "GOODS_INCOME_PO_ACCESS_DENIED";
                    case "goodsIncome.receipt.storageLocationRequired" -> "GOODS_INCOME_STORAGE_LOCATION_REQUIRED";
                    case "goodsIncome.receipt.warehouseRequired" -> "GOODS_INCOME_WAREHOUSE_REQUIRED";
                    case "goodsIncome.asn.required" -> "GOODS_INCOME_ASN_REQUIRED";
                    case "goodsIncome.asn.notFound" -> "GOODS_INCOME_ASN_NOT_FOUND";
                    case "goodsIncome.asn.mismatch" -> "GOODS_INCOME_ASN_MISMATCH";
                    case "goodsIncome.asn.notConfirmed" -> "GOODS_INCOME_ASN_NOT_CONFIRMED";
                    case "goodsIncome.accreditation.blocked" -> "GOODS_INCOME_ACCREDITATION_BLOCKED";
                    case "goodsIncome.accreditation.materialGroupRequired" -> "GOODS_INCOME_ACCREDITATION_MATERIAL_GROUP_REQUIRED";
                    case "goodsIncome.action.receiveNotAllowed" -> "GOODS_INCOME_RECEIVE_NOT_ALLOWED";
                    case "goodsIncome.action.receiveRoleDenied" -> "GOODS_INCOME_RECEIVE_ROLE_DENIED";
                    default -> messageKey.replace(".", "_").toUpperCase(Locale.ROOT);
                };
            }
            if (messageKey.startsWith("purchaseOrder.")) {
                return messageKey.replace(".", "_").toUpperCase(Locale.ROOT);
            }
            if (messageKey.startsWith("purchaseRequest.")) {
                return messageKey.replace(".", "_").toUpperCase(Locale.ROOT);
            }
            if (messageKey.startsWith("supplierGovernance.")) {
                return messageKey.replace(".", "_").toUpperCase(Locale.ROOT);
            }
            if (messageKey.startsWith("inventoryPeriodClose.")) {
                return messageKey.replace(".", "_").toUpperCase(Locale.ROOT);
            }
            if (messageKey.startsWith("dataQuality.")) {
                return messageKey.replace(".", "_").toUpperCase(Locale.ROOT);
            }
        }
        if (throwable instanceof AccessDeniedException
            && throwable.getMessage() != null
            && throwable.getMessage().startsWith("material360.")) {
            return throwable.getMessage();
        }
        return throwable.getClass().getSimpleName();
    }

    private Optional<String> supplyBindingErrorCode(Exception exception, HttpServletRequest request) {
        if (!isSupplyWorkspaceRequest(request) || !(exception instanceof MethodArgumentNotValidException invalid)) {
            return Optional.empty();
        }
        return invalid.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField())
            .map(field -> supplyParameterErrorCode(field, request).orElse("SUPPLY_FILTER_INVALID"))
            .findFirst();
    }

    private Optional<String> supplyParameterErrorCode(String parameter, HttpServletRequest request) {
        if (!isSupplyWorkspaceRequest(request)) {
            return Optional.empty();
        }
        if ("asOf".equals(parameter) || "from".equals(parameter) || "to".equals(parameter)) {
            return Optional.of("SUPPLY_DATE_INVALID");
        }
        if ("page".equals(parameter)) {
            return Optional.of("SUPPLY_PAGE_INVALID");
        }
        if ("size".equals(parameter)) {
            return Optional.of("SUPPLY_PAGE_SIZE_INVALID");
        }
        return Optional.of("SUPPLY_FILTER_INVALID");
    }

    private boolean isSupplyWorkspaceRequest(HttpServletRequest request) {
        return request != null
            && request.getRequestURI() != null
            && request.getRequestURI().startsWith("/api/workspaces/supply");
    }

    private String accessDeniedMessage(AccessDeniedException exception, HttpServletRequest request) {
        if (exception.getMessage() != null && exception.getMessage().startsWith("material360.")) {
            return message(request, exception.getMessage(), "Access denied");
        }
        return message(request, "error.access.denied", "Access denied");
    }

    @ExceptionHandler(InventoryLineMutationNotAllowedException.class)
    ResponseEntity<ApiResponse<Void>> inventoryLineMutationNotAllowed(
            InventoryLineMutationNotAllowedException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        localizedMessage(ex, request),
                        errorCode(ex)));
    }
}
