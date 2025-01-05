# (Experimental) Google APIs client code generator

Generates client code from Google's [disovery document](https://developers.google.com/discovery/v1/using).
Currently generates code for Scala 3.

### Usage

#### Command-line usage

##### Configuration parameters (currently supported):

| Configuration       | Description | Options | Default |
| ------------------- | ---------------- | ------- | --- |
| --out-dir           | Ouput directory | | |
| --specs             | Can be `stdin` or a path to the JSON file. | | |
| --resources-pkg     | Target package for generated resources |  | |
| --schemas-pkg       | Target package for generated schemas |  | |
| --http-source       | Generated http source. | [Sttp4](https://sttp.softwaremill.com/en/latest), [Sttp3](https://sttp.softwaremill.com/en/stable) | |
| --json-codec        | Generated JSON codec | [Jsoniter](https://github.com/plokhotnyuk/jsoniter-scala), [ZioJson](https://zio.dev/zio-json)  | |
| --array-type        | Collection type for JSON arrays | `List`, `Vector`, `Array`, `ZioChunk` | `List` |
| --include-resources | Optional resource filter. | | |

##### Examples:

```shell
curl 'https://pubsub.googleapis.com/$discovery/rest?version=v1' > pubsub_v1.json

codegen \
 --out-dir=src/scala/main/generated \
 --specs=./pubsub_v1.json \
 --resources-pkg=gcp.pubsub.v1.resources \
 --schemas-pkg=gcp.pubsub.v1.schemas \
 --http-source=sttp4 \
 --json-codec=jsoniter \
 --include-resources='projects.*,!projects.snapshots' # optional filters
```

#### TODO add usage example in Scala ... 

### Building and testing

Generate, compile and run tests for generated code
```shell
sbt buildCliBinary test
```