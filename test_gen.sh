#!/usr/bin/env bash

# script to support with quick execution and code inspection on generator changes
# runs code generator via scala-cli for given config
# will ouput code under test-local/src/main/scala

spec=aiplatform
version=v1
json_codec=jsoniter
http_source=sttp4
array_type=ziochunk
out_dir=test-local/src/main/scala/test-$spec-$version/$http_source/$spec/$json_codec
rm -rf $out_dir && mkdir -p $out_dir

echo "running generator for $spec to $out_dir"
scala modules/cli/src/main/scala/cli.scala -- \
    -specs=modules/test-resources/src/main/resources/${spec}_${version}.json \
    -out-dir=$out_dir \
    -out-pkg=gcp.${spec}.${version}.${http_source}.${json_codec}.$array_type \
    -http-source=$http_source \
    -json-codec=$json_codec \
    -jsoniter-json-type=_root_.custom.jsoniter.Json \
    -array-type=$array_type