# (Experimental) Google APIs client code generator

![Maven Central Version](https://img.shields.io/maven-central/v/dev.rolang/gcp-codegen_3)

⚠️ _This project is in an experimental stage (with a messy code base), use with care if you want to give it a try_.

Generates client code from Google's [disovery document](https://developers.google.com/discovery/v1/using) for your Scala (3) tech stack.  
Currently it provides following configurations for generated code:
 - Http sources: [Sttp4](https://sttp.softwaremill.com/en/latest)
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
//> using scala 3.7.4
//> using dep dev.rolang::gcp-codegen::0.0.13

import gcp.codegen.*, java.nio.file.*, GeneratorConfig.*

@main def run =
  val files = Task(
    specsInput = SpecsInput.StdIn,
    config = GeneratorConfig(
      outDir = Path.of("out"),
      outPkg = "example.pubsub.v1",
      httpSource = HttpSource.Sttp4,
      jsonCodec = JsonCodec.Jsoniter,
      arrayType = ArrayType.List,
      preprocess = specs => specs
    )
  ).runAwait()
  println(s"Generated ${files.length} files")
```
Run example:
```shell
cat example/pubsub_v1.json | scala example/generate.scala
```
See output in `example/out`.

#### Command-line executable usage

##### Configuration parameters (currently supported):

| Configuration       | Description | Options | Default |
| ------------------- | ---------------- | ------- | --- |
| -specs              | Can be `stdin` or a path to the JSON file. | | |
| -out-dir            | Ouput directory | | |
| -out-pkg            | Output package |  | |
| -http-source        | Generated http source. | [Sttp4](https://sttp.softwaremill.com/en/stable) | |
| -json-codec         | Generated JSON codec | [Jsoniter](https://github.com/plokhotnyuk/jsoniter-scala), [ZioJson](https://zio.dev/zio-json)  | |
| -jsoniter-json-type | In case of Jsoniter a fully qualified name of the custom type that can represent a raw Json value | |
| -array-type         | Collection type for JSON arrays | `List`, `Vector`, `Array`, `ZioChunk` | `List` |
| -include-resources  | Optional resource filter. | | |

##### Jsoniter Json type and codec example
Jsoniter doesn't ship with a type that can represent raw Json values to be used for mapping of `any` / `object` types,  
but it provides methods to read / write raw values as bytes (related [issue](https://github.com/plokhotnyuk/jsoniter-scala/issues/1257)).  
Given that we can create a custom type with a codec which can look for example like [that](modules/example-jsoniter-json/shared/src/main/scala/json.scala):
```scala
package example.jsoniter
import com.github.plokhotnyuk.jsoniter_scala.core.*

opaque type Json = Array[Byte]
object Json:
  def writeToJson[T: JsonValueCodec](v: T): Json = writeToArray[T](v)

  given codec: JsonValueCodec[Json] = new JsonValueCodec[Json]:
    override def decodeValue(in: JsonReader, default: Json): Json = in.readRawValAsBytes()
    override def encodeValue(x: Json, out: JsonWriter): Unit = out.writeRawVal(x)
    override val nullValue: Json = Array[Byte](0)

  extension (v: Json)
    def readAsUnsafe[T: JsonValueCodec]: T = readFromArray(v)
    def readAs[T: JsonValueCodec]: Either[Throwable, T] =
      try Right(readFromArray(v))
      catch case t: Throwable => Left(t)
```
Then pass it as argument to the code generator like `-jsoniter-json-type=_root_.example.jsoniter.Json`.  
Since this type and codec can be shared across generated clients it has to be provided (at least for now)
instead of being generated for each client to avoid duplicated / redundant code.  

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
  -json-codec=ziojson \
  -include-resources='projects.*,!projects.snapshots' # optional filters
```

### Building and testing

Generate, compile and run tests for generated code
```shell
sbt buildCliBinary test
```