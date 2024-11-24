package gcp.pubsub.v1.sttp4_jsoniter_ziochunk

import gcp.pubsub.v1.sttp4_jsoniter_ziochunk.resources.*
import gcp.pubsub.v1.sttp4_jsoniter_ziochunk.schemas.*
import sttp.client4.UriContext
import scala.util.Random
import sttp.client4.httpclient.HttpClientSyncBackend
import java.net.http.HttpClient
import java.net.http.HttpClient.Version
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import sttp.client4.Request
import pubsub.EmulatorBackend

class PubsubJsoniterResourceSpec extends munit.FunSuite {
  test("PublishMessage") {
    val someTopicName = "topic_" + Random.alphanumeric.take(10).mkString
    val projectId = "any"

    val createTopicReq = projects.Topics.create(
      projectsId = projectId,
      topicsId = someTopicName,
      Topic(
        name = s"projects/$projectId/topics/$someTopicName"
      )
    )

    val publishReq = projects.Topics.publish(
      projectsId = projectId,
      topicsId = someTopicName,
      request = PublishRequest(
        zio.Chunk(PublishMessage(data = "data", attributes = Some(Map("key" -> "value")), orderingKey = Some("key")))
      )
    )

    EmulatorBackend.resource { backend =>
      assert(backend.send(createTopicReq).isSuccess)
      assert(backend.send(publishReq).isSuccess)
    }

  }
}
