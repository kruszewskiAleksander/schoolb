#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'BLAD: %s\n' "$*" >&2; exit 1; }
if [[ "${1:-}" == --help ]]; then
    printf 'Uzycie: sudo bash install.sh [WYMIANA [ARCHIWUM [RAPORTY]]]\n'
    exit 0
fi
(( $# <= 3 )) || fail 'Dozwolone sa maksymalnie trzy sciezki.'
[[ "$(uname -s)" == Linux ]] || fail 'Instalator wymaga Linuksa.'
(( EUID == 0 )) || fail 'Uruchom instalator przez sudo.'
[[ "$(ps -p 1 -o comm= | tr -d '[:space:]')" == systemd ]] || fail 'Ten instalator wymaga systemd jako PID 1.'
for tool in java systemctl systemd-analyze sha256sum readlink install mktemp; do
    command -v "$tool" >/dev/null || fail "Brak polecenia: $tool"
done
BUNDLE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
(cd -- "$BUNDLE_DIR" && sha256sum -c SHA256SUMS)
JAVA_BIN="$(readlink -f -- "$(command -v java)")"
JAVA_SPEC="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p' | tr -d '\r')"
[[ "$JAVA_SPEC" =~ ^[0-9]+$ ]] || fail 'Nie mozna rozpoznac wersji Java.'
(( JAVA_SPEC >= 17 )) || fail 'Wymagana Java 17 lub nowsza.'
SOURCE_DIR="${1:-/home/serwer/szkola_wymiana}"
ARCHIVE_DIR="${2:-/home/serwer/.backup_archiwum}"
REPORT_DIR="${3:-/home/serwer/.raporty}"
for path in "$SOURCE_DIR" "$ARCHIVE_DIR" "$REPORT_DIR" "$JAVA_BIN"; do
    [[ "$path" =~ ^/[A-Za-z0-9._/-]+$ ]] || fail "Sciezka wymaga recznej konfiguracji: $path"
    [[ "$(readlink -m -- "$path")" == "$path" ]] || fail "Uzyj kanonicznej sciezki bez dowiazan i koncowego ukosnika: $path"
done
[[ -d "$SOURCE_DIR" ]] || fail "Folder wymiany nie istnieje: $SOURCE_DIR"
[[ "${ARCHIVE_DIR##*/}" == .* && "${REPORT_DIR##*/}" == .* ]] || fail 'Nazwy archiwum i raportow musza zaczynac sie kropka.'
overlap() { [[ "$1/" == "$2/"* || "$2/" == "$1/"* ]]; }
if overlap "$SOURCE_DIR" "$ARCHIVE_DIR" || overlap "$SOURCE_DIR" "$REPORT_DIR" || overlap "$ARCHIVE_DIR" "$REPORT_DIR"; then
    fail 'Foldery wymiany, archiwum i raportow musza byc rozlaczne.'
fi
[[ ! -e /etc/systemd/system/school-backup.service && ! -L /etc/systemd/system/school-backup.service ]] || fail 'Jednostka juz istnieje; uzyj recznej instrukcji aktualizacji.'
[[ ! -e /opt/school-backup && ! -L /opt/school-backup ]] || fail '/opt/school-backup juz istnieje; sprawdz poprzednia instalacje.'
for path in "$ARCHIVE_DIR" "$REPORT_DIR"; do
    [[ ! -e "$path" && ! -L "$path" ]] || fail "Prywatny katalog juz istnieje; sprawdz wlascicieli wedlug read.me: $path"
done
UNIT_TEXT="$(cat "$BUNDLE_DIR/school-backup.service.in")"
UNIT_TEXT="${UNIT_TEXT//@JAVA@/$JAVA_BIN}"
UNIT_TEXT="${UNIT_TEXT//@SOURCE@/$SOURCE_DIR}"
UNIT_TEXT="${UNIT_TEXT//@ARCHIVE@/$ARCHIVE_DIR}"
UNIT_TEXT="${UNIT_TEXT//@REPORTS@/$REPORT_DIR}"
UNIT_TEMP="$(mktemp /tmp/school-backup.XXXXXX.service)"
trap 'rm -f -- "$UNIT_TEMP"' EXIT
printf '%s\n' "$UNIT_TEXT" > "$UNIT_TEMP"
systemd-analyze verify "$UNIT_TEMP"
install -d -o root -g root -m 0755 /opt/school-backup
install -o root -g root -m 0644 "$BUNDLE_DIR/school-backup.jar" /opt/school-backup/school-backup.jar
install -d -o root -g root -m 0700 "$ARCHIVE_DIR" "$REPORT_DIR"
install -o root -g root -m 0644 "$UNIT_TEMP" /etc/systemd/system/school-backup.service
systemctl daemon-reload
printf '\nZainstalowano. Usluga nie zostala jeszcze uruchomiona.\n'
printf 'Uruchom: sudo systemctl start school-backup.service\n'
printf 'Logi: sudo journalctl -u school-backup.service -n 80 --no-pager\n'
printf 'Po sprawdzeniu kopii przez SMB/SFTP: sudo systemctl enable school-backup.service\n'
