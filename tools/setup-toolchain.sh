#!/usr/bin/env bash
# Usage: tools/setup-toolchain.sh
#
# Installs everything twinBook needs to build and run, under the home directory, without
# sudo. Idempotent: a second run downloads nothing and changes nothing.
#   - Eclipse Temurin JDK 21 in ~/.jdks/ (symlink ~/.jdks/temurin-21)
#   - Android SDK in ~/Android/Sdk: cmdline-tools, platform-tools, platform, build tools,
#     emulator, system image
#   - AVD twinbook_api36 (Pixel 6 profile, x86_64), sized for an 11 GB machine
# Downloads are cached in ~/.cache/twinbook/downloads. Running this script accepts the
# Android SDK license (the Android CLI records it in $ANDROID_HOME/licenses).
# Node is not installed here: it comes from nvm (version in .nvmrc). The script only
# checks that it is present.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

JDK_RELEASE="jdk-21.0.12.1+1"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
JDK_SHA256="ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94"

CMDLINE_TOOLS_VERSION="23.0"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
CMDLINE_TOOLS_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"

# Keep in sync with gradle/libs.versions.toml (compileSdk, buildTools).
SDK_PACKAGES=(
  "platform-tools"
  "platforms/android-37.2"
  "build-tools/37.0.0"
  "emulator"
  "system-images/android-36/google_apis/x86_64"
)
AVD_IMAGE="system-images;android-36;google_apis;x86_64"
AVD_DEVICE="pixel_6"
# key=value pairs enforced in the AVD config.ini.
AVD_CONFIG=(
  "hw.ramSize=3072"
  "vm.heapSize=256"
  "hw.cpu.ncore=4"
  "disk.dataPartition.size=6442450944"
  "hw.keyboard=yes"
  "hw.gpu.enabled=yes"
  "hw.gpu.mode=swiftshader_indirect"
  "fastboot.forceColdBoot=yes"
)

CACHE_DIR="${HOME}/.cache/twinbook/downloads"
CHANGES=0

log() { printf '[setup] %s\n' "$*"; }

