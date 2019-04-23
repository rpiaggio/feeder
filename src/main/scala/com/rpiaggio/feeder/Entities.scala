package com.rpiaggio.feeder

import akka.NotUsed
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import enumeratum._

sealed abstract class ParseAction(val representation: String) extends EnumEntry

object ParseAction extends Enum[ParseAction] {
  override def values = findValues

  final case object Capture extends ParseAction("%")

  final case object Ignore extends ParseAction("*")

  lazy val withRepresentation: Map[String, ParseAction] = values.map(action => action.representation -> action).toMap
}

final case class ParseInstruction(action: ParseAction, until: String)

final case class ParsePattern(start: String, doingFirst: ParseInstruction, andThen: ParseInstruction*) {
  val instructions = doingFirst +: andThen
}

object ParsePattern {
  private val fakeSeparator = "<!!!!>"
  private val anyParserActionPattern = s"\\{(${ParseAction.values.map(a => s"\\${a.representation}").mkString("|")})\\}"

  def apply(pattern: String): ParsePattern = {
    val tokens = pattern
      .replaceAll("[\\n\\r]", "")
      .replaceAll(anyParserActionPattern, s"$fakeSeparator$$1$fakeSeparator")
      .split(fakeSeparator)
    val instructions = tokens.tail.grouped(2).collect { case Array(actionStr, until) => ParseInstruction(ParseAction.withRepresentation(actionStr), until) }.toSeq
    ParsePattern(tokens.head, instructions.head, instructions.tail: _*)
  }
}

final case class FeedEntry(title: String, link: String, description: String) {
  lazy val uris: Stream[Uri] =
    if (link.contains("$page"))
      Stream(1 to PAGES_REQUEST:_*).map{page =>
        Uri(link.replace("$page", page.toString))
      }
    else
      Stream(Uri(link))
}

final case class Feed(channelEntry: FeedEntry, parsePattern: ParsePattern, entryTemplate: FeedEntry) {
  lazy val parser: Flow[ByteString, EntryData, NotUsed] = Flow.fromGraph(new EntryParser(parsePattern))
  lazy val formatter: Flow[EntryData, FeedEntry, Any] = Flow.fromFunction(new EntryCreator(entryTemplate, channelEntry.uris.head))
}

trait FeedList {
  val feeds: Map[String, Feed]
}