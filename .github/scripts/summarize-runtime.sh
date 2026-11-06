#!/usr/bin/env bash
# Deduplicated summary of every runtime mixin/linkage failure in a server boot's logs ($1 = log dir).
D="$1"
cat "$D"/console.log "$D"/latest.log "$D"/debug.log "$D"/crash-reports/*.txt 2>/dev/null \
  | grep -oE "(Critical injection failure|InvalidInjectionException|InvalidMixinException|InvalidAccessorException|MixinApplyError|InjectionError|@Mixin target [^ ]+ was not found|Mixin [^ ]+ failed|was not found in the target|cannot overwrite method|failed injection check|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|AbstractMethodError|IncompatibleClassChangeError|ClassCastException|IllegalAccessError)[^\[]{0,400}" \
  | sed -E 's/[[:space:]]+$//' | grep -vE "ClassNotFoundException: net\.minecraft\.client\." \
  | sort | uniq -c | sort -rn
