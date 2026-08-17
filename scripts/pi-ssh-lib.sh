#!/usr/bin/env bash
# Gemeinsame SSH-Hilfen (Key oder Passwort via sshpass).
pi_ssh_init() {
  SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519_zeitserver}"
  SSHPASS_BIN="$(command -v sshpass 2>/dev/null || echo /opt/homebrew/bin/sshpass)"
  SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
  [[ -f "$SSH_KEY" ]] && SSH_OPTS+=(-i "$SSH_KEY")
}

pi_ssh_test() {
  local host="$1"
  ssh "${SSH_OPTS[@]}" -o BatchMode=yes "$host" true 2>/dev/null
}

pi_ensure_password() {
  local host="$1"
  pi_ssh_test "$host" && return 0
  if [[ -n "${PI_PASSWORD:-}" ]]; then
    return 0
  fi
  PI_PASSWORD="$(osascript -e 'display dialog "Pi-Passwort (Benutzer mohamed):" default answer "" with hidden answer with title "Zeitserver Deploy"' -e 'text returned of result' 2>/dev/null || true)"
  [[ -n "${PI_PASSWORD:-}" ]] || {
    echo "Abbruch: Kein Passwort. Optional: export PI_PASSWORD=…" >&2
    return 1
  }
}

pi_ssh() {
  local host="$1"
  shift
  if pi_ssh_test "$host"; then
    ssh "${SSH_OPTS[@]}" "$host" "$@"
  elif [[ -n "${PI_PASSWORD:-}" ]] && [[ -x "$SSHPASS_BIN" ]]; then
    sshpass -p "$PI_PASSWORD" ssh "${SSH_OPTS[@]}" "$host" "$@"
  else
    echo "SSH fehlgeschlagen (Key/Passwort)." >&2
    return 255
  fi
}

pi_scp() {
  local src="$1" dest="$2"
  local userhost="${dest%%:*}"
  if pi_ssh_test "$userhost"; then
    scp "${SSH_OPTS[@]}" "$src" "$dest"
  elif [[ -n "${PI_PASSWORD:-}" ]] && [[ -x "$SSHPASS_BIN" ]]; then
    sshpass -p "$PI_PASSWORD" scp "${SSH_OPTS[@]}" "$src" "$dest"
  else
    scp "${SSH_OPTS[@]}" "$src" "$dest"
  fi
}

pi_install_ssh_key() {
  local host="$1"
  [[ -f "$SSH_KEY" ]] || return 0
  pi_ssh_test "$host" && return 0
  [[ -n "${PI_PASSWORD:-}" ]] || return 0
  [[ -x "$SSHPASS_BIN" ]] || return 0
  sshpass -p "$PI_PASSWORD" ssh-copy-id -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$host" 2>/dev/null || true
}

pi_find_host() {
  local mac_ip base end i ip host hn
  mac_ip="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
  [[ -n "$mac_ip" ]] || return 1
  base="${mac_ip%.*}"
  end=20
  [[ "$mac_ip" == 172.20.10.* ]] || end=254
  for i in $(seq 1 "$end"); do
    ip="${base}.${i}"
    [[ "$ip" == "$mac_ip" ]] && continue
    nc -z -G 1 "$ip" 22 &>/dev/null || continue
    host="mohamed@${ip}"
    hn="$(ssh "${SSH_OPTS[@]}" -o BatchMode=yes -o ConnectTimeout=3 "$host" hostname 2>/dev/null || true)"
    if [[ -n "${PI_PASSWORD:-}" ]] && [[ -z "$hn" ]] && [[ -x "${SSHPASS_BIN:-}" ]]; then
      hn="$(sshpass -p "$PI_PASSWORD" ssh "${SSH_OPTS[@]}" -o ConnectTimeout=3 "$host" hostname 2>/dev/null || true)"
    fi
    if [[ "$hn" =~ [Zz]eit|[Rr]aspberry ]]; then
      echo "$host"
      return 0
    fi
  done
  # Fallback: erstes SSH-Gerät (ohne .1 Router)
  for i in $(seq 1 "$end"); do
    ip="${base}.${i}"
    [[ "$ip" == "$mac_ip" ]] && continue
    [[ "$i" == 1 ]] && continue
    nc -z -G 1 "$ip" 22 &>/dev/null || continue
    echo "mohamed@${ip}"
    return 0
  done
  return 1
}
