package com.rpiaggio.feeder.uy

import com.rpiaggio.feeder.{Feed, FeedEntry, FeedList, ParsePattern}

object RedTickets extends FeedList {
  private val parsePattern = ParsePattern(
    """GRIDEVENTOIMAGEN"{*}
      |data-gx-enabled-id={*}GRIDEVENTONOMBRE"{*}>{%}</span>{*}
      |data-gx-enabled-id={*}GRIDEVENTODESC"{*}>{%}</span>{*}
      |data-gx-enabled-id={*}GRIDEVENTOID"{*}>{%}</span>{*}""".stripMargin
  )

  private val entryTemplate = FeedEntry("{%1} - {%2}", "https://redtickets.uy/servlet/com.redtickets2.verevento?{%3}", "")

  private def buildFeed(title: String, url: String) =
    Feed(FeedEntry(title, url, title), parsePattern, entryTemplate)

  val feeds = Map(
    "redtickets-familiares" -> buildFeed("RedTickets Familiares", "https://redtickets.uy/servlet/com.redtickets2.busqueda?1"),
    "redtickets-deportes" -> buildFeed("RedTickets Deportes", "https://redtickets.uy/servlet/com.redtickets2.busqueda?2"),
    "redtickets-musica" -> buildFeed("RedTickets Música", "https://redtickets.uy/servlet/com.redtickets2.busqueda?3"),
    "redtickets-cursos" -> buildFeed("RedTickets Cursos", "https://redtickets.uy/servlet/com.redtickets2.busqueda?4"),
    "redtickets-conferencias" -> buildFeed("RedTickets Conferencias", "https://redtickets.uy/servlet/com.redtickets2.busqueda?5"),
    "redtickets-teatro" -> buildFeed("RedTickets Teatro", "https://redtickets.uy/servlet/com.redtickets2.busqueda?6"),
    "redtickets-especiales" -> buildFeed("RedTickets Especiales", "https://redtickets.uy/servlet/com.redtickets2.busqueda?7"),
    "redtickets-futbol" -> buildFeed("RedTickets Fútbol", "https://redtickets.uy/servlet/com.redtickets2.busqueda?8"),

  )
}
