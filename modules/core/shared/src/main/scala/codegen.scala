package gcp.codegen

import java.nio.file.*
import upickle.default.*
import GeneratorConfig.*
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.*
import java.io.File
import scala.util.{Success, Try}
import scala.jdk.CollectionConverters.*
import scala.concurrent.ExecutionContext

extension (p: Path)
  def /(o: Path) = Path.of(p.toString(), o.toString())
  def /(o: Array[String]) = Path.of(p.toString(), o*)
  def /(o: Vector[String]) = Path.of(p.toString(), o*)
  def /(o: String) = Path.of(p.toString(), o)

case class GeneratorConfig(
    outDir: Path,
    outPkg: String,
    httpSource: HttpSource,
    jsonCodec: JsonCodec,
    arrayType: ArrayType,
    dialect: Dialect,
    preprocess: Specs => Specs = s => s
)

object GeneratorConfig:
  enum HttpSource:
    case Sttp4, Sttp3

  enum JsonCodec:
    case ZioJson, Jsoniter

  enum ArrayType {
    case List, Vector, Array, ZioChunk

    def toScalaType(t: String): String = this match
      case List     => s"List[$t]"
      case Vector   => s"Vector[$t]"
      case Array    => s"Array[$t]"
      case ZioChunk => s"zio.Chunk[$t]"
  }

  // potential dialect options
  enum Dialect:
    case Scala3

enum SpecsInput:
  case StdIn
  case FilePath(path: Path)

case class Task(
    specsInput: SpecsInput,
    config: GeneratorConfig
) {
  def run(using ExecutionContext): Future[List[File]] = {
    for {
      content <- specsInput match
        case SpecsInput.StdIn          => Future(Console.in.lines().iterator().asScala.mkString)
        case SpecsInput.FilePath(path) => Future(Files.readString(path))
      specs = read[Specs](content)
      files <- generateBySpec(
        specs = config.preprocess(specs),
        config = config
      )
    } yield files
  }

  def runAwait(timeout: Duration = 30.seconds, ec: ExecutionContext = ExecutionContext.global): List[File] =
    given ExecutionContext = ec
    Await.result(run, timeout)
}

