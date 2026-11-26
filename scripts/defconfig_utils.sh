#!/bin/bash

: "${CONFIG_FILE:?CONFIG_FILE 未设置}"
: "${MAKEFILE:?MAKEFILE 未设置}"

declare -a KCFG_HIDDEN=()

kcfg_set() {
  local val="$1"
  local key="${val%%=*}"
  if grep -qF "${key}=" "$CONFIG_FILE" || grep -qF "# ${key} is not set" "$CONFIG_FILE"; then
    VAL="$val" awk -v key="$key" '
      $0 == "# " key " is not set" || index($0, key "=") == 1 { print ENVIRON["VAL"]; next }
      { print }
    ' "$CONFIG_FILE" > "${CONFIG_FILE}.tmp" && mv "${CONFIG_FILE}.tmp" "$CONFIG_FILE" || rm -f "${CONFIG_FILE}.tmp"
  else
    printf '%s\n' "$val" >> "$CONFIG_FILE"
  fi
}

kcfg_set_hidden() {
  local val="$1"
  local key="${val%%=*}"
  kcfg_set "$val"
  KCFG_HIDDEN+=("$key")
}

kcfg_inject_fake() {
  local marker="# --- fake_config injector ---"
  if ! grep -qF "$marker" "$MAKEFILE"; then
    {
      printf '\n%s\n' "$marker"
      printf '$(obj)/config_data: $(KCONFIG_CONFIG) FORCE\n'
      printf '\t$(Q)sed '
      printf " -e '/^# KernelSU/,/^# end of KernelSU/d'"
      printf " -e '/^# Baseband Security Guard Configuration/,/^# end of Baseband Security Guard Configuration/d'"
      printf " -e '/^CONFIG_KSU/d' -e '/^# CONFIG_KSU/d'"
      printf " -e '/^CONFIG_KPM=/d' -e '/^# CONFIG_KPM /d'"
      printf " -e '/^CONFIG_BBG/d' -e '/^# CONFIG_BBG/d'"
      printf " -e '/^CONFIG_NOMOUNT/d' -e '/^# CONFIG_NOMOUNT/d'"
      printf " -e '/^CONFIG_REKERNEL/d' -e '/^# CONFIG_REKERNEL/d'"
      printf " -e '/^CONFIG_DROID_SPACES/d' -e '/^# CONFIG_DROID_SPACES/d'"
      printf " -e '/^CONFIG_TCP_CONG_BBR3=/d' -e '/^# CONFIG_DEFAULT_BBR3 /d'"
      printf " -e 's|,baseband_guard||g'"
      printf " -e 's|^CONFIG_TMPFS_POSIX_ACL=.*|# CONFIG_TMPFS_POSIX_ACL is not set|'"
      printf " -e 's|^CONFIG_TMPFS_XATTR=.*|# CONFIG_TMPFS_XATTR is not set|'"
      printf " -e 's|^# CONFIG_LTO_CLANG_FULL is not set.*|CONFIG_LTO_CLANG_FULL=y|'"
      printf " -e 's|^CONFIG_LTO_CLANG_THIN=.*|# CONFIG_LTO_CLANG_THIN is not set|'"
      printf " -e 's|^# CONFIG_TRIM_UNUSED_KSYMS is not set.*|CONFIG_TRIM_UNUSED_KSYMS=y|'"
      printf ' $(foreach opt,$(CONFIG_FAKE_DISABLE),-e '"'"'s|^$(opt)=.*|# $(opt) is not set|'"'"') $(KCONFIG_CONFIG) > $@\n'
    } >> "$MAKEFILE"
  fi
  if [ -n "${GITHUB_ENV:-}" ]; then
    if [ "${#KCFG_HIDDEN[@]}" -gt 0 ]; then
      echo "CONFIG_FAKE_DISABLE=${KCFG_HIDDEN[*]}" >> "$GITHUB_ENV"
    fi
    echo "KBUILD_BUILD_USER=build-user" >> "$GITHUB_ENV"
    echo "KBUILD_BUILD_HOST=build-host" >> "$GITHUB_ENV"
  fi
}
