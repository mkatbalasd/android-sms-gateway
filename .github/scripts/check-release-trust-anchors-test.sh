#!/usr/bin/env bash
# Self-test for check-release-trust-anchors.sh (no secrets, no Android tools needed).
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$DIR/../.." && pwd)"
GATE="$DIR/check-release-trust-anchors.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail=0

# expect name want(ok|fail) root
expect() {
  local name="$1" want="$2" root="$3"
  bash "$GATE" "$root" >/dev/null 2>&1
  local rc=$?
  if { [ "$want" = ok ] && [ $rc -eq 0 ]; } || { [ "$want" = fail ] && [ $rc -ne 0 ]; }; then
    echo "PASS $name"
  else
    echo "FAIL $name (rc=$rc)"; fail=1
  fi
}

# fixture name release_cfg sendonly_cfg  -> a minimal tree; the xml files are written by the caller
fixture() {
  local root="$WORK/$1"
  mkdir -p "$root/app/src/main/res/xml"
  cat > "$root/app/build.gradle" <<EOF
android {
    signingConfigs {
        release { storeFile file("keystore.jks") }
    }
    buildTypes {
        debug {
            manifestPlaceholders = [networkSecurityConfig: "@xml/network_security_config"]
        }
        release {
            minifyEnabled false
            manifestPlaceholders = [networkSecurityConfig: "@xml/$2"]
        }
        sendOnly {
            initWith release
            manifestPlaceholders = [networkSecurityConfig: "@xml/$3"]
        }
    }
}
EOF
  echo "$root"
}

GOOD='<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system" /></trust-anchors></base-config><domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="false">127.0.0.1</domain></domain-config></network-security-config>'
USER_BASE='<network-security-config><base-config><trust-anchors><certificates src="system" /><certificates src="user" /></trust-anchors></base-config></network-security-config>'
RAW_BASE='<network-security-config><base-config><trust-anchors><certificates src="system" /><certificates src="@raw/ca" /></trust-anchors></base-config></network-security-config>'
USER_DOMAIN='<network-security-config><base-config><trust-anchors><certificates src="system" /></trust-anchors></base-config><domain-config><domain>example.com</domain><trust-anchors><certificates src="user" /></trust-anchors></domain-config></network-security-config>'
NO_ANCHORS='<network-security-config><base-config cleartextTrafficPermitted="false" /></network-security-config>'

# the real tree must pass
expect real-tree ok "$REPO"

r=$(fixture good rel rel);              echo "$GOOD" > "$r/app/src/main/res/xml/rel.xml"; expect good ok "$r"
r=$(fixture user-release rel rel);      echo "$USER_BASE" > "$r/app/src/main/res/xml/rel.xml"; expect user-anchor-in-release fail "$r"
r=$(fixture raw-release rel rel);       echo "$RAW_BASE" > "$r/app/src/main/res/xml/rel.xml"; expect raw-ca-in-release fail "$r"
r=$(fixture user-domain rel rel);       echo "$USER_DOMAIN" > "$r/app/src/main/res/xml/rel.xml"; expect user-anchor-in-domain-config fail "$r"
r=$(fixture no-anchors rel rel);        echo "$NO_ANCHORS" > "$r/app/src/main/res/xml/rel.xml"; expect no-anchors-declared fail "$r"
r=$(fixture malformed rel rel);         echo '<network-security-config><base-config>' > "$r/app/src/main/res/xml/rel.xml"; expect malformed-xml fail "$r"
r=$(fixture missing-xml rel rel);       expect missing-xml-file fail "$r"
# sendOnly points at a permissive file while release is clean
r=$(fixture sendonly-user rel perm);    echo "$GOOD" > "$r/app/src/main/res/xml/rel.xml"; echo "$USER_BASE" > "$r/app/src/main/res/xml/perm.xml"; expect sendonly-user-anchor fail "$r"
# release points at a permissive file while sendOnly is clean
r=$(fixture release-user rel2 rel);     echo "$GOOD" > "$r/app/src/main/res/xml/rel.xml"; echo "$USER_BASE" > "$r/app/src/main/res/xml/rel2.xml"; expect release-user-anchor fail "$r"
# no build.gradle at all
mkdir -p "$WORK/empty"; expect missing-build-gradle fail "$WORK/empty"
# build.gradle without a sendOnly build type
r="$WORK/no-sendonly"; mkdir -p "$r/app/src/main/res/xml"
printf 'android {\n  buildTypes {\n    release {\n      manifestPlaceholders = [networkSecurityConfig: "@xml/rel"]\n    }\n  }\n}\n' > "$r/app/build.gradle"
echo "$GOOD" > "$r/app/src/main/res/xml/rel.xml"; expect missing-sendonly-type fail "$r"
# build.gradle whose release type sets no placeholder
r="$WORK/no-placeholder"; mkdir -p "$r/app/src/main/res/xml"
printf 'android {\n  buildTypes {\n    release {\n      minifyEnabled false\n    }\n    sendOnly {\n      initWith release\n    }\n  }\n}\n' > "$r/app/build.gradle"
expect missing-placeholder fail "$r"

exit $fail
