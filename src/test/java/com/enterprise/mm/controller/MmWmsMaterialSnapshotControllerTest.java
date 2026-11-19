package com.enterprise.mm.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class MmWmsMaterialSnapshotControllerTest {

    @Test
    void exposesOnlyTheProtectedWmsSnapshotOperation() throws Exception {
        RequestMapping requestMapping = MmWmsMaterialSnapshotController.class.getAnnotation(RequestMapping.class);
        Method queue = MmWmsMaterialSnapshotController.class.getMethod("queue", Integer.class);

        assertThat(requestMapping.value()).containsExactly("/api/integrations/wms/material-snapshots");
        assertThat(queue.getAnnotation(PostMapping.class)).isNotNull();
        assertThat(queue.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyRole('ADMIN','MM_MANAGER','INTEGRATION_OPERATOR')");
    }
}
