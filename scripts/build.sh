#!/usr/bin/env sh

current_dir="$(dirname "$0" | sed -E "s:^\.:$(pwd):")"
parent_dir=$(dirname $current_dir)


function sha1sum() {
  which shasum > /dev/null 2>&1
  shasum_exist=$?
  if [ $shasum_exist -ne 0 ]  ; then
      sha1sum "$@"
  else
      shasum "$@"
  fi
}

cd $parent_dir

files="$parent_dir/src/main/scala/*"
eval expanded_files="$files"
bin_hash=$(sha1sum $expanded_files | sha1sum | cut -d ' ' -f 1)


if [ ! -f ".bin/$bin_hash" ]; then
  rm -rf .bin
  echo "building executable..."
  scala-cli --power package --native --native-mode release-fast . -o .bin/$bin_hash && ln -s $bin_hash .bin/codegen
fi
