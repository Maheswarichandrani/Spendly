-- V1: baseline.
-- Intentionally creates no tables. Running it proves the migration pipeline works
-- and creates flyway_schema_history. Real tables start in V2 (users).
-- Rule: never edit a migration after it has been applied; add a new version instead.
SELECT 1;