def generateBySpec(
    specs: Specs,
    config: GeneratorConfig
)(using ExecutionContext): Future[List[File]] = {
  val basePkgPath = config.outDir / config.outPkg.split('.')
  val resourcesPkg = s"${config.outPkg}.resources"
  val schemasPkg = s"${config.outPkg}.schemas"
  val resourcesSplit = resourcesPkg.split('.')
  val resourcesPath = config.outDir / resourcesSplit
  val schemasPath = config.outDir / schemasPkg.split('.')
  val commonCodecsObj = "codecs"
  val commonCodecsPkg = s"$schemasPkg.$commonCodecsObj"
  val commonCodecsPath = schemasPath / s"$commonCodecsObj.scala"

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
            val path = basePkgPath / s"${specs.name}.scala"
            Files.writeString(
              path,
              List(
                s"${toComment(List(s"${specs.title} ${specs.version}", specs.description, specs.documentationLink))}",
                "",
                config.dialect match
                  case Dialect.Scala3 => s"package ${config.outPkg}",
                "",
                config.httpSource match {
                  case HttpSource.Sttp4 => "import sttp.model.*\nimport sttp.client4.*"
                  case HttpSource.Sttp3 => "import sttp.model.*\nimport sttp.client3.*"
                },
                "",
                s"""val baseUrl: Uri = uri"${specs.baseUrl}"""",
                s"""val basePath: String = "${specs.basePath}"""",
                s"""val rootUrl: Uri = uri"${specs.rootUrl}"""",
                "",
                s"opaque type QueryParameters = Map[String, String]",
                "object QueryParameters:",
                "  extension (m: QueryParameters) def value: Map[String, String] = m",
                "  val empty: QueryParameters = Map.empty",
                "",
                "  def apply(",
                specs.queryParameters
                  .map((k, v) =>
                    s"""${{ toComment(v.description) }}  ${toScalaName(k)}: ${v.typ
                        .withOptional(true)
                        .scalaType(config.arrayType)} = None"""
                  )
                  .mkString("  ", ",\n  ", ""),
                "): QueryParameters =",
                specs.queryParameters
                  .map((k, _) => s""" ${toScalaName(k)}.map(v => Map("$k" -> v.toString)).getOrElse(Map.empty)""")
                  .mkString("  ", "++ \n  ", ""),
                "",
                if specs.endpoints.nonEmpty then "enum Endpoint(val location: String, val url: Uri):" else "",
                specs.endpoints
                  .map(e =>
                    s"""${toComment(
                        Some(e.description)
                      )}  case `${e.location}` extends Endpoint("${e.location}", uri"${e.endpointUrl}")""".stripMargin
                  )
                  .mkString("\n")
              ).mkString("\n")
            )
            List(path.toFile())
          },
          Future {
            val path = resourcesPath / "resources.scala"
            Files.writeString(
              path,
              List(
                config.dialect match
                  case Dialect.Scala3 => s"package $resourcesPkg",
                "",
                config.httpSource match {
                  case HttpSource.Sttp4 => "import sttp.model.*\nimport sttp.client4.*"
                  case HttpSource.Sttp3 => "import sttp.model.*\nimport sttp.client3.*"
                },
                config.dialect match
                  case Dialect.Scala3 => "",
                s"val resourceRequest: ${
                    if config.httpSource == HttpSource.Sttp3 then "RequestT[Empty, Either[String, String], Any]"
                    else "PartialRequest[Either[String, String]]"
                  } = basicRequest.headers(Header.contentType(MediaType.ApplicationJson))",
                s"export ${config.outPkg}.QueryParameters",
                config.dialect match
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
                  rootPkg = config.outPkg,
                  pkg = resourceKey.pkgName(resourcesPkg),
                  resourcesPkg = resourcesPkg,
                  schemasPkg = schemasPkg,
                  baseUrl = specs.baseUrl,
                  resourceName = resourceName,
                  resource = resource,
                  httpSource = config.httpSource,
                  jsonCodec = config.jsonCodec,
                  hasProps = p => specs.hasProps(p),
                  arrType = config.arrayType,
                  commonQueryParams = specs.queryParameters
                )
                val path = resourceKey.dirPath(resourcesPath) / s"$resourceName.scala"
                Files.writeString(path, code)
                path.toFile()
              }
            },
          // generate schemas with properties
          for {
            commonCodecs <- Future {
              commonSchemaCodecs(
                schemas = specs.schemas.filter(_._2.properties.nonEmpty),
                pkg = schemasPkg,
                objName = commonCodecsObj,
                jsonCodec = config.jsonCodec,
                dialect = config.dialect,
                hasProps = p => specs.hasProps(p),
                arrType = config.arrayType
              ) match
                case None => Nil
                case Some(codecs) =>
                  Files.writeString(commonCodecsPath, codecs)
                  List(commonCodecsPath.toFile())
            }
            schemas <- Future
              .traverse(specs.schemas.filter(_._2.properties.nonEmpty)) { (schemaPath, schema) =>
                Future {
                  val code = schemasCode(
                    schema = schema,
                    pkg = schemasPkg,
                    jsonCodec = config.jsonCodec,
                    dialect = config.dialect,
                    hasProps = p => specs.hasProps(p),
                    arrType = config.arrayType,
                    commonCodecsPkg = if commonCodecs.nonEmpty && schema.hasArrays then Some(commonCodecsPkg) else None
                  )
                  val path = schemasPath / s"${schemaPath.scalaName}.scala"
                  Files.writeString(path, code)
                  path.toFile()
                }
              }
          } yield commonCodecs ::: schemas.toList
        )
      )
      .map(_.flatten)
  } yield files
}

val scalaKeyWords = Set("type", "import", "val", "object", "enum", "export")

def toScalaName(n: String): String =
  if scalaKeyWords.contains(n) then s"`$n`"
  else n.replaceAll("[^a-zA-Z0-9_]", "")

