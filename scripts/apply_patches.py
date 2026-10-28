from pathlib import Path
import re
import sys

root = Path("upstream")
app = root / "V2rayNG" / "app"
manifest = app / "src/main/AndroidManifest.xml"
gradle = app / "build.gradle.kts"
proguard = app / "proguard-rules.pro"
settings = root / "V2rayNG" / "settings.gradle.kts"

required = [
    Path("patches/TelegramManager.kt"),
    Path("patches/TelegramSettingsActivity.kt"),
    Path("patches/SessionBackup.kt"),
]
for path in required:
    if not path.is_file():
        raise SystemExit(f"Missing required patch file: {path}")

# Place both Kotlin files in the package used by the upstream v2rayNG app.
dest = app / "src/main/java/com/v2ray/ang/ui"
dest.mkdir(parents=True, exist_ok=True)
for source in required:
    target = dest / source.name
    target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
    print(f"Applied {source} -> {target}")

# Add FileProvider support for installing APK updates downloaded inside the app.
manifest_text = manifest.read_text(encoding="utf-8")
permission = '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n'
if "android.permission.REQUEST_INSTALL_PACKAGES" not in manifest_text:
    app_marker = "    <application"
    if app_marker not in manifest_text:
        raise SystemExit("Could not find <application in AndroidManifest.xml")
    manifest_text = manifest_text.replace(app_marker, permission + app_marker, 1)
provider = """        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/heheconfig_file_paths" />
        </provider>
"""
if "androidx.core.content.FileProvider" not in manifest_text:
    manifest_text = manifest_text.replace("</application>", provider + "    </application>", 1)
manifest.write_text(manifest_text, encoding="utf-8")
xml_dir = app / "src/main/res/xml"
xml_dir.mkdir(parents=True, exist_ok=True)
(xml_dir / "heheconfig_file_paths.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<paths xmlns:android="http://schemas.android.com/apk/res/android">\n    <cache-path name="update_apks" path="updates/" />\n</paths>\n',
    encoding="utf-8",
)

# Register a separate launcher entry for Telegram Import.
text = manifest.read_text(encoding="utf-8")
activity = """        <activity
            android:name=".ui.TelegramSettingsActivity"
            android:label="Telegram Import"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
"""
if "android:name=\".ui.TelegramSettingsActivity\"" not in text:
    if "</application>" not in text:
        raise SystemExit("Could not find </application> in AndroidManifest.xml")
    text = text.replace("</application>", activity + "    </application>", 1)
manifest.write_text(text, encoding="utf-8")

# Add TDLib and the required NDK version idempotently.
text = gradle.read_text(encoding="utf-8")
dependency = '    implementation("com.github.fazilus:tdlib-android:1.8.61")'
if "com.github.fazilus:tdlib-android" not in text:
    if "dependencies {" not in text:
        raise SystemExit("Could not find dependencies block in app/build.gradle.kts")
    text = text.replace("dependencies {", "dependencies {\n" + dependency, 1)
if 'ndkVersion = "30.0.16248370"' not in text:
    if "android {" not in text:
        raise SystemExit("Could not find android block in app/build.gradle.kts")
    text = text.replace("android {", 'android {\n    ndkVersion = "30.0.16248370"', 1)
gradle.write_text(text, encoding="utf-8")

# Ensure JitPack is in dependencyResolutionManagement, not only pluginManagement.
text = settings.read_text(encoding="utf-8")
if "https://jitpack.io" not in text:
    match = re.search(r"(dependencyResolutionManagement\s*\{.*?repositories\s*\{)", text, re.S)
    if not match:
        raise SystemExit("Could not find dependencyResolutionManagement repositories block")
    insert_at = match.end()
    text = text[:insert_at] + '\n        maven { url = uri("https://jitpack.io") }' + text[insert_at:]
settings.write_text(text, encoding="utf-8")

text = proguard.read_text(encoding="utf-8")
keep = "-keep class org.drinkless.tdlib.** { *; }"
if keep not in text:
    text += "\n\n" + keep + "\n"
proguard.write_text(text, encoding="utf-8")

# Fail early if the patch was not actually applied to the upstream checkout.
for target in (dest / "TelegramManager.kt", dest / "TelegramSettingsActivity.kt"):
    if not target.is_file() or target.stat().st_size == 0:
        raise SystemExit(f"Patch application failed: {target}")
if "TelegramSettingsActivity" not in manifest.read_text(encoding="utf-8"):
    raise SystemExit("TelegramSettingsActivity was not registered in the manifest")
if "com.github.fazilus:tdlib-android" not in gradle.read_text(encoding="utf-8"):
    raise SystemExit("TDLib dependency was not added")

# Prevent a green build from silently packaging the old UI without backup controls.
activity_text = (dest / "TelegramSettingsActivity.kt").read_text(encoding="utf-8")
manager_text = (dest / "TelegramManager.kt").read_text(encoding="utf-8")
backup_text = (dest / "SessionBackup.kt").read_text(encoding="utf-8")
for marker in ("startBackupExport()", "startBackupImport()", "RC_EXPORT", "RC_IMPORT", "SessionBackup.export", "SessionBackup.extract", "importFromGitHub()", "testGitHubConfigs", "Test real delays", "RealPingWorkerService"):
    if marker not in activity_text:
        raise SystemExit(f"Missing backup UI/flow marker: {marker}")
if "suspend fun closeClient()" not in manager_text:
    raise SystemExit("TelegramManager.closeClient() is missing")
if "AES/GCM/NoPadding" not in backup_text or "PBKDF2WithHmacSHA1" not in backup_text:
    raise SystemExit("Encrypted SessionBackup implementation is missing")
print("Telegram Import patches applied and validated, including encrypted backup/restore UI.")
