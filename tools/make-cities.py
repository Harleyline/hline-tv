#!/usr/bin/env python3
"""Builds app/src/main/assets/cities-cn.tsv, the places the phone page's city box finds by their
Chinese name. Open-Meteo's geocoder knows few Chinese names: 商丘 and 泉州 found nothing, and 安庆
found a village in Jiangsu instead of the city in Anhui (issue #187).

    curl -O https://download.geonames.org/export/dump/{CN,TW,HK,MO}.zip && unzip {CN,TW,HK,MO}.zip
    python3 -I tools/make-cities.py CN.txt TW.txt HK.txt MO.txt

From GeoNames (CC BY 4.0, https://www.geonames.org): the capital, province and prefecture seats,
county seats, then county-level divisions whose name no seat has. One line per place:

    name  aliases(,)  province  prefecture  lat  lon  rank  ascii

name is the simplified Chinese name without 市/县/区 (the app strips the same from what is typed):
the one GeoNames lists most often in some form (成都, 成都市), aliases the others (天府, 沪),
rank 0 for the capital and province seats, 1 prefecture, 2 county, 3 county-level division, ascii
GeoNames' romanized name (what ipinfo.io calls the city). prefecture is the prefecture-level city
(or a province's own prefecture, or its directly-governed county-level division) in Chinese, so
the TV can walk a province, then a city, then its districts: the table is chosen three deep.

China's Hong Kong, Macao and Taiwan come from three files of their own: GeoNames keeps them as
countries, and their first administrative level is a district — Hong Kong's eighteen, Macao's
eight parishes — not a province. They are read apart and listed the way the mainland's are, as
the region, then its city (Hong Kong and Macao are one city each), then its towns (the owner,
1.74). Taiwan's counties and cities are its towns' level above, one step shallower than the
mainland's, where a province holds the cities and a city the towns. Their names come traditional
from GeoNames and are turned simplified below.
"""
import os
import re
import sys
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/cities-cn.tsv")
RANK = {"PPLC": 0, "PPLA": 0, "PPLA2": 1, "PPLA3": 2, "ADM3": 3}
# The three regions' own files: the region, and the level in it that holds the towns.
REGIONS = {"TW": ("台湾", "ADM3"), "HK": ("香港", "ADM1"), "MO": ("澳门", "ADM1")}
# Hong Kong and Macao are one city each: the region itself, as the place it is.
REGION_SEATS = {"HK": ("香港", 22.3193, 114.1694), "MO": ("澳门", 22.1987, 113.5439)}
# The Chinese names GeoNames has none of, or a less usual one: seven of Hong Kong's eighteen
# districts and Macao's busiest parish are listed in Korean and pinyin alone, and its Islands
# district otherwise comes out as 岛屿.
REGION_NAMES = {
    "Islands District": "离岛区",
    "Southern": "南区",
    "Yuen Long District": "元朗区",
    "Tsuen Wan District": "荃湾区",
    "Tai Po District": "大埔区",
    "Sai Kung District": "西贡区",
    "Sham Shui Po": "深水埗区",
    "Wong Tai Sin": "黄大仙区",
    "Nossa Senhora de Fatima": "花地玛堂区",
}
CJK = re.compile(r"^[一-鿿]+$")
SUFFIX = re.compile(r"(壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|自治州|自治县|地区|新区|省|市|县|区|盟|旗|镇|乡)$")
# Traditional characters that turn up in the three regions' names, and their simplified forms
# (zh-cn), so the list reads as the mainland's does: 台灣 there is 台湾, 桃園 桃园.
T2S = str.maketrans(
    "來內勢區員國圍園壇壢壯壽學寧寶將岡島峽崙嶼巒庫廟彌復恆愛東棲楊榮樂樹橋橫歸滿濃濟濱灣烏營獅瑪環結綠線縣"
    "羅義聖腳臺興莊華萬蓮蘆蘇蘭裏裡觀豐貢車軍連達邊鄉銅鎮鑼長門間關雙離雲霧頂順頭風館馬魚鳥鳳鶯鹽麥龍龜",
    "来内势区员国围园坛坜壮寿学宁宝将冈岛峡仑屿峦库庙弥复恒爱东栖杨荣乐树桥横归满浓济滨湾乌营狮玛环结绿线县"
    "罗义圣脚台兴庄华万莲芦苏兰里里观丰贡车军连达边乡铜镇锣长门间关双离云雾顶顺头风馆马鱼鸟凤莺盐麦龙龟",
)


def simplified(name):
    # Traditional characters are outside GB2312; simplified place names are inside it.
    try:
        name.encode("gb2312")
        return True
    except UnicodeEncodeError:
        return False


def short(name):
    s = SUFFIX.sub("", name)
    return s if len(s) >= 2 else name


def chinese_names(alternates):
    names = [n for n in alternates.split(",") if CJK.match(n)]
    return [n for n in names if simplified(n)] or names