def resourceCode(
    rootPkg: String,
    pkg: String,
    resourcesPkg: String,
    schemasPkg: String,
    baseUrl: String,
    resourceName: String,
    resource: Resource,
    httpSource: HttpSource,
    jsonCodec: JsonCodec,
    arrType: ArrayType,
    hasProps: SchemaPath => Boolean,
    commonQueryParams: Map[String, Parameter]
) =
  List(
    s"package $pkg",
    "",
    s"import $schemasPkg.*",
    s"import $resourcesPkg.*",
    "",
    httpSource match {
      case HttpSource.Sttp4 => "import sttp.model.Uri, sttp.model.Uri.PathSegment, sttp.client4.*"
      case HttpSource.Sttp3 => "import sttp.model.Uri, sttp.model.Uri.PathSegment, sttp.client3.*"
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

          val reqUri = s"endpointUrl.addPathSegments(List(${pathSegments.mkString(", ")}))"

          val req = v.request.filter(_.schemaPath.forall(hasProps))

          val params =
            v.scalaParameters.map((n, t) => s"${toComment(t.description)}$n: ${t.scalaType(arrType)}") :::
              req.toList.map(r => s"request: ${r.scalaType(arrType)}") :::
              List(
                s"endpointUrl: Uri = $rootPkg.baseUrl",
                "commonQueryParams: QueryParameters = QueryParameters.empty"
              )

          val body = req match
            case None    => ""
            case Some(_) => """.body(request.toJsonString)"""

          val queryParams = "\n    val params = " +
            (v.scalaQueryParams match
              case Nil => "commonQueryParams.value"
              case qParams =>
                qParams
                  .map {
                    case (k, p) if p.required => s"""Map("$k" -> $k)"""
                    case (k, p)               => s"""$k.map(p => Map("$k" -> p.toString)).getOrElse(Map.empty)"""
                  }
                  .mkString("", " ++ ", " ++ commonQueryParams.value\n")
            )

          def responseType(t: String) =
            httpSource match
              case HttpSource.Sttp4 => s"Request[Either[String, $t]]"
              case HttpSource.Sttp3 =>
                s"RequestT[Identity, Either[String, $t], Any]"

          val (resType, mapResponse) = v.response match
            case Some(r) if r.schemaPath.forall(hasProps) =>
              val bodyType = r.scalaType(arrType)

              (
                responseType(bodyType),
                jsonCodec match
                  case JsonCodec.ZioJson =>
                    s"""|.response(
                        |  asStringAlways.mapWithMetadata((body, metadata) =>
                        |    if (metadata.isSuccess) then body.fromJson[$bodyType] else Left(body)
                        |  )
                        |)""".stripMargin
                  case JsonCodec.Jsoniter =>
                    s"""|.response(
                        |  asByteArrayAlways.mapWithMetadata((bytes, metadata) =>
                        |    if (metadata.isSuccess) {
                        |      try {
                        |        Right(readFromArray[$bodyType](bytes))
                        |      } catch {
                        |        case e: Throwable => Left(e.getMessage())
                        |      }
                        |    } else Left(String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                        |  )
                        |)""".stripMargin
              )
            case _ => (responseType("String"), "")

          s"""|def ${toScalaName(k)}(\n${params.mkString(",\n")}): $resType = {$queryParams
                |  resourceRequest.${v.httpMethod.toLowerCase()}(${reqUri}.addParams(params))$body$mapResponse
                |}""".stripMargin
        }
        .mkString("\n", "\n\n", "\n") +
      "}"
  ).mkString("\n")

