#!/usr/bin/env bash
# Writes the placeholder app/google-services.json the fork's other workflows use (no secrets,
# no real Firebase project; it only satisfies the google-services Gradle plugin).
set -euo pipefail
cat > app/google-services.json <<'EOF'
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "placeholder-project",
    "storage_bucket": "placeholder-project.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
        "android_client_info": { "package_name": "com.mnaseb.sim" }
      },
      "oauth_client": [],
      "api_key": [ { "current_key": "AIzaSy000000000000000000000000000000000" } ],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    }
  ],
  "configuration_version": "1"
}
EOF
