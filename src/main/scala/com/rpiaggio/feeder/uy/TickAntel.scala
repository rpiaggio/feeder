package com.rpiaggio.feeder.uy

import com.rpiaggio.feeder._

object TickAntel extends FeedList {
  private val parsePattern = ParsePattern(
    """<div class="item">{*}
      |href="{%}"{*}
      |<p class="txt-upper txt-blue txt-bold">{%}</p>{*}
      |<span class="span-block">{%}</span>{*}
      |<p>{%}</p>{*}
      |</div>""".stripMargin
  )

  private val entryTemplate = FeedEntry("{%3} - {%2}", "{%1}", "{%4}")

  private def buildFeed(title: String, url: String) =
    Feed(FeedEntry(title, url, title), parsePattern, entryTemplate)

  val feeds = Map(
    "tickantel-musica" -> buildFeed("TickAntel Música", "https://tickantel.com.uy/inicio/buscar_categoria?cat_id=1"),
    "tickantel-teatro" -> buildFeed("TickAntel Teatro", "https://tickantel.com.uy/inicio/buscar_categoria?cat_id=2")
  )
}
