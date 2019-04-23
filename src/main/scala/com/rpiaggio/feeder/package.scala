package com.rpiaggio

package object feeder {
  type EntryData = Seq[String]

  protected[feeder] val PAGES_REQUEST = 10

  protected[feeder] val MAX_REDIR_COUNT = 20
}
