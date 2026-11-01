-- Isolated Sherlock username-search queue. Results are restricted to the requesting Telegram user.
create table if not exists public.sherlock_jobs (
  id uuid primary key default gen_random_uuid(),
  telegram_user_id bigint not null references public.psurvival_bot_profiles(telegram_user_id) on delete cascade,
  chat_id bigint not null,
  username text not null check (username ~ '^[A-Za-z0-9_.-]{1,64}
  status text not null default 'queued' check (status in ('queued','running','succeeded','failed','cancelled')),
  total_sites integer not null default 0 check (total_sites >= 0),
  checked_sites integer not null default 0 check (checked_sites >= 0),
  found_count integer not null default 0 check (found_count >= 0),
  results jsonb not null default '[]'::jsonb check (jsonb_typeof(results) = 'array'),
  error_message text,
  worker_id text,
  attempts integer not null default 0,
  created_at timestamptz not null default now(),
  started_at timestamptz,
  finished_at timestamptz,
  updated_at timestamptz not null default now(),
  constraint sherlock_jobs_request_unique unique (telegram_user_id, request_key)
);
create index if not exists sherlock_jobs_user_created_idx on public.sherlock_jobs (telegram_user_id, created_at desc);
create index if not exists sherlock_jobs_queue_idx on public.sherlock_jobs (created_at) where status = 'queued';
alter table public.sherlock_jobs enable row level security;
revoke all on public.sherlock_jobs from anon, authenticated;
grant all on public.sherlock_jobs to service_role;

create or replace function public.sherlock_claim_next_job(p_worker_id text)
returns setof public.sherlock_jobs
language plpgsql security invoker
set search_path = pg_catalog, public
as $$
begin
  if p_worker_id is null or length(p_worker_id) < 1 or length(p_worker_id) > 128 then
    raise exception 'invalid worker id' using errcode = '22023';
  end if;
  return query
  with candidate as (
    select id from public.sherlock_jobs
    where status = 'queued'
    order by created_at asc
    for update skip locked
    limit 1
  )
  update public.sherlock_jobs j
  set status = 'running', worker_id = p_worker_id,
      attempts = attempts + 1, started_at = now(), updated_at = now()
  from candidate c where j.id = c.id
  returning j.*;
end;
$$;
revoke all on function public.sherlock_claim_next_job(text) from public, anon, authenticated;
grant execute on function public.sherlock_claim_next_job(text) to service_role;
),
  request_key text not null,
  status text not null default 'queued' check (status in ('queued','running','succeeded','failed','cancelled')),
  total_sites integer not null default 0 check (total_sites >= 0),
  checked_sites integer not null default 0 check (checked_sites >= 0),
  found_count integer not null default 0 check (found_count >= 0),
  results jsonb not null default '[]'::jsonb check (jsonb_typeof(results) = 'array'),
  error_message text,
  worker_id text,
  attempts integer not null default 0,
  created_at timestamptz not null default now(),
  started_at timestamptz,
  finished_at timestamptz,
  updated_at timestamptz not null default now()
);
create index if not exists sherlock_jobs_user_created_idx on public.sherlock_jobs (telegram_user_id, created_at desc);
create index if not exists sherlock_jobs_queue_idx on public.sherlock_jobs (created_at) where status = 'queued';
alter table public.sherlock_jobs enable row level security;
revoke all on public.sherlock_jobs from anon, authenticated;
grant all on public.sherlock_jobs to service_role;

create or replace function public.sherlock_claim_next_job(p_worker_id text)
returns setof public.sherlock_jobs
language plpgsql security definer
set search_path = pg_catalog, public
as $$
begin
  if p_worker_id is null or length(p_worker_id) < 1 or length(p_worker_id) > 128 then
    raise exception 'invalid worker id' using errcode = '22023';
  end if;
  return query
  with candidate as (
    select id from public.sherlock_jobs
    where status = 'queued'
    order by created_at asc
    for update skip locked
    limit 1
  )
  update public.sherlock_jobs j
  set status = 'running', worker_id = p_worker_id,
      attempts = attempts + 1, started_at = now(), updated_at = now()
  from candidate c where j.id = c.id
  returning j.*;
end;
$$;
revoke all on function public.sherlock_claim_next_job(text) from public, anon, authenticated;
grant execute on function public.sherlock_claim_next_job(text) to service_role;
