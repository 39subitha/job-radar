"""Job sources: one fetcher per career-site system (ATS), plus optional job-search APIs.

Every fetcher returns dicts: {title, url, location, posted, desc}. Endpoints verified 2026-10-04.
"""
import html
import json
import os
import re
import time
import xml.etree.ElementTree as ET
from urllib.parse import quote

import requests

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
TIMEOUT = 25
COMPANY_BUDGET = 400  # seconds per company; slow sites get cut off, not the whole run
MAX_DETAIL_CALLS = 30   # per company, to stay polite


def session():
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept-Language": "en"})
    return s


def _job(title, url, location=None, posted=None, desc=""):
    return {"title": html.unescape(title or "").strip(), "url": url or "", "location": location or "",
            "posted": str(posted or ""), "desc": desc or ""}


def _phrase(q):
    return f'"{q}"' if " " in q else q


# ---------------------------------------------------------------- keyword-search systems

def workday(s, p, q):
    base = f"https://{p['tenant']}.wd{p['wdn']}.myworkdayjobs.com"
    api = f"{base}/wday/cxs/{p['tenant']}/{p['site']}"
    out = []
    for offset in (0, 20):
        r = s.post(f"{api}/jobs", json={"appliedFacets": {}, "limit": 20, "offset": offset, "searchText": q},
                   headers={"Accept": "application/json"}, timeout=TIMEOUT)
        r.raise_for_status()
        d = r.json()
        for j in d.get("jobPostings", []):
            if "externalPath" not in j:
                continue
            item = _job(j["title"], f"{base}/en-US/{p['site']}{j['externalPath']}", j.get("locationsText"), j.get("postedOn"))
            item["_detail"] = api + j["externalPath"]
            out.append(item)
        if d.get("total", 0) <= offset + 20:
            break
    return out


def workday_detail(s, job):
    d = s.get(job["_detail"], headers={"Accept": "application/json"}, timeout=TIMEOUT).json()
    info = d.get("jobPostingInfo", {})
    job["desc"] = info.get("jobDescription", "")
    if info.get("location") and (not job["location"] or "Locations" in job["location"]):
        extra = info.get("additionalLocations") or []
        job["location"] = "; ".join([info["location"]] + extra)


_SF_LOC = re.compile(r"\s*\(([^()]*)\)\s*$")


def successfactors(s, p, q):
    r = s.get(f"https://{p['host']}/services/rss/job/", params={"locale": p["locale"], "keywords": f"({_phrase(q)})"},
              timeout=TIMEOUT)
    r.raise_for_status()
    out = []
    for it in ET.fromstring(r.content).iter("item"):
        title = it.findtext("title") or ""
        m = _SF_LOC.search(title)  # "Tooling Specialist (Plattsburgh, NY, US)"
        loc = m.group(1) if m else ""
        if m:
            title = title[:m.start()]
        url = (it.findtext("link") or "").split("?")[0]
        out.append(_job(title, url, loc, it.findtext("pubDate"), it.findtext("description")))
    return out


def smartrecruiters(s, p, q):
    co = p["company"]
    r = s.get(f"https://api.smartrecruiters.com/v1/companies/{co}/postings", params={"q": q, "limit": 100}, timeout=TIMEOUT)
    r.raise_for_status()
    out = []
    for j in r.json().get("content", []):
        loc = j.get("location", {})
        item = _job(j["name"], f"https://jobs.smartrecruiters.com/{co}/{j['id']}",
                    ", ".join(filter(None, [loc.get("city"), loc.get("region"), loc.get("country", "").upper()])),
                    j.get("releasedDate"))
        item["_detail"] = f"https://api.smartrecruiters.com/v1/companies/{co}/postings/{j['id']}"
        out.append(item)
    return out


def smartrecruiters_detail(s, job):
    d = s.get(job["_detail"], timeout=TIMEOUT).json()
    sec = d.get("jobAd", {}).get("sections", {})
    job["desc"] = "\n".join(v.get("text", "") for v in sec.values() if isinstance(v, dict))
    job["url"] = d.get("postingUrl") or job["url"]


