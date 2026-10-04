"""Korean career-site fetchers (South Korea jobs). Same contract as backend/sources.py.

All are full-list fetchers fn(s, p): Korean group sites list only tens of open postings, and their
keyword search is title-only/unreliable, so we pull everything open and let is_relevant() filter.
NOTE: titles are Korean (e.g. "[생산기술] 치공구 설계"); is_relevant() needs Korean terms such as
치공구, 지그, 금형, 설비설계, 생산기술, 공정설계, 기구설계, 철도차량, 대차, CATIA.
JOBFLEX (recruiter.co.kr) postings are often batch announcements ("8월 수시채용_경력") whose real
job list is only in the description/attached image, so they rarely match a title filter.
Endpoints verified 2026-10-04.
"""
import html
import json
import re
import time

import requests

try:
    from sources import _job, TIMEOUT
except ImportError:  # standalone use
    TIMEOUT = 25

    def _job(title, url, location=None, posted=None, desc=""):
        return {"title": html.unescape(title or "").strip(), "url": url or "", "location": location or "",
                "posted": str(posted or ""), "desc": desc or ""}

MAX_DETAIL_CALLS = 30


def _text(h):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", h or ""))).strip()


# ---------------------------------------------------------------- Midas JOBFLEX (*.recruiter.co.kr)
# Used by Hyundai Rotem, Hyundai Mobis, Hyundai WIA, Hyundai Transys, Hyundai Kefico, Hyundai Steel,
# KAI, HD Hyundai group (sub "hd"), LS Electric, LS Mtron, SeAH, Hyundai Glovis.  p = {"sub": "hyundai-rotem"}

def jobflex(s, p):
    host = f"{p['sub']}.recruiter.co.kr"
    api = "https://api-recruiter.recruiter.co.kr"
    hdr = {"prefix": host, "Origin": f"https://{host}", "Accept": "application/json"}
    out = []
    for page in range(1, 6):
        body = {"pageableRq": {"page": page, "size": 100, "sort": ["openDateTime,desc"]},
                "filter": {"keyword": "", "openStatusList": ["OPEN"], "submissionStatusList": ["IN_SUBMISSION"]}}
        r = s.post(f"{api}/position/v1/jobflex", json=body, headers=hdr, timeout=TIMEOUT)
        r.raise_for_status()
        d = r.json()
        for j in d.get("list", []):
            tags = ", ".join(t["tagName"] for t in j.get("tagList") or [])
            item = _job(j["title"], f"https://{host}/career/jobs/{j['positionSn']}", p.get("location", "South Korea"),
                        j.get("startDateTime"), tags)
            item["_sn"] = j["positionSn"]
            out.append(item)
        if page >= (d.get("pagination") or {}).get("totalPages", 1):
            break
        time.sleep(0.5)
    if p.get("detail", True):  # description = HTML body of the announcement (sometimes only an image)
        for item in out[:MAX_DETAIL_CALLS]:
            try:
                x = s.get(f"{api}/position/v2/jobflex/{item['_sn']}", headers=hdr, timeout=TIMEOUT).json()
                item["desc"] = (item["desc"] + "\n" + _text(x.get("jobDescription"))).strip()
            except Exception:
                pass
            time.sleep(0.3)
    for item in out:
        item.pop("_sn", None)
    return out


# ---------------------------------------------------------------- Hyundai Motor / Kia (talent.hyundai.com, career.kia.com)
# p = {"base": "https://talent.hyundai.com", "svc": "HM", "hgr": "1", "ext": "hc"}
#     {"base": "https://career.kia.com",     "svc": "KM", "hgr": "2", "ext": "kc"}
# The HTML pages sit behind a NetFunnel queue; the JSON API behind them does not.

def hkmc(s, p):
    base, svc = p["base"], p["svc"]
    out = []
    for page in range(1, 6):
        # Kia answers 400 unless the X-HKMC-TOKEN header (the browser sends "null" when logged out) is present
        r = s.get(f"{base}/api/rec/AP-{svc}-FO-02700",
                  headers={"X-HKMC-SERVICE": svc, "X-HKMC-TOKEN": "null", "Accept": "application/json",
                           "Content-Type": "application/x-www-form-urlencoded"},
                  params={"hgrCd": p["hgr"], "lang": "ko", "page": page, "pageblock": 100, "searchSectorList": "",
                          "searchSecList": "", "searchFieldList": "", "searchOccupList": "", "searchPlaceList": "",
                          "searchText": ""}, timeout=TIMEOUT)
        r.raise_for_status()
        d = r.json()["data"]
        rows = d.get("list") or []
        for j in rows:
            url = (f"{base}/apply/applyView.{p['ext']}?recuYy={j['recuYy']}&recuType={j['recuType']}"
                   f"&recuCls={j['recuCls']}")
            desc = " / ".join(filter(None, [j.get("secCodeNm"), j.get("jdRecuCateNm"), j.get("fldCodeNm")]))
            out.append(_job(j["recuNoticeNm"], url, j.get("workPlaceCodeNm") or "South Korea", j.get("applyStartDt"), desc))
        if not rows or len(out) >= int(d.get("listCnt") or 0):
            break
        time.sleep(0.5)
    return out


