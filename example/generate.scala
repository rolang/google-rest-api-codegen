//> using scala 3.7.3
//> using dep dev.rolang::gcp-codegen::0.0.8

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