download() { # url dest
  if [ -f "$2" ]; then return 0; fi
  log "downloading $(basename "$2")"
  mkdir -p "$(dirname "$2")"
  curl -fsSL --retry 3 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

install_jdk() {
  local target="${HOME}/.jdks/${JDK_RELEASE}"
  if [ -x "${target}/bin/java" ]; then
    log "JDK ${JDK_RELEASE} already installed"
  else
    local archive="${CACHE_DIR}/$(basename "${JDK_URL}")"
    download "${JDK_URL}" "${archive}"
    echo "${JDK_SHA256}  ${archive}" | sha256sum -c --quiet -
    mkdir -p "${HOME}/.jdks"
    tar -xzf "${archive}" -C "${HOME}/.jdks"
    log "JDK installed in ${target}"
    CHANGES=$((CHANGES + 1))
  fi
  if [ "$(readlink "${JAVA_HOME}" 2>/dev/null || true)" != "${JDK_RELEASE}" ]; then
    ln -sfn "${JDK_RELEASE}" "${JAVA_HOME}"
    log "linked ${JAVA_HOME} -> ${JDK_RELEASE}"
    CHANGES=$((CHANGES + 1))
  fi
}

install_cmdline_tools() {
  local target="${ANDROID_HOME}/cmdline-tools/latest"
  if [ -x "${target}/bin/sdkmanager" ] \
    && grep -q "^Pkg.Revision=${CMDLINE_TOOLS_VERSION}" "${target}/source.properties"; then
    log "cmdline-tools ${CMDLINE_TOOLS_VERSION} already installed"
    return
  fi
  local archive="${CACHE_DIR}/$(basename "${CMDLINE_TOOLS_URL}")"
  download "${CMDLINE_TOOLS_URL}" "${archive}"
  echo "${CMDLINE_TOOLS_SHA1}  ${archive}" | sha1sum -c --quiet -
  local tmp
  tmp="$(mktemp -d)"
  unzip -q "${archive}" -d "${tmp}"
  rm -rf "${target}"
  mkdir -p "$(dirname "${target}")"
  mv "${tmp}/cmdline-tools" "${target}"
  rm -rf "${tmp}"
  log "cmdline-tools installed in ${target}"
  CHANGES=$((CHANGES + 1))
}

install_sdk_packages() {
  # cmdline-tools 23 replaced sdkmanager with the Android CLI (`android sdk`). It uses
  # "/" in package paths, accepts the SDK license on install (writing
  # $ANDROID_HOME/licenses/android-sdk-license) and no longer has a --licenses step.
  # A package counts as installed when its package.xml exists.
  local pkg missing=()
  for pkg in "${SDK_PACKAGES[@]}"; do
    if [ -f "${ANDROID_HOME}/${pkg}/package.xml" ]; then
      log "SDK package ${pkg} already installed"
    else
      missing+=("${pkg}")
    fi
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    log "installing SDK packages: ${missing[*]}"
    android --sdk="${ANDROID_HOME}" sdk install "${missing[@]}"
    for pkg in "${missing[@]}"; do
      if [ ! -f "${ANDROID_HOME}/${pkg}/package.xml" ]; then
        log "ERROR: ${pkg} did not install"
        exit 1
      fi
    done
    CHANGES=$((CHANGES + 1))
  fi
  if [ ! -s "${ANDROID_HOME}/licenses/android-sdk-license" ]; then
    log "ERROR: ${ANDROID_HOME}/licenses/android-sdk-license is missing after install"
    exit 1
  fi
  log "SDK license accepted (${ANDROID_HOME}/licenses/android-sdk-license)"
}

create_avd() {
  local avd_home="${ANDROID_AVD_HOME:-${HOME}/.android/avd}"
  local config="${avd_home}/${TWINBOOK_AVD}.avd/config.ini"
  if [ -f "${config}" ]; then
    log "AVD ${TWINBOOK_AVD} already exists"
  else
    log "creating AVD ${TWINBOOK_AVD}"
    echo no | avdmanager create avd -n "${TWINBOOK_AVD}" -k "${AVD_IMAGE}" -d "${AVD_DEVICE}" > /dev/null
    CHANGES=$((CHANGES + 1))
  fi
  local kv key
  for kv in "${AVD_CONFIG[@]}"; do
    key="${kv%%=*}"
    if ! grep -qxF "${kv}" "${config}"; then
      sed -i "/^${key//./\\.}[[:space:]]*=/d" "${config}"
      echo "${kv}" >> "${config}"
      log "AVD config: ${kv}"
      CHANGES=$((CHANGES + 1))
    fi
  done
}

check_node() {
  local want
  want="$(tr -d '[:space:]v' < "${TWINBOOK_ROOT}/.nvmrc")"
  if ! command -v node > /dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" != "${want}" ]; then
    log "ERROR: Node ${want} not found under ~/.nvm/versions/node. Install it with: nvm install ${want}"
    exit 1
  fi
  log "Node $(node --version) at $(command -v node)"
}

check_kvm() {
  if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
    log "KVM accessible"
  else
    log "WARNING: /dev/kvm is not readable and writable by $(id -un); the emulator will not start."
    log "         Owner fix: sudo usermod -aG kvm $(id -un), then restart WSL (wsl --shutdown)."
  fi
}

mkdir -p "${CACHE_DIR}" "${ANDROID_HOME}"
install_jdk
install_cmdline_tools
install_sdk_packages
create_avd
check_node
check_kvm
java -version 2>&1 | head -n 1 | sed 's/^/[setup] /'
if [ "${CHANGES}" -eq 0 ]; then
  log "done: nothing changed"
else
  log "done: ${CHANGES} change(s)"
fi
