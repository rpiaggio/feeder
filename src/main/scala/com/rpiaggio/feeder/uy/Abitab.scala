package com.rpiaggio.feeder.uy

import com.rpiaggio.feeder._

object Abitab extends FeedList {
  private val parsePattern = ParsePattern(
    """<div class=\"box\">{*}
      |<div class=\"box-title\"><a href=\'{%}\'>{%}</a>{*}
      |</div></div>""".stripMargin
  )

  private val entryTemplate = FeedEntry("{%2}", "{%1}", "")

  private def buildFeed(title: String, url: String) =
    Feed(FeedEntry(title, url, title), parsePattern, entryTemplate)

  // TODO: MULTIPAGE!

  val feeds = Map(
    "abitab-musica" -> buildFeed("Abitab Música", "https://www.abitab.com.uy/abitab/AjaxPromo?idroot=1&categoria=12071&site=12&channel=abitab&page=$page"),
    "abitab-danza" -> buildFeed("Abitab Danza", "https://www.abitab.com.uy/abitab/AjaxPromo?idroot=1&categoria=12899&site=12&channel=abitab&page=$page"),
    "abitab-teatro" -> buildFeed("Abitab Teatro", "https://www.abitab.com.uy/abitab/AjaxPromo?idroot=1&categoria=12072&site=12&channel=abitab&page=$page"),
    "abitab-varios" -> buildFeed("Abitab Varios", "https://www.abitab.com.uy/abitab/AjaxPromo?idroot=1&categoria=11785&site=12&channel=abitab&page=$page")
  )
}
