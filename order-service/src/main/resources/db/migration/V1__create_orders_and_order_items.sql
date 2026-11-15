-- user_id has no FK: users live in auth_db (one database per service).
-- Deliberately no index on (user_id, created_at) yet: incident 007 measures and adds it.
CREATE TABLE orders (
    id             uuid          PRIMARY KEY,
    user_id        uuid          NOT NULL,
    status         varchar(16)   NOT NULL,
    total_amount   numeric(12,2) NOT NULL,
    failure_reason varchar(64)   NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0)
);

-- product_id has no FK: products live in catalog_db.
-- unit_price is a snapshot of the price at order time.
-- The unique (order_id, product_id) index also serves lookups by order_id.
CREATE TABLE order_items (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   uuid          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id bigint        NOT NULL,
    quantity   int           NOT NULL,
    unit_price numeric(12,2) NOT NULL,
    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0)
);
