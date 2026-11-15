CREATE TABLE products (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku         varchar(64)   NOT NULL,
    name        varchar(200)  NOT NULL,
    description text          NULL,
    price       numeric(12,2) NOT NULL,
    active      boolean       NOT NULL DEFAULT true,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_price CHECK (price > 0)
);

-- 1:1 with products. Kept separate so the hot, frequently updated stock row
-- does not contend with product reads (and later, the product cache).
-- CHECK (available >= 0) is added in V2 as part of the incident 001 fix.
CREATE TABLE inventory (
    product_id bigint      PRIMARY KEY REFERENCES products (id) ON DELETE RESTRICT,
    available  int         NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
