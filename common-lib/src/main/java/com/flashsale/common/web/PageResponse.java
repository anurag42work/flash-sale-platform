package com.flashsale.common.web;

import java.util.List;

/**
 * One page of results in API responses. Our own record instead of serialising Spring Data's Page,
 * whose JSON shape is not a stable API (Spring warns about it).
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
