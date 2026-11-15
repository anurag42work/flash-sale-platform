package com.flashsale.order.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** catalog.* settings: where catalog-service is and how long to wait for it. */
@ConfigurationProperties("catalog")
public record CatalogProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
