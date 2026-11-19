package com.enterprise.mm.erp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

class ControllerComponentScanTest {
    @Test
    void existingAndErpControllersCanBeRegisteredTogether() {
        var registry = new DefaultListableBeanFactory();
        var scanner = new ClassPathBeanDefinitionScanner(registry, false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.scan("com.enterprise.mm.controller", "com.enterprise.mm.erp");
        assertThat(registry.containsBeanDefinition("warehouseIdentificationController")).isTrue();
        assertThat(registry.containsBeanDefinition("erpWarehouseIdentificationController")).isTrue();
    }
}