def schemasCode(
    schema: Schema,
    pkg: String,
    jsonCodec: JsonCodec,
    dialect: Dialect,
    hasProps: SchemaPath => Boolean,
    arrType: ArrayType,
    commonCodecsPkg: Option[String]
): String = {
  def enums =
    schema.properties.collect:
      case (k, Property(_, SchemaType.Array(e: SchemaType.Enum, _), _)) => k -> e
      case (k, Property(_, e: SchemaType.Enum, _))                      => k -> e

  def `def toJsonString`(objName: String) = jsonCodec match
    case JsonCodec.ZioJson =>
      s"def toJsonString: String = $objName.jsonCodec.encodeJson(this, None).toString()"
    case JsonCodec.Jsoniter =>
      s"def toJsonString: String = writeToString(this)"

  def jsonDecoder(objName: String) =
    List(
      s"object $objName {",
      enums
        .map((k, e) => s"enum ${toScalaName(k)}:\n  case ${e.values.map(v => toScalaName(v.value)).mkString(", ")}\n")
        .mkString("\n"),
      jsonCodec match
        case JsonCodec.ZioJson =>
          s"${implicitVal(dialect)} jsonCodec: JsonCodec[$objName] = JsonCodec.derived[$objName]"
        case JsonCodec.Jsoniter =>
          s"""|${implicitVal(dialect)} jsonCodec: JsonValueCodec[$objName] = 
              |  JsonCodecMaker.make(CodecMakerConfig.withAllowRecursiveTypes(true).withDiscriminatorFieldName(None))""".stripMargin,
      "}"
    ).mkString("\n")

  def toSchemaClass(s: Schema): String =
    val scalaName = s.id.scalaName
    s"""|case class $scalaName(
        |${s
         .scalaProperties(hasProps)
         .map { (n, t) =>
           val enumType =
             if jsonCodec == JsonCodec.ZioJson then SchemaType.EnumType.Literal
             else SchemaType.EnumType.Nominal(s"$scalaName.$n")
           s"${toComment(t.description)}$n: ${
               (if (t.optional) s"${t.scalaType(arrType, enumType)} = None" else t.scalaType(arrType, enumType))
             }"
         }
         .mkString("", ",\n", "")}
        |) {\n${`def toJsonString`(scalaName)}\n}\n
        |
        |${jsonDecoder(scalaName)}
        |""".stripMargin

  List(
    s"package $pkg",
    "",
    jsonCodec match {
      case JsonCodec.ZioJson => "import zio.json.*"
      case JsonCodec.Jsoniter =>
        """|import com.github.plokhotnyuk.jsoniter_scala.core.*
           |import com.github.plokhotnyuk.jsoniter_scala.macros.*""".stripMargin
    },
    commonCodecsPkg match
      case Some(codecsPkg) =>
        dialect match
          case Dialect.Scala3 => s"import $codecsPkg.given"
      case _ => "",
    toSchemaClass(schema)
  ).mkString("\n")
}

def commonSchemaCodecs(
    schemas: Map[SchemaPath, Schema],
    pkg: String,
    objName: String,
    jsonCodec: JsonCodec,
    dialect: Dialect,
    hasProps: SchemaPath => Boolean,
    arrType: ArrayType
): Option[String] = {
  (jsonCodec, arrType) match
    case (JsonCodec.Jsoniter, ArrayType.ZioChunk) =>
      schemas.toList
        .flatMap((sk, sv) =>
          sv.scalaProperties(hasProps)
            .collect { case (k, Property(_, SchemaType.Array(typ, _), _)) =>
              val enumType =
                if jsonCodec == JsonCodec.ZioJson then SchemaType.EnumType.Literal
                else SchemaType.EnumType.Nominal(s"${sk.lastOption.getOrElse("")}.$k")
              typ.scalaType(arrType, enumType)
            }
        )
        .distinct match
        case Nil => None
        case props =>
          Some(
            List(
              s"""|package $pkg
                  |
                  |import com.github.plokhotnyuk.jsoniter_scala.core.*
                  |import com.github.plokhotnyuk.jsoniter_scala.macros.*
                  |import zio.Chunk""".stripMargin,
              "",
              s"object $objName {",
              props
                .map { t =>
                  val prefix = implicitVal(dialect) + " " + toScalaName(t + "ChunkCodec")
                  s"""|${prefix}: JsonValueCodec[Chunk[$t]] = new JsonValueCodec[Chunk[$t]] {
                      |  val arrCodec: JsonValueCodec[Array[$t]] = JsonCodecMaker.make
                      |
                      |  override val nullValue: Chunk[$t] = Chunk.empty
                      |
                      |  override def decodeValue(in: JsonReader, default: Chunk[$t]): Chunk[$t] =
                      |    Chunk.fromArray(arrCodec.decodeValue(in, default.toArray))
                      |
                      |  override def encodeValue(x: Chunk[$t], out: JsonWriter): Unit =
                      |    arrCodec.encodeValue(x.toArray, out)
                      |}""".stripMargin
                }
                .mkString("\n\n"),
              "}"
            ).mkString("\n")
          )
    case _ => None
}

case class FlatPath(path: String, params: List[String])

case class MediaUploadProtocol(multipart: Boolean, path: String) derives Reader
case class MediaUpload(protocols: Map[String, MediaUploadProtocol], accept: List[String]) derives Reader

