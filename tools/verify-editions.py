"""Inspect actual APK DEX definitions to verify edition isolation (Python standard library only)."""
from pathlib import Path
import json
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APKS = {
    "wechatHook": ROOT / "app/build/outputs/apk/wechatHook/release/app-wechatHook-release.apk",
    "systemuiOnly": ROOT / "app/build/outputs/apk/systemuiOnly/release/app-systemuiOnly-release.apk",
}


def class_definitions(data):
    if not data.startswith(b"dex\n"):
        raise AssertionError("Not a DEX file")
    strings_count, strings_offset = struct.unpack_from("<II", data, 56)
    types_count, types_offset = struct.unpack_from("<II", data, 64)
    classes_count, classes_offset = struct.unpack_from("<II", data, 96)

    def string_at(index):
        assert 0 <= index < strings_count
        offset = struct.unpack_from("<I", data, strings_offset + index * 4)[0]
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        end = data.index(b"\0", offset)
        return data[offset:end].decode("utf-8")  # Class descriptors are ASCII.

    names = set()
    for index in range(classes_count):
        type_index = struct.unpack_from("<I", data, classes_offset + index * 32)[0]
        assert 0 <= type_index < types_count
        string_index = struct.unpack_from("<I", data, types_offset + type_index * 4)[0]
        names.add(string_at(string_index))
    return names


reports = []
for edition, path in APKS.items():
    assert path.is_file(), f"Build {edition}Release first"
    metadata = json.loads((path.parent / "output-metadata.json").read_text(encoding="utf-8"))
    assert metadata["applicationId"] == "io.github.wechatbubbles"
    elements = [item for item in metadata["elements"] if item["outputFile"] == path.name]
    assert len(elements) == 1
    assert elements[0]["versionCode"] == 7
    assert elements[0]["versionName"] == f"1.2.0-{edition}"
    with zipfile.ZipFile(path) as package:
        entry = package.read("assets/xposed_init").decode("utf-8").strip()
        assert entry == "io.github.wechatbubbles.SystemUiHook", entry
        dex_files = [package.read(name) for name in package.namelist()
                     if name.startswith("classes") and name.endswith(".dex")]
    assert dex_files, "APK contains no DEX"
    classes = set().union(*(class_definitions(data) for data in dex_files))
    assert "Lio/github/wechatbubbles/SystemUiHook;" in classes
    assert not any(name.startswith("Lde/robv/android/xposed/") for name in classes), "Xposed API must stay compileOnly"
    observer = "Lio/github/wechatbubbles/ChatStateObserver;" in classes
    relay = "Lio/github/wechatbubbles/ChatStateClient;" in classes
    if edition == "wechatHook":
        assert observer and relay, "Precise edition must include its read-only observer"
    else:
        assert not observer and not relay, "SystemUI-only APK must exclude WeChat observer/relay classes"
        assert all(b"com.tencent.mm.ui.chatting." not in data for data in dex_files), "WeChat UI reflection strings leaked into SystemUI-only APK"
        assert "Lio/github/wechatbubbles/LastOpenedChat;" in classes
    reports.append({"edition": edition, "apk": path.name, "bytes": path.stat().st_size,
                    "class_count": len(classes), "wechat_observer": observer,
                    "wechat_relay": relay, "xposed_api_packaged": False})
print(json.dumps(reports, ensure_ascii=False, indent=2))
print("PASS: two APK editions are isolated; SystemUI-only contains no WeChat UI observer.")
