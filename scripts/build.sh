#!/usr/bin/env sh

current_dir="$(pwd)$(dirname "$0" | sed 's/^.//')"
parent_dir=$(dirname $current_dir)

function sha1sum() {
  if which shasum > /dev/null ; then
      shasum "$@"
  else
      sha1sum "$@"
  fi
}


bin_hash=$(sha1sum "$current_dir/src/main/scala/*" | sha1sum | cut -d ' ' -f 1)

if [ ! -f ".bin/$bin_hash" ]; then
  rm -rf .bin
  echo "building executable..."
  scala-cli --power package --native --native-mode release-fast . -o .bin/$bin_hash && ln -s $bin_hash .bin/codegen
fi
