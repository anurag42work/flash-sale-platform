package com.flashsale.catalog.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local test data: 10,000 products with 100 units each. Only with the "dev" profile (run-local.sh turns it on),
 * so tests and production never get fake data. Runs only when the products table is empty.
 * generate_series builds all rows inside Postgres in one statement, far faster than 10,000 inserts from Java.
 */
@Component
@Profile("dev")
class DevDataSeeder implements ApplicationRunner {

    static final int PRODUCTS = 10_000;
    static final int STOCK_PER_PRODUCT = 100;

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final JdbcTemplate jdbc;

    DevDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long existing = jdbc.queryForObject("SELECT count(*) FROM products", Long.class);
        if (existing != null && existing > 0) {
            log.info("Dev seed skipped: {} products already exist", existing);
            return;
        }
        int products = jdbc.update("""
                INSERT INTO products (sku, name, description, price)
                SELECT 'SKU-' || lpad(g::text, 5, '0'),
                       'Product ' || g,
                       'Seeded test product number ' || g,
                       round((5 + (g % 500) * 1.37)::numeric, 2)
                FROM generate_series(1, ?) AS g
                """, PRODUCTS);
        jdbc.update("INSERT INTO inventory (product_id, available) SELECT id, ? FROM products", STOCK_PER_PRODUCT);
        log.info("Dev seed: inserted {} products with {} units each", products, STOCK_PER_PRODUCT);
    }
}
