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
    message = os.environ.get(
        "TEST_MESSAGE", "✅ اتصال تلگرام از GitHub Actions موفق است."
    ).strip()

    missing = [
        name for name, value in (
            ("TG_API_ID", api_id),
            ("TG_API_HASH", api_hash),
            ("TG_SESSION", session),
        ) if not value
    ]
    if missing:
        print("ERROR: Missing GitHub Actions secrets: " + ", ".join(missing))
        sys.exit(2)

    try:
        api_id_int = int(api_id)
    except ValueError:
        print("ERROR: TG_API_ID must be numeric. Copy the API ID from my.telegram.org.")
        sys.exit(2)

    client = TelegramClient(StringSession(session), api_id_int, api_hash)
    try:
        print("Connecting to Telegram...")
        await client.connect()

        if not await client.is_user_authorized():
            print("ERROR: TG_SESSION is not authorized or has expired.")
            sys.exit(3)

        me = await client.get_me()
        print(f"CONNECTED: Authorized as account ID {me.id}.")

        if mode == "connection":
            await client.send_message("me", message)
            print("SUCCESS: Message sent to your Saved Messages.")
            return

        if mode != "send_message":
            print(f"ERROR: Unknown TEST_MODE: {mode}")
            sys.exit(4)

        if not target_id:
            print("ERROR: Enter a target ID or @username in the workflow form.")
            sys.exit(4)

        # Usernames can be resolved by Telegram. Numeric IDs generally require
        # a peer entity/access hash already known to this account.
        if target_id.startswith("@"):
            target = await client.get_entity(target_id)
        elif target_id.lstrip("-").isdigit():
            numeric_id = int(target_id)
            if numeric_id == me.id:
                target = "me"
            else:
                target = None
                async for dialog in client.iter_dialogs():
                    if getattr(dialog.entity, "id", None) == numeric_id:
                        target = dialog.entity
                        break
                if target is None:
                    print(
                        "ERROR: This numeric ID is not in the account's available dialogs. "
                        "Try the person's @username, or open/start a chat with them first "
                        "and rerun the workflow."
                    )
                    sys.exit(4)
        else:
            print("ERROR: Use a numeric ID or a public @username.")
            sys.exit(4)

        sent = await client.send_message(target, message)
        print(f"SUCCESS: Telegram accepted the message (message ID {sent.id}).")
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}")
        sys.exit(1)
    finally:
        if client.is_connected():
            await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