def phenom(s, p, q):
    base = p["base"]
    if "_lang" not in p:
        page = s.get(base, timeout=TIMEOUT).text
        p["_lang"] = (re.search(r'"locale"\s*:\s*"([^"]+)"', page) or [None, "en_global"])[1]
        p["_country"] = (re.search(r'"country"\s*:\s*"([^"]+)"', page) or [None, "global"])[1]
    host = "/".join(base.split("/")[:3])
    body = {"lang": p["_lang"], "deviceType": "desktop", "country": p["_country"], "pageName": "search-results",
            "ddoKey": "refineSearch", "sortBy": "", "subsearch": "", "from": 0, "jobs": True, "counts": True,
            "all_fields": ["category", "country", "city"], "size": 50, "clearAll": False, "jdsource": "facets",
            "isSliderEnable": False, "pageId": "page20", "siteType": "external", "keywords": q, "global": True,
            "selected_fields": {}, "locationData": {}}
    r = s.post(f"{host}/widgets", json=body, headers={"Accept": "application/json"}, timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(j["title"], f"{base}/job/{j['jobId']}", j.get("cityStateCountry") or j.get("location"),
                 j.get("postedDate"), j.get("descriptionTeaser", ""))
            for j in r.json()["refineSearch"]["data"]["jobs"]]


def eightfold(s, p, q):
    r = s.get(f"https://{p['host']}/api/pcsx/search", params={"domain": p["domain"], "query": q, "location": "", "start": 0},
              timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(j["name"], f"https://{p['host']}{j['positionUrl']}", "; ".join(j.get("locations", [])), j.get("postedTs"))
            for j in r.json()["data"]["positions"]]


def oracle(s, p, q):
    url = (f"https://{p['host']}/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true"
           f"&expand=requisitionList.secondaryLocations&finder=findReqs;siteNumber={p['site']},"
           f"keyword=%22{quote(q)}%22,limit=50,offset=0,sortBy=POSTING_DATES_DESC")
    r = s.get(url, timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(j["Title"], f"{p['public_base']}/job/{j['Id']}", j.get("PrimaryLocation"), j.get("PostedDate"),
                 j.get("ShortDescriptionStr") or "")
            for j in r.json()["items"][0].get("requisitionList", [])]


def avature(s, p, q):
    r = s.get(f"{p['portal']}/SearchJobs/{quote(q)}/feed/", params={"listFilterMode": 1, "folderRecordsPerPage": 20},
              timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(it.findtext("title"), it.findtext("link"), it.findtext("description"), it.findtext("pubDate"))
            for it in ET.fromstring(r.content).iter("item")]


def icims(s, p, q):
    out = []
    for host in p["hosts"]:
        r = s.get(f"https://{host}/jobs/search", params={"ss": 1, "searchKeyword": q, "in_iframe": 1, "pr": 0}, timeout=TIMEOUT)
        r.raise_for_status()
        for url, title in re.findall(r'<a[^>]*href="(https://[^"]*/jobs/\d+/[^"]*)"[^>]*>\s*(?:<span[^>]*>[^<]*</span>)?\s*<h3[^>]*>([^<]*)', r.text):
            out.append(_job(title, url.replace("?in_iframe=1", "").replace("&in_iframe=1", ""), host.split("-")[2].upper()))
        time.sleep(0.5)
    return out


def capgemini(s, p, q):
    r = s.get("https://cg-jobstream-api.azurewebsites.net/api/job-search", params={"page": 1, "size": 50, "search": q},
              timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(j["title"], j.get("apply_job_url"), j.get("location"), j.get("updated_at"),
                 j.get("description_stripped") or j.get("description", ""))
            for j in r.json()["data"]]


def digitalrecruiters(s, p, q):
    r = s.post("https://api.digitalrecruiters.com/public/v1/careers-site/job-ads",
               params={"domainName": p["domain"], "limit": 50, "page": 1, "locale": "en_GB"},
               json={"q": q, "filters": {}}, timeout=TIMEOUT)
    r.raise_for_status()
    out = []
    for j in r.json()["items"]:
        loc = j.get("location")
        if isinstance(loc, dict):
            loc = ", ".join(str(v) for v in (loc.get("city"), loc.get("country")) if v)
        out.append(_job(j["title"], f"https://{p['domain']}/en/annonce/{j['url']}", loc))
    return out


def zwayam(s, p, q):
    fc = {"paginationStartNo": 0, "selectedCall": "sort", "sortCriteria": {"name": "modifiedDate", "isAscending": False},
          "anyOfTheseWords": q}
    r = s.post("https://public.zwayam.com/jobs/search", files={"filterCri": (None, json.dumps(fc)),
               "domain": (None, p["domain"]), "companyId": (None, p["company_id"])}, timeout=90)
    r.raise_for_status()
    out = []
    for x in r.json()["data"]["data"]:
        j = x["_source"]
        out.append(_job(j.get("jobTitle"), f"https://{p['domain']}/cyient/jobview/{j.get('jobUrl')}", j.get("location"),
                        j.get("modifiedDate"), j.get("jobDescription") or j.get("shortDescriptionWithoutHtml") or ""))
    return out


