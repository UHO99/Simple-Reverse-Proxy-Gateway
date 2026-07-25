package com.test.bench.testserver.common.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;

@Configuration
public class FlywayConfig {

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.migrate();
        return flyway;
    }

    // entityManagerFactory가 flyway 빈보다 반드시 나중에 초기화되도록 강제
    @Bean
    public static BeanFactoryPostProcessor flywayJpaDependency() {
        return beanFactory -> {
            BeanDefinition bd = beanFactory.getBeanDefinition("entityManagerFactory");
            String[] existing = bd.getDependsOn();
            String[] updated = existing != null
                    ? Arrays.copyOf(existing, existing.length + 1)
                    : new String[1];
            updated[updated.length - 1] = "flyway";
            bd.setDependsOn(updated);
        };
    }
}
