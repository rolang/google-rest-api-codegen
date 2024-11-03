#!/usr/bin/env bash

test_dir=src/test/scala/generated
bin_hash=$(sha1sum src/main/scala/* | sha1sum | cut -d ' ' -f 1)

if [ ! -f ".bin/$bin_hash" ]; then 
  rm -rf .bin
  echo ".bin/$bin_hash was not found"
  echo "building executable with $(scala-cli --version)"
  scala-cli --power package --native --native-mode release-fast . -o .bin/$bin_hash && \
  ln -s $bin_hash .bin/codegen
fi

rm -rf $test_dir && \
.bin/codegen \
 --out-dir=$test_dir \
 --specs=src/test/resources/pubsub_v1.json \
 --resources-pkg=gcp.pubsub.v1.resources.jsoniter \
 --schemas-pkg=gcp.pubsub.v1.schemas.jsoniter \
 --http-source=sttp4 \
 --json-codec=jsoniter && \
.bin/codegen \
 --out-dir=$test_dir \
 --specs=src/test/resources/storage_v1.json \
 --resources-pkg=gcp.storage.v1.resources.jsoniter \
 --schemas-pkg=gcp.storage.v1.schemas.jsoniter \
 --http-source=sttp4 \
 --json-codec=jsoniter && \
.bin/codegen \
 --out-dir=$test_dir \
 --specs=src/test/resources/aiplatform_v1.json \
 --resources-pkg=gcp.aiplatform.v1.resources.jsoniter \
 --schemas-pkg=gcp.aiplatform.v1.schemas.jsoniter \
 --http-source=sttp4 \
 --json-codec=jsoniter && \
scala-cli fmt . && \
scala-cli test .