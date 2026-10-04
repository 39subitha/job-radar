# Job Radar

Daily tooling / fixture design job finder for India-first, worldwide search.

- `backend/` — Python robot run daily by GitHub Actions (`.github/workflows/daily.yml`, 08:00 IST):
  reads ~53 company career sites, filters and scores jobs against `profile.json`, saves `data/jobs.json`, emails a summary.
- `backend/companies.json` — company list and how to read each career site.
- `android/` — the Android app (reads `data/jobs.json`).

Optional GitHub secrets: `GMAIL_USER`, `GMAIL_APP_PASSWORD`, `EMAIL_TO` (daily email);
`ADZUNA_APP_ID`, `ADZUNA_APP_KEY`, `JOOBLE_KEY` (extra jobs from other companies).

Run locally: `pip install requests && python backend/run.py --no-email`
