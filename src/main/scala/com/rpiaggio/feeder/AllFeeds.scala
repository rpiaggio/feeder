package com.rpiaggio.feeder

object AllFeeds extends FeedList {
  val feeds = uy.TickAntel.feeds ++ uy.RedUTS.feeds
}