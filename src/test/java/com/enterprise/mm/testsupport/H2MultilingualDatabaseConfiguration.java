package com.enterprise.mm.testsupport;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("test")
public class H2MultilingualDatabaseConfiguration {
    @Bean
    ApplicationRunner registerH2MultilingualNameFunction(DataSource dataSource) {
        return args -> {
            try (var connection = dataSource.getConnection()) {
                if (!connection.getMetaData().getURL().startsWith("jdbc:h2:")) {
                    return;
                }
            }
            new JdbcTemplate(dataSource).execute(
                "CREATE ALIAS IF NOT EXISTS multilingual_name_value FOR \""
                    + H2MultilingualNameValue.class.getName()
                    + ".resolve\""
            );
        };
    }
}