# ---------------------------------------------------------------- Samsung group (samsungcareers.com)  p = {}

def samsung(s, p):
    out = []
    for page in range(1, 11):
        t = s.post("https://www.samsungcareers.com/hr/list.data",
                   data={"currentPageNo": page, "intNo": 0, "strVal": "", "strTxt": "", "strKey": "", "strCompany": "",
                         "strType": "", "strOrderBy": "", "strEntity": ""}, timeout=TIMEOUT).text
        for li in re.findall(r"<li>(.*?)</li>", t, re.S):
            m = re.search(r'<a href="/#none"\s+data-value="([\d,]+)">\s*<p class="company">(.*?)</p>\s*'
                          r'<h3 class="title">(.*?)</h3>', li, re.S)
            if not m:
                continue
            no = m.group(1).replace(",", "")
            period = re.search(r'class="period">\s*([^<]+?)\s*<', li)
            flags = re.findall(r'class="flag grey">([^<]+)<', li)
            out.append(_job(f"[{_text(m.group(2))}] {_text(m.group(3))}", f"https://www.samsungcareers.com/hr/?no={no}",
                            "South Korea", period.group(1) if period else "", ", ".join(flags)))
        mx = re.search(r'class="divCnt"[^>]*data-max="(\d+)"', t)
        if not mx or page >= int(mx.group(1)):
            break
        time.sleep(0.5)
    return out


# ---------------------------------------------------------------- LG group (careers.lg.com)  p = {}

def lgcareers(s, p):
    r = s.post("https://api.careers.lg.com/rmk/job/retrieveJobNoticesList",
               json={"careerList": [], "jobGroupList": [], "desireLocList": [], "companyCodeList": []},
               headers={"Origin": "https://careers.lg.com", "Referer": "https://careers.lg.com/"}, timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(f"[{j.get('companyName')}] {j['jobNoticeName']}", f"https://careers.lg.com/apply/detail?id={j['jobNoticeId']}",
                 j.get("workLocationName"), j.get("recEndDateTime"),
                 " / ".join(filter(None, [j.get("careerTypeName"), j.get("jobGroupName"), j.get("hashtagText")])))
            for j in r.json()["data"]["jobNoticeList"] if j.get("noticeStatus") == "POSTING"]


# ---------------------------------------------------------------- SK group (skcareers.com)  p = {}

def skcareers(s, p):
    r = s.post("https://www.skcareers.com/Recruit/GetRecruitList",  # 'sort' must be non-empty or the site 404s
               data={"sort": "2", "searchText": "", "corpCode": "", "jobRole": "", "recruitType": "", "workingType": "",
                     "workingRegion": ""}, headers={"X-Requested-With": "XMLHttpRequest"}, timeout=TIMEOUT)
    r.raise_for_status()
    return [_job(f"[{j.get('corpName')}] {j['title']}", f"https://www.skcareers.com/Recruit/Detail/{j['noticeID']}",
                 j.get("workingArea") or "South Korea", j.get("start"),
                 " / ".join(filter(None, [j.get("jobRole"), j.get("recruitType"), j.get("workingType")])))
            for j in r.json().get("list") or []]


# ---------------------------------------------------------------- Hanwha group (hanwhain.com)  p = {}

def hanwha(s, p):
    out = []
    for page in range(0, 5):
        body = {"langCd": "KO", "searchText": "", "sdSeqList": None, "rtNrcrtYn": "", "rtCarrYn": "", "rtIntnYn": "",
                "rtPermanentWorkYn": "", "rtTempWorkYn": "", "djSeqList": None, "rjSeqList": None, "page": page, "size": 100}
        r = s.post("https://hwadm.hanwhain.com/new-backend/portal/api/rcRecruit/search-rcrt", json=body,
                   headers={"Origin": "https://www.hanwhain.com", "Referer": "https://www.hanwhain.com/"}, timeout=TIMEOUT)
        r.raise_for_status()
        d = r.json()["data"]
        for j in d.get("list") or []:
            tags = ", ".join(str(t.get("tagNm") or t) for t in j.get("tagList") or [] if t)
            out.append(_job(f"[{j.get('sdNm')}] {j['rtNm']}", f"https://www.hanwhain.com/apply/recruit/detail?rtSeq={j['rtSeq']}",
                            "South Korea", j.get("rtAcptStrtDttm"), tags))
        if not d.get("hasNext"):
            break
        time.sleep(0.5)
    return out


# ---------------------------------------------------------------- Doosan, POSCO, small boards, GreetingHR

