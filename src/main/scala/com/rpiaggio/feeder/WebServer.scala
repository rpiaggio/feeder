package com.rpiaggio.feeder

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Cookie
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Attributes, FlowShape, Inlet, Materializer, Outlet}
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import enumeratum._
import org.jsoup.Jsoup

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import io.github.howardjohn.lambda.akka.AkkaHttpLambdaHandler

object WebServer {
  implicit private val system: ActorSystem = ActorSystem("feeder-system")
  implicit private val materializer: ActorMaterializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit private val executionContext: ExecutionContext = system.dispatcher

  private val maxRedirCount = 20

  type EntryData = Seq[String]

  sealed abstract class ParseAction(val representation: String) extends EnumEntry

  final object ParseAction extends Enum[ParseAction] {
    override def values = findValues

    final case object Capture extends ParseAction("%")

    final case object Ignore extends ParseAction("*")

    lazy val withRepresentation: Map[String, ParseAction] = values.map(action => action.representation -> action).toMap
  }

  final case class ParseInstruction(action: ParseAction, until: String)

  final case class ParsePattern(start: String, doingFirst: ParseInstruction, andThen: ParseInstruction*) {
    val instructions = doingFirst +: andThen
  }

  private val tickAntelPattern =
    """<div class="item">{*}
      |href="{%}"{*}
      |<p class="txt-upper txt-blue txt-bold">{%}</p>{*}
      |<span class="span-block">{%}</span>{*}
      |<p>{%}</p>{*}
      |</div>""".stripMargin

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

  final case class FeedEntry(title: String, link: String, description: String) {
    lazy val uri = Uri(link)
  }

  private val tickAntelEntryTemplate = FeedEntry("{%3} - {%2}", "{%1}", "{%4}")

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


  class EntryParserGraphStage(pattern: ParsePattern) extends GraphStage[FlowShape[ByteString, EntryData]] {
    val input = Inlet[ByteString]("EntryParser.in")
    val output = Outlet[EntryData]("EntryParser.out")

    override def shape: FlowShape[ByteString, EntryData] = FlowShape(input, output)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

      var entryRemainingInstructions: Option[Seq[ParseInstruction]] = None

      var currentEntry: EntryData = Seq.empty[String]

      var entryQueue = mutable.Queue.empty[EntryData]

      var previousBuffer: ByteString = ByteString.empty

      setHandler(input, new InHandler {
        override def onPush(): Unit = {
          val bytes = previousBuffer ++ grab(input)

          def parseChunk(lastIndex: Int = 0): Unit = {
            val nextString = entryRemainingInstructions.fold(pattern.start)(_.head.until)
            val nextIndex = bytes.indexOfSlice(nextString.getBytes(StandardCharsets.UTF_8), lastIndex)

            if (nextIndex >= 0) {
              entryRemainingInstructions.fold {
                entryRemainingInstructions = Some(pattern.instructions)
                parseChunk(nextIndex + pattern.start.length)
              } { instructions =>
                val currentInstruction = instructions.head
                if (currentInstruction.action == ParseAction.Capture) {
                  currentEntry = currentEntry :+ new String(bytes.slice(lastIndex, nextIndex).toArray, StandardCharsets.UTF_8).replaceAll("\n\r", "")
                }

                val remainingInstructions = instructions.tail
                if (remainingInstructions.isEmpty) {
                  if (isAvailable(output)) push(output, currentEntry) else entryQueue.enqueue(currentEntry)
                  entryRemainingInstructions = None
                  currentEntry = Seq.empty[String]
                } else {
                  entryRemainingInstructions = Some(remainingInstructions)
                }

                parseChunk(nextIndex + currentInstruction.until.length)
              }
            } else {
              previousBuffer = bytes.drop(lastIndex)
            }
          }

          parseChunk()
          if (!hasBeenPulled(input)) pull(input)
        }
      })

      setHandler(output, new OutHandler {
        override def onPull() = {
          if (entryQueue.nonEmpty) {
            push(output, entryQueue.dequeue)
          }
          if (!hasBeenPulled(input)) pull(input)
        }
      })
    }

  }

  def entryCreator(entryTemplate: FeedEntry, baseUri: Uri): Flow[EntryData, FeedEntry, Any] = Flow.fromFunction { seq =>
    val parameterPattern = "\\{%(\\d+)\\}".r

    def replace(template: String): String = {
      parameterPattern.replaceAllIn(template, mtch => seq(mtch.group(1).toInt - 1))
    }

    FeedEntry(
      Jsoup.parse(replace(entryTemplate.title)).body.text,
      Uri(replace(entryTemplate.link)).resolvedAgainst(baseUri).toString,
      replace(entryTemplate.description)
    )
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

  final case class Feed(channelEntry: FeedEntry, parsePattern: ParsePattern, entryTemplate: FeedEntry) {
    lazy val parser = Flow.fromGraph(new EntryParserGraphStage(parsePattern))
    lazy val formatter = entryCreator(entryTemplate, channelEntry.uri)
  }

  val tickAntelMusicaFeed = Feed(FeedEntry("TickAntel Música", "https://tickantel.com.uy/inicio/buscar_categoria?cat_id=1", "TickAntel Música"), tickAntelParsePattern, tickAntelEntryTemplate)
  val tickAntelTeatroFeed = Feed(FeedEntry("TickAntel Teatro", "https://tickantel.com.uy/inicio/buscar_categoria?cat_id=2", "TickAntel Teatro"), tickAntelParsePattern, tickAntelEntryTemplate)

  val feeds = Map(
    "tickantel-musica" -> tickAntelMusicaFeed,
    "tickantel-teatro" -> tickAntelTeatroFeed,
  )

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

  val xmlRoute =
    path("feed" / Segment) { feedName =>
      get {
        feeds.get(feedName).fold(complete(s"Unknown stream [$feedName].")) { feed =>
          complete(
            requestWithRedirects(HttpRequest(uri = feed.channelEntry.uri))
              .transform {
                _.map { response =>
                  HttpResponse(
                    // headers = List(headers.`Content-Type`(MediaTypes.`application/rss+xml`.withCharset(HttpCharsets.`UTF-8`))),
                    entity = response.entity.transformDataBytes(
                      feed.parser.via(feed.formatter).via(rssRender)
                        .recover { case t: Throwable => ByteString(t.getMessage + "\n" + t.getStackTrace.mkString("\n")) }
                        .prepend(Source(List(prefix(feed.channelEntry))))
                        .concat(Source(List(suffix)))
                        .log("Feeder")
                    ).withContentType(ContentTypes.NoContentType)
                    //.withContentType(MediaTypes.`application/rss+xml`.withCharset(HttpCharsets.`UTF-8`))
                  )
                }
              }
          )
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

  val router = xmlRoute ~ xslRoute ~ cssRoute


  def main(args: Array[String]) {
    import scala.io.StdIn

    val bindingFuture = Http().bindAndHandle(router, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }

  class EntryPoint extends AkkaHttpLambdaHandler(router)

}