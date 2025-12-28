package gcp.aiplatform.v1.sttp4_jsoniter_ziochunk

import gcp.aiplatform.v1.sttp4_jsoniter_ziochunk.schemas.*
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

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

    case class NestedArgs(nestedArg1: String, nestedArg2: Boolean, nestedArg3: Option[String])
    object NestedArgs:
      given JsonValueCodec[NestedArgs] = JsonCodecMaker.make

    case class Args(arg1: Int, arg2: NestedArgs)
    object Args:
      given JsonValueCodec[Args] = JsonCodecMaker.make

    val decoded = readFromString[GoogleCloudAiplatformV1FunctionCall](json)
    val args = decoded.args.map(_.readAs[Args])

    assert(decoded.name == Some("func name"))
    assert(args.nonEmpty)
    assert(args.exists(_.isRight))
    assert(args.flatMap(_.toOption).get == Args(1, NestedArgs("a", true, None)))
  }
}
