import cats.effect.IO
import cats.effect.std.AtomicCell
import fs2.Stream
import fs2.io.file.{Files, Path, Watcher}
import org.typelevel.log4cats.Logger

import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration.DurationLong

final class TransactionSimulator
(
  accountClient: AccountClient,
  checkpointStore: CheckpointStore,
  control: StreamControl
)(using logger: Logger[IO]) {

  def runAll(dataDir: String, parallelism: Int): IO[Unit] = {
    val dir = Path(dataDir)

    val existingFiles: Stream[IO, String] =
      Files[IO]
        .list(dir)
        .filter(p => p.extName == ".csv" && !p.toString.contains("archive"))
        .map(_.toString)

    val newFiles: Stream[IO, String] =
      Files[IO]
        .watch(dir)
        .collect {
          case Watcher.Event.Created(path, _) if path.extName == ".csv" && !path.toString.contains("archive") =>
            path.toString
        }

    val allFiles: Stream[IO, String] = existingFiles ++ newFiles

    allFiles
      .map(csvPath => Stream.eval(runFile(csvPath)).flatten)
      .parJoin(parallelism)
      .compile
      .drain
  }

  private def runFile(csvPath: String): IO[Stream[IO, Unit]] = {
    for {
      checkpoint <- checkpointStore.read(csvPath)
      _ <- logger.info(
        s"Starting $csvPath" +
          checkpoint.fold(" from beginning")(c => s" resuming after $c")
      )

      lastSeenCell <- AtomicCell[IO].of(Map.empty[String, Instant])
      anchorCell <- AtomicCell[IO].of(Option.empty[(Instant, Instant)])
    } yield {
      CsvParser
        .streamRows(csvPath, checkpoint, logger)
        .evalMap { row =>
          for {
            // --- Timing ---
            anchor <- anchorCell.get
            now <- IO.realTimeInstant
            _ <- anchor match {
              case None => anchorCell.set(Some((now, row.dateTime)))
              case Some((wallAnchor, dataAnchor)) =>
                val dataOffsetMs = row.dateTime.toEpochMilli - dataAnchor.toEpochMilli
                val wallOffsetMs = now.toEpochMilli - wallAnchor.toEpochMilli
                val sleepMs = dataOffsetMs - wallOffsetMs

                if (sleepMs > 0)
                  IO.sleep(sleepMs.millis)
                else
                  IO.unit
            }
            _ <- control.awaitResumed
            lastSeen <- lastSeenCell.get
            _ <- if (SalaryEmitter.shouldEmit(row.clientId, lastSeen.get(row.clientId), row.dateTime)) {
              val ldt = row.dateTime.atZone(ZoneOffset.UTC)
              val salary = SalaryEmitter.salaryAmount(
                row.clientId,
                overdraftLimit = BigDecimal(0),
                month = ldt.getMonthValue,
                year = ldt.getYear
              )
              logger.info(s"[SALARY] ${row.clientId} amount=$salary") >>
                accountClient.credit(row.clientId, salary) >>
                control.recordSent("salary")
            } else IO.unit

            _ <- if (row.isDebit) {
              accountClient.debit(row.clientId, row.absoluteAmount) >>
                control.recordSent("debit")
            } else {
              accountClient.credit(row.clientId, row.absoluteAmount) >>
                control.recordSent("credit")
            }

            _ <- lastSeenCell.update(_.updated(row.clientId, row.dateTime))
            _ <- checkpointStore.write(csvPath, row.dateTime)
          } yield ()
        }
        .onFinalize {
          val source = Path(csvPath)
          val archiveDir = source.parent.getOrElse(Path(".")) / "archive"
          val destination = archiveDir / source.fileName

          Files[IO].createDirectories(archiveDir) >>
            Files[IO].move(source, destination) >>
            logger.info(s"Archived $csvPath -> $destination")
        }
    }
  }
}