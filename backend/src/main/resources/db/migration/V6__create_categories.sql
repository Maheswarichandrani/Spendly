-- V6: categories. What money was spent on (Food, Rent...) or came from (Salary...).
-- Every user gets a default set at registration (DefaultCategories.java) and can add their own.

CREATE TABLE categories (
    id           UUID        PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    -- INCOME or EXPENSE. A transaction's kind must match its category's kind.
    kind         TEXT        NOT NULL,
    icon         TEXT        NOT NULL,
    color        TEXT        NOT NULL,
    archived_at  TIMESTAMPTZ,
    sort_order   INTEGER     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT categories_name_length  CHECK (length(name) BETWEEN 1 AND 50),
    CONSTRAINT categories_kind_valid   CHECK (kind IN ('INCOME', 'EXPENSE')),
    CONSTRAINT categories_icon_format  CHECK (icon ~ '^[a-z0-9-]{1,50}$'),
    CONSTRAINT categories_color_format CHECK (color ~ '^#[0-9A-F]{6}$')
);

-- One active category per name per kind per user, ignoring case. "Gifts" may exist once
-- as INCOME and once as EXPENSE. Archived names are free again (partial index, like accounts).
CREATE UNIQUE INDEX categories_user_kind_name_active_key
    ON categories (user_id, kind, lower(name)) WHERE archived_at IS NULL;

-- The category picker: WHERE user_id = ? AND kind = ? ORDER BY sort_order.
CREATE INDEX categories_user_kind_sort_idx ON categories (user_id, kind, sort_order);

-- Backfill: users who registered before this step get the same defaults new users get.
-- MUST match DefaultCategories.java. New users are seeded by Java, not by this SQL.
-- gen_random_uuid() (v4) is fine here: these few rows don't need time-ordered ids.
INSERT INTO categories (id, user_id, name, kind, icon, color, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), u.id, d.name, d.kind, d.icon, d.color, d.sort_order, now(), now()
FROM users u
CROSS JOIN (VALUES
    ('Food & Dining',     'EXPENSE', 'fork-knife',        '#F97316', 0),
    ('Groceries',         'EXPENSE', 'shopping-cart',     '#84CC16', 1),
    ('Transport',         'EXPENSE', 'bus',               '#0EA5E9', 2),
    ('Fuel',              'EXPENSE', 'gas-pump',          '#64748B', 3),
    ('Shopping',          'EXPENSE', 'shopping-bag',      '#EC4899', 4),
    ('Bills & Utilities', 'EXPENSE', 'receipt',           '#EAB308', 5),
    ('Rent',              'EXPENSE', 'house',             '#8B5CF6', 6),
    ('Health',            'EXPENSE', 'heartbeat',         '#EF4444', 7),
    ('Entertainment',     'EXPENSE', 'film-slate',        '#A855F7', 8),
    ('Education',         'EXPENSE', 'graduation-cap',    '#3B82F6', 9),
    ('Travel',            'EXPENSE', 'airplane-tilt',     '#14B8A6', 10),
    ('Gifts & Donations', 'EXPENSE', 'gift',              '#F43F5E', 11),
    ('Other',             'EXPENSE', 'dots-three-circle', '#6B7280', 12),
    ('Salary',            'INCOME',  'briefcase',         '#16A34A', 0),
    ('Business',          'INCOME',  'storefront',        '#0D9488', 1),
    ('Interest',          'INCOME',  'percent',           '#2563EB', 2),
    ('Gifts',             'INCOME',  'gift',              '#DB2777', 3),
    ('Refunds',           'INCOME',  'arrow-u-up-left',   '#7C3AED', 4),
    ('Other',             'INCOME',  'dots-three-circle', '#6B7280', 5)
) AS d (name, kind, icon, color, sort_order);
