import java.nio.file.*
import upickle.default.*
import GeneratorConfig.*
import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import java.io.File
import scala.util.{Failure, Success}
import scala.jdk.CollectionConverters.*

// usage example
// curl "https://pubsub.googleapis.com/\$discovery/rest?version=v1" | ./codegen \
//  --out-dir=src/main/scala \
//  --specs=stdin \
//  --resources-pkg=gcp.pubsub.v1.resources.sttp4 \
//  --schemas-pkg=gcp.pubsub.v1.schemas \
//  --http-source=sttp4 \
//  --json-codec=ziojson
//  --include-resources='projects.*,!projects*snapshots'

@main def run(args: String*) = {
  argsToTask(args) match
    case Left(err) => Console.err.println(s"Invalid arguments: $err")
    case Right(task) =>
      Await
        .ready(
          for {
            content <- task.specsInput match
              case SpecsInput.StdIn =>
                Future(Console.in.lines().iterator().asScala.mkString)
              case SpecsInput.FilePath(path) => Future(Files.readString(path))
            specs = mapSpecs(read[Specs](content))
            files <- generateBySpec(
              specs = task.config.preprocess(specs),
              config = task.config
            )
          } yield println(s"Generated ${files.length} files for ${specs.name}"),
          30.seconds
        )
        .onComplete {
          case Failure(exception) =>
            println(s"Failure: ${exception.printStackTrace()}")
          case Success(_) => ()
        }
}

extension (p: Path)
  def /(o: Path) = Path.of(p.toString(), o.toString())
  def /(o: Array[String]) = Path.of(p.toString(), o*)
  def /(o: Vector[String]) = Path.of(p.toString(), o*)
  def /(o: String) = Path.of(p.toString(), o)

case class GeneratorConfig(
    outDir: Path,
    resourcesPkg: String,
    schemasPkg: String,
    httpSource: HttpSource = HttpSource.Sttp4,
    jsonCodec: JsonCodec = JsonCodec.ZioJson,
    preprocess: Specs => Specs = s => s
)

object GeneratorConfig:
  enum HttpSource:
    case Sttp4, Sttp3

  enum JsonCodec:
    case ZioJson

enum SpecsInput:
  case StdIn
  case FilePath(path: Path)

case class Task(specsInput: SpecsInput, config: GeneratorConfig)

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
      preprocess = s => {
        incResources.partitionMap(s => if s.startsWith("!") then Left(s.stripPrefix("!")) else Right(s)) match
          case (Nil, Nil)  => s
          case (excl, Nil) => s.copy(resources = s.resources.view.filterKeys(!_.hasMatch(excl)).toMap)
          case (Nil, incl) => s.copy(resources = s.resources.view.filterKeys(_.hasMatch(incl)).toMap)
          case (excl, incl) =>
            s.copy(resources = s.resources.view.filterKeys(k => !k.hasMatch(excl) && k.hasMatch(incl)).toMap)
      }
    )
  )

def generateBySpec(
    specs: Specs,
    config: GeneratorConfig
): Future[List[File]] = {
  val resourcesPath = config.outDir / config.resourcesPkg.split('.')
  val schemasPath = config.outDir / config.schemasPkg.split('.')

  for {
    _ <- Future {
      (schemasPath +: resourcesPath +: specs.resources.keySet.map(_.dirPath(resourcesPath)).toSeq).foreach(
        Files.createDirectories(_)
      )
    }
    files <- Future
      .sequence(
        List(
          Future
            .traverse(specs.resources) { (resourceKey, resource) =>
              val resourceName = resourceKey.scalaName

              Future {
                val code = resourceCode(
                  pkg = resourceKey.pkgName(config.resourcesPkg),
                  schemasPkg = config.schemasPkg,
                  baseUrl = specs.baseUrl,
                  resourceName = resourceName,
                  resource = resource,
                  httpSource = config.httpSource,
                  jsonCodec = config.jsonCodec,
                  hasProps = p => specs.hasProps(p)
                )
                val path = resourceKey.dirPath(resourcesPath) / s"$resourceName.scala"
                Files.writeString(path, code)
                path.toFile()
              }
            },
          // generate schemas with properties
          Future
            .traverse(specs.schemas.filter(_._2.properties.nonEmpty)) { (schemaPath, schema) =>
              Future {
                val code = schemasCode(
                  schema,
                  config.schemasPkg,
                  config.jsonCodec,
                  p => specs.hasProps(p)
                )
                val path = schemasPath / s"${schemaPath.scalaName}.scala"
                Files.writeString(path, code)
                path.toFile()
              }
            }
        )
      )
      .map(_.flatten)
  } yield files
}

