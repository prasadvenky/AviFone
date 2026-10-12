#!/usr/bin/env bash
# Installs an Android development environment into the user's home directory
# (no root required). Safe to re-run: completed steps are skipped.
#
#   - Android Studio            -> ~/Android/android-studio
#   - Android SDK (cmdline-tools, platform-tools, build-tools, platforms,
#     emulator, system image)   -> ~/Android/Sdk
#   - JDK: Android Studio's bundled JetBrains Runtime is used as JAVA_HOME
#   - An emulator AVD           -> ~/.android/avd
#   - Env vars (ANDROID_HOME, JAVA_HOME, PATH) appended to ~/.bashrc
#
# Override any version below via environment variables, e.g.
#   PLATFORM_API=37 ./setup-android-dev.sh
set -euo pipefail

INSTALL_ROOT="${INSTALL_ROOT:-$HOME/Android}"
SDK_DIR="$INSTALL_ROOT/Sdk"
STUDIO_DIR="$INSTALL_ROOT/android-studio"
DL_DIR="${DL_DIR:-$INSTALL_ROOT/.downloads}"

STUDIO_VERSION="${STUDIO_VERSION:-2026.2.1.8}"
STUDIO_URL="${STUDIO_URL:-https://edgedl.me.gvt1.com/android/studio/ide-zips/$STUDIO_VERSION/android-studio-rabbit1-linux.tar.gz}"
STUDIO_SHA256="${STUDIO_SHA256:-f8775c67cf899d9133712a2d7ee3ef7231e07fb16dbe0544c47e5f5babd5931b}"

CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-16111833}"
CMDLINE_TOOLS_SHA1="${CMDLINE_TOOLS_SHA1:-e025545c62a8e64c7559119566a569fb1dec5f60}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"

PLATFORM_API="${PLATFORM_API:-36}"
EXTRA_PLATFORM_API="${EXTRA_PLATFORM_API:-37.0}"
BUILD_TOOLS="${BUILD_TOOLS:-36.1.0}"
NDK_VERSION="${NDK_VERSION:-30.0.16248370}"
CMAKE_VERSION="${CMAKE_VERSION:-4.1.2}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-${PLATFORM_API};google_apis_playstore;x86_64}"
AVD_NAME="${AVD_NAME:-Pixel_API_${PLATFORM_API}}"
AVD_DEVICE="${AVD_DEVICE:-pixel_8}"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# Download $1 to $2 and verify with checksum tool $3 (sha1sum/sha256sum) against $4.
fetch() {
  local url="$1" out="$2" tool="$3" sum="$4"
  if [[ -f "$out" ]] && echo "$sum  $out" | "$tool" -c --status; then
    echo "Already downloaded: $(basename "$out")"
    return
  fi
  echo "Downloading $url"
  curl -fL --retry 3 -C - -o "$out" "$url" || curl -fL --retry 3 -o "$out" "$url"
  echo "$sum  $out" | "$tool" -c --status || die "Checksum mismatch for $out"
}

# ---------------------------------------------------------------- preflight
log "Preflight checks"
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || die "This script supports Linux x86_64 only"
for cmd in curl unzip tar sha1sum sha256sum; do
  command -v "$cmd" >/dev/null || die "Missing required command: $cmd (sudo apt install $cmd)"
done
free_gb=$(df -BG --output=avail "$HOME" | tail -1 | tr -dc '0-9')
(( free_gb >= 20 )) || die "Need at least 20 GB free in \$HOME (have ${free_gb} GB)"

missing_libs=()
for pkg in libc6:i386 libncurses5:i386 libstdc++6:i386 libbz2-1.0:i386 libpulse0 libnss3 libxcomposite1; do
  dpkg -s "$pkg" &>/dev/null || missing_libs+=("$pkg")
