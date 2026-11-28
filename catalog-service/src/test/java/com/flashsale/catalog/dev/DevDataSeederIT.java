package com.flashsale.catalog.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.catalog.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listener running and would react to the
// next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest
@ActiveProfiles("dev")
class DevDataSeederIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DevDataSeeder seeder;

    @Test
    void seedsTenThousandProductsWithStockOnlyOnce() {
        // Start from empty tables (the database may be shared with other test classes when container reuse is on).
        jdbc.execute("TRUNCATE inventory, products RESTART IDENTITY CASCADE");

        seeder.run(null);
        seeder.run(null); // second run must not add anything

        assertThat(count("SELECT count(*) FROM products")).isEqualTo(10_000);
        assertThat(count("SELECT count(*) FROM inventory WHERE available = 100")).isEqualTo(10_000);
        assertThat(count("SELECT count(*) FROM products WHERE price <= 0")).isZero();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
