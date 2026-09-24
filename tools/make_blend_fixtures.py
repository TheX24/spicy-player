"""Blend fixtures for the Kotlin port: real documents, and what mild-lyrics makes of them.

    python tools/make_blend_fixtures.py build/blend-fixtures <spotify track id>...

Needs the mild-lyrics checkout at ~/Projects/refs/mild-lyrics; reads its Spicy Lyrics cache
(%LOCALAPPDATA%/mild-lyrics) where it has one. The output is full song lyrics: keep it out of git.
BlendParityTest reads it from build/blend-fixtures.

For each track: the base (Spicy Lyrics' cached copy, else BiniLyrics' Apple TTML, else LRCLIB),
the three donors as mild-lyrics' own providers shape them, and for every blend the document
mild-lyrics' _blend + stand_down (+ no_overlap, as its view applies) produces.
"""
import json, os, re, sys, urllib.request, pathlib

sys.path.insert(0, os.path.expanduser("~/Projects/refs/mild-lyrics/mild-lyrics"))
import lyric_sources as LS
import spicy_lyrics as SL

OUT = pathlib.Path(sys.argv[1])
TIDS = sys.argv[2:]
CACHE = pathlib.Path(os.environ["LOCALAPPDATA"]) / "mild-lyrics"

BLENDS = {
    "blend_qq": ("qq", None),
    "blend_kugou": ("kugou", None),
    "blend_netease": ("netease", None),
    "blend_netease_qq": ("netease", "qq"),
    "blend_netease_kugou": ("netease", "kugou"),
}
NAMES = {"qq": "QQ Music", "kugou": "Kugou", "netease": "NetEase"}


def meta_of(tid):
    html = urllib.request.urlopen(urllib.request.Request(
        "https://open.spotify.com/embed/track/" + tid, headers={"User-Agent": "Mozilla/5.0"}), timeout=15).read().decode()
    d = json.loads(re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html).group(1))
    e = d["props"]["pageProps"]["state"]["data"]["entity"]
    return {"title": e["name"], "artist": ", ".join(a["name"] for a in e.get("artists", [])),
            "length": e.get("duration", 0) / 1000.0, "album": ""}


def clean(doc):
    """Only what the port reads, zero-width spaces out (our TTML parser strips them)."""
    if not doc:
        return None
    doc = LS._stamped(SL.payload(doc))
    def syl(y):
        return {"Text": str(y.get("Text", "")).replace("​", ""), "StartTime": y["StartTime"],
                "EndTime": y.get("EndTime", y["StartTime"]), "IsPartOfWord": bool(y.get("IsPartOfWord")),
                **({"Guess": True} if y.get("Guess") else {})}
    def group(g):
        if not isinstance(g, dict):
            return None
        out = {"Syllables": [syl(y) for y in g.get("Syllables") or [] if isinstance(y, dict)]}
        for k in ("StartTime", "EndTime"):
            if isinstance(g.get(k), (int, float)):
                out[k] = g[k]
        return out
    lines = []
    for it in LS._items(doc):
        line = {}
        if isinstance(it.get("Text"), str):
            line["Text"] = it["Text"].replace("​", "")
        for k in ("StartTime", "EndTime"):
            if isinstance(it.get(k), (int, float)):
                line[k] = it[k]
        if isinstance(it.get("Lead"), dict):
            line["Lead"] = group(it["Lead"])
        bg = it.get("Background")
        bg = bg if isinstance(bg, list) else [bg] if isinstance(bg, dict) else []
        if bg:
            line["Background"] = [g for g in map(group, bg) if g]
        lines.append(line)
    out = {"Content": lines}
    for k in ("_via", "_alone"):
        if k in doc:
            out[k] = doc[k]
    return out


def base_of(tid, meta):
    rec = CACHE / "spicy" / (tid + ".json")
    if rec.exists():
        body = json.loads(rec.read_text(encoding="utf-8"))
        doc = SL.payload(body.get("doc") or body.get("body") or body)
        if LS.quality(doc) in ("line", "static"):
            return doc, LS._words_from(doc), "spicy"
    got = LS.from_bini(tid, meta)
    if got and LS.quality(got) in ("line", "static"):
        return got, "Apple Music", "bini"
    got = LS.from_lrclib(tid, meta)
    if got and LS.quality(got) != "none":
        return got, "LRCLIB", "lrclib"
    return None, None, None


def main():
    for tid in TIDS:
        try:
            meta = meta_of(tid)
        except Exception as e:
            print("meta", tid, e); continue
        base, words, origin = base_of(tid, meta)
        if not base:
            print("no line-level base", tid, meta["title"]); continue
        donors = {n: fn(tid, meta) for n, fn in (("qq", LS.from_qq), ("kugou", LS.from_kugou), ("netease", LS.from_netease))}
        base, donors = LS._stamped(base), {k: LS._stamped(v) for k, v in donors.items()}
        base_c = clean(base)
        donors_c = {k: clean(v) for k, v in donors.items()}
        expected = {}
        for bid, (timing, spare) in BLENDS.items():
            t = donors_c.get(timing)
            s = donors_c.get(spare) if spare else None
            if t is None and s is None:
                continue
            first = (t, NAMES[timing], timing)
            second = (s, NAMES[spare] if spare else "", spare or "")
            lead, fill = LS.in_order(base_c, first, second)
            out = LS._blend(base_c, words, lead[0], None, origin, lead[1], fill[0], fill[1])
            out = LS.stand_down(out, lead[0], base_c, lead[2])
            if out is not None:
                out = {**LS.no_overlap(out), **{k: out[k] for k in ("_via", "_alone") if k in out}}
            expected[bid] = clean(out)
        fixture = {"tid": tid, "meta": meta, "words": words, "origin": origin, "base": base_c,
                   "donors": donors_c, "expected": expected}
        (OUT / (tid + ".json")).write_text(json.dumps(fixture, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print("ok", tid, meta["title"], "|", origin, {k: bool(v) for k, v in donors_c.items()},
              {k: (v or {}).get("_via") or (v or {}).get("_alone") for k, v in expected.items()})


main()