case class Method(
    httpMethod: String,
    path: String,
    flatPath: Option[FlatPath],
    parameters: Map[String, Parameter],
    parameterOrder: List[String],
    response: Option[SchemaType],
    request: Option[SchemaType] = None,
    mediaUpload: Option[MediaUpload] = None
) {
  private lazy val flatPathParams: List[(String, Parameter)] = flatPath.toList.flatMap(p =>
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

  // filter out path params if flatPath params are given
  private lazy val pathParams: List[(String, Parameter)] =
    parameters.toList.filterNot((_, p) => flatPathParams.nonEmpty && p.location == "path")

  // non optional parameters first
  def scalaParameters: List[(String, Parameter)] =
    (flatPathParams ::: pathParams)
      .map((k, v) => (toScalaName(k), v))
      .sortBy(!_._2.required)

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
        .map(v => v.obj.map((k, v) => k -> Parameter.read(k, v)).toMap)
        .getOrElse(Map.empty),
      response = o.value
        .get("response")
        .map(r => SchemaType.readType(SchemaPath.empty, r.obj)),
      request = o.value
        .get("request")
        .map(r => SchemaType.readType(SchemaPath.empty, r.obj)),
      mediaUpload = o.value.get("supportsMediaUpload").map(f => f.bool) match
        case Some(true) => o.value.get("mediaUpload").map(m => read[MediaUpload](m))
        case _          => None
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
  def scalaType(arrType: ArrayType) = typ.withOptional(!required).scalaType(arrType)
}

object Parameter:
  def read(name: String, o: ujson.Value) =
    val typ = SchemaType.readType(SchemaPath(name), o.obj)
    val typDesc = typ.description
    Parameter(
      description = o.obj.get("description").map(_.str).map(_ + typDesc.map("\n" + _).getOrElse("")),
      location = o("location").str,
      typ = typ,
      required = o.obj.get("required").map(_.bool).getOrElse(false),
      pattern = o.obj.get("pattern").map(_.str)
    )

case class Property(description: Option[String], typ: SchemaType, readOnly: Boolean = false) {
  def optional: Boolean = typ.optional || readOnly
  def scalaType(arrType: ArrayType, enumType: SchemaType.EnumType): String =
    typ.withOptional(optional).scalaType(arrType, enumType)
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
  case Enum(typ: String, values: List[SchemaType.EnumValue], override val optional: Boolean) extends SchemaType(true)

  private def toType(t: String) = if optional then s"Option[$t]" else t

  def description: Option[String] = this match
    case Enum(_, values, _) =>
      values.collect { case SchemaType.EnumValue(n, desc) if desc.nonEmpty => s"$n: $desc" } match
        case Nil => None
        case v   => Some(v.mkString("\n"))
    case _ => None

  def schemaPath: Option[SchemaPath] = this match
    case Ref(ref, _)           => Some(ref)
    case Array(Ref(ref, _), _) => Some(ref)
    case _                     => None

  def withOptional(o: Boolean) = this match
    case t: Ref       => t.copy(optional = o)
    case t: Primitive => t.copy(optional = o)
    case t: Array     => t.copy(optional = o)
    case t: Object    => t.copy(optional = o)
    case t: Enum      => t.copy(optional = o)

  def scalaType(
      arrayType: ArrayType,
      enumType: SchemaType.EnumType = SchemaType.EnumType.Literal
  ): String = this match
    case Primitive("string", _, Some("google-datetime"))   => toType("java.time.OffsetDateTime")
    case Primitive("string", _, _)                         => toType("String")
    case Primitive("integer", _, Some("int32" | "uint32")) => toType("Int")
    case Primitive("integer", _, Some("int64" | "uint64")) => toType("Long")
    case Primitive("number", _, Some("double" | "float"))  => toType("Double")
    case Primitive("boolean", _, _)                        => toType("Boolean")
    case Ref(ref, _)                                       => toType(ref.scalaName)
    case Array(t, _)  => toType(arrayType.toScalaType(t.scalaType(arrayType, enumType)))
    case Object(t, _) => toType(s"Map[String, ${t.scalaType(arrayType)}]")
    case Enum(_, values, _) =>
      enumType match
        case SchemaType.EnumType.Literal       => toType(values.map(v => v.value).mkString("\"", "\" | \"", "\""))
        case SchemaType.EnumType.Nominal(name) => toType(name)
    case _ => toType("String")

object SchemaType:
  case class EnumValue(value: String, enumDescription: String)

  enum EnumType:
    case Literal
    case Nominal(prefix: String)

  private def toPrimitive(o: ujson.Obj, optional: Boolean) = SchemaType.Primitive(
    `type` = o("type").str,
    optional = optional,
    format = o.value.get("format").map(_.str)
  )

  def readType(context: SchemaPath, o: ujson.Obj): SchemaType =
    val desc = o.value.get("description").map(_.str)
    val optional = desc
      .map(_.toLowerCase())
      .exists(d => d.startsWith("optional") || !d.startsWith("required"))

    o.value.get("items").map(_.obj) match
      case Some(v) =>
        SchemaType.Array(readType(context.add("items"), v), optional)
      case _ =>
        (o.value.get("enum")) match
          case Some(e) =>
            SchemaType.Enum(
              typ = o("type").str,
              read[List[String]](e)
                .zip(read[List[String]](o("enumDescriptions")))
                .map((v, vd) => EnumValue(value = v, enumDescription = vd)),
              false
            )
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
                      if Set("uploadType", "upload_protocol").exists(context.jsonPath.lastOption.contains) then
                        """"([\w]+)"""".r
                          .findAllMatchIn(desc.getOrElse(""))
                          .map(_.group(1))
                          .toList
                          .collect { case v: String => EnumValue(value = v, enumDescription = "") } match
                          case Nil    => toPrimitive(o, optional)
                          case values => SchemaType.Enum(typ = o("type").str, optional = optional, values = values)
                      else toPrimitive(o, optional)
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
  def hasRequired: Boolean = properties.exists(!_._2.typ.optional)

  def hasArrays: Boolean = properties.exists(_._2.typ match {
    case gcp.codegen.SchemaType.Array(_, _) => true
    case _                                  => false
  })

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

case class Endpoint(endpointUrl: String, location: String, description: String) derives Reader

case class Specs(
    id: String,
    name: String,
    title: String,
    description: String,
    revision: String,
    documentationLink: String,
    protocol: String,
    ownerName: String,
    discoveryVersion: String,
    resources: Map[ResourcePath, Resource],
    version: String,
    schemas: Map[SchemaPath, Schema],
    rootUrl: String,
    baseUrl: String,
    basePath: String,
    endpoints: List[Endpoint],
    queryParameters: Map[String, Parameter]
) {
  def hasProps(schemaName: SchemaPath): Boolean =
    schemas.get(schemaName).exists(_.properties.nonEmpty)
}

def camelToSnakeCase(camelCase: String): String = {
  val camelCaseRegex = "([A-Z][a-z]+)".r
  camelCaseRegex.replaceAllIn(camelCase, matched => "_" + matched.group(0).toLowerCase)
}

def implicitVal(dialect: Dialect) = dialect match
  case Dialect.Scala3 => "given"

// comment splitted into multipl lines
private def toComment(content: Iterable[String], indent: String = "  "): String =
  if content.isEmpty then ""
  else
    content
      .flatMap(_.split('\n'))
      .flatMap(_.replace("e.g. ", "e.g.: ").split("\\. "))
      .filter(_.trim.nonEmpty)
      .mkString(s"$indent// ", s"\n$indent// ", "\n")

object Specs:
  given Reader[Specs] = reader[ujson.Obj].map(o =>
    Specs(
      id = o("id").str,
      title = o("title").str,
      description = o("description").str,
      revision = o("revision").str,
      name = o("name").str,
      documentationLink = o("documentationLink").str,
      protocol = o("protocol").str,
      ownerName = o("ownerName").str,
      discoveryVersion = o("discoveryVersion").str,
      resources = readResources(
        o("resources").obj.map((k, v) => ResourcePath(k) -> v).toList,
        Map.empty
      ),
      version = o("version").str,
      baseUrl = o("baseUrl").str,
      rootUrl = o("rootUrl").str,
      schemas = Schema.readSchemas(o("schemas").obj),
      basePath = o("basePath").str,
      endpoints = o.value.get("endpoints").map(read[List[Endpoint]](_)).getOrElse(Nil),
      queryParameters = o.value.get("parameters") match
        case None    => Map.empty
        case Some(v) => v.obj.map((k, v) => k -> Parameter.read(k, v)).filter(_._2.location == "query").toMap
    )
  )
