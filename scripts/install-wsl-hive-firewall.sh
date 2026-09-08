#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this installer as WSL root." >&2
  exit 1
fi

cat > /etc/systemd/system/aetheris-metastore-firewall.service <<'UNIT'
[Unit]
Description=Restrict Aetheris Hive Metastore to WSL loopback clients
After=network.target
Before=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c '/usr/sbin/iptables -C INPUT -p tcp --dport 9083 ! -i lo -j REJECT 2>/dev/null || /usr/sbin/iptables -I INPUT 1 -p tcp --dport 9083 ! -i lo -j REJECT'
ExecStop=/bin/sh -c '/usr/sbin/iptables -C INPUT -p tcp --dport 9083 ! -i lo -j REJECT 2>/dev/null && /usr/sbin/iptables -D INPUT -p tcp --dport 9083 ! -i lo -j REJECT || true'

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable aetheris-metastore-firewall.service
systemctl restart aetheris-metastore-firewall.service
if ! systemctl is-active --quiet aetheris-metastore-firewall.service; then
  systemctl --no-pager status aetheris-metastore-firewall.service >&2 || true
  exit 1
fi
echo "Hive Metastore firewall installed: only loopback may reach TCP 9083."
