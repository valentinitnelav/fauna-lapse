#!/usr/bin/env bash
# Creates the FaunaLapse release key, once (maintainers only). Run it in your own terminal: it asks
# for a password and does not show it.
#
# Android installs an update only if it is signed with the same key as the installed app. If the
# key or its password is lost, users must uninstall FaunaLapse (losing its settings) to get newer
# versions, so back up both (the script says how at the end).
#
# Usage: bash tools/create_release_key.sh [folder]   (default: ~/faunalapse-signing, outside the repo)
# It writes <folder>/faunalapse-release.jks (the key) and <folder>/signing.properties (where the key
# is and its password), which app/build.gradle reads for release builds.
set -euo pipefail

dir="${1:-$HOME/faunalapse-signing}"
keystore="$dir/faunalapse-release.jks"
props="$dir/signing.properties"

command -v keytool > /dev/null || { echo "keytool not found: it comes with Java (JDK 17 or newer)." >&2; exit 1; }
if [ -e "$keystore" ] || [ -e "$props" ]; then
  echo "$dir already holds a key; nothing was changed (a new key would break updates)." >&2
  exit 1
fi

read -r -p "Name on the certificate [FaunaLapse]: " name
read -r -p "Two-letter country code [DE]: " country
read -r -s -p "New password (at least 6 characters, not shown): " pw; echo
read -r -s -p "The same password again: " pw2; echo
[ "$pw" = "$pw2" ] || { echo "The two passwords differ; nothing was changed." >&2; exit 1; }
[ "${#pw}" -ge 6 ] || { echo "The password is too short; nothing was changed." >&2; exit 1; }

mkdir -p "$dir"
chmod 700 "$dir"
umask 077
export FAUNALAPSE_KEY_PW="$pw"   # keytool reads it from here, so it never shows in the process list
# PKCS12: one password for the file and the key. 10000 days (27 years) outlasts the project; RSA 4096
# stays strong for that long.
keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias faunalapse \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=${name:-FaunaLapse}, C=${country:-DE}" \
  -storepass:env FAUNALAPSE_KEY_PW -keypass:env FAUNALAPSE_KEY_PW

# A backslash starts an escape in a properties file, so double it.
cat > "$props" << EOF
# FaunaLapse release signing, read by app/build.gradle. Never put this file in git or send it.
storeFile=$keystore
storePassword=${pw//\\/\\\\}
keyAlias=faunalapse
keyPassword=${pw//\\/\\\\}
EOF

echo
echo "Done: $keystore"
echo "      $props"
echo "Fingerprint of the key (apksigner shows the same one for every release APK):"
keytool -list -v -keystore "$keystore" -storepass:env FAUNALAPSE_KEY_PW | grep -E "SHA-?256:" || true
echo
echo "Back up now, before the first release:"
echo "  1. Copy $keystore to two places you control (for example an encrypted USB stick and"
echo "     your institute's storage)."
echo "  2. Keep the password in a password manager, not next to those copies."
echo "  3. Never put the key, the password or signing.properties in git, an issue or an email."
