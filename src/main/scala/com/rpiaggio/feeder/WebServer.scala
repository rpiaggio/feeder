package com.rpiaggio.feeder

import java.nio.charset.StandardCharsets

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity.CloseDelimited
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Cookie
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl._
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future}
import io.github.howardjohn.lambda.akka.AkkaHttpLambdaHandler

object WebServer {
  implicit private val system: ActorSystem = ActorSystem("feeder-system")
  implicit private val materializer: ActorMaterializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit private val executionContext: ExecutionContext = system.dispatcher

  private val maxRedirCount = 20

  def requestWithRedirects(req: HttpRequest, count: Int = 0)(implicit system: ActorSystem, mat: Materializer): Future[HttpResponse] = {
    //    println(s"Requesting [$req.uri]")
    val result =
      Http().singleRequest(req).flatMap { resp =>
        //        println(s"Response [$resp]")
        resp.status match {
          case StatusCodes.MovedPermanently | StatusCodes.Found | StatusCodes.SeeOther | StatusCodes.UseProxy | StatusCodes.TemporaryRedirect | StatusCodes.PermanentRedirect =>
            resp.header[headers.Location].map { loc =>
              val cookie = resp.header[headers.`Set-Cookie`].map(header => Cookie(header.cookie.pair))
              val newReq = req.copy(uri = loc.uri, headers = req.headers ++ cookie)
              if (count < maxRedirCount) requestWithRedirects(newReq, count + 1) else Http().singleRequest(newReq)
            }.getOrElse(throw new RuntimeException(s"location not found on redirect for ${req.uri}"))
          case _ => Future(resp)
        }
      }

    result.onFailure { case t: Throwable =>
      t.printStackTrace()
    }

    result
  }

  val rssRender: Flow[FeedEntry, ByteString, Any] = Flow.fromFunction { entry =>
    val node =
        <item>
          <title>{entry.title}</title>
          <link>{entry.link}</link>
          <description>{entry.description}</description>
        </item>

    ByteString(node.toString + "\n", StandardCharsets.UTF_8)
  }


  def prefix(channelEntry: FeedEntry) =
    ByteString(
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<?xml-stylesheet type=\"text/xsl\" href=\"../xsl\"?>\n" +
        "<rss version=\"2.0\">\n" +
        "<channel>\n" +
        <title>{channelEntry.title}</title>.toString +
        <link>{channelEntry.link}</link>.toString +
        <description>{channelEntry.description}</description>.toString + "\n",
      StandardCharsets.UTF_8
    )

  val suffix =
    ByteString(
      "</channel>\n" +
        "</rss>",
      StandardCharsets.UTF_8
    )

  def feedSource(feed: Feed): Future[Source[FeedEntry, Any]] =
    requestWithRedirects(HttpRequest(uri = feed.channelEntry.uri))
      .transform {
        _.map { response =>
          response.entity.dataBytes.via(feed.parser).via(feed.formatter)
        }
      }

  def responseFromSource(channelEntry: FeedEntry)(source: Source[FeedEntry, Any]): HttpResponse =
    HttpResponse(
      entity = CloseDelimited(ContentTypes.NoContentType,
        source.via(rssRender)
          .recover { case t: Throwable => ByteString(t.getMessage + "\n" + t.getStackTrace.mkString("\n")) }
          .prepend(Source(List(prefix(channelEntry))))
          .concat(Source(List(suffix)))
          .log("Feeder")
      )
    )


  val allRoute =
    path("all") {
      get {
        complete(
          Future.sequence(AllFeeds.feeds.values.map(feedSource)).map(sources =>
            Source.combine(sources.head, sources.tail.head, sources.tail.tail.toSeq: _*)(Concat(_))
            //            _.foldLeft(Source.empty[FeedEntry])(_ ++ _)
          )
            .map(responseFromSource(FeedEntry("Feeder Merged Feed", "http://feeder/all", "Feeder Merged Feed")))
        )
      }
    }

  val xmlRoute =
    path("feed" / Segment) { feedName =>
      get {
        AllFeeds.feeds.get(feedName).fold(complete(s"Unknown stream [$feedName].")) { feed =>
          complete(feedSource(feed).map(responseFromSource(feed.channelEntry)))
        }
      }
    }

  val xslRoute =
    path("xsl") {
      getFromResource("preview.xsl")
    }

  val cssRoute =
    path("css") {
      getFromResource("style.css")
    }

  val router = xmlRoute ~ xslRoute ~ cssRoute //~ allRoute


  def main(args: Array[String]) {
    import scala.io.StdIn

    val bindingFuture = Http().bindAndHandle(router, "localhost", 8080)

    println("Server online at http://localhost:8080/")
    println("Valid feeds:")
    AllFeeds.feeds.keys.toSeq.sorted.foreach(key => println(s"* $key"))
    println("Press RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }

  class EntryPoint extends AkkaHttpLambdaHandler(router)

}