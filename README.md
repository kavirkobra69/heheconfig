# v2rayNG + Telegram Import (نسخه ۶)

## ویژگی‌های نسخه ۶
- کلید روشن/خاموش کنار هر چنل
- فیلتر پروتکل: vmess, vless, trojan, ss, hysteria2, tuic
- جلوگیری از ایمپورت کانفیگ‌های تکراری و پاک‌کردن تاریخچه
- تست Worker، کپی کانفیگ‌ها، نوار پیشرفت و گزارش پروتکل‌ها
- نمایش زمان و تعداد آخرین ایمپورت

## ساختار فایل‌ها
- `patches/TelegramManager.kt` و `patches/TelegramSettingsActivity.kt`: فایل‌های ورودی برای build workflow
- `cloudflare-worker/worker.js`: کد Cloudflare Worker
- `snippets/AndroidManifest-add.xml`: ورودی Manifest
- `snippets/gradle-add.txt`: وابستگی‌ها و تنظیمات Gradle/ProGuard

## Build
از تب Actions، ورک‌فلو Build را اجرا کن. فایل APK به‌صورت Artifact منتشر می‌شود.

نکته: پوشه‌های workflow و scripts عمداً نگه داشته شده‌اند تا فرایند ساخت APK خراب نشود.
