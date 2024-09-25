import zio.json.*
import zio.json.ast.*

class PubsubTest extends munit.FunSuite {
  test("PublishMessage") {
    val pMsg = gcp.pubsub.v1.schemas
      .PublishMessage(data = "data", attributes = Some(Map("key" -> "value")), orderingKey = Some("key"))
    assert(pMsg.toJsonString.fromJson[Json.Obj].isRight)
  }
}