def ripplehire(s, p, q):
    params = {"page": 0, "search": q, "token": p["token"], "source": "CAREERSITE", "pagesize": 50}
    r = s.post(f"https://{p['sub']}.ripplehire.com/candidate/candidatejobsearch",
               data={"careerSiteUrlParams": json.dumps(params), "lang": "en"},
               headers={"Accept": "application/json", "X-Requested-With": "XMLHttpRequest"}, timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(j["jobTitle"], f"https://{p['sub']}.ripplehire.com/candidate/?token={p['token']}&source=CAREERSITE#detail/job/{j['jobSeq']}",
                 j.get("jobLocation"), None, j.get("jobDesc") or "")
            for j in r.json().get("jobVoList") or []]


def liebherr(s, p, q):
    t = s.get("https://www.liebherr.com/en-int/careers/job-vacancies-5370609", params={"term": q, "p": 1, "ps": 50},
              timeout=TIMEOUT).text
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', t, re.S)
    if not m:
        return []
    jobs = re.findall(r'\{"city":\{"label":"([^"]*)"\},"company":\{"name":"([^"]*)"\},"jobId":"(\d+)","jobDetailPageUrl":"([^"]+)","title":"([^"]+)"', m.group(1))
    return [_job(json.loads(f'"{ti}"'), "https://www.liebherr.com" + url, city) for city, co, jid, url, ti in jobs]


# ---------------------------------------------------------------- full-list systems (no keyword search)

def greenhouse(s, p):
    r = s.get(f"https://boards-api.greenhouse.io/v1/boards/{p['token']}/jobs", params={"content": "true"}, timeout=90)
    r.raise_for_status()
    return [_job(j["title"], j["absolute_url"], (j.get("location") or {}).get("name"), j.get("updated_at"),
                 html.unescape(j.get("content", ""))) for j in r.json()["jobs"]]


def lever(s, p):
    r = s.get(f"https://api.lever.co/v0/postings/{p['company']}", params={"mode": "json"}, timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(j["text"], j["hostedUrl"], (j.get("categories") or {}).get("location"), None, j.get("descriptionPlain", ""))
            for j in r.json()]


def sensehq(s, p):
    out = []
    for page in range(1, 40):
        t = s.get(f"https://{p['sub']}.sensehq.com/careers", params={"page": page}, timeout=TIMEOUT).text
        m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', t, re.S)
        if not m:
            break
        jd = json.loads(m.group(1))["props"]["pageProps"]["jobsData"]
        rows = jd.get("rows") or []
        out += [_job(r["title"], f"https://{p['sub']}.sensehq.com/careers/jobs/{r['id']}", r.get("location"), None,
                     r.get("description_external") or "") for r in rows]
        if not rows or len(out) >= jd.get("count", 0):
            break
        time.sleep(0.5)
    return out


def umantis(s, p):
    out, seen = [], set()
    for page in range(1, 40):
        t = s.get(f"https://recruitingapp-{p['app_id']}.umantis.com/Jobs/All",
                  params={"lang": "eng", f"tc{p['list_id']}": f"p{page}"}, timeout=TIMEOUT).text
        found = re.findall(r'href="/Vacancies/(\d+)/Description/(\d+)"[^>]*aria-label="([^"]*)"', t)
        new = [f for f in found if f[0] not in seen]
        if not new:
            break
        for vid, lang, title in new:
            seen.add(vid)
            out.append(_job(title, f"https://recruitingapp-{p['app_id']}.umantis.com/Vacancies/{vid}/Description/{lang}"))
        time.sleep(0.5)
    return out


def avature_folder(s, p):
    out = []
    for feed in p["feeds"]:
        r = s.get(feed, timeout=TIMEOUT)
        r.raise_for_status()
        out += [_job(it.findtext("title"), it.findtext("link"), it.findtext("description"), it.findtext("pubDate"))
                for it in ET.fromstring(r.content).iter("item")]
    return out


KEYWORD = {"workday": workday, "successfactors": successfactors, "smartrecruiters": smartrecruiters, "phenom": phenom,
           "eightfold": eightfold, "oracle": oracle, "avature": avature, "icims": icims, "capgemini": capgemini,
           "digitalrecruiters": digitalrecruiters, "zwayam": zwayam, "ripplehire": ripplehire, "liebherr": liebherr}
