"""Relevance filter and 0-100 match score against profile.json."""
import json
import re
from functools import lru_cache
from pathlib import Path

PROFILE = json.loads((Path(__file__).parent / "profile.json").read_text())


@lru_cache(maxsize=None)
def _rx(kw):
    # word match that also accepts plural forms: fixture -> fixtures
    return re.compile(r"\b" + re.escape(kw.lower()) + r"(?:s|es)?\b")


def _hits(text, keywords):
    return [k for k in keywords if _rx(k).search(text)]


def title_is_relevant(title):
    t = title.lower()
    if _hits(t, PROFILE["title_exclude"]):
        return False
    return bool(_hits(t, PROFILE["title_strong"]) or _hits(t, PROFILE["title_medium"]))


_YEARS = re.compile(r"(\d{1,2})\s*(?:\+|-|–|to)?\s*(\d{1,2})?\s*\+?\s*(?:years|yrs|year|ans|jahre)", re.I)


def min_years(text):
    """Smallest 'N years' requirement mentioned, ignoring company-age phrases."""
    found = []
    for m in _YEARS.finditer(text):
        n = int(m.group(1))
        if 0 < n <= 25:
            found.append(n)
    return min(found) if found else None


def score(title, desc):
    t = title.lower()
    d = (desc or "").lower()
    full = t + " \n " + d
    pts = 0
    matched = []

    if _hits(t, ["fixture", "jig", "tool design", "tool designer", "tooling engineer", "outillage", "utillaje", "vorrichtung"]):
        pts += 50   # exactly the user's job
    elif _hits(t, PROFILE["title_strong"]):
        pts += 38
    elif _hits(t, PROFILE["title_medium"]):
        pts += 20

    strong = _hits(full, PROFILE["skills_strong"])
    medium = _hits(full, PROFILE["skills_medium"])
    pts += min(len(strong) * 7, 28) + min(len(medium) * 3, 12)
    matched += strong + medium

    if _hits(full, PROFILE["domain"]):
        pts += 10

    yrs = min_years(d) if d else None
    if yrs is None:
        pts += 8
    elif yrs <= PROFILE["experience_years"] + 2:
        pts += 15
        matched.append(f"{yrs}+ yrs ok")
    elif yrs >= PROFILE["experience_years"] + 5:
        pts -= 20

    if _hits(t, PROFILE["title_senior"]):
        pts -= 15
    elif _hits(t, ["senior", "lead", "sr", "staff"]):
        pts -= 5

    # dedupe chips, keep order, show nicest form
    seen, chips = set(), []
    for m in matched:
        k = m.lower()
        if k not in seen:
            seen.add(k)
            chips.append(m.upper() if m in ("catia", "plm", "nx", "dfm", "dfma") else m)
    return max(0, min(100, pts)), chips[:8], yrs


# "IN" alone is ambiguous (Indiana, USA), so only trust it after an Indian state code
_IND_CODE = re.compile(r"\bIND\b|,\s*(?:KA|TN|DL|MH|TS|TG|AP|HR|UP|GJ|KL|WB|RJ|MP|PB|OR|OD)\s*,\s*IN\s*$")


def is_home(location):
    """True if the job location is in the home country (India)."""
    loc = location or ""
    return bool(_hits(loc.lower(), PROFILE["home_keywords"]) or _IND_CODE.search(loc))
