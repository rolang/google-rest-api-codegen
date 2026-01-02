// for test runs using scala-cli
//> using jvm system
//> using scala 3.7.4
//> using file ../../../../core/shared/src/main/scala/codegen.scala
//> using dep com.lihaoyi::upickle:4.4.2

package gcp.codegen.cli

import gcp.codegen.*

import java.nio.file.*
import GeneratorConfig.*
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

@main def run(args: String*) =
  argsToTask(args) match
    case Left(err) =>
      Console.err.println(s"Invalid arguments: $err")
      sys.exit(1)
    case Right(task) =>
      try
        val files = Await.result(task.run, 30.seconds)
        println(s"Generated ${files.length} files")
        sys.exit(0)
      catch
        case err: Throwable =>
          Console.err.println(s"Failure: ${err.getMessage()}")
          sys.exit(1)

private def argsToTask(args: Seq[String]): Either[String, Task] =
  val argsMap = args.toList
    .flatMap(_.split('=').map(_.trim()))
    .sliding(2, 2)
    .collect { case a :: b :: _ =>
      a.toLowerCase() -> b
    }
    .toMap

  for {
    outDir <- argsMap
      .get("-out-dir")
      .map(p => Path.of(p))
      .toRight("Missing -out-dir")
    specs <- argsMap
      .get("-specs")
      .map {
        case "stdin" => SpecsInput.StdIn
        case p       => SpecsInput.FilePath(Path.of(p))
      }
      .toRight("Missing -specs")
    outPkg <- argsMap
      .get("-out-pkg")
      .toRight("Missing -out-pkg")
    httpSource <- argsMap
      .get("-http-source")
      .flatMap(v => HttpSource.values.find(_.toString().equalsIgnoreCase(v)))
      .toRight("Missing or invalid -http-source")
    customJsoniterJsonRef = argsMap.get("-jsoniter-json-type")
    jsonCodec <- (
      argsMap
        .get("-json-codec")
        .map(_.toLowerCase()),
      argsMap.get("-jsoniter-json-type")
    ) match {
      case (Some("ziojson"), _)               => Right(JsonCodec.ZioJson)
      case (Some("jsoniter"), Some(jsonType)) => Right(JsonCodec.Jsoniter(jsonType))
      case (Some("jsoniter"), None)           => Left("Missing -jsoniter-json-type")
      case _                                  => Left("Missing or invalid -json-codec")
    }
    arrayType <- argsMap.get("-array-type") match
      case None    => Right(ArrayType.List)
      case Some(v) => ArrayType.values.find(_.toString().equalsIgnoreCase(v)).toRight(s"Invalid array-type $v")
    incResources = argsMap
      .get("-include-resources")
      .toList
      .flatMap(_.split(',').toList)
  } yield Task(
    specsInput = specs,
    config = GeneratorConfig(
      outDir = outDir,
      outPkg = outPkg,
      httpSource = httpSource,
      jsonCodec = jsonCodec,
      preprocess = s => {
        incResources.partitionMap(s => if s.startsWith("!") then Left(s.stripPrefix("!")) else Right(s)) match
          case (Nil, Nil)   => s
          case (excl, Nil)  => s.copy(resources = s.resources.view.filterKeys(!_.hasMatch(excl)).toMap)
          case (Nil, incl)  => s.copy(resources = s.resources.view.filterKeys(_.hasMatch(incl)).toMap)
          case (excl, incl) =>
            s.copy(resources = s.resources.view.filterKeys(k => !k.hasMatch(excl) && k.hasMatch(incl)).toMap)
      },
      arrayType = arrayType
    )
  )