val scalaKeyWords = Set("type", "import", "val", "object", "enum")

def toScalaName(n: String): String =
  if scalaKeyWords.contains(n) then s"`$n`"
  else if n.contains(".") then s"`$n`"
  else n

def mapSpecs(specs: Specs): Specs = {
  // add a PublishMessage schema with non optional data and removed messageId / publishTime
  val newSchemaId = SchemaPath("PublishMessage")
  val updatedSchemas = specs.schemas.map((k, v) => (k.scalaName, v)).flatMap {
    case (k @ "PubsubMessage", pm) =>
      Map(
        SchemaPath(k) -> pm,
        newSchemaId -> pm.copy(
          id = newSchemaId,
          properties = pm.properties
            .filter((k, _) => !Set("messageId", "publishTime").contains(k))
            .map {
              case (k @ "data", v) =>
                (k, v.copy(typ = v.typ.withOptional(false)))
              case kv => kv
            }
        )
      )
    // update PublishRequest to use PublishMessage schema
    case (k @ "PublishRequest", pr) =>
      Map(
        SchemaPath(k) -> pr.copy(
          properties = pr.properties
            .map {
              case (k @ "messages", v) =>
                (
                  k,
                  v.copy(typ = SchemaType.Array(SchemaType.Ref(newSchemaId, false), false))
                )
              case kv => kv
            }
        )
      )
    case (k, v) => Map(SchemaPath(k) -> v)
  }

  specs.copy(schemas = updatedSchemas)
}

def resourceCode(
    pkg: String,
    schemasPkg: String,
    baseUrl: String,
    resourceName: String,
    resource: Resource,
    httpSource: HttpSource,
    jsonCodec: JsonCodec,
    hasProps: SchemaPath => Boolean
) = {
  List(
    s"package $pkg",
    "",
    s"import $schemasPkg.*",
    "",
    httpSource match {
      case HttpSource.Sttp4 => "import sttp.client4.*"
      case HttpSource.Sttp3 => "import sttp.client3.*"
    },
    jsonCodec match {
      case JsonCodec.ZioJson => "import zio.json.*"
    },
    "",
    s"object ${resourceName} {" +
      resource.methods
        .map { (k, v) =>

          val reqUri =
            v.scalaPathParams.foldLeft(baseUrl + v.path) { (u, paramName) =>
              u.replace(s"{+$paramName}", s"$$$paramName")
                .replace(s"{$paramName}", s"$$$paramName")
            }

          val req = v.request.filter(_.schemaPath.forall(hasProps))

          val params =
            v.scalaParameters.map((n, t) => s"$n: $t") :::
              req.toList.map(r => s"request: ${r.scalaType}")

          val body = req match
            case None    => ""
            case Some(_) => """.body(request.toJsonString)"""

          val queryParams =
            if v.scalaQueryParams.nonEmpty then
              "\n    val params = " + v.scalaQueryParams
                .map {
                  case (k, p) if p.required =>
                    s"""Map("$k" -> ${toScalaName(k)})"""
                  case (k, p) =>
                    s"""${toScalaName(
                        k
                      )}.map(p => Map("$k" -> p.toString)).getOrElse(Map.empty)"""

                }
                .mkString("", " ++ ", "\n")
            else ""

          val addParams =
            if v.scalaQueryParams.nonEmpty then ".addParams(params)" else ""

          def responseType(t: String) =
            httpSource match
              case HttpSource.Sttp4 => s"Request[Either[String, $t]]"
              case HttpSource.Sttp3 =>
                s"RequestT[Identity, Either[String, $t], Any]"

          val (resType, mapResponse) = v.response match
            case Some(r) if r.schemaPath.forall(hasProps) =>
              (
                responseType(r.scalaType),
                s".mapResponse(_.flatMap(_.fromJson[${r.scalaType}]))"
              )
            case _ => (responseType("String"), "")

          s"""|def ${k}(${params.mkString(",\n")}): $resType = {$queryParams
              |  basicRequest.${v.httpMethod
               .toLowerCase()}(uri"$reqUri"$addParams)$body$mapResponse
              |}""".stripMargin
        }
        .mkString("\n", "\n\n", "\n") +
      "}"
  ).mkString("\n")
}

