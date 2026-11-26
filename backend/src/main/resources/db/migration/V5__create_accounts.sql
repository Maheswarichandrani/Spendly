-- V5: accounts. Where money lives: cash, bank, cards, wallets...
--
-- Money columns are NUMERIC(19,4): exact decimals (never float), up to 15 digits before
-- the point and 4 after. 4 decimals covers every real currency (most use 2, some 3).
--
-- current_balance is a cache of "opening balance + every movement". From step 011 it is
-- changed only by an atomic UPDATE ... SET current_balance = current_balance + ?, in the
-- same DB transaction as the movement, so it can always be rebuilt from history.
--
-- Credit cards are liabilities. Their balance is negative when money is owed:
-- -5000.0000 means "I owe 5000". Spending makes it more negative; paying the bill is a
-- transfer into the card, which brings it back towards 0.

CREATE TABLE accounts (
    id                UUID          PRIMARY KEY,
    user_id           UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name              TEXT          NOT NULL,
    type              TEXT          NOT NULL,
    -- Phosphor icon name, e.g. "bank", "credit-card". The app maps it to the icon.
    icon              TEXT          NOT NULL,
    -- "#RRGGBB", stored uppercase.
    color             TEXT          NOT NULL,
    opening_balance   NUMERIC(19,4) NOT NULL DEFAULT 0,
    current_balance   NUMERIC(19,4) NOT NULL DEFAULT 0,
    include_in_total  BOOLEAN       NOT NULL DEFAULT true,
    -- NULL = active. Archived accounts are hidden and left out of totals, but keep history.
    archived_at       TIMESTAMPTZ,
    sort_order        INTEGER       NOT NULL,
    -- Credit cards only (see the CHECK below).
    credit_limit      NUMERIC(19,4),
    statement_day     INTEGER,
    due_day           INTEGER,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,

    CONSTRAINT accounts_name_length        CHECK (length(name) BETWEEN 1 AND 50),
    CONSTRAINT accounts_type_valid         CHECK (type IN ('CASH', 'BANK', 'SAVINGS', 'DEBIT_CARD', 'CREDIT_CARD',
                                                           'WALLET', 'INVESTMENT', 'OTHER')),
    CONSTRAINT accounts_icon_format        CHECK (icon ~ '^[a-z0-9-]{1,50}$'),
    CONSTRAINT accounts_color_format       CHECK (color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT accounts_credit_fields_only CHECK (type = 'CREDIT_CARD'
                                                  OR (credit_limit IS NULL AND statement_day IS NULL AND due_day IS NULL)),
    CONSTRAINT accounts_credit_limit_min   CHECK (credit_limit >= 0),
    CONSTRAINT accounts_statement_day      CHECK (statement_day BETWEEN 1 AND 31),
    CONSTRAINT accounts_due_day            CHECK (due_day BETWEEN 1 AND 31)
);

-- One active account per name per user, ignoring case ("HDFC" and "hdfc" clash).
-- Partial (WHERE archived_at IS NULL): an archived "Old Wallet" doesn't block a new one.
-- Its leading user_id also serves the foreign key and "all accounts of this user".
CREATE UNIQUE INDEX accounts_user_name_active_key ON accounts (user_id, lower(name)) WHERE archived_at IS NULL;

-- The account list screen: WHERE user_id = ? ORDER BY sort_order.
CREATE INDEX accounts_user_sort_idx ON accounts (user_id, sort_order);
