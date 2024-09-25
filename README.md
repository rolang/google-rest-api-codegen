# (Experimental) Google APIs client code generator

Generates client code from Google's [disovery document](https://developers.google.com/discovery/v1/using).

### Running and testing via [scala-cli](https://scala-cli.virtuslab.org/)

```shell
# ensure target directory is empty: rm -rf src/test/scala/generated
scala-cli . -- \
 --out-dir=src/test/scala/generated \
 --specs=src/test/resources/pubsub_v1.json \
 --resources-pkg=gcp.pubsub.v1.resources \
 --schemas-pkg=gcp.pubsub.v1.schemas \
 --http-source=sttp4 \
 --json-codec=ziojson \
 --include-resources='projects.*,!projects.snapshots' # optional filters

# run formatter 
scala-cli fmt .

# checkout the ouput in src/test/scala/generated

# compile and test generated output
scala-cli test .
```