/* plain scala json encoder impl
def toJsonStrPair(
    name: String,
    p: Property,
    isFirst: Boolean,
    hasRequired: Boolean
) = {
  val sName = toScalaName(name)
  val getName = sName + (if p.typ.optional then ".get" else "")
  def append(str: String) =
    val line = (isFirst, hasRequired, p.typ.optional) match
      case (true, _, _)     => str
      case (_, false, true) => s"$${if (sb.size > 1) \",\" else \"\"}$str"
      case _                => "," + str

    (if p.typ.optional then s"if (${sName}.nonEmpty) " else "") + s"sb.append(s\"\"\"$line\"\"\")"

  p.typ match
    case SchemaType.Ref(ref, _)               => append(s"\"$sName\":$${$getName.toJsonString}")
    case SchemaType.Primitive("string", _, _) => append(s"\"$sName\":\"$${$getName}\"")
    case _: SchemaType.Primitive              => append(s"\"$sName\":$${$getName}")
    case SchemaType.Array(SchemaType.Ref(ref, _), _) =>
      append(s"""\"$sName\":[$${$getName.map(_.toJsonString).mkString("", ",", "")}]""")
    case SchemaType.Array(_, _) => append(s"""\"$sName\":[$${$getName.mkString("\\\"", "\\\",\\\"", "\\\"")}]""")
    case SchemaType.Object(_, _) =>
      append(
        s"\"$sName\":$${$getName.map { case (k, v) => s\"\"\"\"$$k\":\"$$v\"\"\"\"}.mkString(\"{\", \",\", \"}\")}"
      )
} */

def schemasCode(
    v: Schema,
    pkg: String,
    jsonCodec: JsonCodec,
    hasProps: SchemaPath => Boolean
): String =
  List(
    s"package $pkg",
    "",
    jsonCodec match {
      case JsonCodec.ZioJson => "import zio.json.*"
    }, {
      // plain scala json encoder impl
      // val `def toJsonString` =
      //   s"""| {\n  def toJsonString: String = {
      //       |    val sb = StringBuilder("{")
      //       |    ${v
      //        .sortedProps(hasProps)
      //        .zipWithIndex
      //        .map { case ((n, p), i) =>
      //          toJsonStrPair(n, p, isFirst = i == 0, hasRequired = v.hasRequired)
      //        }
      //        .mkString("\n    ")}
      //       |    sb.append("}").result()
      //       |  }
      //       |}""".stripMargin

      val scalaName = v.id.scalaName

      val `def toJsonString` = jsonCodec match
        case JsonCodec.ZioJson =>
          s"def toJsonString: String = $scalaName.jsonCodec.encodeJson(this, None).toString()"

      val jsonDecoder = jsonCodec match
        case JsonCodec.ZioJson =>
          s"""|object $scalaName {
              |  implicit val jsonCodec: JsonCodec[$scalaName] = JsonCodec.derived[$scalaName]
              |}""".stripMargin

      s"""|case class $scalaName(
          |${v
           .scalaProperties(hasProps)
           .map((n, t) => s"$n: $t")
           .mkString("    ", ",\n    ", "")}
          |) {\n  ${`def toJsonString`}\n}
          |
          |$jsonDecoder
          |""".stripMargin
    }
  ).mkString("\n")

case class Method(
    httpMethod: String,
    path: String,
    parameters: Map[String, Parameter],
    parameterOrder: List[String],
    response: Option[SchemaType],
    request: Option[SchemaType] = None
) {
  // non optional parameters first
  def sortedParams: List[(String, Parameter)] =
    parameters.toList.sortBy(!_._2.required)

  def scalaParameters: List[(String, String)] = sortedParams.map { (k, v) =>
    (toScalaName(k), v.scalaType)
  }

  def scalaPathParams: List[String] = parameterOrder.map(toScalaName(_))

  def scalaQueryParams: List[(String, Parameter)] =
    sortedParams.dropWhile((k, _) => parameterOrder.contains(k))
}

object Method:
  given Reader[Method] = reader[ujson.Obj].map { o =>
    Method(
      httpMethod = o("httpMethod").str,
      path = o("path").str,
      parameterOrder = o.value.get("parameterOrder").map(read[List[String]](_)).getOrElse(Nil),
      parameters = o.value
        .get("parameters")
        .map(read[Map[String, Parameter]](_))
        .getOrElse(Map.empty),
      response = o.value
        .get("response")
        .map(r => SchemaType.readType(SchemaPath.empty, r.obj)),
      request = o.value
        .get("request")
        .map(r => SchemaType.readType(SchemaPath.empty, r.obj))
    )
  }

