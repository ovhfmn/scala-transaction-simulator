import cats.effect.IO
import cats.effect.std.AtomicCell
import fs2.Stream
import fs2.io.file.{Files, Path, Watcher}
import org.typelevel.log4cats.Logger

import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration.DurationLong

/** Core Streaming engine
 *
 * One FS2 stream per CSV file. All streams run concurrently via
 * Stream.parJoin - each maintains its own timing, checkpoint, and
 * per-account salary state independently.
 *
 * Timing model:
 * The simulator captures a wall-clock anchor at startup and
 * maps each row's dateTime to a relative offset from the first
 * row in that file. IO.sleep fires each event at the correct relatie time,
 * preserving the original inter-event gaps.
 *
 * Example: If row 1 is 2010-03-01T09:00:00Z and
 * row 2 is 2010-03-01T09:00:30Z, the simulator
 * sleeps 30s between them - as in the original data.
 *
 * Salary injection:
 * Before each debit/credit, SalaryEmitter checks whether the
 * account's salary day boundary has been crossed.
 * If yes, a credit is fired first, then the original transaction.
 * Per-account last-seen timestamp is tracked in a local Map inside
 * each stream - no shared state, no coordination needed.
 */
final class TransactionSimulator
(
  accountClient: AccountClient,
  checkpointStore: CheckpointStore,
  control: StreamControl
)(using logger: Logger[IO]) {

  //* Discover and stream all CSV files under data directory */
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

  //* Stream a single CSV file with timing, salary injection, and checkpoint */
  private def runFile(csvPath: String): IO[Stream[IO, Unit]] = {
    for {
      checkpoint <- checkpointStore.read(csvPath)
      _ <- logger.info(
        s"Starting $csvPath" +
          checkpoint.fold(" from beginning")(c => s" resuming after $c")
      )

      // Per-account last-seen timestamp - local to this stream, no shared state
      lastSeenCell <- AtomicCell[IO].of(Map.empty[String, Instant])
      // Anchor: stream's wall-clock time paired w/ first row's dateTime
      anchorCell <- AtomicCell[IO].of(Option.empty[(Instant, Instant)])
    } yield {
      CsvParser
        .streamRows(csvPath, checkpoint)
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
            // --- Pause check ---
            _ <- control.awaitResumed
            // --- Salary injection ---
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

            //  --- Main Transaction ---
            _ <- if (row.isDebit) {
              accountClient.debit(row.clientId, row.absoluteAmount) >>
                control.recordSent("debit")
            } else {
              accountClient.credit(row.clientId, row.absoluteAmount) >>
                control.recordSent("credit")
            }

            // --- Update per-account last-seen ---
            _ <- lastSeenCell.update(_.updated(row.clientId, row.dateTime))
            // --- Checkpoint ---
            _ <- checkpointStore.write(csvPath, row.dateTime)
          } yield ()
        }
        // --- moves successfuly processed file to /archive ---
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