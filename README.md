# (Experimental) Google APIs client code generator

![Maven Central Version](https://img.shields.io/maven-central/v/dev.rolang/gcp-codegen_3)
[![Sonatype Snapshots](https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.rolang/gcp-codegen_3.svg?label=Sonatype%20Snapshot)](https://oss.sonatype.org/content/repositories/snapshots/dev/rolang/gcp-codegen_3/)

Generates client code from Google's [disovery document](https://developers.google.com/discovery/v1/using).
Currently generates code for Scala 3.

### Usage

#### Usage via Scala command line example
See example under [example/generate.scala](./example/generate.scala).

```scala
//> using scala 3.6.2
//> using dep dev.rolang::gcp-codegen::0.0.1

import gcp.codegen.*, java.nio.file.*, GeneratorConfig.*

@main def run =
  val files = Task(
    specsInput = SpecsInput.FilePath(Path.of("pubsub_v1.json")),
    config = GeneratorConfig(
      outDir = Path.of("out"),
      resourcesPkg = "example.pubsub.v1.resource",
      schemasPkg = "example.pubsub.v1.schemas",
      httpSource = HttpSource.Sttp4,
      jsonCodec = JsonCodec.Jsoniter,
      dialect = Dialect.Scala3,
      arrayType = ArrayType.List,
      preprocess = specs => specs
    )
  ).runAwait()
  println(s"Generated ${files.length} files")
```
Run example:
```shell
cd example && scala generate.scala
```
See output in `example/out`.

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

### Building and testing

Generate, compile and run tests for generated code
```shell
sbt buildCliBinary test
```