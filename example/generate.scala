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
