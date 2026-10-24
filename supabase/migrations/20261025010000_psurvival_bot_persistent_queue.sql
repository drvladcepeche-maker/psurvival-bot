-- Persistent storage for the Puzzles & Survival Telegram bot.
-- All tables are private to backend/service-role code; no client-facing RLS policies.
create table if not exists public.psurvival_bot_profiles (
  telegram_user_id bigint primary key,
  chat_id bigint not null,
  settings jsonb not null default '{}'::jsonb check (jsonb_typeof(settings) = 'object'),
  automation_enabled boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);

create table if not exists public.psurvival_bot_devices (
  worker_id text primary key check (length(worker_id) between 1 and 128),
  token_hash text not null unique check (token_hash ~ '^[0-9a-f]{64}$'),
  label text not null default 'Android worker',
  enabled boolean not null default true,
  capabilities jsonb not null default '{}'::jsonb check (jsonb_typeof(capabilities) = 'object'),
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.psurvival_bot_jobs (
  id uuid primary key default gen_random_uuid(),
  telegram_user_id bigint not null references public.psurvival_bot_profiles(telegram_user_id) on delete cascade,
  chat_id bigint not null,
  task_type text not null check (task_type ~ '^[a-z][a-z0-9_]{0,63}$'),
  payload jsonb not null default '{}'::jsonb check (jsonb_typeof(payload) = 'object'),
  status text not null default 'queued' check (status in ('queued','running','succeeded','failed','cancelled')),
  priority smallint not null default 0 check (priority between -100 and 100),
  scheduled_at timestamptz not null default now(),
  started_at timestamptz,
  finished_at timestamptz,
  worker_id text references public.psurvival_bot_devices(worker_id) on delete set null,
  lease_expires_at timestamptz,
  attempts integer not null default 0 check (attempts >= 0),
  max_attempts integer not null default 3 check (max_attempts between 1 and 10),
  idempotency_key text,
  result jsonb check (result is null or jsonb_typeof(result) = 'object'),
  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint psurvival_bot_jobs_idempotency unique (telegram_user_id, idempotency_key)
);

create index if not exists psurvival_bot_jobs_ready_idx
  on public.psurvival_bot_jobs (status, scheduled_at, priority desc, created_at)
  where status = 'queued';
create index if not exists psurvival_bot_jobs_lease_idx
  on public.psurvival_bot_jobs (lease_expires_at)
  where status = 'running';
create index if not exists psurvival_bot_jobs_user_idx
  on public.psurvival_bot_jobs (telegram_user_id, created_at desc);

create table if not exists public.psurvival_bot_job_events (
  id bigint generated always as identity primary key,
  job_id uuid not null references public.psurvival_bot_jobs(id) on delete cascade,
  event_type text not null check (event_type in ('queued','claimed','heartbeat','progress','succeeded','failed','cancelled','lease_expired','retry_scheduled')),
  worker_id text,
  details jsonb not null default '{}'::jsonb check (jsonb_typeof(details) = 'object'),
  created_at timestamptz not null default now()
);
create index if not exists psurvival_bot_job_events_job_idx
  on public.psurvival_bot_job_events (job_id, created_at);

alter table public.psurvival_bot_profiles enable row level security;
alter table public.psurvival_bot_devices enable row level security;
alter table public.psurvival_bot_jobs enable row level security;
alter table public.psurvival_bot_job_events enable row level security;

revoke all on public.psurvival_bot_profiles, public.psurvival_bot_devices,
  public.psurvival_bot_jobs, public.psurvival_bot_job_events from anon, authenticated;
grant all on public.psurvival_bot_profiles, public.psurvival_bot_devices,
  public.psurvival_bot_jobs, public.psurvival_bot_job_events to service_role;
grant usage, select on sequence public.psurvival_bot_job_events_id_seq to service_role;

create or replace function public.psurvival_bot_claim_next_job(p_worker_id text, p_lease_seconds integer default 90)
returns setof public.psurvival_bot_jobs
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if p_worker_id is null or length(p_worker_id) < 1 or length(p_worker_id) > 128 then
    raise exception 'invalid worker id' using errcode = '22023';
  end if;
  if p_lease_seconds is null or p_lease_seconds < 15 or p_lease_seconds > 600 then
    raise exception 'lease must be between 15 and 600 seconds' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.psurvival_bot_devices d
    where d.worker_id = p_worker_id and d.enabled = true
  ) then
    raise exception 'worker is not enabled' using errcode = '42501';
  end if;

  return query
  with candidate as (
    select j.id
    from public.psurvival_bot_jobs j
    where j.status = 'queued'
      and j.scheduled_at <= now()
      and j.attempts < j.max_attempts
    order by j.priority desc, j.scheduled_at asc, j.created_at asc
    for update skip locked
    limit 1
  )
  update public.psurvival_bot_jobs j
  set status = 'running',
      worker_id = p_worker_id,
      started_at = coalesce(j.started_at, now()),
      lease_expires_at = now() + make_interval(secs => p_lease_seconds),
      attempts = j.attempts + 1,
      updated_at = now()
  from candidate c
  where j.id = c.id
  returning j.*;

  update public.psurvival_bot_devices
    set last_seen_at = now(), updated_at = now()
    where worker_id = p_worker_id;

  -- Event insertion is done separately by the worker API after claim.
end;
$$;

revoke all on function public.psurvival_bot_claim_next_job(text, integer) from public, anon, authenticated;
grant execute on function public.psurvival_bot_claim_next_job(text, integer) to service_role;

comment on table public.psurvival_bot_profiles is 'Private persistent Telegram user settings for the Puzzles & Survival bot.';
comment on table public.psurvival_bot_devices is 'Registered executor workers; token_hash stores SHA-256 hex, never a raw worker token.';
comment on table public.psurvival_bot_jobs is 'Durable task queue with retries, scheduling, and expiring worker leases.';
comment on table public.psurvival_bot_job_events is 'Append-only lifecycle log for executor tasks.';
