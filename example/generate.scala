//> using scala 3.7.4
//> using dep dev.rolang::gcp-codegen::0.0.12

import gcp.codegen.*, java.nio.file.*, GeneratorConfig.*

@main def run =
  val files = Task(
    specsInput = SpecsInput.StdIn,
    config = GeneratorConfig(
      outDir = Path.of("out"),
      outPkg = "example.pubsub.v1",
      httpSource = HttpSource.Sttp4,
      jsonCodec = JsonCodec.ZioJson,
      arrayType = ArrayType.List,
      preprocess = specs => specs
    )
  ).runAwait()
  println(s"Generated ${files.length} files")
