#!/data/data/com.termux/files/usr/bin/bash
set -eu
pkg install -y python git
mkdir -p "$HOME/.config/termux-bridge" "$HOME/bin"
curl -fsSL "https://raw.githubusercontent.com/drvladcepeche-maker/psurvival-bot/main/termux-bridge/bridge.py" -o "$HOME/bin/termux-bridge.py"
chmod 700 "$HOME/bin/termux-bridge.py"
printf "Paste the one-time Termux bridge token: "
stty -echo
IFS= read -r TOKEN
stty echo
printf "\\n"
if [ "${#TOKEN}" -ne 64 ]; then echo "Invalid token length"; unset TOKEN; exit 1; fi
printf '{"token":"%s"}\\n' "$TOKEN" > "$HOME/.config/termux-bridge/config.json"
chmod 600 "$HOME/.config/termux-bridge/config.json"
unset TOKEN
echo "Installed. Start with: python ~/bin/termux-bridge.py"
