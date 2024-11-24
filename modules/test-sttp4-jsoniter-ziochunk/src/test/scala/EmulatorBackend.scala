package pubsub

import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend
import java.net.http.HttpClient
import java.net.http.HttpClient.Version
import scala.util.Using

class EmulatorBackend(backend: SyncBackend) extends AutoCloseable {
  def send[T](req: Request[T]): Response[T] =
    backend.send(req.copy(uri = req.uri.scheme("http").host("localhost").port(8085)))

  override def close(): Unit = backend.close()
}

object EmulatorBackend:
  def resource = Using
    .resource(
      EmulatorBackend(
        HttpClientSyncBackend.usingClient(
          HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(Version.HTTP_2)
            .build()
        )
      )
    )
