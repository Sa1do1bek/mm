package com.enterprise.mm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.RoleDataScopeService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoleDataScopeResponseAdviceTest {

    @Test
    void pageResponseWithNullContentDoesNotThrowNullPointerException() {
        RoleDataScopeResponseAdvice advice = new RoleDataScopeResponseAdvice(mock(RoleDataScopeService.class));
        ApiResponse<PageResponse<Object>> body = ApiResponse.ok(new PageResponse<>(null, 0, 20, 0, 0));

        assertThatCode(() -> advice.beforeBodyWrite(body, null, null, null, null, null))
            .doesNotThrowAnyException();
    }

    @Test
    void pageResponseWithNullItemsSkipsThemBeforeDataScopeChecks() {
        RoleDataScopeService scopeService = mock(RoleDataScopeService.class);
        Object visible = new Object();
        when(scopeService.canAccessPayload(visible)).thenReturn(true);
        RoleDataScopeResponseAdvice advice = new RoleDataScopeResponseAdvice(scopeService);
        ApiResponse<PageResponse<Object>> body = ApiResponse.ok(new PageResponse<>(Arrays.asList(null, visible), 0, 20, 2, 1));

        Object result = advice.beforeBodyWrite(body, null, null, null, null, null);

        assertThat(result).isInstanceOf(ApiResponse.class);
        Object data = ((ApiResponse<?>) result).data();
        assertThat(data).isInstanceOf(PageResponse.class);
        assertThat(((PageResponse<?>) data).content()).singleElement().isSameAs(visible);
    }

    @Test
    void deniedSingleRecordNamesDataScopeMismatch() {
        RoleDataScopeService scopeService = mock(RoleDataScopeService.class);
        Object hidden = new Object();
        when(scopeService.canAccessPayload(hidden)).thenReturn(false);
        RoleDataScopeResponseAdvice advice = new RoleDataScopeResponseAdvice(scopeService);

        assertThatThrownBy(() -> advice.beforeBodyWrite(ApiResponse.ok(hidden), null, null, null, null, null))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("outside your permitted data scope");
    }
}
