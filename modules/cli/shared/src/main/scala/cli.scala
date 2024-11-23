import codegen.*
import codegen.GeneratorConfig.*

import java.nio.file.*
import upickle.default.*
import GeneratorConfig.*
import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import java.io.File
import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters.*

@main def run(args: String*) =
  argsToTask(args) match
    case Left(err) => Console.err.println(s"Invalid arguments: $err")
    case Right(task) =>
      Await
        .ready(task.run, 30.seconds)
        .onComplete {
          case Failure(exception) => Console.err.println(s"Failure: ${exception.printStackTrace()}")
          case Success(_)         => ()
        }

def argsToTask(args: Seq[String]): Either[String, Task] =
  val argsMap = args.toList
    .flatMap(_.split('=').map(_.trim().toLowerCase()))
    .sliding(2, 2)
    .collect { case a :: b :: _ =>
      a -> b
    }
    .toMap

  for {
    outDir <- argsMap
      .get("--out-dir")
      .map(p => Path.of(p))
      .toRight("Missing --out-dir")
    specs <- argsMap
      .get("--specs")
      .map {
        case "stdin" => SpecsInput.StdIn
        case p       => SpecsInput.FilePath(Path.of(p))
      }
      .toRight("Missing --specs")
    resourcesPkg <- argsMap
      .get("--resources-pkg")
      .toRight("Missing --resources-pkg")
    schemasPkg <- argsMap.get("--schemas-pkg").toRight("Missing --schemas-pkg")
    httpSource <- argsMap
      .get("--http-source")
      .flatMap(v => HttpSource.values.find(_.toString().equalsIgnoreCase(v)))
      .toRight("Missing or invalid --http-source")
    jsonCodec <- argsMap
      .get("--json-codec")
      .flatMap(v => JsonCodec.values.find(_.toString().equalsIgnoreCase(v)))
      .toRight("Missing or invalid --json-codec")
    dialect = argsMap
      .get("--dialect")
      .flatMap(v => Dialect.values.find(_.toString().equalsIgnoreCase(v)))
      .getOrElse(Dialect.Scala3)
    arrayType <- argsMap.get("--array-type") match
      case None    => Right(ArrayType.List)
      case Some(v) => ArrayType.values.find(_.toString().equalsIgnoreCase(v)).toRight(s"Invalid array-type $v")
    incResources = argsMap
      .get("--include-resources")
      .toList
      .flatMap(_.split(',').toList)
  } yield Task(
    specsInput = specs,
    config = GeneratorConfig(
      outDir = outDir,
      resourcesPkg = resourcesPkg,
      schemasPkg = schemasPkg,
      httpSource = httpSource,
      jsonCodec = jsonCodec,
      dialect = dialect,
      preprocess = s => {
        incResources.partitionMap(s => if s.startsWith("!") then Left(s.stripPrefix("!")) else Right(s)) match
          case (Nil, Nil)  => s
          case (excl, Nil) => s.copy(resources = s.resources.view.filterKeys(!_.hasMatch(excl)).toMap)
          case (Nil, incl) => s.copy(resources = s.resources.view.filterKeys(_.hasMatch(incl)).toMap)
          case (excl, incl) =>
            s.copy(resources = s.resources.view.filterKeys(k => !k.hasMatch(excl) && k.hasMatch(incl)).toMap)
      },
      arrayType = arrayType
    )
  )
