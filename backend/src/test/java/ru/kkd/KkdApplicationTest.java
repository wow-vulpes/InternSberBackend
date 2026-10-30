package ru.kkd;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.kkd.support.PostgresTestSupport;

@SpringBootTest
@Testcontainers
class KkdApplicationTest {

    @Container static final PostgreSQLContainer<?> POSTGRES = PostgresTestSupport.newContainer();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void contextLoads(@Autowired JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kkd.kkd_rule", Integer.class))
                .isEqualTo(4);
    }
}
