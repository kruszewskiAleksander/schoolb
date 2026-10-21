#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'BLAD: %s\n' "$*" >&2; exit 1; }
[[ "$(uname -s)" == Linux ]] || fail 'Uruchom ten skrypt na Linuksie.'
command -v java >/dev/null || fail 'Brak Java. Zainstaluj Java 17 lub nowsza; patrz read.me.'
BUNDLE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p' | tr -d '\r')"
[[ "$JAVA_SPEC" =~ ^[0-9]+$ ]] || fail "Nie mozna rozpoznac wersji Java: $JAVA_SPEC"
(( JAVA_SPEC >= 17 )) || fail 'Wymagana Java 17 lub nowsza.'
(cd -- "$BUNDLE_DIR" && sha256sum -c SHA256SUMS)
TEST_ROOT="$(mktemp -d /var/tmp/school-backup-check.XXXXXX)"
trap 'rmdir -- "$TEST_ROOT" 2>/dev/null || printf "Pozostawiono dane diagnostyczne: %s\n" "$TEST_ROOT"' EXIT
cd -- "$TEST_ROOT"
java -ea -cp "$BUNDLE_DIR/school-backup.jar:$BUNDLE_DIR/school-backup-tests.jar" SchoolBackupDaemonTest
printf '\nTest lokalny zakonczony. Przed autostartem sprawdz takze produkcyjny udzial SMB/SFTP.\n'
