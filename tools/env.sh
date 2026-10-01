# Usage: source tools/env.sh
#
# Sets up the twinBook build environment in the current shell: JAVA_HOME (Temurin 21),
# ANDROID_HOME, and a PATH with the JDK, Android SDK tools and the nvm Node version
# named in .nvmrc placed first. Works from a non-interactive shell with no profile.
# Every script in tools/ sources this file. Safe to source more than once.
# Does not change shell options, so it is safe under `set -euo pipefail`.

TWINBOOK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export TWINBOOK_ROOT

export JAVA_HOME="${HOME}/.jdks/temurin-21"
export ANDROID_HOME="${HOME}/Android/Sdk"
# Older emulator builds still read ANDROID_SDK_ROOT. Keep both pointing at the same SDK.
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export TWINBOOK_AVD="twinbook_api36"

# Node from nvm, without loading nvm itself: highest installed version matching the
# major version in .nvmrc.
_twinbook_node_major="$(tr -d '[:space:]v' < "${TWINBOOK_ROOT}/.nvmrc" 2>/dev/null || true)"
_twinbook_node_bin=""
if [ -n "${_twinbook_node_major}" ] && [ -d "${HOME}/.nvm/versions/node" ]; then
  _twinbook_node_ver="$(ls -1 "${HOME}/.nvm/versions/node" 2>/dev/null \
    | grep -E "^v${_twinbook_node_major}\." | sort -V | tail -n 1 || true)"
  if [ -n "${_twinbook_node_ver}" ]; then
    _twinbook_node_bin="${HOME}/.nvm/versions/node/${_twinbook_node_ver}/bin"
  fi
fi

_twinbook_path="${JAVA_HOME}/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${ANDROID_HOME}/cmdline-tools/latest/bin"
if [ -n "${_twinbook_node_bin}" ]; then
  _twinbook_path="${_twinbook_node_bin}:${_twinbook_path}"
fi
# env -i leaves PATH unset; fall back to the system default.
export PATH="${_twinbook_path}:${PATH:-/usr/local/bin:/usr/bin:/bin}"

unset _twinbook_node_major _twinbook_node_ver _twinbook_node_bin _twinbook_path
