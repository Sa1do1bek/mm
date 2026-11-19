package com.enterprise.mm.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class MmWmsMaterialBaselineControllerTest {

    @Test
    void exposesOnlyTheProtectedWmsBaselineOperation() throws Exception {
        RequestMapping requestMapping = MmWmsMaterialBaselineController.class.getAnnotation(RequestMapping.class);
        Method queue = MmWmsMaterialBaselineController.class.getMethod("queue");

        assertThat(requestMapping.value()).containsExactly("/api/integrations/wms/material-baseline");
        assertThat(queue.getAnnotation(PostMapping.class)).isNotNull();
        assertThat(queue.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyRole('ADMIN','MM_MANAGER','INTEGRATION_OPERATOR')");
    }
}
