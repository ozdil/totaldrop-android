#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Isolated build recipe for x86_64 (x86_64-linux-android):
#   1. Compiles the 16 KB page-aligned x86_64 Rust bridge (.so) via Android NDK Clang.
#   2. Verifies ELF PT_LOAD 16 KB alignment (0x4000) for Android 15+ 16 KB x86_64 emulators.
#   3. Builds split-ABI x86_64 APKs for Chromebooks (ARCVM), Android PC / Play Games on PC,
#      Google TV x86, and CI emulators.
#
# Options:
#   --rust-only   Compile and verify only the native Rust .so libraries.
#   --skip-tv     Skip the Jetpack Compose for TV Gradle build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUST_TARGET="x86_64-linux-android"
ANDROID_ABI="x86_64"
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
CLANG_BIN="${TOOLCHAIN_BIN}/x86_64-linux-android${API_LEVEL}-clang"
CLANGXX_BIN="${TOOLCHAIN_BIN}/x86_64-linux-android${API_LEVEL}-clang++"
AR_BIN="${TOOLCHAIN_BIN}/llvm-ar"
RANLIB_BIN="${TOOLCHAIN_BIN}/llvm-ranlib"
READELF_BIN="${TOOLCHAIN_BIN}/llvm-readelf"

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
export CC_x86_64_linux_android="${CLANG_BIN}"
export CXX_x86_64_linux_android="${CLANGXX_BIN}"
export AR_x86_64_linux_android="${AR_BIN}"
export RANLIB_x86_64_linux_android="${RANLIB_BIN}"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="${CLANG_BIN}"
export CARGO_ENCODED_RUSTFLAGS="-L$(printf '\x1f')${WORK_DIR}/libgcc_workaround$(printf '\x1f')-C$(printf '\x1f')link-arg=-Wl,--hash-style=both$(printf '\x1f')-C$(printf '\x1f')link-arg=-Wl,-z,max-page-size=16384$(printf '\x1f')-C$(printf '\x1f')link-arg=-Wl,-z,common-page-size=16384$(printf '\x1f')-C$(printf '\x1f')link-arg=-Wl,--build-id=sha1"

echo "==> [x86_64] Building Rust bridge (${RUST_TARGET}) with 16 KB page alignment..."
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

# Verify 16 KB ELF LOAD alignment (0x4000 = 16384 bytes) for Android 15+ 16 KB emulator images.
if [[ -x "${READELF_BIN}" ]]; then
    echo "==> [x86_64] Verifying 16 KB ELF PT_LOAD segment alignment..."
    LOAD_ALIGNS="$("${READELF_BIN}" -lW "${JNI_OUT}/librust_lib_totaldrop.so" | awk '$1 == "LOAD" { print $NF }')"
    if [[ -z "${LOAD_ALIGNS}" ]]; then
        echo "Error: No ELF PT_LOAD segments found in librust_lib_totaldrop.so!" >&2
        exit 1
    fi
    for align in ${LOAD_ALIGNS}; do
        bytes=$((align))
        if (( bytes < 16384 || bytes % 16384 != 0 )); then
            echo "Error: librust_lib_totaldrop.so PT_LOAD alignment ${align} (${bytes} B) < 16 KB (0x4000)!" >&2
            "${READELF_BIN}" -lW "${JNI_OUT}/librust_lib_totaldrop.so" >&2
            exit 1
        fi
    done
fi

mkdir -p "${MODULE_ROOT}/ui-android-tv/src/main/jniLibs/${ANDROID_ABI}"
cp -f "${JNI_OUT}/libtotaldrop_app_rust.so" "${MODULE_ROOT}/ui-android-tv/src/main/jniLibs/${ANDROID_ABI}/libtotaldrop_app_rust.so"

if [[ "${RUST_ONLY}" -eq 0 ]]; then
    if command -v flutter >/dev/null 2>&1; then
        echo "==> [x86_64] Building M3 Expressive x86_64 split-ABI APK (Chromebook / Emulator)..."
        (
            cd "${MODULE_ROOT}/ui-m3-expressive"
            flutter pub get --enforce-lockfile
            flutter build apk --release --split-per-abi --target-platform android-x64
            cp -f build/app/outputs/flutter-apk/app-x86_64-release.apk \
                "${OUT_DIR}/TotalDrop-m3-expressive-${ANDROID_ABI}.apk"
        )
    fi

    if [[ "${SKIP_TV}" -eq 0 ]] && command -v gradle >/dev/null 2>&1; then
        echo "==> [x86_64] Building Native Android TV x86_64 split-ABI APK..."
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

echo "==> [x86_64] Build complete. Artifacts and SHA256SUMS written to ${OUT_DIR}"
