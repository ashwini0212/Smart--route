#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu 22.04/24.04 server for SmartRoute (see docs/deployment.md).
# Run as a user with sudo:   curl -fsSL <raw url of this file> | bash
# or, after cloning:         bash deploy/setup-server.sh
#
# It installs Docker, adds 2 GB of swap (building the backend image on a 4 GB machine while the stack runs
# is the tightest moment), opens ports 22/80/443 in the host firewall and clones the repository.
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/ashwini0212/Smart--route.git}"
APP_DIR="${APP_DIR:-$HOME/smartroute}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Installing Docker"
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
fi

if ! swapon --show | grep -q /swapfile; then
  echo "Adding 2 GB swap"
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "Opening ports 22, 80 and 443 in the host firewall"
if command -v ufw >/dev/null 2>&1; then
  sudo ufw allow 22/tcp
  sudo ufw allow 80/tcp
  sudo ufw allow 443/tcp
  sudo ufw allow 443/udp
  sudo ufw --force enable
fi
# Oracle Cloud's Ubuntu images ship iptables rules that reject everything but SSH, independently of ufw.
if sudo iptables -L INPUT -n 2>/dev/null | grep -q "REJECT"; then
  for port in 80 443; do
    sudo iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null \
      || sudo iptables -I INPUT 5 -p tcp --dport "$port" -j ACCEPT
  done
  if command -v netfilter-persistent >/dev/null 2>&1; then sudo netfilter-persistent save; fi
fi

if [ ! -d "$APP_DIR/.git" ]; then
  git clone "$REPO_URL" "$APP_DIR"
fi

if [ ! -f "$APP_DIR/.env" ]; then
  cp "$APP_DIR/deploy/env.production.example" "$APP_DIR/.env"
  chmod 600 "$APP_DIR/.env"
fi

cat <<MSG

Done. Next:
  1. Log out and back in (so your user can run docker without sudo).
  2. Edit $APP_DIR/.env and replace every CHANGE-ME (openssl rand -base64 48 makes a good secret).
  3. cd $APP_DIR && bash deploy/deploy.sh
MSG