case class Resource(methods: Map[String, Method]) derives Reader

@scala.annotation.tailrec
def readResources(
    resources: List[(ResourcePath, ujson.Value)],
    result: Map[ResourcePath, Resource]
): Map[ResourcePath, Resource] =
  resources match
    case (k, v) :: xs =>
      v.obj.remove("resources").map(_.obj) match
        case None => readResources(xs, result.updated(k, read[Resource](v)))
        case Some(obj) =>
          readResources(
            obj.map((a, b) => ResourcePath(k, a) -> b).toList ::: xs,
            result
          )
    case Nil => result

case class Parameter(
    description: Option[String],
    location: String,
    typ: SchemaType,
    required: Boolean = false,
    pattern: Option[String] = None
) {
  def scalaType = typ.withOptional(!required).scalaType
}

object Parameter:
  given Reader[Parameter] = reader[ujson.Obj].map { o =>
    Parameter(
      description = o.value.get("description").map(_.str),
      location = o("location").str,
      typ = SchemaType.readType(SchemaPath.empty, o),
      required = o.value.get("required").map(_.bool).getOrElse(false),
      pattern = o.value.get("pattern").map(_.str)
    )
  }

case class Property(description: Option[String], typ: SchemaType) {
  def scalaType: String = typ.scalaType
  def schemaPath: Option[SchemaPath] = typ.schemaPath
  def nestedSchemaPath: Option[SchemaPath] = typ.schemaPath.filter(_.hasNested)
}

object Property:
  def readProperty(name: SchemaPath, o: ujson.Obj) =
    Property(
      description = o.value.get("description").map(_.str),
      typ = SchemaType.readType(name, o)
    )

enum SchemaType(val optional: Boolean):

  case Ref(ref: SchemaPath, override val optional: Boolean) extends SchemaType(optional)
  case Primitive(
      `type`: String,
      override val optional: Boolean,
      format: Option[String] = None
  ) extends SchemaType(optional)
  case Array(items: SchemaType, override val optional: Boolean) extends SchemaType(optional)
  case Object(additionalProperties: SchemaType, override val optional: Boolean) extends SchemaType(optional)

  private def toType(t: String) = if optional then s"Option[$t]" else t

  def schemaPath: Option[SchemaPath] = this match
    case Ref(ref, _)           => Some(ref)
    case Array(Ref(ref, _), _) => Some(ref)
    case _                     => None

  def withOptional(o: Boolean) = this match
    case t: Ref       => t.copy(optional = o)
    case t: Primitive => t.copy(optional = o)
    case t: Array     => t.copy(optional = o)
    case t: Object    => t.copy(optional = o)

  def scalaType: String = this match
    case Primitive("string", _, Some("google-datetime")) =>
      toType("java.time.OffsetDateTime")
    case Primitive("string", _, _)                         => toType("String")
    case Primitive("integer", _, Some("int32" | "uint32")) => toType("Int")
    case Primitive("integer", _, Some("int64" | "uint64")) => toType("Long")
    case Primitive("boolean", _, _)                        => toType("Boolean")
    case Ref(ref, _)                                       => toType(ref.scalaName)
    case Array(t, _)                                       => toType(s"IndexedSeq[${t.scalaType}]")
    case Object(t, _)                                      => toType(s"Map[String, ${t.scalaType}]")
    case _                                                 => toType("String")

object SchemaType:
  def readType(context: SchemaPath, o: ujson.Obj): SchemaType =
    val optional = o.value
      .get("description")
      .exists(_.str.toLowerCase().startsWith("optional"))

    o.value.get("items").map(_.obj) match
      case Some(v) =>
        SchemaType.Array(readType(context.add("items"), v), optional)
      case _ =>
        o.value.get("additionalProperties").map(_.obj) match
          case Some(v) =>
            SchemaType.Object(
              readType(context.add("additionalProperties"), v),
              optional
            )
          case _ =>
            o.value.get("$ref").map(_.str) match
              case Some(ref) => SchemaType.Ref(SchemaPath(ref), optional)
              case _ =>
                if !o.value.keySet.contains("properties") then
                  SchemaType.Primitive(
                    `type` = o("type").str,
                    optional = optional,
                    format = o.value.get("format").map(_.str)
                  )
                else SchemaType.Ref(context, optional)

