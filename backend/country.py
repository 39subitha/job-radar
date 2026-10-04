"""Best-effort country name from the messy location strings career sites return."""
import re

NAMES = {
    "India": ["india"], "United States": ["united states", "usa", "u.s."], "United Kingdom": ["united kingdom", "england", "scotland", "wales", "great britain"],
    "Germany": ["germany", "deutschland"], "France": ["france"], "Spain": ["spain", "españa"], "Italy": ["italy", "italia"],
    "Canada": ["canada"], "Mexico": ["mexico", "méxico"], "Brazil": ["brazil", "brasil"], "China": ["china"], "Japan": ["japan"],
    "South Korea": ["south korea", "korea"], "Singapore": ["singapore"], "Malaysia": ["malaysia"], "Thailand": ["thailand", "bangkok"],
    "Vietnam": ["vietnam", "viet nam"], "Indonesia": ["indonesia"], "Philippines": ["philippines"], "Australia": ["australia"],
    "New Zealand": ["new zealand"], "United Arab Emirates": ["united arab emirates", "uae", "dubai", "abu dhabi"],
    "Saudi Arabia": ["saudi arabia", "riyadh", "jeddah"], "Qatar": ["qatar", "doha"], "Egypt": ["egypt"], "Morocco": ["morocco", "maroc"],
    "South Africa": ["south africa"], "Poland": ["poland", "polska"], "Czech Republic": ["czech", "czechia"], "Hungary": ["hungary"],
    "Romania": ["romania"], "Portugal": ["portugal"], "Netherlands": ["netherlands"], "Belgium": ["belgium"], "Switzerland": ["switzerland", "schweiz", "suisse"],
    "Austria": ["austria", "österreich"], "Sweden": ["sweden"], "Norway": ["norway"], "Finland": ["finland"], "Denmark": ["denmark"],
    "Ireland": ["ireland"], "Turkey": ["turkey", "türkiye"], "Slovakia": ["slovakia"], "Serbia": ["serbia"], "Bulgaria": ["bulgaria"],
    "Taiwan": ["taiwan"], "Israel": ["israel"], "Argentina": ["argentina"], "Chile": ["chile"], "Colombia": ["colombia"],
    "Tunisia": ["tunisia"], "Jordan": ["jordan"], "Ukraine": ["ukraine", "kyiv", "lviv"], "Kazakhstan": ["kazakhstan"],
}
ISO2 = {"US": "United States", "GB": "United Kingdom", "UK": "United Kingdom", "DE": "Germany", "FR": "France", "ES": "Spain",
        "IT": "Italy", "CA": "Canada", "MX": "Mexico", "BR": "Brazil", "CN": "China", "JP": "Japan", "KR": "South Korea",
        "SG": "Singapore", "MY": "Malaysia", "TH": "Thailand", "VN": "Vietnam", "AU": "Australia", "NZ": "New Zealand",
        "AE": "United Arab Emirates", "SA": "Saudi Arabia", "EG": "Egypt", "MA": "Morocco", "ZA": "South Africa", "PL": "Poland",
        "CZ": "Czech Republic", "HU": "Hungary", "RO": "Romania", "PT": "Portugal", "NL": "Netherlands", "BE": "Belgium",
        "CH": "Switzerland", "AT": "Austria", "SE": "Sweden", "IE": "Ireland", "TR": "Turkey", "SK": "Slovakia", "IN": "India"}
ISO3 = {"USA": "United States", "GBR": "United Kingdom", "DEU": "Germany", "FRA": "France", "ESP": "Spain", "ITA": "Italy",
        "CAN": "Canada", "MEX": "Mexico", "CHN": "China", "IND": "India", "DOM": "Dominican Republic", "POL": "Poland",
        "CZE": "Czech Republic", "BRA": "Brazil", "JPN": "Japan", "KOR": "South Korea", "AUS": "Australia", "NLD": "Netherlands"}
