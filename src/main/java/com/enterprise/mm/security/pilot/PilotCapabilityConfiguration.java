package com.enterprise.mm.security.pilot;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;

@Configuration(proxyBeanMethods = false)
public class PilotCapabilityConfiguration {
    private static boolean treasuryCommand(Method method, Class<?> type) {
        return com.enterprise.mm.service.IntegrationSettingService.class.isAssignableFrom(type)
            && java.util.Set.of("enqueueAction", "cancelDocument", "acceptCallback").contains(method.getName());
    }

    private static PilotCapabilityBoundary boundary(Method method, Class<?> type) {
        PilotCapabilityBoundary direct = AnnotatedElementUtils.findMergedAnnotation(method, PilotCapabilityBoundary.class);
        if (direct != null) return direct;
        PilotCapabilityBoundary owner = AnnotatedElementUtils.findMergedAnnotation(type, PilotCapabilityBoundary.class);
        if (owner == null || Arrays.asList(owner.reads()).contains(method.getName())) return null;
        return owner;
    }

    @Bean
    public Advisor pilotCapabilityAdvisor(PilotCapabilityPolicy policy) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> type) {
                return method.getDeclaringClass() != Object.class && (boundary(method, type) != null || treasuryCommand(method, type));
            }
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, (MethodInterceptor) invocation -> {
            if (treasuryCommand(invocation.getMethod(), AopProxyUtils.ultimateTargetClass(invocation.getThis()))
                && invocation.getArguments().length > 0 && invocation.getArguments()[0] == com.enterprise.mm.enums.ExternalIntegrationSystem.TREASURY)
                policy.require(PilotCapability.MM_FINANCE_EXECUTION);
            var required = boundary(invocation.getMethod(), AopProxyUtils.ultimateTargetClass(invocation.getThis()));
            if (required != null) policy.require(required.value());
            return invocation.proceed();
        });
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return advisor;
    }
}
