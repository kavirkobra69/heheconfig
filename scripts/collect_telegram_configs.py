#!/usr/bin/env python3
"""Collect V2Ray-compatible links from Telegram channels using the authenticated Telegram API."""
import asyncio
import json
import os
import re
import sys
from pathlib import Path

from telethon import TelegramClient, utils
from telethon.sessions import StringSession

ROOT = Path(__file__).resolve().parent.parent
CHANNELS_FILE = ROOT / "telegram_channels.txt"
OUTPUT_FILE = ROOT / "configs.json"
LINK_RE = re.compile(r"""(?:vmess|vless|trojan|ss|hysteria2|hy2|tuic)://[^\s<>"'\x60]+""", re.I)
MESSAGES_PER_CHANNEL = 1000


def read_channels():
    if not CHANNELS_FILE.exists():
        return []
    channels = []
    for raw in CHANNELS_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        line = re.sub(r"^https?://t\.me/", "", line).strip().strip("/")
        line = line.removeprefix("@")
        # Accept numeric Telegram IDs (usually -100xxxxxxxxxx) or usernames.
        if re.fullmatch(r"-?\d+", line) or re.fullmatch(r"[A-Za-z0-9_]{4,64}", line):
            channels.append(line)
        else:
            print(f"Skipping invalid channel entry: {raw!r}", file=sys.stderr)
    return list(dict.fromkeys(channels))


def extract_links(text):
    if not text:
        return set()
    return {link.rstrip(".,;)}]") for link in LINK_RE.findall(text)}


async def resolve_channel(client, value, dialogs_by_peer, dialogs_by_id):
    if not re.fullmatch(r"-?\d+", value):
        return await client.get_entity(value)

    numeric_id = int(value)
    # Dialog IDs often use the marked form -100<channel_id>; Telethon entity IDs
    # use the underlying positive channel ID. Prefer entities already in dialogs,
    # since Telegram requires an access_hash for channels.
    if numeric_id in dialogs_by_peer:
        return dialogs_by_peer[numeric_id]
    underlying_id = str(abs(numeric_id))
    if underlying_id.startswith("100") and len(underlying_id) > 10:
        underlying_id = underlying_id[3:]
    elif numeric_id < 0:
        underlying_id = str(abs(numeric_id))
    try:
        return await client.get_entity(int(underlying_id))
    except (ValueError, TypeError):
        pass
    if int(underlying_id) in dialogs_by_id:
        return dialogs_by_id[int(underlying_id)]
    raise ValueError(
        f"Cannot resolve numeric channel ID {value}. Make sure the Telegram account "
        "behind TG_SESSION has joined/opened that channel at least once, then retry."
    )


async def main():
    api_id = os.environ.get("TG_API_ID", "").strip()
    api_hash = os.environ.get("TG_API_HASH", "").strip()
    session = os.environ.get("TG_SESSION", "").strip()
    if not api_id.isdigit() or not api_hash or not session:
        print("Missing or invalid TG_API_ID, TG_API_HASH, or TG_SESSION GitHub Actions secrets.", file=sys.stderr)
        return 1

    channels = read_channels()
    if not channels:
        print("No channels configured. Add numeric channel IDs or public usernames to telegram_channels.txt.")
        return 0

    try:
        old_data = json.loads(OUTPUT_FILE.read_text(encoding="utf-8")) if OUTPUT_FILE.exists() else []
        if not isinstance(old_data, list):
            raise ValueError("configs.json must contain a JSON array")
        configs = {item for item in old_data if isinstance(item, str)}
    except (json.JSONDecodeError, OSError, ValueError) as exc:
        print(f"Cannot read existing configs.json: {exc}", file=sys.stderr)
        return 1

    client = TelegramClient(StringSession(session), int(api_id), api_hash)
    try:
        await client.connect()
        if not await client.is_user_authorized():
            print("TG_SESSION is not authorized. Generate a new StringSession and update the GitHub secret.", file=sys.stderr)
            return 1

        dialogs_by_peer = {}
        dialogs_by_id = {}
        async for dialog in client.iter_dialogs():
            entity = dialog.entity
            dialogs_by_peer[dialog.id] = entity
            dialogs_by_peer[utils.get_peer_id(entity)] = entity
            if getattr(entity, "id", None) is not None:
                dialogs_by_id[int(entity.id)] = entity

        before_count = len(configs)
        for channel_value in channels:
            try:
                entity = await resolve_channel(client, channel_value, dialogs_by_peer, dialogs_by_id)
                label = getattr(entity, "title", None) or getattr(entity, "username", None) or channel_value
                found = set()
                async for message in client.iter_messages(entity, limit=MESSAGES_PER_CHANNEL):
                    found.update(extract_links(message.message))
                configs.update(found)
                print(f"{label} ({channel_value}): scanned up to {MESSAGES_PER_CHANNEL} messages; found {len(found)} config(s)")
            except Exception as exc:
                print(f"WARNING: channel {channel_value} failed: {type(exc).__name__}: {exc}", file=sys.stderr)

        ordered = sorted(configs)
        OUTPUT_FILE.write_text(json.dumps(ordered, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Total unique configs: {len(ordered)}; added this run: {len(ordered) - before_count}")
        if not ordered:
            print("WARNING: configs.json is still empty. Check channel IDs, channel membership, and recent messages.", file=sys.stderr)
        return 0
    finally:
        await client.disconnect()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
