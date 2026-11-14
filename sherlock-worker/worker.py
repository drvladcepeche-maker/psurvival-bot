import logging
import os
from datetime import datetime, timezone
from typing import Any

import requests
from sherlock_project.sherlock import sherlock
from sherlock_project.sites import SitesInformation
from sherlock_project.notify import QueryNotify
from sherlock_project.result import QueryStatus

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
log = logging.getLogger("sherlock-worker")
SUPABASE_URL = os.getenv("SUPABASE_URL", "https://xevqbgjtxuveeqaiacvm.supabase.co").rstrip("/")
GATEWAY_URL = f"{SUPABASE_URL}/functions/v1/sherlock-worker-gateway"
OIDC_AUDIENCE = GATEWAY_URL
WORKER_ID = "github-actions-sherlock"
REQUEST_TIMEOUT = max(5, int(os.getenv("SHERLOCK_TIMEOUT", "20")))
MAX_SITES = max(1, min(500, int(os.getenv("SHERLOCK_MAX_SITES", "500"))))
HTTP = requests.Session()


def github_oidc_token() -> str:
    url = os.environ.get("ACTIONS_ID_TOKEN_REQUEST_URL")
    secret = os.environ.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN")
    if not url or not secret:
        raise RuntimeError("GitHub Actions OIDC is unavailable; workflow needs id-token: write")
    response = HTTP.get(url, headers={"Authorization": f"Bearer {secret}"},
                        params={"audience": OIDC_AUDIENCE}, timeout=20)
    response.raise_for_status()
    token = response.json().get("value")
    if not token:
        raise RuntimeError("GitHub OIDC endpoint returned no token")
    return token


def gateway(payload: dict[str, Any]) -> Any:
    response = HTTP.post(
        GATEWAY_URL,
        headers={"Authorization": f"Bearer {github_oidc_token()}", "Content-Type": "application/json"},
        json=payload, timeout=30,
    )
    response.raise_for_status()
    data = response.json()
    if not data.get("ok"):
        raise RuntimeError(data.get("error", "Sherlock gateway request failed"))
    return data.get("data")


def patch_job(job_id: str, payload: dict[str, Any], notification: str | None = None) -> None:
    gateway({"action": "patch", "job_id": job_id, "worker_id": WORKER_ID,
             "payload": payload, "notification": notification})


class Collector(QueryNotify):
    def update(self, result) -> None:
        # Sherlock requires a notifier callback even when progress is not streamed.
        return None


def process(job: dict[str, Any]) -> None:
    job_id, username = job["id"], job["username"]
    try:
        sites = SitesInformation()
        sites.remove_nsfw_sites()
        site_data = {name: item.information for name, item in sites.sites.items()}
        if len(site_data) > MAX_SITES:
            site_data = dict(list(site_data.items())[:MAX_SITES])
        patch_job(job_id, {"total_sites": len(site_data),
                           "updated_at": datetime.now(timezone.utc).isoformat()})
        # Public Actions logs must not contain searched usernames, job IDs, or raw exception details.
        # Search terms and result URLs remain in the protected job record, not stdout/stderr.
        log.info("Starting Sherlock lookup; sites=%s", len(site_data))
        results = sherlock(username=username, site_data=site_data, query_notify=Collector(), timeout=REQUEST_TIMEOUT)
        normalized = []
        for site_name, site_result in results.items():
            result = site_result.get("status")
            if result is None:
                continue
            status = result.status.value if hasattr(result.status, "value") else str(result.status)
            normalized.append({
                "site": site_name, "url": site_result.get("url_user", ""),
                "status": status, "response_seconds": result.query_time, "context": result.context,
            })
        found = [x for x in normalized if x["status"] == QueryStatus.CLAIMED.value]
        compact = found + [x for x in normalized
                           if x["status"] in (QueryStatus.UNKNOWN.value, QueryStatus.WAF.value)]
        finished = datetime.now(timezone.utc).isoformat()
        lines = [
            "Sherlock: проверка завершена", f"Username: {username}",
            f"Проверено: {len(normalized)}", f"Найдено публичных профилей: {len(found)}",
            f"Неопределённых ответов / блокировок: {sum(1 for x in normalized if x['status'] in ('Unknown', 'WAF'))}",
        ]
        for item in found[:20]:
            lines.append(f"• {item['site']}: {item['url']}")
        if len(found) > 20:
            lines.append(f"И ещё {len(found) - 20}. Полный список доступен через /sherlock_history.")
        patch_job(job_id, {
            "status": "succeeded", "total_sites": len(site_data),
            "checked_sites": len(normalized), "found_count": len(found),
            "results": compact, "finished_at": finished, "updated_at": finished,
        }, "\n".join(lines))
        log.info("Finished Sherlock lookup; found=%s checked=%s", len(found), len(normalized))
    except Exception as exc:
        log.error("Sherlock job failed; exception_type=%s", type(exc).__name__)
        finished = datetime.now(timezone.utc).isoformat()
        patch_job(job_id, {
            "status": "failed", "error_message": str(exc)[:500],
            "finished_at": finished, "updated_at": finished,
        }, f"Sherlock: ошибка проверки {username}. Подробности доступны в /sherlock_history.")


def claim() -> dict[str, Any] | None:
    rows = gateway({"action": "claim", "worker_id": WORKER_ID})
    return rows[0] if rows else None


def main() -> None:
    log.info("Sherlock GitHub Actions worker started")
    processed = 0
    while True:
        job = claim()
        if not job:
            log.info("Queue is empty; processed=%s", processed)
            return
        process(job)
        processed += 1


if __name__ == "__main__":
    main()