US_STATES = {"AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
             "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
             "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "PR", "DC"}
US_STATE_NAMES = ["alabama", "arizona", "california", "colorado", "connecticut", "florida", "georgia", "illinois", "indiana", "iowa",
                  "kansas", "kentucky", "louisiana", "maryland", "massachusetts", "michigan", "minnesota", "mississippi", "missouri",
                  "nebraska", "nevada", "new hampshire", "new jersey", "new mexico", "new york", "north carolina", "ohio", "oklahoma",
                  "oregon", "pennsylvania", "south carolina", "tennessee", "texas", "utah", "virginia", "west virginia", "washington",
                  "wisconsin", "puerto rico"]
CA_PROV = {"QC", "ON", "BC", "AB", "MB", "NS", "SK", "NB"}
CITIES = {
    "Spain": ["fuenlabrada", "madrid", "getafe", "illescas", "martos", "barcelona", "sevilla", "seville", "beasain", "zaragoza", "cadiz", "albacete", "irun", "rivabellosa"],
    "Italy": ["torino", "turin", "milan", "milano", "rome", "roma", "naples", "napoli", "pistoia", "brescia"],
    "France": ["rochefort", "trappes", "tarnos", "meroux", "saint nazaire", "paris", "toulouse", "vénissieux", "venissieux", "annonay", "belfort", "valenciennes", "la rochelle", "reichshoffen", "marignane", "nantes", "lyon", "bordeaux", "tarbes", "le creusot", "ornans", "petite-forêt", "crespin", "aytré", "saint-nazaire", "blagnac", "rennes", "lille", "grenoble", "villeurbanne", "strasbourg", "mulhouse", "metz"],
    "Germany": ["berlin", "munich", "münchen", "hamburg", "hennigsdorf", "kassel", "bautzen", "görlitz", "salzgitter", "nürnberg", "nuremberg", "erlangen", "krefeld", "stuttgart", "friedrichshafen", "herzogenaurach", "schweinfurt", "bremen", "regensburg"],
    "United Kingdom": ["bristol", "derby", "london", "glasgow", "manchester", "birmingham, uk", "filton", "broughton", "inchinnan", "crewe", "belfast", "coventry"],
    "Canada": ["montreal", "montréal", "toronto", "mississauga", "la pocatière", "kingston", "thunder bay", "vancouver"],
    "Egypt": ["cairo"], "Morocco": ["casablanca", "tangier", "fes"], "Malaysia": ["penang", "kuala lumpur"],
    "China": ["wuhan", "shandong", "shanghai", "beijing", "suzhou", "changzhou", "tianjin", "qingdao", "shenzhen", "xi'an", "chongqing"],
    "Mexico": ["toluca", "nuevo león", "nuevo leon", "queretaro", "querétaro", "monterrey", "saltillo", "chihuahua", "ciudad sahagún", "juarez", "guadalajara"],
    "Australia": ["bendigo", "perth", "melbourne", "sydney", "brisbane", "dandenong", "maryborough"],
    "United States": ["indianapolis", "evendale", "aiken", "lynn", "hornell", "plattsburgh", "hawthorne", "seattle", "everett", "st. louis", "berkeley, mo", "cincinnati", "erie", "grain valley", "fort worth", "houston", "huntsville", "wichita", "starbase", "mcgregor", "costa mesa", "long beach", "chicago", "peoria", "detroit"],
    "Poland": ["chrzanow", "wrocław", "wroclaw", "katowice", "kraków", "krakow", "gdańsk", "siedlce"],
    "Czech Republic": ["rakovnik", "ostrava", "praha", "prague", "plzeň"], "Singapore": ["singapore"],
    "Netherlands": ["hengelo", "amsterdam", "eindhoven"], "Belgium": ["grace-hollogne", "charleroi", "brugge"],
    "South Korea": ["seoul", "changwon", "uiwang"], "Japan": ["tokyo", "osaka", "kobe"],
}
_SPLIT = re.compile(r"[,;/|~\-–()]")


def _has(text, word):
    return re.search(r"(?<![a-z])" + re.escape(word) + r"(?![a-z])", text) is not None


def country_of(location, is_india=False):
    if is_india:
        return "India"
    loc = (location or "").strip()
    if not loc:
        return "Other"
    low = loc.lower()
    # Workday/RTX style "US-FL-MELBOURNE..." or "USA - Berkeley, MO" or "United States-California-..."
    if re.match(r"^(US|USA)\b", loc) or low.startswith("united states"):
        return "United States"
    m = re.match(r"^([A-Z]{2})-[A-Z]{2,4}-", loc)   # Collins style "GB-WLV-WOLVERHAMPTON-001"
    if m and m.group(1) in ISO2:
        return ISO2[m.group(1)]
    for country, words in NAMES.items():
        if any(_has(low, w) for w in words):
            return country
    for country, cities in CITIES.items():
        if any(_has(low, c) for c in cities):
            return country
    if any(_has(low, s) for s in US_STATE_NAMES):
        return "United States"
    # drop trailing postal codes like "H4R 1K2", "21157", "S817DJ"
    tokens = [t.strip() for t in loc.split(",") if t.strip() and not re.search(r"\d", t)]
    last = tokens[-1] if tokens else ""
    if last in ISO3:
        return ISO3[last]
    if re.fullmatch(r"[A-Z]{2}", last):
        if last == "DE":
            return "Germany"          # Delaware is rare in these feeds
        if last in US_STATES:
            return "United States"    # includes CA (California), IN (Indiana), MA, GA
        if last in CA_PROV:
            return "Canada"
        if last in ISO2:
            return ISO2[last]
    if re.search(r"\b\d{5}\b", loc) and "," not in loc:
        return "Other"
    if "remote" in low:
        return "Remote"
    return "Other"
