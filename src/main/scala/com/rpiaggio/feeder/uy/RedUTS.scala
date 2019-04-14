package com.rpiaggio.feeder.uy

import com.rpiaggio.feeder._

object RedUTS extends FeedList {
  private val parsePattern1 = ParsePattern(
    """<article{*}
      |<a href="{%}"{*}
      |<div class="event-date-day" date="">{%}</div>{*}
      |<div class="event-date-month text-uppercase" date="">{%}</div>{*}
      |<div class="event-title">{%}</div>{*}
      |<div class="event-venue">{%}</div>{*}
      |<div class="event-hour">{%}</div>{*}
      |</article>""".stripMargin
  )

  private val parsePattern = ParsePattern(
    """<article{*}
      |<a href="{%}"{*}
      |<div class="event-date entry-meta">{%}<header{*}
      |<div class="event-title">{%}</div>{*}
      |<div class="event-venue">{%}</div>{*}
      |<div class="event-btns entry-meta">{%}<a{*}
      |</div>{%}</a>{*}
      |</article>""".stripMargin
  )

  private val entryTemplate1 = FeedEntry("{%4} - {%3} {%2} - {%6}", "{%1}", "{%5}")
  private val entryTemplate = FeedEntry("{%3} - {%2} - {%5}", "{%1}", "{%6}<br/>{%4}")

  private def buildFeed(title: String, url: String) =
    Feed(FeedEntry(title, url, title), parsePattern, entryTemplate)

  val feeds = Map(
    "reduts-conciertos" -> buildFeed("RedUTS Conciertos", "https://reduts.com.uy/categoria/conciertos/?posts_per_page=100"),
    "reduts-teatro" -> buildFeed("RedUTS Teatro", "https://reduts.com.uy/categoria/teatro/?posts_per_page=100"),
    "reduts-electronica" -> buildFeed("RedUTS Electrónica", "https://reduts.com.uy/categoria/electronica/?posts_per_page=100"),
    "reduts-especiales" -> buildFeed("RedUTS Especiales", "https://reduts.com.uy/categoria/especiales/?posts_per_page=100")
  )
}
