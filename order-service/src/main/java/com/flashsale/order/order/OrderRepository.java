package com.flashsale.order.order;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    // Filters by owner in SQL, so another user's order is simply "not found".
    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    // No index on (user_id, created_at) yet: deliberately slow at scale (incident 007).
    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
