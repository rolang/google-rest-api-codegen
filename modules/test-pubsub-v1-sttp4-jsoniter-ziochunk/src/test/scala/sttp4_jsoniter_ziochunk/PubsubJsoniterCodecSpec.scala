package gcp.pubsub.v1.sttp4_jsoniter_ziochunk

import gcp.pubsub.v1.sttp4_jsoniter_ziochunk.schemas.*
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString

class PubsubJsoniterCodecSpec extends munit.FunSuite {
  test("PublishMessage with ordering key") {
    val pMsg = PubsubMessage(data = Some("data"), attributes = Some(Map("key" -> "value")), orderingKey = Some("key"))
    val expected = """{"attributes":{"key":"value"},"orderingKey":"key","data":"data"}"""
    val encoded = pMsg.toJsonString

    assert(encoded == expected)

    val decoded = readFromString[PubsubMessage](expected)
    assert(pMsg == decoded)
  }

  test("PublishMessage no ordering key") {
    val pMsg = PubsubMessage(data = Some("data"), attributes = Some(Map("key" -> "value")))
    val expected = """{"attributes":{"key":"value"},"data":"data"}"""
    val encoded = pMsg.toJsonString

    assert(encoded == expected)

    val decoded = readFromString[PubsubMessage](expected)
    assert(pMsg == decoded)
  }

  test("PublishMessage no ordering key, no attributes") {
    val pMsg = PubsubMessage(data = Some("data"))
    val expected = """{"data":"data"}"""
    val encoded = pMsg.toJsonString

    assert(encoded == expected)

    val decoded = readFromString[PubsubMessage](expected)
    assert(pMsg == decoded)
  }
}
