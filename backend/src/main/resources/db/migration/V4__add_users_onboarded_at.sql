-- V4: remember when a user finished onboarding.
-- NULL = not finished yet: the app shows the onboarding screens after login.
-- A timestamp (not a boolean) costs nothing extra and tells us *when*, which helps support.
-- Nullable with no default, so adding it is instant and existing rows stay valid (NULL).
ALTER TABLE users ADD COLUMN onboarded_at TIMESTAMPTZ;
