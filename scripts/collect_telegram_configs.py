#!/usr/bin/env python3
"""Collect V2Ray-compatible links from recent public Telegram channel previews."""
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from html import unescape

ROOT = Path(__file__).resolve().parent.parent
CHANNELS_FILE = ROOT / "telegram_channels.txt"
OUTPUT_FILE = ROOT / "configs.json"
LINK_RE = re.compile(r"(?:vmess|vless|trojan|ss|hysteria2|hy2|tuic)://[^\\s<>\\\"'\\x60]+", re.I)
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"
# Scan several pages on every run so the first run can import older posts too.
MAX_PAGES_PER_CHANNEL = 12


def clean_html(value: str) -> str:
    value = re.sub(r"<br\\s*/?>", "\\n", value, flags=re.I)
    value = re.sub(r"<[^>]+>", " ", value)
    return unescape(value)


def get_html(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"},
    )
    with urllib.request.urlopen(request, timeout=25) as response:
        return response.read().decode("utf-8", errors="replace")


def read_channels():
    if not CHANNELS_FILE.exists():
        return []
    channels = []
    for raw in CHANNELS_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        line = re.sub(r"^https?://t\\.me/", "", line).strip().strip("/")
        line = line.removeprefix("@")
        if re.fullmatch(r"[A-Za-z0-9_]{4,64}", line):
            channels.append(line)
        else:
            print(f"Skipping invalid channel entry: {raw!r}", file=sys.stderr)
    return list(dict.fromkeys(channels))


def collect_channel(channel: str):
    found = set()
    before = None
    for page in range(MAX_PAGES_PER_CHANNEL):
        url = f"https://t.me/s/{channel}" + (f"?before={before}" if before else "")
        try:
            html = get_html(url)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            print(f"WARNING: @{channel} page {page + 1} failed: {exc}", file=sys.stderr)
            break

        chunks = re.split(r"tgme_widget_message_wrap", html)[1:]
        if not chunks:
            print(
                f"WARNING: no public preview posts found for @{channel}; "
                "the channel may be private, unavailable, or blocked from GitHub Actions.",
                file=sys.stderr,
            )
            break

        oldest_ts = None
        oldest_id = None
        for chunk in chunks:
            tm = re.search(r'<time[^>]*datetime="([^"]+)"', chunk)
            if not tm:
                continue
            try:
                ts = int(datetime.fromisoformat(tm.group(1).replace("Z", "+00:00")).timestamp())
            except ValueError:
                continue

            id_match = re.search(r'data-post="[^"/]+/(\\d+)"', chunk)
            post_id = int(id_match.group(1)) if id_match else None
            if oldest_ts is None or ts < oldest_ts:
                oldest_ts, oldest_id = ts, post_id

            # Don't filter by age: the first run must also collect configs posted
            # more than eight hours ago. Existing configs are deduplicated below.
            for link in LINK_RE.findall(clean_html(chunk)):
                found.add(link.rstrip(".,;)}]"))

        if oldest_ts is None or not oldest_id or oldest_id == before:
            break
        before = oldest_id

    print(f"@{channel}: found {len(found)} config(s) in up to {MAX_PAGES_PER_CHANNEL} pages")
    return found


def main():
    channels = read_channels()
    if not channels:
        print("No channels configured. Add public channel usernames to telegram_channels.txt.")
        return

    try:
        old_data = json.loads(OUTPUT_FILE.read_text(encoding="utf-8")) if OUTPUT_FILE.exists() else []
        if not isinstance(old_data, list):
            raise ValueError("configs.json must contain a JSON array")
        configs = set(item for item in old_data if isinstance(item, str))
    except (json.JSONDecodeError, OSError, ValueError) as exc:
        print(f"Cannot read existing configs.json: {exc}", file=sys.stderr)
        sys.exit(1)

    before_count = len(configs)
    for channel in channels:
        configs.update(collect_channel(channel))
        time.sleep(1)

    ordered = sorted(configs)
    OUTPUT_FILE.write_text(json.dumps(ordered, ensure_ascii=False, indent=2) + "\\n", encoding="utf-8")
    print(f"Total unique configs: {len(ordered)}; added this run: {len(ordered) - before_count}")
    if not ordered:
        print(
            "WARNING: configs.json is still empty. Check the Actions log above; "
            "this collector reads only links visible in public t.me/s previews."
        )


if __name__ == "__main__":
    main()
