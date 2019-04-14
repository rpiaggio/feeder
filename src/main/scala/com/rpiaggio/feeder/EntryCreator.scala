package com.rpiaggio.feeder

import akka.http.scaladsl.model.Uri
import org.jsoup.Jsoup

class EntryCreator(entryTemplate: FeedEntry, baseUri: Uri) extends Function1[EntryData, FeedEntry] {

  private val parameterPattern = "\\{%(\\d+)\\}".r

  override def apply(seq: EntryData) = {
    def replace(template: String): String = {
      parameterPattern.replaceAllIn(template, mtch => seq(mtch.group(1).toInt - 1))
    }

    FeedEntry(
      Jsoup.parse(replace(entryTemplate.title)).body.text,
      Uri(replace(entryTemplate.link)).resolvedAgainst(baseUri).toString,
      replace(entryTemplate.description)
    )
  }
}