done
if (( ${#missing_libs[@]} )); then
  warn "Missing system libraries (Studio/emulator may not run). Install with:"
  warn "  sudo dpkg --add-architecture i386 && sudo apt update && sudo apt install ${missing_libs[*]}"
fi

if [[ -r /dev/kvm && -w /dev/kvm ]]; then
  echo "KVM acceleration: available"
else
  warn "No read/write access to /dev/kvm; emulator will be slow. Fix: sudo usermod -aG kvm \$USER (then log out/in)"
fi

mkdir -p "$INSTALL_ROOT" "$SDK_DIR" "$DL_DIR"

# ----------------------------------------------------------- Android Studio
log "Android Studio $STUDIO_VERSION"
if [[ "$(cat "$STUDIO_DIR/.installed-version" 2>/dev/null)" == "$STUDIO_VERSION" ]]; then
  echo "Already installed at $STUDIO_DIR"
else
  studio_tgz="$DL_DIR/android-studio-$STUDIO_VERSION-linux.tar.gz"
  fetch "$STUDIO_URL" "$studio_tgz" sha256sum "$STUDIO_SHA256"
  rm -rf "$STUDIO_DIR.new" && mkdir -p "$STUDIO_DIR.new"
  tar -xzf "$studio_tgz" -C "$STUDIO_DIR.new" --strip-components=1
  rm -rf "$STUDIO_DIR" && mv "$STUDIO_DIR.new" "$STUDIO_DIR"
  echo "$STUDIO_VERSION" >"$STUDIO_DIR/.installed-version"
  echo "Installed to $STUDIO_DIR"
fi

export JAVA_HOME="$STUDIO_DIR/jbr"
[[ -x "$JAVA_HOME/bin/java" ]] || die "Bundled JDK not found at $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# --------------------------------------------------- SDK command-line tools
log "Android SDK command-line tools (build $CMDLINE_TOOLS_BUILD)"
if [[ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "Already installed"
else
  clt_zip="$DL_DIR/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}.zip"
  fetch "$CMDLINE_TOOLS_URL" "$clt_zip" sha1sum "$CMDLINE_TOOLS_SHA1"
  tmp_extract="$(mktemp -d "$DL_DIR/clt.XXXXXX")"
  unzip -q "$clt_zip" -d "$tmp_extract"
  mkdir -p "$SDK_DIR/cmdline-tools"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$tmp_extract/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$tmp_extract"
fi

export ANDROID_HOME="$SDK_DIR" ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$SDK_DIR/emulator:$JAVA_HOME/bin:$PATH"

# ------------------------------------------------------------ SDK packages
log "Accepting SDK licenses"
yes | sdkmanager --sdk_root="$SDK_DIR" --licenses >/dev/null || true

packages=(
  "platform-tools"
  "emulator"
  "build-tools;$BUILD_TOOLS"
  "ndk;$NDK_VERSION"
  "cmake;$CMAKE_VERSION"
  "platforms;android-$PLATFORM_API"
  "$SYSTEM_IMAGE"
)
[[ -n "$EXTRA_PLATFORM_API" ]] && packages+=("platforms;android-$EXTRA_PLATFORM_API")

log "Installing SDK packages: ${packages[*]}"
sdkmanager --sdk_root="$SDK_DIR" --install "${packages[@]}"

# sdkmanager (now a wrapper around the Android CLI) exits 0 even when a package
# name is not found, so confirm each one actually landed on disk.
for pkg in "${packages[@]}"; do
  [[ -d "$SDK_DIR/${pkg//;//}" ]] || die "SDK package not installed: $pkg (check name with: sdkmanager --list)"
done

# --------------------------------------------------------------------- AVD
log "Emulator AVD '$AVD_NAME'"
if avdmanager list avd -c 2>/dev/null | grep -qx "$AVD_NAME"; then
  echo "Already exists"
else
  echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d "$AVD_DEVICE"
  echo "Created"
fi

# ------------------------------------------------------------ shell config
log "Configuring ~/.bashrc"
marker="# >>> android-dev-env >>>"
if grep -qF "$marker" "$HOME/.bashrc" 2>/dev/null; then
  echo "Already configured"
else
  cat >>"$HOME/.bashrc" <<EOF

$marker
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="\$ANDROID_HOME"
export JAVA_HOME="$STUDIO_DIR/jbr"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:$STUDIO_DIR/bin:\$JAVA_HOME/bin:\$PATH"
# <<< android-dev-env <<<
EOF
  echo "Added env vars to ~/.bashrc"
fi

# ----------------------------------------------------------- desktop entry
desktop="$HOME/.local/share/applications/android-studio.desktop"
mkdir -p "$(dirname "$desktop")"
cat >"$desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Android Studio
Exec="$STUDIO_DIR/bin/studio.sh" %f
Icon=$STUDIO_DIR/bin/studio.svg
Categories=Development;IDE;
Terminal=false
StartupWMClass=jetbrains-studio
EOF

# ------------------------------------------------------------------ verify
log "Verification"
echo "java:        $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "sdkmanager:  $(sdkmanager --sdk_root="$SDK_DIR" --version 2>/dev/null)"
echo "adb:         $("$SDK_DIR/platform-tools/adb" version | head -1)"
echo "emulator:    $("$SDK_DIR/emulator/emulator" -version 2>/dev/null | head -1)"
echo "AVDs:        $(avdmanager list avd -c 2>/dev/null | tr '\n' ' ')"

cat <<EOF

Done. Next steps:
  1. Open a new terminal (or: source ~/.bashrc)
  2. Launch Android Studio:  studio.sh   (or from the app menu)
     On first launch choose "Do not import settings"; point it at SDK: $SDK_DIR
  3. Start the emulator:     emulator -avd $AVD_NAME
EOF
