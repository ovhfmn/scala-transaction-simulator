import cats.effect.IO
import fs2.io.file.{Files, Path}
import fs2.{Stream, text}
import org.typelevel.log4cats.Logger

import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

object CsvParser {

  private val csvDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def streamRows(csvPath: String,
                 resumeAfter: Option[Instant])
                (using logger: Logger[IO]): Stream[IO, TransactionRow] = {
    Files[IO]
      .readAll(Path(csvPath))
      .through(text.utf8.decode)
      .through(text.lines)
      .filter(_.trim.nonEmpty)
      .pull
      .uncons1
      .flatMap {
        case None => fs2.Pull.done
        case Some((headerLine, rest)) =>
          val headers = headerLine.split(",").map(_.trim.toLowerCase).toVector

          val dateIdx = headers.indexOf("datetime")
          val clientIdx = headers.indexOf("client_id")
          val amountIdx = headers.indexOf("amount")

          if (dateIdx < 0 || clientIdx < 0 || amountIdx < 0) {
            fs2.Pull.raiseError[IO](
              new IllegalArgumentException(
                s"$csvPath missing required columns. " +
                  s"Found: ${headers.mkString(", ")}. " +
                  s"Required: datetime, client_id, amount"
              )
            )
          }
          else {
            rest.evalMapFilter { line =>
                parseRow(csvPath, line, dateIdx, clientIdx, amountIdx, resumeAfter)
              }
              .pull
              .echo
          }
      }.stream
  }

  private def parseRow(csvPath: String, line: String, dateIdx: Int, clientIdx: Int, amountIdx: Int, resumeAfter: Option[Instant])
                      (using logger: Logger[IO]): IO[Option[TransactionRow]] = IO {
    val cols = line.split(",").map(_.trim)

    val result = for {
      rawDate <- cols.lift(dateIdx).filter(_.nonEmpty)
      clientId <- cols.lift(clientIdx).filter(_.nonEmpty)
      rawAmt <- cols.lift(amountIdx).filter(_.nonEmpty)
      dateTime <- scala.util.Try(
        LocalDateTime.parse(rawDate.trim, csvDateFormat)
          .toInstant(ZoneOffset.UTC)
      ).toOption
      amount <- scala.util.Try(BigDecimal(rawAmt)).toOption
      // Zero-amount rows are skipped
      if amount != BigDecimal(0)
    } yield {
      val tx = TransactionRow(dateTime, clientId, amount)
      tx
    }

    result
  }.flatMap {
    case None =>
      logger.debug(s"Skipped malformed row in $csvPath: $line").as(None)

    case Some(row) =>
      resumeAfter match {
        case Some(checkpoint) if !row.dateTime.isAfter(checkpoint) =>
          // Row before checkpoint is skipped
          IO.pure(None)
        case _ =>
          IO.pure(Some(row))
      }
  }
}
