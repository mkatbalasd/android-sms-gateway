#!/usr/bin/env bash
# R5-7 gate: the shipped build types (release and sendOnly) must trust the system CA store only.
#
# Usage: check-release-trust-anchors.sh [repo-root]      (default: the current directory)
#
# Reads <root>/app/build.gradle, finds the network security config that the `release` and
# `sendOnly` build types name in manifestPlaceholders, parses that XML file and FAILS when
#   - build.gradle, either build type, its placeholder or its XML file is missing or unreadable,
#   - the XML is not well formed, or declares no system trust anchor at all,
#   - ANY certificates element, in the base config, a domain config or debug-overrides, has a src
#     other than "system" (user, @raw/..., anything else).
# Fail closed: every unexpected condition is a failure. Exit 0 only when all checks pass.
set -euo pipefail
ROOT="${1:-.}"
command -v python3 >/dev/null || { echo "FAIL: python3 is not available" >&2; exit 1; }

python3 - "$ROOT" <<'PY'
import os
import re
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
gradle_path = os.path.join(root, "app", "build.gradle")
xml_dir = os.path.join(root, "app", "src", "main", "res", "xml")


def fail(msg):
    print("FAIL: " + msg, file=sys.stderr)
    sys.exit(1)


def block(text, name):
    """Body of `name { ... }` (brace matched), or None."""
    m = re.search(r"(?:^|\s)" + re.escape(name) + r"\s*\{", text)
    if not m:
        return None
    depth = 0
    for i in range(m.end() - 1, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[m.end():i]
    return None


if not os.path.isfile(gradle_path):
    fail("missing " + gradle_path)
with open(gradle_path, encoding="utf-8") as f:
    gradle = f.read()

build_types = block(gradle, "buildTypes")
if build_types is None:
    fail("no buildTypes block in app/build.gradle")

for build_type in ("release", "sendOnly"):
    body = block(build_types, build_type)
    if body is None:
        fail("build type %s not found in app/build.gradle" % build_type)
    m = re.search(r'networkSecurityConfig\s*:\s*"@xml/([A-Za-z0-9_]+)"', body)
    if not m:
        fail("build type %s sets no networkSecurityConfig placeholder" % build_type)
    name = m.group(1)
    path = os.path.join(xml_dir, name + ".xml")
    if not os.path.isfile(path):
        fail("build type %s names @xml/%s but %s does not exist" % (build_type, name, path))
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        fail("%s is not well formed: %s" % (path, e))
    sources = [c.get("src") for c in tree.getroot().iter("certificates")]
    if not sources:
        fail("%s declares no trust anchors" % path)
    bad = [s for s in sources if s != "system"]
    if bad:
        fail("%s (build type %s) trusts a non-system anchor: %s" % (path, build_type, ", ".join(map(str, bad))))
    print("ok: %s -> %s trusts the system store only" % (build_type, name))
PY
