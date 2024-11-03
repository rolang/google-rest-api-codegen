package pubsub.sttp4_jsoniter

import gcp.pubsub.v1.resources.jsoniter.*
import gcp.pubsub.v1.schemas.jsoniter.*
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
        name = s"projects/$projectId/topics/$someTopicName",
        state = None,
        labels = Some(Map("label" -> someTopicName))
      )
    )

    val publishReq = projects.Topics.publish(
      projectsId = projectId,
      topicsId = someTopicName,
      request = PublishRequest(
        List(PublishMessage(data = "data", attributes = Some(Map("key" -> "value")), orderingKey = Some("key")))
      )
    )

    EmulatorBackend.resource { backend =>
      assert(backend.send(createTopicReq).body.isRight)
      assert(backend.send(publishReq).body.isRight)
    }

  }
}
