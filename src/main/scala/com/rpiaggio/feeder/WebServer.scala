package com.rpiaggio.feeder

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Cookie
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Attributes, FlowShape, Inlet, Materializer, Outlet}
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import enumeratum._

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn

object WebServer {
  implicit private val system: ActorSystem = ActorSystem("my-system")
  implicit private val materializer: ActorMaterializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit private val executionContext: ExecutionContext = system.dispatcher


  private val WEBPAGE_URI = Uri("https://tickantel.com.uy/inicio/buscar_categoria?cat_id=1")

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


    class EntryParserGraphStage(pattern: ParsePattern) extends GraphStage[FlowShape[ByteString, Entry]] {
      val input = Inlet[ByteString]("EntryParser.in")
      val output = Outlet[Entry]("EntryParser.out")

      override def shape: FlowShape[ByteString, Entry] = FlowShape(input, output)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

        var entryRemainingInstructions: Option[Seq[ParseInstruction]] = None

        var currentEntry: Entry = Seq.empty[String]

        var entryQueue = mutable.Queue.empty[Entry]

        var previousBuffer: ByteString = ByteString.empty

        setHandler(input, new InHandler {
          override def onPush(): Unit = {
            val bytes = previousBuffer ++ grab(input)

            //            println(s"ONPUSH! $bytes")


            def parseChunk(lastIndex: Int = 0): Unit = {


              //              println(s"parseChunk($lastIndex) - entryRemainingInstructions [$entryRemainingInstructions] - currentEntry [$currentEntry]")


              val nextString = entryRemainingInstructions.fold(pattern.start)(_.head.until)
              val nextIndex = bytes.indexOfSlice(nextString.getBytes(StandardCharsets.UTF_8), lastIndex)

              if (nextIndex >= 0) {

                //                println(s"FOUND! nextIndex [$nextIndex]")

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

                    //                    println(s"ENQUEUEING [$currentEntry]")

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
            //            println(s"ONPULL! QUEUE: ${entryQueue.length}")


            if (entryQueue.nonEmpty) {
              //              println(s"PUSHING! ${entryQueue.head}")

              push(output, entryQueue.dequeue)
            }
            if (!hasBeenPulled(input)) pull(input)
          }
        })
      }

    }

    val parser = Flow.fromGraph(new EntryParserGraphStage(tickAntelParsePattern))

    val rssRender: Flow[Entry, ByteString, Any] = Flow.fromFunction { seq =>
      ByteString("<\n" + seq.zipWithIndex.map { case (s, i) => s"   $$${i + 1} = $s" }.mkString("\n") + "\n>\n" +
        Uri(seq.head).resolvedAgainst(WEBPAGE_URI) + "\n")
    }

    val route =
      path("hello") {
        get {
          complete(
            requestWithRedirects(HttpRequest(uri = WEBPAGE_URI))
              .transform {
                _.map { response =>
                  HttpResponse(entity = response.entity.transformDataBytes(parser.via(rssRender)))
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