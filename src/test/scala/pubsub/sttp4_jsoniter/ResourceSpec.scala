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
    val pMsg = PublishMessage(data = "data", attributes = Some(Map("key" -> "value")), orderingKey = Some("key"))
    val someTopicName = "topic_" + Random.alphanumeric.take(10).mkString
    val projectId = "any"

    val createTopicReq = projects.Topics.create(
      projectsId = projectId,
      topicsId = someTopicName,
      Topic(
        name = s"projects/$projectId/topics/$someTopicName",
        state = None,
        labels = Some(Map("label" -> someTopicName)),
        messageStoragePolicy = None,
        kmsKeyName = None,
        schemaSettings = None,
        satisfiesPzs = None,
        messageRetentionDuration = None,
        ingestionDataSourceSettings = None
      )
    )

    val publisgReq = projects.Topics.publish(
      projectsId = projectId,
      topicsId = someTopicName,
      request = PublishRequest(Vector(PublishMessage(data = "message", attributes = None, orderingKey = None)))
    )

    EmulatorBackend.resource { backend =>
      assert(backend.send(createTopicReq).body.isRight)
      assert(backend.send(publisgReq).body.isRight)
    }

  }
}