FULL_LIST = {"greenhouse": greenhouse, "lever": lever, "sensehq": sensehq, "umantis": umantis, "avature_folder": avature_folder}
DETAIL = {"workday": workday_detail, "smartrecruiters": smartrecruiters_detail}


def fetch_company(c, terms, is_relevant):
    """All relevant jobs of one company, de-duplicated by URL, with descriptions where available."""
    s = session()
    kind, p = c["type"], dict(c.get("params", {}))
    found = {}
    if kind in FULL_LIST:
        batches = [FULL_LIST[kind](s, p)]
    else:
        batches = []
        deadline = time.time() + COMPANY_BUDGET
        for q in list(terms) + c.get("extra_terms", []):
            if time.time() > deadline:
                print(f"    {c['name']}: time budget used, skipping remaining keywords")
                break
            batches.append(KEYWORD[kind](s, p, q))
            time.sleep(0.7)
    for b in batches:
        for j in b:
            if j["title"] and j["url"] and j["url"] not in found and is_relevant(j["title"]):
                found[j["url"]] = j
    jobs = list(found.values())
    if kind in DETAIL:
        deadline = time.time() + COMPANY_BUDGET / 2
        for j in jobs[:MAX_DETAIL_CALLS]:
            if time.time() > deadline:
                break
            try:
                DETAIL[kind](s, j)
            except Exception as e:
                print(f"    detail failed {j['url']}: {e}")
            time.sleep(0.4)
    for j in jobs:
        j.pop("_detail", None)
        j["company"] = c["name"]
        j["source"] = kind
    return jobs


# ---------------------------------------------------------------- job-search APIs (optional, free keys)

ADZUNA_COUNTRIES = ["in", "gb", "de", "fr", "us", "ca", "au", "nl", "pl", "es", "it", "at", "ch", "be", "sg", "nz"]
JOOBLE_LOCATIONS = ["India", "Germany", "France", "United Kingdom", "United States", "Canada", "Australia", "UAE", "Spain", "Netherlands"]
AGG_QUERIES = ["fixture design engineer", "tooling engineer", "jig fixture design"]


def fetch_aggregators(terms, is_relevant):
    """Adzuna + Jooble free APIs: jobs from companies not in our list. Skipped when no API keys are set."""
    s = session()
    out = []
    aid, akey = os.environ.get("ADZUNA_APP_ID"), os.environ.get("ADZUNA_APP_KEY")
    if aid and akey:
        for cc in ADZUNA_COUNTRIES:
            for q in AGG_QUERIES[:2]:
                try:
                    r = s.get(f"https://api.adzuna.com/v1/api/jobs/{cc}/search/1",
                              params={"app_id": aid, "app_key": akey, "what": q, "results_per_page": 50,
                                      "max_days_old": 30, "content-type": "application/json"}, timeout=TIMEOUT)
                    r.raise_for_status()
                    for j in r.json().get("results", []):
                        item = _job(j.get("title"), j.get("redirect_url"), (j.get("location") or {}).get("display_name"),
                                    j.get("created"), j.get("description", ""))
                        item["company"] = (j.get("company") or {}).get("display_name") or "Unknown company"
                        out.append(item)
                except Exception as e:
                    print(f"    adzuna {cc} failed: {e}")
                time.sleep(1)
    jkey = os.environ.get("JOOBLE_KEY")
    if jkey:
        for loc in JOOBLE_LOCATIONS:
            for q in AGG_QUERIES:
                try:
                    r = s.post(f"https://jooble.org/api/{jkey}", json={"keywords": q, "location": loc}, timeout=TIMEOUT)
                    r.raise_for_status()
                    for j in r.json().get("jobs", []):
                        item = _job(j.get("title"), j.get("link"), j.get("location"), j.get("updated"), j.get("snippet", ""))
                        item["company"] = j.get("company") or "Unknown company"
                        out.append(item)
                except Exception as e:
                    print(f"    jooble {loc} failed: {e}")
                time.sleep(1)
    result = []
    for j in out:
        if j["title"] and j["url"] and is_relevant(j["title"]):
            j["source"] = "aggregator"
            result.append(j)
    return result

# Korean career sites live in their own module
from sources_korea import FULL_LIST as _KOREA_FULL_LIST  # noqa: E402
FULL_LIST.update(_KOREA_FULL_LIST)
