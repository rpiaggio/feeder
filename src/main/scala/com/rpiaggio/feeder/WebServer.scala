package com.rpiaggio.feeder

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Cookie
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn

object WebServer {
  implicit private val system: ActorSystem = ActorSystem("my-system")
  implicit private val materializer: ActorMaterializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit private val executionContext: ExecutionContext = system.dispatcher

  private val maxRedirCount = 20

  def main(args: Array[String]) {
    def requestWithRedirects(req: HttpRequest, count: Int = 0)(implicit system: ActorSystem, mat: Materializer): Future[HttpResponse] = {
      Http().singleRequest(req).flatMap { resp =>
        resp.status match {
          case StatusCodes.Found => resp.header[headers.Location].map { loc =>
            val cookie = resp.header[headers.`Set-Cookie`].map(header => Cookie(header.cookie.pair))
            val newReq = req.copy(uri = loc.uri, headers = req.headers ++ cookie)
            if (count < maxRedirCount) requestWithRedirects(newReq, count + 1) else Http().singleRequest(newReq)
          }.getOrElse(throw new RuntimeException(s"location not found on 302 for ${req.uri}"))
          case _ => Future(resp)
        }
      }
    }

    val extractEntries: Flow[ByteString, ByteString, Any] = Flow.fromFunction(identity)


    def handler(request: HttpRequest): Future[HttpResponse] = {
      requestWithRedirects(HttpRequest(uri = Uri("https://tickantel.com.uy/inicio/buscar_categoria?cat_id=1")))
        .transform {
          _.map { response =>
            HttpResponse(entity = response.entity.transformDataBytes(extractEntries))
          }
        }
    }

    //    val route =
    //      path("hello") {
    //        get {
    //          Http().singleRequest(uri = Uri("https://tickantel.com.uy/inicio/buscar_categoria?0&cat_id=1"))
    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
    //        }
    //      }

    val bindingFuture = Http().bindAndHandleAsync(handler, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }

}