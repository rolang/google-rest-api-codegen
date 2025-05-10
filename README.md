# (Experimental) Google APIs client code generator

![Maven Central Version](https://img.shields.io/maven-central/v/dev.rolang/gcp-codegen_3)

⚠️ _This project is in an experimental stage (with a messy code base), use with care if you want to give it a try_.

Generates client code from Google's [disovery document](https://developers.google.com/discovery/v1/using) for your Scala (3) tech stack.  
Currently it provides following configurations for generated code:
 - Http sources: [Sttp4](https://sttp.softwaremill.com/en/latest), [Sttp3](https://sttp.softwaremill.com/en/stable)
 - JSON codecs: [Jsoniter](https://github.com/plokhotnyuk/jsoniter-scala), [ZioJson](https://zio.dev/zio-json)
 - JSON Array collection type: `List`, `Vector`, `Array`, `ZioChunk`

__NOTE__: Generated code does not include authentication.

If you're using [http4s](https://github.com/http4s/http4s) and [circe](https://github.com/circe/circe) you may also want to check some similar projects:
 - [hamnis/google-discovery-scala](https://github.com/hamnis/google-discovery-scala)
 - [armanbilge/gcp4s](https://github.com/armanbilge/gcp4s)

### Usage

The generator can be used with any tool that can perform system calls to a command line executable or add Scala 3 dependencies (e.g. [scala command line](https://scala-cli.virtuslab.org/), [sbt 1](https://www.scala-sbt.org/1.x/docs/), [sbt 2](https://www.scala-sbt.org/2.x/docs/en/index.html), [mill](https://mill-build.org), etc.).  

#### Usage via Scala command line example
See example under [example/generate.scala](./example/generate.scala).

```scala
//> using scala 3.7.0
//> using dep dev.rolang::gcp-codegen::0.0.5

import gcp.codegen.*, java.nio.file.*, GeneratorConfig.*

@main def run =
  val files = Task(
    specsInput = SpecsInput.FilePath(Path.of("pubsub_v1.json")),
    config = GeneratorConfig(
      outDir = Path.of("out"),
      outPkg = "example.pubsub.v1",
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
cd example && scala generate.scala && scala fmt ./out
```
See output in `example/out`.

#### Command-line executable usage

##### Configuration parameters (currently supported):

| Configuration       | Description | Options | Default |
| ------------------- | ---------------- | ------- | --- |
| -specs             | Can be `stdin` or a path to the JSON file. | | |
| -out-dir           | Ouput directory | | |
| -out-pkg           | Output package |  | |
| -http-source       | Generated http source. | [Sttp4](https://sttp.softwaremill.com/en/latest), [Sttp3](https://sttp.softwaremill.com/en/stable) | |
| -json-codec        | Generated JSON codec | [Jsoniter](https://github.com/plokhotnyuk/jsoniter-scala), [ZioJson](https://zio.dev/zio-json)  | |
| -array-type        | Collection type for JSON arrays | `List`, `Vector`, `Array`, `ZioChunk` | `List` |
| -include-resources | Optional resource filter. | | |

##### Examples:

_Command line binaries are not published (not yet).  
A command line binary can be built from the source via `sbt buildCliBinary`.  
The output directory is `modules/cli/target/bin`.  
E.g. on Linux the output file will be like `modules/cli/target/bin/gcp-codegen-x86_64-linux`._

```shell
curl 'https://pubsub.googleapis.com/$discovery/rest?version=v1' > pubsub_v1.json

# the path to the executable may be different
./modules/cli/target/bin/gcp-codegen-x86_64-linux \
  -out-dir=src/scala/main/generated \
  -specs=./pubsub_v1.json \
  -out-pkg=gcp.pubsub.v1 \
  -http-source=sttp4 \
  -json-codec=jsoniter \
  -include-resources='projects.*,!projects.snapshots' # optional filters
```

### Building and testing

Generate, compile and run tests for generated code
```shell
sbt buildCliBinary test
```