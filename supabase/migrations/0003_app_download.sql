-- Invite links send friends without the app to the APK in this bucket.
-- Public read needs no policy, and uploads stay limited to the project owner.
insert into storage.buckets (id, name, public) values ('app', 'app', true) on conflict (id) do nothing;
