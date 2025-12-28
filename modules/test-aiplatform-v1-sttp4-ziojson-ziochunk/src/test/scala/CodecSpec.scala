package gcp.aiplatform.v1.sttp4_ziojson_ziochunk

import gcp.aiplatform.v1.sttp4_ziojson_ziochunk.schemas.*
import zio.json.*
import zio.json.ast.Json

class CodecSpec extends munit.FunSuite {
  test("decode as GoogleCloudAiplatformV1FunctionCall") {
    val json = """|{
                  |  "name": "func name",
                  |  "args": {
                  |     "arg1": 1,
                  |     "arg2": {
                  |       "nestedArg1": "a",
                  |       "nestedArg2": true
                  |     }
                  |   }
                  |}""".stripMargin

    val decoded = json.fromJson[GoogleCloudAiplatformV1FunctionCall]
    val args = decoded.map(_.args)

    assert(decoded.map(_.name) == Right(Some("func name")))
    assert(args.isRight)
    assert(args.exists(_.nonEmpty))
    assert(
      args.toOption.flatten.get == Json.Obj(
        "arg1" -> Json.Num(1),
        "arg2" -> Json.Obj(
          "nestedArg1" -> Json.Str("a"),
          "nestedArg2" -> Json.Bool(true)
        )
      )
    )
  }
}
