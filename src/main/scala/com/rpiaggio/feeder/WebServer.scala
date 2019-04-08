package com.rpiaggio.feeder

import java.nio.charset.{Charset, StandardCharsets}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Cookie
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import enumeratum._

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn

object WebServer {
  implicit private val system: ActorSystem = ActorSystem("my-system")
  implicit private val materializer: ActorMaterializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit private val executionContext: ExecutionContext = system.dispatcher

  private val maxRedirCount = 20

  type Entry = Seq[String]

  sealed abstract class ParseAction(val representation: String) extends EnumEntry

  final object ParseAction extends Enum[ParseAction] {
    override def values = findValues

    final case object Capture extends ParseAction("%")

    final case object Ignore extends ParseAction("*")

    lazy val withRepresentation: Map[String, ParseAction] = values.map(action => action.representation -> action).toMap
  }

  final case class ParseInstruction(action: ParseAction, until: String)

  final case class ParsePattern(start: String, doingFirst: ParseInstruction, andThen: ParseInstruction*)

  private val tickAntelPattern =
    """<div class="item">{*}
      |href="{%}"{*}
      |<p class="txt-upper txt-blue txt-bold">{%}</p>{*}
      |<span class="span-block">{%}</span>{*}""".stripMargin

  private val fakeSeparator = "<!!!!>"
  private val anyParserActionPattern = s"\\{(${ParseAction.values.map(a => s"\\${a.representation}").mkString("|")})\\}"

  def parseParsePattern(pattern: String): ParsePattern = {
    val tokens = pattern
      .replaceAll("[\\n\\r]", "")
      .replaceAll(anyParserActionPattern, s"$fakeSeparator$$1$fakeSeparator")
      .split(fakeSeparator)
    val instructions = tokens.tail.grouped(2).collect { case Array(actionStr, until) => ParseInstruction(ParseAction.withRepresentation(actionStr), until) }.toSeq
    ParsePattern(tokens.head, instructions.head, instructions.tail: _*)
  }

  private val tickAntelParsePattern = parseParsePattern(tickAntelPattern)

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

    case class EntryParser(pattern: ParsePattern) {

      var mode = 0 // 0 initing, 1 capturing

      var current = Seq.empty[String]

      println(pattern.start)

      def parse: Flow[ByteString, Seq[String], Any] = Flow.fromFunction { bytes =>
        println(bytes)
        println(new String(bytes.toArray, StandardCharsets.UTF_8))



        Seq(bytes.indexOfSlice(pattern.start.getBytes(StandardCharsets.UTF_8)).toString)

//        current
      }
    }

    val rssRender: Flow[Seq[String], ByteString, Any] = Flow.fromFunction { seq =>
      ByteString(seq.mkString("<", ",", ">\n"))
    }

    val route =
      path("hello") {
        get {
          complete(
            requestWithRedirects(HttpRequest(uri = Uri("https://tickantel.com.uy/inicio/buscar_categoria?cat_id=1")))
              .transform {
                _.map { response =>
                  HttpResponse(entity = response.entity.transformDataBytes(EntryParser(tickAntelParsePattern).parse.via(rssRender)))
                }
              }
          )
          //              Http().singleRequest(uri = Uri("https://tickantel.com.uy/inicio/buscar_categoria?0&cat_id=1"))
          //    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
        }
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }

}