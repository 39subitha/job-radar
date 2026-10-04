"""Relevance filter and 0-100 match score, one score per friend profile (backend/profiles/*.json)."""
import json
import re
from functools import lru_cache
from pathlib import Path

PROFILE_DIR = Path(__file__).parent / "profiles"
PROFILES = {p["id"]: p for p in (json.loads(f.read_text()) for f in sorted(PROFILE_DIR.glob("*.json"))) if p.get("enabled")}

# India location words, shared by everyone (used for the "india" flag and country detection)
HOME_KEYWORDS = PROFILES.get("friend1", next(iter(PROFILES.values()), {})).get("home_keywords", [])


@lru_cache(maxsize=None)
def _rx(kw):
    # word match that also accepts plural forms: fixture -> fixtures
    if re.search(r"[가-힣]", kw):
        return re.compile(re.escape(kw))   # Korean words join with particles, so plain substring match
    return re.compile(r"\b" + re.escape(kw.lower()) + r"(?:s|es)?\b")


def _hits(text, keywords):
    return [k for k in keywords if _rx(k).search(text)]


def title_is_relevant(title, P):
    t = title.lower()
    if _hits(t, P.get("title_exclude", [])):
        return False
    return bool(_hits(t, P["title_strong"]) or _hits(t, P.get("title_medium", [])))


def relevant_to_anyone(title):
    return any(title_is_relevant(title, P) for P in PROFILES.values())


def all_search_terms():
    seen, out = set(), []
    for P in PROFILES.values():
        for t in P["search_terms"]:
            if t.lower() not in seen:
                seen.add(t.lower())
                out.append(t)
    return out


ACRONYMS = {"catia", "plm", "nx", "dfm", "dfma", "gd&t", "cnc", "plc", "sap", "mes", "ppap", "apqp", "fmea", "sql", "aws", "qa", "qms", "spc", "msa"}

_YEARS = re.compile(r"(\d{1,2})\s*(?:\+|-|–|to)?\s*(\d{1,2})?\s*\+?\s*(?:years|yrs|year|ans|jahre)", re.I)


def min_years(text):
    """Smallest 'N years' requirement mentioned, ignoring company-age phrases."""
    found = []
    for m in _YEARS.finditer(text):
        n = int(m.group(1))
        if 0 < n <= 25:
            found.append(n)
    return min(found) if found else None


def score(title, desc, P):
    t = title.lower()
    d = (desc or "").lower()
    full = t + " \n " + d
    if _hits(t, P.get("title_exclude", [])):
        return 0, [], None
    pts = 0
    matched = []

    if _hits(t, P.get("title_exact", [])):
        pts += 50   # exactly this friend's job
    elif _hits(t, P["title_strong"]):
        pts += 38
    elif _hits(t, P.get("title_medium", [])):
        pts += 20

    strong = _hits(full, P["skills_strong"])
    medium = _hits(full, P.get("skills_medium", []))
    pts += min(len(strong) * 7, 28) + min(len(medium) * 3, 12)
    matched += strong + medium

    if _hits(full, P.get("domain", [])):
        pts += 10

    yrs = min_years(d) if d else None
    exp = P.get("experience_years", 0)
    if yrs is None:
        pts += 8
    elif yrs <= exp + 2:
        pts += 15
        matched.append(f"{yrs}+ yrs ok")
    elif yrs >= exp + 5:
        pts -= 20

    if _hits(t, P.get("title_senior", [])):
        pts -= 15
    elif exp < 6 and _hits(t, ["senior", "lead", "sr", "staff"]):
        pts -= 5

    # dedupe chips, keep order, show nicest form
    seen, chips = set(), []
    for m in matched:
        k = m.lower()
        if k not in seen:
            seen.add(k)
            chips.append(m.upper() if k in ACRONYMS else m)
    return max(0, min(100, pts)), chips[:8], yrs


# "IN" alone is ambiguous (Indiana, USA), so only trust it after an Indian state code
_IND_CODE = re.compile(r"\bIND\b|,\s*(?:KA|TN|DL|MH|TS|TG|AP|HR|UP|GJ|KL|WB|RJ|MP|PB|OR|OD)\s*,\s*IN\s*$")


def is_home(location):
    """True if the job location is in India."""
    loc = location or ""
    return bool(_hits(loc.lower(), HOME_KEYWORDS) or _IND_CODE.search(loc))
