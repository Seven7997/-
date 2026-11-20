"""Package the two built APK flavors and audited sources.

This script packages existing release outputs only. It never invokes Gradle, touches a
device, reads app data, or includes private keys, original APKs, or screenshots.
"""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
DIST.mkdir(exist_ok=True)

FLAVORS = (
    ("wechatHook", "WeChatHook", "1.2.0-wechatHook"),
    ("systemuiOnly", "SystemUIOnly", "1.2.0-systemuiOnly"),
)

# Validate both expected outputs before copying either one. Never build or substitute.
sources = []
for flavor, label, version_name in FLAVORS:
    source = ROOT / f"app/build/outputs/apk/{flavor}/release/app-{flavor}-release.apk"
    if not source.is_file():
        raise SystemExit(
            f"Missing {flavor} release APK: {source}. "
            "Build the flavor locally before packaging; this script does not build."
        )
    sources.append((source, label))

artifacts = []
for source, label in sources:
    destination = DIST / f"WeChatBubbles-v1.2.0-{label}.apk"
    shutil.copy2(source, destination)
    artifacts.append(destination)

# Keep the source archive limited to public project inputs and the verification record.
paths = [ROOT / p for p in (
    ".gitignore", "README.md", "settings.gradle", "build.gradle", "gradle.properties",
    "gradlew", "gradlew.bat", "app/build.gradle", "app/libs/xposed-api-82.jar",
)]
for folder in ("app/src", "gradle/wrapper", "tools"):
    paths.extend(
        p for p in (ROOT / folder).rglob("*")
        if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc"
    )
record = DIST / "验证记录-v1.2.0.md"
if record.is_file():
    paths.append(record)

archive = DIST / "WeChatBubbles-v1.2.0-source.zip"
blocked_names = {
    "local-signing.properties", "local.properties", "debug.keystore",
    "wechat-8.0.71-v3080.apk", "wechat-8.0.71-v3080.zip",
}
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(set(paths)):
        if not p.is_file():
            continue
        relative = p.relative_to(ROOT).as_posix()
        if relative.startswith((".signing/", "research-rom/", "verification/")):
            raise SystemExit(f"Unexpected private/generated path in deliverable: {relative}")
        if relative.startswith("dist/") and p != record:
            raise SystemExit(f"Generated dist artifact must not be included: {relative}")
        if p.name in blocked_names or p.suffix.lower() == ".apk":
            raise SystemExit(f"Blocked private/APK file in deliverable: {relative}")
        if any(part.lower() in {"screenshots", "screenshot", "截图"} for part in p.parts):
            raise SystemExit(f"Screenshot must not be exported: {relative}")
        z.write(p, "WeChatBubbles/" + relative)
artifacts.append(archive)

results = [
    {"file": p.name, "bytes": p.stat().st_size,
     "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
    for p in artifacts
]
checksum = DIST / "SHA256SUMS.txt"
checksum.write_text(
    "".join(f"{r['sha256']}  {r['file']}\n" for r in results),
    encoding="utf-8",
)
print(json.dumps(results, ensure_ascii=False, indent=2))
