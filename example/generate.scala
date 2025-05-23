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
