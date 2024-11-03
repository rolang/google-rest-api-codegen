import GeneratorConfig.*

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
          case Failure(exception) => Console.err.println(s"Failure: ${exception.printStackTrace()}")
          case Success(_)         => ()
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
    dialect: Dialect = Dialect.Scala3,
    preprocess: Specs => Specs = s => s
)

object GeneratorConfig:
  enum HttpSource:
    case Sttp4, Sttp3

  enum JsonCodec:
    case ZioJson, Jsoniter

  enum Dialect:
    case Scala3, Scala2

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
    dialect = argsMap
      .get("--dialect")
      .flatMap(v => Dialect.values.find(_.toString().equalsIgnoreCase(v)))
      .getOrElse(Dialect.Scala3)
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
      }
    )
  )

def generateBySpec(
    specs: Specs,
    config: GeneratorConfig
): Future[List[File]] = {
  val resourcesSplit = config.resourcesPkg.split('.')
  val resourcesPath = config.outDir / resourcesSplit
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
          Future {
            val path = resourcesPath / "resources.scala"
            Files.writeString(
              path,
              List(
                config.dialect match
                  case Dialect.Scala2 => s"package ${resourcesSplit.dropRight(1).mkString(".")}"
                  case Dialect.Scala3 => s"package ${config.resourcesPkg}",
                "",
                config.httpSource match {
                  case HttpSource.Sttp4 => "import sttp.model.*\nimport sttp.client4.*"
                  case HttpSource.Sttp3 => "import sttp.model.*\nimport sttp.client3.*"
                },
                config.dialect match
                  case Dialect.Scala2 => s"package object ${resourcesSplit.last} {"
                  case Dialect.Scala3 => "",
                "val resourceRequest: PartialRequest[Either[String, String]] = basicRequest.headers(Header.contentType(MediaType.ApplicationJson))",
                s"""val resourceBaseUrl: Uri = uri"${specs.baseUrl}"""",
                config.dialect match
                  case Dialect.Scala2 => "}"
                  case Dialect.Scala3 => ""
              ).mkString("\n")
            )
            List(path.toFile())
          },
          Future
            .traverse(specs.resources) { (resourceKey, resource) =>
              val resourceName = resourceKey.scalaName
              Future {
                val code = resourceCode(
                  pkg = resourceKey.pkgName(config.resourcesPkg),
                  resourcesPkg = config.resourcesPkg,
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
                  config.dialect,
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

val scalaKeyWords = Set("type", "import", "val", "object", "enum", "export")

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
          properties = pr.properties.map {
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
    resourcesPkg: String,
    schemasPkg: String,
    baseUrl: String,
    resourceName: String,
    resource: Resource,
    httpSource: HttpSource,
    jsonCodec: JsonCodec,
    hasProps: SchemaPath => Boolean
) =
  List(
    s"package $pkg",
    "",
    s"import $schemasPkg.*",
    s"import $resourcesPkg.*",
    "",
    httpSource match {
      case HttpSource.Sttp4 => "import sttp.model.Uri.PathSegment\nimport sttp.client4.*"
      case HttpSource.Sttp3 => "import sttp.model.Uri.PathSegment\nimport sttp.client3.*"
    },
    jsonCodec match {
      case JsonCodec.ZioJson  => "import zio.json.*"
      case JsonCodec.Jsoniter => "import com.github.plokhotnyuk.jsoniter_scala.core.*"
    },
    "",
    s"object ${resourceName} {" +
      resource.methods
        .map { (k, v) =>
          val pathSegments =
            v.urlPath
              .split("/")
              .map(s =>
                "\\{(.*?)\\}".r.findAllIn(s).toList match
                  case Nil                => s"PathSegment(\"$s\")"
                  case v :: Nil if v == s => "PathSegment(" + toScalaName(v.stripPrefix("{").stripSuffix("}")) + ")"
                  case vars =>
                    "PathSegment(s\"" + vars.foldLeft(s)((res, v) =>
                      res.replace(v, "$" + v.stripPrefix("{").stripSuffix("}"))
                    ) + "\")"
              )

          val reqUri = s"resourceBaseUrl.addPathSegments(List(${pathSegments.mkString(", ")}))"

          val req = v.request.filter(_.schemaPath.forall(hasProps))

          val params =
            v.scalaParameters.map((n, t) => s"$n: ${t.scalaType}") :::
              req.toList.map(r => s"request: ${r.scalaType}")

          val body = req match
            case None    => ""
            case Some(_) => """.body(request.toJsonString)"""

          val queryParams =
            if v.scalaQueryParams.nonEmpty then
              "\n    val params = " + v.scalaQueryParams
                .map {
                  case (k, p) if p.required => s"""Map("$k" -> $k)"""
                  case (k, p)               => s"""$k.map(p => Map("$k" -> p.toString)).getOrElse(Map.empty)"""
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
              val bodyType = r.scalaType

              (
                responseType(bodyType),
                jsonCodec match
                  case JsonCodec.ZioJson => s".mapResponse(_.flatMap(_.fromJson[$bodyType]))"
                  case JsonCodec.Jsoniter =>
                    s"""|.response(
                        |  asByteArrayAlways.map(a =>
                        |    try {
                        |      Right(readFromArray[$bodyType](a))
                        |    } catch {
                        |      case e: Throwable => Left(e.getMessage())
                        |    }
                        |  )
                        |)""".stripMargin
              )
            case _ => (responseType("String"), "")

          s"""|def ${toScalaName(k)}(${params.mkString(",\n")}): $resType = {$queryParams
              |  resourceRequest.${v.httpMethod.toLowerCase()}($reqUri$addParams)$body$mapResponse
              |}""".stripMargin
        }
        .mkString("\n", "\n\n", "\n") +
      "}"
  ).mkString("\n")

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
    dialect: Dialect,
    hasProps: SchemaPath => Boolean
): String = {
  def `def toJsonString`(objName: String) = jsonCodec match
    case JsonCodec.ZioJson =>
      s"def toJsonString: String = $objName.jsonCodec.encodeJson(this, None).toString()"
    case JsonCodec.Jsoniter =>
      s"def toJsonString: String = writeToString(this)"

  def jsonDecoder(objName: String) = jsonCodec match
    case JsonCodec.ZioJson =>
      s"""|object $objName {
          |  ${
           if dialect == Dialect.Scala3 then "given" else "implicit val"
         } jsonCodec: JsonCodec[$objName] = JsonCodec.derived[$objName]
          |}""".stripMargin
    case JsonCodec.Jsoniter =>
      s"""|object $objName {
          |  ${if dialect == Dialect.Scala3 then "given" else "implicit val"} jsonCodec: JsonValueCodec[$objName] =
          |    JsonCodecMaker.make(CodecMakerConfig.withAllowRecursiveTypes(true).withDiscriminatorFieldName(None))
          |}""".stripMargin

  def toSchemaClass(s: Schema): String =
    val scalaName = s.id.scalaName
    s"""|case class $scalaName(
        |${s
         .scalaProperties(hasProps)
         .map { (n, t) =>
           s"${t.description
               .map { d =>
                 d.replace("\n", "\n//").split("\\. ").filter(_.nonEmpty).mkString("    // ", ". \n    // ", "\n")
               }
               .getOrElse("")}$n: ${(if (t.optional) s"${t.scalaType} = None" else t.scalaType)}"
         }
         .mkString("", ",\n", "")}
        |) {\n${`def toJsonString`(scalaName)}\n}\n
        |
        |${jsonDecoder(scalaName)}
        |""".stripMargin

  val props = v.scalaProperties(hasProps)

  List(
    s"package $pkg",
    "",
    jsonCodec match {
      case JsonCodec.ZioJson => "import zio.json.*"
      case JsonCodec.Jsoniter =>
        """|import com.github.plokhotnyuk.jsoniter_scala.core.*
           |import com.github.plokhotnyuk.jsoniter_scala.macros.*""".stripMargin
    },
    toSchemaClass(v)
  ).mkString("\n")
}

case class FlatPath(path: String, params: List[String])

case class Method(
    httpMethod: String,
    path: String,
    flatPath: Option[FlatPath],
    parameters: Map[String, Parameter],
    parameterOrder: List[String],
    response: Option[SchemaType],
    request: Option[SchemaType] = None
) {
  private def flatPathParams: List[(String, Parameter)] = flatPath.toList.flatMap(p =>
    p.params.map(param =>
      param -> Parameter(
        description = None,
        location = "path",
        typ = SchemaType.Primitive("string", false, None),
        required = true,
        pattern = None
      )
    )
  )

  def urlPath: String = flatPath.map(_.path).getOrElse(path)

  // non optional parameters first
  def scalaParameters: List[(String, Parameter)] =
    (flatPathParams ::: parameters.toList.drop(if flatPath.nonEmpty then 1 else 0))
      .map((k, v) => (toScalaName(k), v))
      .sortBy(!_._2.required)

  def scalaPathParams: List[String] = scalaParameters.collect {
    case (k, p) if p.location == "path" => k
  }

  def scalaQueryParams: List[(String, Parameter)] = scalaParameters.filter(_._2.location == "query")
}

object Method:
  given Reader[Method] = reader[ujson.Obj].map { o =>
    Method(
      httpMethod = o("httpMethod").str,
      path = o("path").str,
      flatPath = o.value
        .get("flatPath")
        .map(_.str)
        .map(path =>
          FlatPath(
            path = path,
            params = "\\{(.*?)\\}".r.findAllIn(path).map(_.stripPrefix("{").stripSuffix("}")).toList
          )
        ),
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
          val newRes = obj.map((a, b) => ResourcePath(k, a) -> b).toList ::: xs
          Try(read[Resource](v)) match
            case Success(res) => readResources(newRes, result.updated(k, res))
            case _            => readResources(newRes, result)
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

case class Property(description: Option[String], typ: SchemaType, readOnly: Boolean = false) {
  def optional: Boolean = typ.optional || readOnly
  def scalaType: String = typ.withOptional(optional).scalaType
  def schemaPath: Option[SchemaPath] = typ.schemaPath
  def nestedSchemaPath: Option[SchemaPath] = typ.schemaPath.filter(_.hasNested)
}

object Property:
  def readProperty(name: SchemaPath, o: ujson.Obj) =
    Property(
      description = o.value.get("description").map(_.str),
      typ = SchemaType.readType(name, o),
      readOnly = o.value.get("readOnly").map(_.bool).getOrElse(false)
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
    case Primitive("string", _, Some("google-datetime"))   => toType("java.time.OffsetDateTime")
    case Primitive("string", _, _)                         => toType("String")
    case Primitive("integer", _, Some("int32" | "uint32")) => toType("Int")
    case Primitive("integer", _, Some("int64" | "uint64")) => toType("Long")
    case Primitive("number", _, Some("double" | "float"))  => toType("Double")
    case Primitive("boolean", _, _)                        => toType("Boolean")
    case Ref(ref, _)                                       => toType(ref.scalaName)
    case Array(t, _)                                       => toType(s"List[${t.scalaType}]")
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
    def scalaName: String =
      s.filter(!Set("items", "properties").contains(_)).map(_.capitalize).mkString

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
  private def sortedProps(hasProps: SchemaPath => Boolean): List[(String, Property)] =
    properties
      .filter { (_, prop) =>
        prop.schemaPath.forall(hasProps(_))
      }
      .sortBy(_._2.typ.optional)
  def scalaProperties(hasProps: SchemaPath => Boolean): List[(String, Property)] =
    sortedProps(hasProps).map { (k, prop) => (toScalaName(k), prop) }
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
    def pkgPath: Vector[String] = r.dropRight(1).map(camelToSnakeCase)
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
  def hasProps(schemaName: SchemaPath): Boolean =
    schemas.get(schemaName).exists(_.properties.nonEmpty)
}

def camelToSnakeCase(camelCase: String): String = {
  val camelCaseRegex = "([A-Z][a-z]+)".r
  camelCaseRegex.replaceAllIn(camelCase, matched => "_" + matched.group(0).toLowerCase)
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
