from pathlib import Path

root = Path("upstream")
app = root / "V2rayNG" / "app"
manifest = app / "src/main/AndroidManifest.xml"
gradle = app / "build.gradle.kts"
proguard = app / "proguard-rules.pro"

# Use the Java source root, which is already configured in this upstream project.
dest = app / "src/main/java/com/v2ray/ang/ui"
dest.mkdir(parents=True, exist_ok=True)
for name in ("TelegramManager.kt", "TelegramSettingsActivity.kt"):
    (dest / name).write_text((Path("patches") / name).read_text(encoding="utf-8"), encoding="utf-8")

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
if "TelegramSettingsActivity" not in text:
    text = text.replace("</application>", activity + "    </application>")
manifest.write_text(text, encoding="utf-8")

text = gradle.read_text(encoding="utf-8")
dependency = '    implementation("com.github.fazilus:tdlib-android:1.8.61")'
if "com.github.fazilus:tdlib-android" not in text:
    text = text.replace("dependencies {", "dependencies {\n" + dependency, 1)
gradle.write_text(text, encoding="utf-8")

settings = root / "V2rayNG" / "settings.gradle.kts"
text = settings.read_text(encoding="utf-8")
if "https://jitpack.io" not in text:
    text = text.replace("repositories {", 'repositories {\n        maven { url = uri("https://jitpack.io") }', 1)
settings.write_text(text, encoding="utf-8")

text = proguard.read_text(encoding="utf-8")
keep = "-keep class org.drinkless.tdlib.** { *; }"
if keep not in text:
    text += "\n\n" + keep + "\n"
proguard.write_text(text, encoding="utf-8")

# The current upstream build needs this NDK version for its native library.
text = gradle.read_text(encoding="utf-8")
if 'ndkVersion = "30.0.16248370"' not in text:
    text = text.replace("android {", 'android {\n    ndkVersion = "30.0.16248370"', 1)
gradle.write_text(text, encoding="utf-8")
