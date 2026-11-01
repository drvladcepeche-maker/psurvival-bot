import json
import os
import time
import logging
from datetime import datetime, timezone
from typing import Any

import requests
from sherlock_project.sherlock import sherlock
from sherlock_project.sites import SitesInformation
from sherlock_project.notify import QueryNotify
from sherlock_project.result import QueryStatus

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
log = logging.getLogger("sherlock-worker")

SUPABASE_URL = os.environ["SUPABASE_URL"].rstrip("/")
SERVICE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
TELEGRAM_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
WORKER_ID = os.getenv("SHERLOCK_WORKER_ID", "sherlock-python-1")
POLL_SECONDS = max(2, int(os.getenv("POLL_SECONDS", "5")))
REQUEST_TIMEOUT = max(5, int(os.getenv("SHERLOCK_TIMEOUT", "30")))
MAX_SITES = max(1, min(500, int(os.getenv("SHERLOCK_MAX_SITES", "500"))))

session = requests.Session()
session.headers.update({
    "apikey": SERVICE_KEY,
    "Authorization": f"Bearer {SERVICE_KEY}",
    "Content-Type": "application/json",
    "Prefer": "return=representation",
})

def db(path: str, method: str = "GET", payload: Any = None):
    response = session.request(method, f"{SUPABASE_URL}/rest/v1/{path}", json=payload, timeout=30)
    response.raise_for_status()
    return response.json() if response.content else None

def telegram_send(chat_id: int, text: str):
    if not TELEGRAM_TOKEN:
        return
    try:
        response = requests.post(
            f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage",
            json={"chat_id": chat_id, "text": text[:3900], "disable_web_page_preview": True},
            timeout=20,
        )
        response.raise_for_status()
        if not response.json().get("ok"):
            raise RuntimeError("Telegram Bot API rejected sendMessage")
    except Exception:
        log.exception("Telegram notification failed")

class Collector(QueryNotify):
    def __init__(self):
        super().__init__()
        self.results = []

    def update(self, result):
        status = result.status.value if hasattr(result.status, "value") else str(result.status)
        self.results.append({
            "site": result.site_name,
            "url": result.site_url_user,
            "status": status,
            "response_seconds": result.query_time,
            "context": result.context,
        })

def patch_job(job_id: str, payload: dict):
    db(f"sherlock_jobs?id=eq.{job_id}", "PATCH", payload)

def process(job: dict):
    job_id = job["id"]
    username = job["username"]
    chat_id = int(job["chat_id"])
    collector = Collector()
    try:
        sites = SitesInformation()
        sites.remove_nsfw_sites()
        site_data = {name: item.information for name, item in sites.sites.items()}
        if len(site_data) > MAX_SITES:
            site_data = dict(list(site_data.items())[:MAX_SITES])
        patch_job(job_id, {"total_sites": len(site_data), "updated_at": datetime.now(timezone.utc).isoformat()})
        log.info("Starting %s across %s sites", username, len(site_data))
        results = sherlock(
            username=username,
            site_data=site_data,
            query_notify=collector,
            timeout=REQUEST_TIMEOUT,
        )
        # Build from returned result objects; collector also records incremental updates.
        normalized = []
        for site_name, site_result in results.items():
            result = site_result.get("status")
            if result is None:
                continue
            status = result.status.value if hasattr(result.status, "value") else str(result.status)
            normalized.append({
                "site": site_name,
                "url": site_result.get("url_user", ""),
                "status": status,
                "response_seconds": result.query_time,
                "context": result.context,
            })
        found = [item for item in normalized if item["status"] == QueryStatus.CLAIMED.value]
        # Keep only found profiles and diagnostically useful non-definitive outcomes.
        compact = found + [item for item in normalized if item["status"] in (QueryStatus.UNKNOWN.value, QueryStatus.WAF.value)]
        patch_job(job_id, {
            "status": "succeeded",
            "total_sites": len(site_data),
            "checked_sites": len(normalized),
            "found_count": len(found),
            "results": compact,
            "finished_at": datetime.now(timezone.utc).isoformat(),
            "updated_at": datetime.now(timezone.utc).isoformat(),
        })
        lines = [
            "Sherlock: проверка завершена",
            f"Username: {username}",
            f"Проверено: {len(normalized)}",
            f"Найдено профилей: {len(found)}",
            f"Неопределённых ответов / блокировок: {sum(1 for x in normalized if x['status'] in ('Unknown', 'WAF'))}",
        ]
        for item in found[:20]:
            lines.append(f"• {item['site']}: {item['url']}")
        if len(found) > 20:
            lines.append(f"И ещё {len(found) - 20}. Полный список: /sherlock_history")
        telegram_send(chat_id, "\n".join(lines))
        log.info("Finished %s: found=%s checked=%s", username, len(found), len(normalized))
    except Exception as exc:
        log.exception("Sherlock job failed: %s", job_id)
        patch_job(job_id, {
            "status": "failed",
            "error_message": str(exc)[:500],
            "finished_at": datetime.now(timezone.utc).isoformat(),
            "updated_at": datetime.now(timezone.utc).isoformat(),
        })
        telegram_send(chat_id, f"Sherlock: ошибка проверки {username}. Подробности доступны в /sherlock_history.")

def claim():
    rows = db("rpc/sherlock_claim_next_job", "POST", {"p_worker_id": WORKER_ID})
    return rows[0] if rows else None

def main():
    log.info("Sherlock worker started; id=%s", WORKER_ID)
    while True:
        try:
            job = claim()
            if job:
                process(job)
            else:
                time.sleep(POLL_SECONDS)
        except KeyboardInterrupt:
            raise
        except Exception:
            log.exception("Queue polling failed")
            time.sleep(max(POLL_SECONDS, 10))

if __name__ == "__main__":
    main()
