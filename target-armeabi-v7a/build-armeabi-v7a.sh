#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Isolated build recipe for armeabi-v7a (armv7-linux-androideabi):
#   1. Compiles the 32-bit ARMv7 + NEON Rust bridge (.so) via Android NDK Clang.
#   2. Builds the split-ABI armeabi-v7a APK for legacy 32-bit ARM phones and
#      32-bit Android TV / Google TV streaming sticks (Cortex-A53/A55 32-bit OS).
#
# Options:
#   --rust-only   Compile only the native Rust .so libraries.
#   --skip-tv     Skip the Jetpack Compose for TV Gradle build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUST_TARGET="armv7-linux-androideabi"
ANDROID_ABI="armeabi-v7a"
API_LEVEL="${ANDROID_API_LEVEL:-31}"

RUST_ONLY=0
SKIP_TV=0

for arg in "$@"; do
    case "$arg" in
        --rust-only) RUST_ONLY=1 ;;
        --skip-tv) SKIP_TV=1 ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

resolve_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
        printf '%s\n' "${ANDROID_NDK_HOME}"
        return 0
    fi
    if [[ -n "${ANDROID_NDK_LATEST_HOME:-}" && -d "${ANDROID_NDK_LATEST_HOME}" ]]; then
        printf '%s\n' "${ANDROID_NDK_LATEST_HOME}"
        return 0
    fi
    if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT}" ]]; then
        printf '%s\n' "${ANDROID_NDK_ROOT}"
        return 0
    fi
    local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "${sdk}" && -d "${sdk}/ndk" ]]; then
        local latest
        latest="$(find "${sdk}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
        if [[ -n "${latest}" ]]; then
            printf '%s\n' "${latest}"
            return 0
        fi
    fi
    echo "Error: ANDROID_NDK_HOME (or ANDROID_SDK_ROOT/ndk/<version>) is not set." >&2
    exit 1
}

NDK_DIR="$(resolve_ndk)"
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) HOST_TAG="linux-x86_64" ;;
    Darwin-arm64|Darwin-x86_64) HOST_TAG="darwin-x86_64" ;;
    *)
        echo "Unsupported NDK host platform: $(uname -s)-$(uname -m)" >&2
        exit 1
        ;;
esac

TOOLCHAIN_BIN="${NDK_DIR}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
CLANG_BIN="${TOOLCHAIN_BIN}/armv7a-linux-androideabi${API_LEVEL}-clang"
CLANGXX_BIN="${TOOLCHAIN_BIN}/armv7a-linux-androideabi${API_LEVEL}-clang++"
AR_BIN="${TOOLCHAIN_BIN}/llvm-ar"
RANLIB_BIN="${TOOLCHAIN_BIN}/llvm-ranlib"

if [[ ! -x "${CLANG_BIN}" ]]; then
    echo "Error: NDK Clang wrapper not found at ${CLANG_BIN}" >&2
    exit 1
fi

WORK_DIR="${SCRIPT_DIR}/work"
OUT_DIR="${SCRIPT_DIR}/out"
JNI_OUT="${OUT_DIR}/jniLibs/${ANDROID_ABI}"
mkdir -p "${WORK_DIR}/libgcc_workaround" "${JNI_OUT}"

# NDK r23+ replaced libgcc with libunwind; provide a linker redirect matching Cargokit.
printf 'INPUT(-lunwind)\n' > "${WORK_DIR}/libgcc_workaround/libgcc.a"

export PATH="${TOOLCHAIN_BIN}:${PATH}"
export CC_armv7_linux_androideabi="${CLANG_BIN}"
export CXX_armv7_linux_androideabi="${CLANGXX_BIN}"
export AR_armv7_linux_androideabi="${AR_BIN}"
export RANLIB_armv7_linux_androideabi="${RANLIB_BIN}"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="${CLANG_BIN}"
export CARGO_ENCODED_RUSTFLAGS="-L$(printf '\x1f')${WORK_DIR}/libgcc_workaround$(printf '\x1f')-C$(printf '\x1f')link-arg=-Wl,--hash-style=both$(printf '\x1f')-C$(printf '\x1f')link-arg=-Wl,--build-id=sha1$(printf '\x1f')-C$(printf '\x1f')target-feature=+v7,+thumb-mode,+neon"

echo "==> [armeabi-v7a] Building 32-bit ARMv7 Rust bridge (${RUST_TARGET})..."
(
    cd "${MODULE_ROOT}"
    RUSTUP_TOOLCHAIN=stable cargo build \
        --locked \
        --release \
        -p rust_lib_totaldrop \
        --target "${RUST_TARGET}" \
        --config "${SCRIPT_DIR}/cargo-config.toml"
)

BUILT_SO="${MODULE_ROOT}/target/${RUST_TARGET}/release/librust_lib_totaldrop.so"
cp -f "${BUILT_SO}" "${JNI_OUT}/librust_lib_totaldrop.so"
cp -f "${BUILT_SO}" "${JNI_OUT}/libtotaldrop_app_rust.so"

mkdir -p "${MODULE_ROOT}/ui-android-tv/src/main/jniLibs/${ANDROID_ABI}"
cp -f "${JNI_OUT}/libtotaldrop_app_rust.so" "${MODULE_ROOT}/ui-android-tv/src/main/jniLibs/${ANDROID_ABI}/libtotaldrop_app_rust.so"

if [[ "${RUST_ONLY}" -eq 0 ]]; then
    if command -v flutter >/dev/null 2>&1; then
        echo "==> [armeabi-v7a] Building M3 Expressive 32-bit split-ABI APK..."
        (
            cd "${MODULE_ROOT}/ui-m3-expressive"
            flutter pub get --enforce-lockfile
            flutter build apk --release --split-per-abi --target-platform android-arm
            cp -f build/app/outputs/flutter-apk/app-armeabi-v7a-release.apk \
                "${OUT_DIR}/TotalDrop-m3-expressive-${ANDROID_ABI}.apk"
        )
    fi

    if [[ "${SKIP_TV}" -eq 0 ]] && command -v gradle >/dev/null 2>&1; then
        echo "==> [armeabi-v7a] Building Native Android TV 32-bit split-ABI APK..."
        (
            cd "${MODULE_ROOT}/ui-android-tv"
            gradle --no-daemon assembleRelease -PtargetAbi="${ANDROID_ABI}"
            find build/outputs/apk/release -name "*${ANDROID_ABI}*.apk" -exec \
                cp -f {} "${OUT_DIR}/TotalDrop-android-tv-${ANDROID_ABI}.apk" \;
        )
    fi
fi

(
    cd "${OUT_DIR}"
    find . -maxdepth 3 -type f \( -name '*.so' -o -name '*.apk' \) -print0 \
        | sort -z \
        | xargs -0 sha256sum > SHA256SUMS
)

echo "==> [armeabi-v7a] Build complete. Artifacts and SHA256SUMS written to ${OUT_DIR}"
