# v2rayNG + Telegram Import (نسخه ۷)

## جدید در نسخه ۷: پشتیبان‌گیری و انتقال حساب
- کارت «پشتیبان‌گیری و انتقال حساب» در صفحه تنظیمات تلگرام.
- خروجی گرفتن از تنظیمات و نشست TDLib در فایل رمزگذاری‌شده `tg-v2ray-backup.tgv2r`.
- بازیابی فایل با همان رمز پس از نصب مجدد.
- فایل با AES-256-GCM و کلید مشتق‌شده از رمز کاربر رمزگذاری می‌شود.

**هشدار امنیتی:** فایل پشتیبان می‌تواند دسترسی به نشست تلگرام را در اختیار دارنده‌اش بگذارد. آن را منتشر نکن و رمز آن را با دیگران به اشتراک نگذار. برای انتقال، نشست قبلی را مدیریت کن تا تلگرام آن را باطل نکند.

## فایل‌های نسخه ۷
- `kotlin/TelegramManager.kt`
- `kotlin/TelegramSettingsActivity.kt`
- `kotlin/SessionBackup.kt` (جدید)
- `cloudflare-worker/worker.js`
- `snippets/AndroidManifest-add.xml`
- `snippets/gradle-add.txt`

فایل‌های Kotlin در پوشه `patches/` نگهداری می‌شوند تا workflow ساخت آن‌ها را در سورس upstream کپی کند. فایل‌های `scripts/` و `.github/workflows/` باید باقی بمانند تا بیلد خودکار کار کند.
