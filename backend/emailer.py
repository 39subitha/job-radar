"""Daily summary email via Gmail SMTP. Needs env: GMAIL_USER, GMAIL_APP_PASSWORD, EMAIL_TO."""
import html
import os
import smtplib
from datetime import date
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

MAX_IN_EMAIL = 40


def _color(s):
    return "#1a7f37" if s >= 70 else "#b08800" if s >= 50 else "#6e7781"


def _rows(jobs):
    rows = []
    for j in jobs:
        chips = " · ".join(html.escape(m) for m in j["matched"][:6])
        rows.append(f"""
<tr><td style="padding:10px 0;border-bottom:1px solid #eee">
  <span style="display:inline-block;min-width:44px;padding:2px 6px;border-radius:10px;background:{_color(j['score'])};color:#fff;font-weight:bold;text-align:center">{j['score']}%</span>
  <b style="font-size:15px">{html.escape(j['title'])}</b><br>
  <span style="color:#444">{html.escape(j['company'])} — {html.escape(j['location'] or 'Location not given')}</span><br>
  <span style="color:#1a7f37;font-size:13px">{chips}</span><br>
  <a href="{html.escape(j['url'])}" style="color:#0969da">View &amp; apply →</a>
</td></tr>""")
    return "".join(rows)


def _section(title, jobs):
    if not jobs:
        return ""
    return f'<h3 style="margin:18px 0 0;border-bottom:2px solid #1f4e79">{title} ({len(jobs)})</h3><table style="width:100%;border-collapse:collapse">{_rows(jobs)}</table>'


def _for_friend(jobs, P):
    """Same jobs, with score and matched skills for this friend."""
    from score import score
    out = []
    for j in jobs:
        s, chips, _ = score(j["title"], j.get("desc", ""), P)
        out.append({**j, "score": s, "matched": chips})
    return out


def build_html(P, new_jobs, all_jobs, status):
    home = set(P.get("preferred_countries") or ["India"])
    mine = _for_friend(new_jobs, P)
    local = sorted((j for j in mine if j.get("country") in home and j["score"] >= P.get("home_min_score_email", 40)),
                   key=lambda j: -j["score"])
    abroad = sorted((j for j in mine if j.get("country") not in home and j["score"] >= P.get("abroad_min_score_email", 60)),
                    key=lambda j: -j["score"])
    local_shown = local[:MAX_IN_EMAIL]
    abroad_shown = abroad[:max(10, MAX_IN_EMAIL - len(local_shown))]
    failed = [s["name"] for s in status if s["status"] == "error"]
    more = len(local) + len(abroad) - len(local_shown) - len(abroad_shown)
    where = " & ".join(sorted(home))
    body = f"""<div style="font-family:Arial,sans-serif;max-width:640px">
<h2 style="margin-bottom:4px">Job Radar — {date.today():%d %b %Y}</h2>
<p style="color:#555;margin-top:0">For <b>{html.escape(P['label'])}</b> · <b>{len(local) + len(abroad)}</b> new matching jobs · {len(all_jobs)} open in total · {sum(1 for s in status if s['status']=='ok')} companies checked</p>
{_section("📍 " + html.escape(where), local_shown)}
{_section("🌍 Other countries – strong matches", abroad_shown)}
{'' if local_shown or abroad_shown else '<p>No new strong matches today. Your app still has all open jobs.</p>'}
{f'<p>+ {more} more in the app.</p>' if more > 0 else ''}
{f'<p style="color:#999;font-size:12px">Could not check today: {", ".join(map(html.escape, failed))}</p>' if failed else ''}
<p style="color:#999;font-size:12px">Open the Job Radar app for filters, full descriptions and your application tracker.</p></div>"""
    return body, len(local) + len(abroad)


def send_digest(P, new_jobs, all_jobs, status):
    user, pw = os.environ.get("GMAIL_USER"), os.environ.get("GMAIL_APP_PASSWORD")
    to = os.environ.get(P.get("email_secret", "")) or (os.environ.get("EMAIL_TO") if P["id"] == "friend1" else None)
    if not (user and pw and to):
        print(f"Email for {P['id']} not configured - skipping")
        return
    body, n = build_html(P, new_jobs, all_jobs, status)
    msg = MIMEMultipart("alternative")
    msg["Subject"] = f"Job Radar: {n} new jobs for you ({date.today():%d %b})"
    msg["From"] = f"Job Radar <{user}>"
    msg["To"] = to
    msg.attach(MIMEText(body, "html", "utf-8"))
    with smtplib.SMTP_SSL("smtp.gmail.com", 465) as s:
        s.login(user, pw)
        s.sendmail(user, [a.strip() for a in to.split(",")], msg.as_string())
    print(f"Email sent for {P['id']}")
