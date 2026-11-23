-- Incident 001: the database itself refuses negative stock.
-- The conditional UPDATE in InventoryService is the real fix; this is the safety net if any code path
-- (a bug, a manual SQL fix, a future feature) tries to go below zero.
ALTER TABLE inventory
    ADD CONSTRAINT ck_inventory_available CHECK (available >= 0);
