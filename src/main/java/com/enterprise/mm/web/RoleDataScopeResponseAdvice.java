package com.enterprise.mm.web;

import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.RoleDataScopeService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 3)
@RequiredArgsConstructor
public class RoleDataScopeResponseAdvice implements ResponseBodyAdvice<Object> {
    private final RoleDataScopeService roleDataScopeService;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
        Object body,
        MethodParameter returnType,
        MediaType selectedContentType,
        Class<? extends HttpMessageConverter<?>> selectedConverterType,
        ServerHttpRequest request,
        ServerHttpResponse response
    ) {
        if (!(body instanceof ApiResponse<?> apiResponse) || apiResponse.data() == null) {
            return body;
        }
        Object scopedData = scope(apiResponse.data());
        if (scopedData == apiResponse.data()) {
            return body;
        }
        return new ApiResponse<>(scopedData, apiResponse.message(), apiResponse.errorCode(), apiResponse.timestamp());
    }

    private Object scope(Object data) {
        if (data instanceof PageResponse<?> page) {
            List<?> content = page.content() == null ? List.of() : page.content();
            List<?> filtered = content.stream()
                .filter(Objects::nonNull)
                .filter(roleDataScopeService::canAccessPayload)
                .toList();
            if (filtered.size() == content.size()) {
                return page;
            }
            int totalPages = page.size() == 0 ? 0 : (int) Math.ceil((double) filtered.size() / page.size());
            return new PageResponse<>(filtered, page.page(), page.size(), filtered.size(), totalPages);
        }
        if (!roleDataScopeService.canAccessPayload(data)) {
            throw new NotFoundException(
                "Record is outside your permitted data scope; check owner or department assignment",
                "error.resource.out_of_scope"
            );
        }
        return data;
    }
}