opaque type SchemaPath = Vector[String]
object SchemaPath:
  val empty: SchemaPath = Vector.empty
  def apply(name: String): SchemaPath = Vector(name)

  extension (s: SchemaPath)
    def scalaName: String = s
      .filter(!Set("items", "properties").contains(_))
      .map(_.capitalize)
      .mkString
    def add(nested: String): SchemaPath = s.appended(nested)
    def hasNested: Boolean = s.size > 1
    def jsonPath: Vector[String] = s

case class Schema(
    id: SchemaPath,
    description: Option[String],
    properties: List[(String, Property)]
) {
  def hasRequired = properties.exists(!_._2.typ.optional)

  // required properties first
  // references wihout properties are excluded
  def sortedProps(hasProps: SchemaPath => Boolean): List[(String, Property)] =
    properties
      .filter { (_, prop) =>
        prop.schemaPath.forall(hasProps(_))
      }
      .sortBy(_._2.typ.optional)

  def scalaProperties(hasProps: SchemaPath => Boolean): List[(String, String)] =
    sortedProps(hasProps).map { (k, prop) =>
      (toScalaName(k), prop.scalaType)
    }
}

object Schema:
  private def readProps(
      props: ujson.Obj,
      parentName: SchemaPath
  ): List[(String, Property)] =
    props.value.view
      .mapValues(_.obj)
      .map { (k, obj) =>
        val name = parentName.add(k)
        (k, Property.readProperty(name, obj))
      }
      .toList

  private def readSchema(name: SchemaPath, data: ujson.Obj): Schema =
    Schema(
      id = name,
      description = data.value.get("description").map(_.str),
      properties = readProps(data("properties").obj, name.add("properties"))
    )

  def readSchemas(o: ujson.Obj): Map[SchemaPath, Schema] = {
    @scala.annotation.tailrec
    def readSchemas(
        schemas: List[(SchemaPath, ujson.Obj)],
        result: Map[SchemaPath, Schema]
    ): Map[SchemaPath, Schema] =
      schemas match {
        case Nil => result
        case (name, data) :: xs =>
          val schema = readSchema(name, data)

          schema.properties
            .map(_._2.nestedSchemaPath)
            .flatMap {
              case None    => Nil
              case Some(p) => List((p, p.jsonPath.foldLeft(o)(_.apply(_).obj)))
            } match {
            case Nil => readSchemas(xs, result.updated(name, schema))
            case nested =>
              readSchemas(nested ::: xs, result.updated(name, schema))
          }
      }

    readSchemas(
      o.value.map((k, json) => (SchemaPath(k), ujson.Obj(json.obj))).toList,
      Map.empty
    )
  }

opaque type ResourcePath = Vector[String]
object ResourcePath:
  def apply(p: String): ResourcePath = Vector(p)
  def apply(pp: Vector[String], p: String): ResourcePath = pp :+ p
  extension (r: ResourcePath)
    def add(p: String): ResourcePath = r :+ p
    def scalaName: String = r.last.capitalize
    def pkgPath: Vector[String] = r.dropRight(1)
    def pkgName(base: String): String = s"$base${if pkgPath.nonEmpty then pkgPath.mkString(".", ".", "") else ""}"
    def dirPath(base: Path): Path = base / pkgPath
    def matches(v: String): Boolean =
      val regex = s"^${v.replace(".", "\\.").replace("*", ".*")}$$"
      val path = r.mkString(".")
      path.matches(regex)
    def hasMatch(v: Seq[String]): Boolean = v.exists(matches)

case class Specs(
    name: String,
    protocol: String,
    ownerName: String,
    discoveryVersion: String,
    resources: Map[ResourcePath, Resource],
    version: String,
    baseUrl: String,
    schemas: Map[SchemaPath, Schema]
) {
  def hasProps(schemaName: SchemaPath) =
    schemas.get(schemaName).exists(_.properties.nonEmpty)
}

object Specs:
  given Reader[Specs] = reader[ujson.Obj].map(o =>
    Specs(
      name = o("name").str,
      protocol = o("protocol").str,
      ownerName = o("ownerName").str,
      discoveryVersion = o("discoveryVersion").str,
      resources = readResources(
        o("resources").obj.map((k, v) => ResourcePath(k) -> v).toList,
        Map.empty
      ),
      version = o("version").str,
      baseUrl = o("baseUrl").str,
      schemas = Schema.readSchemas(o("schemas").obj)
    )
  )
