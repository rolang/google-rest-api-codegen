package gcp.pubsub.v1.sttp4_jsoniter_ziochunk

import gcp.pubsub.v1.sttp4_jsoniter_ziochunk.schemas.*

class PubsubJsoniterCodecSpec extends munit.FunSuite {
  test("PublishMessage") {
    val pMsg = PubsubMessage(data = Some("data"), attributes = Some(Map("key" -> "value")), orderingKey = Some("key"))
    val expected = """{"data":"data","attributes":{"key":"value"},"orderingKey":"key"}"""
    val encoded = pMsg.toJsonString

    assert(encoded == expected)
  }

  test("PublishMessage no ordering key") {
    val pMsg = PubsubMessage(data = Some("data"), attributes = Some(Map("key" -> "value")))
    val expected = """{"data":"data","attributes":{"key":"value"}}"""
    val encoded = pMsg.toJsonString

    assert(encoded == expected)
  }

  test("PublishMessage no ordering key, no attributes") {
    val pMsg = PubsubMessage(data = Some("data"))
    val expected = """{"data":"data"}"""
    val encoded = pMsg.toJsonString

    assert(encoded == expected)
  }
}
