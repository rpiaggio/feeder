package com.rpiaggio.feeder.uy

import com.rpiaggio.feeder.{Feed, FeedEntry, FeedList, ParsePattern}

object DSM extends FeedList {
  private val parsePattern = ParsePattern(
    """<article{*}
      |<h2{*}
      |<a{*}href="{%}"{*}>{%}</a>{*}
      |</h2>{%}</div>{*}
      |</article>""".stripMargin
  )

  private val entryTemplate = FeedEntry("{%2}", "{%1}", "{%3}")

  private def buildFeed(title: String, url: String) =
    Feed(FeedEntry(title, url, title), parsePattern, entryTemplate)

  val feeds = Map(
    "dsm-noticias" -> buildFeed("DSM - Noticias", "https://www.dsm.edu.uy/es/dsm/noticias.html")
  )
}