def doosan(s, p):
    """career.doosan.com (Doosan group: Enerbility, Bobcat, Robotics, Fuel Cell, Tesna...): server-rendered list, one page."""
    t = s.get("https://career.doosan.com/dsp/sa/RecList.jsp", timeout=TIMEOUT).text
    out = []
    for rid, mgt, typ, comp, body, btn in re.findall(
            r"onclick=\"goDetail\('(\d+)', '(\w+)', '(\w+)', '(\w+)'\);\" class=\"list-tit\">(.*?)</a>\s*<div class=\"btn-box\">(.*?)</div>",
            t, re.S):
        if "disabled" in btn:  # 접수마감 (closed)
            continue
        co = _text((re.search(r'<div class="company">(.*?)</div>', body, re.S) or [None, ""])[1])
        title = _text((re.search(r"<strong[^>]*>(.*?)</strong>", body, re.S) or [None, ""])[1])
        period = _text((re.search(r'<div class="deadline">(.*?)</div>', body, re.S) or [None, ""])[1])
        # detail opens via JS form post; list URL + fragment keeps each job unique
        out.append(_job(f"{title} ({co})", f"https://career.doosan.com/dsp/sa/RecList.jsp#REC_ID={rid}", "South Korea",
                        period.split("~")[0].split()[-1] if "~" in period else "", f"{co} {period}"))
    return out


def posco(s, p):
    """recruit.posco.com (POSCO group). JSON list; host sometimes times out on connect, so retry once."""
    params = {"rowCount": 100, "pageSize": 10, "currPage": 1, "offset": 0, "SEARCH_TYPE": "", "SEARCH_ORDER": "s1",
              "SEARCH_KEYWORD": ""}
    for attempt in range(2):
        try:
            r = s.get("https://recruit.posco.com/h22a01-recruit/H22A1000/list", params=params,
                      headers={"AJAX": "true", "Accept": "application/json"}, timeout=40)
            break
        except requests.exceptions.ConnectionError:
            if attempt:
                raise
            time.sleep(10)
    r.raise_for_status()
    return [_job(f"{j['HR_AFTC_MRG_ADOP_NTIC_SUJX']} ({j.get('COMPANY_NAME', '')})",
                 f"https://recruit.posco.com/h22a01-front/H22A1001.html?id={j['HR_AFTC_MRG_ADOP_NTIC_ID']}",
                 "South Korea", None,
                 f"{j.get('RECU_FIELD') or ''} | {j.get('HR_ADOP_CDDT_ELCN_GRD_NM') or ''} | until {j.get('END_ACTIVE_DATE')}")
            for j in r.json().get("recuList") or []]


def board_php(s, p):
    """Simple PHP bulletin-board careers page (e.g. Woojin Industrial Systems wjis.co.kr/kr/recruit/recruit_list.php)."""
    base = p["url"]
    host = "/".join(base.split("/")[:3])
    t = s.get(base, timeout=TIMEOUT).content.decode("utf-8", "replace")
    out = []
    for row in t.split('<div class="bbs-list-row">')[1:]:
        m = re.search(r'<a href="([^"]*bgu=view[^"]*)">.*?<strong class="bbs-subject-txt">(.*?)</strong>', row, re.S)
        if not m:
            continue
        title = _text(m.group(2))
        date = (re.search(r'data-label="등록일">([^<]+)<', row) or [None, ""])[1].strip()
        loc = re.search(r"\(([^()]+)\)\s*$", title)
        out.append(_job(title, host + html.unescape(m.group(1)), loc.group(1) if loc else "South Korea", date))
    return out


def greetinghr(s, p):
    """GreetingHR career sites (*.career.greetinghr.com or custom domains): openings embedded in __NEXT_DATA__."""
    r = s.get(p["url"], timeout=TIMEOUT)
    r.raise_for_status()
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', r.text, re.S)
    if not m:
        return []
    host = "/".join(r.url.split("/")[:3])
    found = {}

    def walk(o):
        if isinstance(o, dict):
            if "openingId" in o and "title" in o:
                locs = []
                for jp in ((o.get("openingJobPosition") or {}).get("openingJobPositions") or []):
                    pl = jp.get("workspacePlace") or {}
                    if pl.get("place"):
                        locs.append(pl["place"])
                found[o["openingId"]] = _job(o["title"], f"{host}/ko/o/{o['openingId']}", "; ".join(dict.fromkeys(locs)),
                                             o.get("openDate"))
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(json.loads(m.group(1)))
    return list(found.values())


FULL_LIST = {"jobflex": jobflex, "hkmc": hkmc, "samsung": samsung, "lgcareers": lgcareers, "skcareers": skcareers,
             "hanwha": hanwha, "doosan": doosan, "posco": posco, "board_php": board_php,
             "greetinghr": greetinghr}
KEYWORD = {}


