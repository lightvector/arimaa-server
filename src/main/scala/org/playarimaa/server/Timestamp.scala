package org.playarimaa.server
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.DateTimeFormatterBuilder

object Timestamp {

  /** A timestamp to be recorded in a log or returned by the server in a query.
    * In particular, a number of seconds since the epoch (midnight Jan 1 1970) but
    * in a floating point format to allow sub-second precision.
    */
  type Timestamp = Double

  /** Returns a timestamp representing the current system time, with at least millisecond
    * precision. */
  def get: Timestamp = {
    System.currentTimeMillis.toDouble / 1000.0
  }


  //Formatter for parsing datetimes
  val dtFormatter: DateTimeFormatter =
    new DateTimeFormatterBuilder().
      append(null,
        Array(
          //No time zone
          DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd HH:mm").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd").getParser(),

          //Time zone like '-06:00'
          DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mmZ").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ssZ").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd HH:mmZ").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-ddZ").getParser(),

          //Time zone ID, like America/Los_Angeles or Europe/London
          DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss ZZZ").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm ZZZ").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss ZZZ").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd HH:mm ZZZ").getParser(),
          DateTimeFormat.forPattern("yyyy-MM-dd ZZZ").getParser()
        )
      ).toFormatter.withZoneUTC()

  /** Parses a timestamp from a datetime string in a variety of ISO8601-like formats.
    * Raises an exception on an invalid format */
  def parse(s: String): Timestamp = {
    dtFormatter.parseDateTime(s).getMillis / 1000.0
  }
}
