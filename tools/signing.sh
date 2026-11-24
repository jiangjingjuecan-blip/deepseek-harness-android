#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p .signing
if [[ ! -f .signing/release.p12 ]]; then
    [[ -s .signing/password ]] || openssl rand -hex 32 > .signing/password
    keytool -genkeypair -noprompt -keystore .signing/new.p12 -storetype PKCS12 \
        -storepass:file .signing/password -alias harness-test -keyalg RSA -keysize 3072 \
        -validity 3650 -dname 'CN=Harness Local Test' && mv .signing/new.p12 .signing/release.p12
fi
