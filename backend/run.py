"""Daily job collection: fetch all sources, filter, score, merge with previous run, email digest.

Usage: python backend/run.py [--no-email] [--only "Company Name"]
"""
import argparse
import hashlib
import json
import re
import sys
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

import score
import sources
import emailer

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "jobs.json"
COMPANIES = Path(__file__).parent / "companies.json"
DROP_AFTER_MISSES = 3   # job disappears after not being seen in 3 runs in a row
MIN_SCORE = 25          # anything below this is not stored
DESC_LIMIT = 6000


def now():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def clean_text(html):
    if not html:
        return ""
    t = re.sub(r"(?i)<br\s*/?>|</p>|</li>|</h\d>|</div>", "\n", html)
    t = re.sub(r"(?i)<li[^>]*>", "• ", t)
    t = re.sub(r"<[^>]+>", " ", t)
    for a, b in (("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&#39;", "'"), ("&quot;", '"'), ("&rsquo;", "'")):
        t = t.replace(a, b)
    t = re.sub(r"[ \t\r\f\v]+", " ", t)
    t = re.sub(r"\n\s*\n+", "\n\n", t)
    return t.strip()


def job_id(company, url, title, location):
    key = url or f"{company}|{title}|{location}"
    return hashlib.sha1(key.encode()).hexdigest()[:12]


def _one(c):
    if c.get("status") != "ok":
        return [], {"name": c["name"], "industry": c.get("industry", ""), "status": "unsupported",
                    "note": c.get("notes", "")[:140], "jobs": 0}
    t0 = time.time()
    try:
        found = sources.fetch_company(c, score.PROFILE["search_terms"], score.title_is_relevant)
        print(f"  {c['name']:<28} {len(found):>3} relevant  ({time.time() - t0:.0f}s)", flush=True)
        return found, {"name": c["name"], "industry": c.get("industry", ""), "status": "ok",
                       "jobs": len(found), "careers": c.get("careers", "")}
    except Exception as e:
        print(f"  {c['name']:<28} ERROR {str(e)[:120]}", flush=True)
        return [], {"name": c["name"], "industry": c.get("industry", ""), "status": "error",
                    "note": str(e)[:140], "jobs": 0, "careers": c.get("careers", "")}


def collect(companies, only=None):
    todo = [c for c in companies if not only or c["name"].lower() == only.lower()]
    jobs, status = [], []
    with ThreadPoolExecutor(max_workers=8) as pool:
        for found, st in pool.map(_one, todo):
            jobs += found
            status.append(st)
    if not only:
        try:
            extra = sources.fetch_aggregators(score.PROFILE["search_terms"], score.title_is_relevant)
            print(f"  job-search APIs: {len(extra)} relevant")
            jobs += extra
        except Exception:
            traceback.print_exc()
    return jobs, status


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-email", action="store_true")
    ap.add_argument("--only")
    ap.add_argument("--email-all", action="store_true", help="email all open jobs, not only new ones")
    args = ap.parse_args()

    companies = json.loads(COMPANIES.read_text())
    old = json.loads(DATA.read_text()) if DATA.exists() else {"jobs": []}
    old_by_id = {j["id"]: j for j in old["jobs"]}
    ts = now()

    print("Collecting jobs...")
    raw, status = collect(companies, args.only)

    fresh = {}
    for r in raw:
        desc = clean_text(r.get("desc", ""))[:DESC_LIMIT]
        s, chips, yrs = score.score(r["title"], desc)
        if s < MIN_SCORE:
            continue
        jid = job_id(r["company"], r.get("url", ""), r["title"], r.get("location", ""))
        # same title+company+location from two sources -> keep the one with longer description
        dkey = (r["company"].lower(), r["title"].lower().strip(), (r.get("location") or "").lower()[:30])
        prev = fresh.get(dkey)
        if prev and len(prev["desc"]) >= len(desc):
            continue
        fresh[dkey] = {
            "id": jid, "title": r["title"].strip(), "company": r["company"],
            "location": (r.get("location") or "").strip(), "url": r.get("url", ""),
            "posted": r.get("posted") or "", "desc": desc, "score": s, "matched": chips,
            "min_years": yrs, "source": r.get("source", ""), "india": score.is_home(r.get("location", "")),
            "first_seen": old_by_id.get(jid, {}).get("first_seen", ts), "last_seen": ts, "misses": 0,
        }

    merged = {j["id"]: j for j in fresh.values()}
    ran = {s["name"] for s in status if s["status"] == "ok"}
    for jid, j in old_by_id.items():
        if jid in merged:
            continue
        # count a miss only if its source actually ran fine this time (failed sites keep their jobs)
        if j["company"] in ran or (j.get("source") == "aggregator" and not args.only):
            j["misses"] = j.get("misses", 0) + 1
        if j.get("misses", 0) < DROP_AFTER_MISSES:
            merged[jid] = j

    # India first, then abroad; best match first inside each
    out_jobs = sorted(merged.values(), key=lambda j: (not j.get("india"), -j["score"], j["first_seen"]))
    new_jobs = [j for j in out_jobs if j["id"] not in old_by_id]
    out = {"generated": ts, "profile": score.PROFILE["label"], "companies": status, "jobs": out_jobs}
    DATA.parent.mkdir(exist_ok=True)
    DATA.write_text(json.dumps(out, ensure_ascii=False, indent=0))
    print(f"Saved {len(out_jobs)} jobs ({len(new_jobs)} new) -> {DATA}")

    if not args.no_email and not args.only:
        emailer.send_digest(out_jobs if args.email_all else new_jobs, out_jobs, status)


if __name__ == "__main__":
    sys.exit(main())
