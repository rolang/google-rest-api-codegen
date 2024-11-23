import codegen.*
import codegen.GeneratorConfig.*
import scala.concurrent.Future
import java.nio.file.Path
import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import java.nio.file.Files

@main def generate(args: String*) = {
  val (outSrcDir, dialect, httpSource, jsonCodec, arrayType) = args.toList match
    case outDir :: scalaV :: httpSource :: jsonCodec :: arrayType :: Nil =>
      (
        Path.of(
          outDir,
          if scalaV.startsWith("3") then "scala-3" else "scala-2"
        ),
        if scalaV.startsWith("3") then Dialect.Scala3 else Dialect.Scala2,
        HttpSource.valueOf(httpSource),
        JsonCodec.valueOf(jsonCodec),
        ArrayType.valueOf(arrayType)
      )
    case other => throw IllegalArgumentException(s"Invalid args ${other.mkString(" ")}")

  def resourcePath(resource: String) =
    s"modules/test-gen/shared/src/main/resources/$resource"

  val tasks: List[Task] = for {
    (specPkg, specPath) <- List(
      "gcp.pubsub.v1" -> resourcePath("pubsub_v1.json"),
      "gcp.storage.v1" -> resourcePath("storage_v1.json"),
      "gcp.aiplatform.v1" -> resourcePath("aiplatform_v1.json")
    )
    basePkgName = s"$specPkg.${httpSource}_${jsonCodec}_${arrayType}".toLowerCase()
    baseOut = Path.of(outSrcDir.toString(), basePkgName.replace(".", "/"))
  } yield Task(
    SpecsInput.FilePath(Path.of(specPath)),
    GeneratorConfig(
      outDir = baseOut,
      resourcesPkg = basePkgName + ".resources",
      schemasPkg = basePkgName + ".schemas",
      httpSource = httpSource,
      jsonCodec = jsonCodec,
      arrayType = arrayType,
      dialect = dialect
    )
  )

  def deleteRecursively(path: Path): Future[Unit] = Future {
    try {
      println(s"Deleting directory $path")
      if (Files.exists(path)) {
        Files
          .walk(path)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(Files.delete)
      }
      println(s"Directory $path deleted")
    } catch {
      case e: Throwable => println(s"Failed to delete directory: ${e.getMessage}")
    }
  }

  Await
    .ready(
      for {
        _ <- deleteRecursively(outSrcDir)
        _ <- Future(Files.createDirectories(outSrcDir))
        _ <- Future.traverse(tasks)(_.run)
      } yield (),
      30.seconds
    )
}
