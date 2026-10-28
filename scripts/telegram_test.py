#!/usr/bin/env python3
"""Manual Telegram connection/message test for GitHub Actions."""
import asyncio
import os
import sys

from telethon import TelegramClient
from telethon.sessions import StringSession


async def main():
    api_id = os.environ.get("TG_API_ID", "").strip()
    api_hash = os.environ.get("TG_API_HASH", "").strip()
    session = os.environ.get("TG_SESSION", "").strip()
    mode = os.environ.get("TEST_MODE", "connection").strip()
    target_id = os.environ.get("TARGET_ID", "").strip()
    message = os.environ.get("TEST_MESSAGE", "✅ اتصال تلگرام از GitHub Actions موفق است.").strip()

    missing = [name for name, value in (
        ("TG_API_ID", api_id), ("TG_API_HASH", api_hash), ("TG_SESSION", session)
    ) if not value]
    if missing:
        print("ERROR: Missing GitHub Actions secrets: " + ", ".join(missing))
        sys.exit(2)

    try:
        client = TelegramClient(StringSession(session), int(api_id), api_hash)
        await client.connect()
        if not await client.is_user_authorized():
            print("ERROR: TG_SESSION is invalid or expired. Create a new StringSession.")
            sys.exit(3)

        me = await client.get_me()
        print(f"CONNECTED: Telegram authorization succeeded (account id={me.id}).")

        if mode == "connection":
            await client.send_message("me", message)
            print("SUCCESS: Test message sent to Saved Messages (Telegram 'me').")
        elif mode == "send_message":
            if not target_id:
                print("ERROR: Enter a target Telegram user/chat ID in the workflow form.")
                sys.exit(4)
            try:
                target = int(target_id)
            except ValueError:
                print("ERROR: TARGET_ID must be a numeric Telegram ID.")
                sys.exit(4)
            await client.send_message(target, message)
            print(f"SUCCESS: Message sent to target ID {target}.")
        else:
            print(f"ERROR: Unknown TEST_MODE: {mode}")
            sys.exit(4)
        await client.disconnect()
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}")
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