def region_names(alternates, ascii_name):
    """The Chinese names of a place in Hong Kong, Macao or Taiwan, simplified."""
    if ascii_name in REGION_NAMES:
        return [REGION_NAMES[ascii_name]]
    return [n.translate(T2S) for n in alternates.split(",") if CJK.match(n)]


def forms(names):
    """The one name to show: the form GeoNames lists most often, short of its 市/县/区."""
    counted = Counter(short(n) for n in names)
    return max(counted, key=lambda n: (counted[n], len(n) >= 2, -len(n)))


def rows_of(path):
    for line in open(path, encoding="utf-8"):
        yield line.rstrip("\n").split("\t")


def mainland(path):
    """The mainland: its provinces, the prefecture each place's seat stands for, its places."""
    provinces, prefectures, rows = {}, {}, []
    for c in rows_of(path):
        code, admin1 = c[7], c[10]
        if code == "ADM1":
            # Not the one-character abbreviations (豫, 闽).
            names = [short(n) for n in chinese_names(c[3]) if len(short(n)) >= 2]
            if names:
                provinces[admin1] = min(names, key=len)
        elif code == "ADM2":
            # The prefecture-level city: its Chinese name (its own is romanized, 玉树藏族自治州).
            names = [short(n) for n in chinese_names(c[3]) if len(short(n)) >= 2]
            if names:
                prefectures[admin1 + "." + c[11]] = min(names, key=len)
        elif code in RANK:
            names = chinese_names(c[3])
            if names:
                # (rank, people, the names, the province, the prefecture, lat, lon, ascii)
                rows.append((RANK[code], -int(c[14] or 0), names, admin1, float(c[4]), float(c[5]), c[2], c[11]))
    out = []
    for rank, people, names, admin1, lat, lon, ascii_name, admin2 in rows:
        # A place with no prefecture of its own belongs to its province's seat: with none there
        # either, the province stands in, so every place can still be reached three deep.
        seat = prefectures.get(admin1 + "." + admin2, "") or prefectures.get(admin1 + ".", "")
        out.append((rank, people, names, provinces.get(admin1, ""), seat or provinces.get(admin1, ""), lat, lon, ascii_name))
    return out


def region(path, region_name, town_code):
    """Hong Kong, Macao or Taiwan: the region as its province, its cities, and their towns."""
    country = os.path.basename(path)[:2]
    seats, towns = {}, []
    for c in rows_of(path):
        names = region_names(c[3], c[2])
        if not names:
            continue
        short_names = [short(n) for n in names if len(short(n)) >= 2]
        if not short_names:
            continue
        # Taiwan's counties and cities are the level above its towns, and are places themselves.
        if c[7] == "ADM2":
            # 新竹市 and 新竹县, 嘉义市 and 嘉义县: one city in the list, and the city rather
            # than the county (it comes first by its romanized name).
            seats.setdefault(c[11], (min(short_names, key=len), c[2], float(c[4]), float(c[5])))
        elif c[7] == town_code:
            towns.append((3, 0, short_names, region_name, seats.get(c[11], (region_name,))[0],
                          float(c[4]), float(c[5]), c[2]))
    # Hong Kong and Macao have no cities under them: the region is its own one, and its towns
    # are its districts.
    if country in REGION_SEATS:
        name, lat, lon = REGION_SEATS[country]
        cities = [(name, name, lat, lon)]
    else:
        by_name = {}
        for name, ascii_name, lat, lon in sorted(seats.values(), key=lambda v: v[1]):
            by_name.setdefault(name, (name, ascii_name, lat, lon))
        cities = list(by_name.values())
    return [(1, 0, [name], region_name, name, lat, lon, ascii_name) for name, ascii_name, lat, lon in cities] + towns


rows = []
for path in sys.argv[1:]:
    country = os.path.basename(path)[:2]
    if country in REGIONS:
        region_name, town_code = REGIONS[country]
        rows += region(path, region_name, town_code)
    else:
        rows += mainland(path)

rows.sort(key=lambda r: (r[0], r[1]))
seen = set()
out = []
for rank, _, names, province, prefecture, lat, lon, ascii_name in rows:
    name = forms(names)
    # One town per name under a city: a county-level division named like the seat it belongs to
    # is that seat, and the TV shows names alone, so two places of one name in one city could
    # not be told apart there anyway.
    if (name, prefecture) in seen:
        continue
    seen.add((name, prefecture))
    aliases = ",".join(sorted({short(n) for n in names} - {name}))
    out.append(f"{name}\t{aliases}\t{province}\t{prefecture}\t{lat:.4f}\t{lon:.4f}\t{rank}\t{ascii_name}")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("# GeoNames (CC BY 4.0), tools/make-cities.py\n")
    f.write("\n".join(out) + "\n")
print(f"{len(out)} places, {os.path.getsize(OUT)} bytes -> {OUT}")
