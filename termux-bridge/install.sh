#!/data/data/com.termux/files/usr/bin/bash
set -eu
pkg install -y python git curl unzip
git config --global http.version HTTP/1.1
mkdir -p "$HOME/.config/termux-bridge" "$HOME/bin"
if [ ! -d "$HOME/psurvival-bot" ]; then
  if ! git clone https://github.com/drvladcepeche-maker/psurvival-bot.git "$HOME/psurvival-bot"; then
    echo "Git clone failed; trying source archive fallback..."
    TMP="$PREFIX/tmp/psurvival-bot.zip"
    curl -fL --retry 3 "https://codeload.github.com/drvladcepeche-maker/psurvival-bot/zip/refs/heads/main" -o "$TMP"
    mkdir -p "$PREFIX/tmp/psurvival-bot-extract"
    unzip -oq "$TMP" -d "$PREFIX/tmp/psurvival-bot-extract"
    rm -rf "$HOME/psurvival-bot"
    mv "$PREFIX/tmp/psurvival-bot-extract/psurvival-bot-main" "$HOME/psurvival-bot"
    rm -rf "$PREFIX/tmp/psurvival-bot-extract" "$TMP"
  fi
fi
curl -fsSL "https://raw.githubusercontent.com/drvladcepeche-maker/psurvival-bot/main/termux-bridge/bridge.py" -o "$HOME/bin/termux-bridge.py"
chmod 700 "$HOME/bin/termux-bridge.py"
printf "Paste the one-time Termux bridge token: "
stty -echo
IFS= read -r TOKEN
stty echo
printf "\n"
if [ "${#TOKEN}" -ne 64 ]; then echo "Invalid token length"; unset TOKEN; exit 1; fi
printf '{"token":"%s"}\n' "$TOKEN" > "$HOME/.config/termux-bridge/config.json"
chmod 600 "$HOME/.config/termux-bridge/config.json"
unset TOKEN
echo "Installation complete; starting bridge worker now."
exec python "$HOME/bin/termux-bridge.py"
