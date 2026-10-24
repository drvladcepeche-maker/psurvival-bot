alter table public.psurvival_bot_devices
  add column if not exists owner_telegram_user_id bigint
  references public.psurvival_bot_profiles(telegram_user_id) on delete cascade;

create index if not exists psurvival_bot_devices_owner_idx
  on public.psurvival_bot_devices (owner_telegram_user_id);

create or replace function public.psurvival_bot_claim_next_job(p_worker_id text, p_lease_seconds integer default 90)
returns setof public.psurvival_bot_jobs
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_owner_id bigint;
begin
  if p_worker_id is null or length(p_worker_id) < 1 or length(p_worker_id) > 128 then
    raise exception 'invalid worker id' using errcode = '22023';
  end if;
  if p_lease_seconds is null or p_lease_seconds < 15 or p_lease_seconds > 600 then
    raise exception 'lease must be between 15 and 600 seconds' using errcode = '22023';
  end if;

  select d.owner_telegram_user_id into v_owner_id
  from public.psurvival_bot_devices d
  where d.worker_id = p_worker_id and d.enabled = true;
  if v_owner_id is null then
    raise exception 'worker is not enabled or has no owner' using errcode = '42501';
  end if;

  return query
  with candidate as (
    select j.id
    from public.psurvival_bot_jobs j
    where j.telegram_user_id = v_owner_id
      and j.status = 'queued'
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
end;
$$;

revoke all on function public.psurvival_bot_claim_next_job(text, integer) from public, anon, authenticated;
grant execute on function public.psurvival_bot_claim_next_job(text, integer) to service_role;
