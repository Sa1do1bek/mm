package com.enterprise.mm.security.pilot;

import java.util.List;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PilotPersistenceConfiguration {
    @Bean
    HibernatePropertiesCustomizer pilotMutationListeners(PilotCapabilityPolicy policy) {
        return properties -> properties.put("hibernate.integrator_provider", (IntegratorProvider) () -> List.of(new Integrator() {
            @Override public void integrate(Metadata metadata, BootstrapContext context, SessionFactoryImplementor factory) {
                var registry = factory.getServiceRegistry().getService(EventListenerRegistry.class);
                var guard = new PilotPersistenceGuard(policy);
                registry.appendListeners(EventType.PRE_INSERT, guard);
                registry.appendListeners(EventType.PRE_UPDATE, guard);
                registry.appendListeners(EventType.PRE_DELETE, guard);
            }
            @Override public void disintegrate(SessionFactoryImplementor factory, SessionFactoryServiceRegistry registry) { }
        }));
    }
}
