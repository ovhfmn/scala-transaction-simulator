import cats.effect.IO
import cats.effect.std.AtomicCell
import fs2.io.file.Watcher.EventType

final class StreamControl
(
  pausedCell: AtomicCell[IO, Boolean],
  statsCell: AtomicCell[IO, StreamStats]
) {

  def pause: IO[Unit] = pausedCell.set(true)
  def resume: IO[Unit] = pausedCell.set(false)
  def isPaused: IO[Boolean] = pausedCell.get

  def awaitResumed: IO[Unit] =
    isPaused.flatMap {
      case false => IO.unit
      case true =>
        IO.sleep(scala.concurrent.duration.FiniteDuration(500, "ms"))
          >> awaitResumed
    }

  def recordSent(eventType: String): IO[Unit] = statsCell.update(_.record(eventType))

  def getStats: IO[StreamStats] = statsCell.get
}

object StreamControl {
  def make: IO[StreamControl] = {
    for {
      pause <- AtomicCell[IO].of(false)
      stats <- AtomicCell[IO].of(StreamStats.empty)
    } yield StreamControl(pause, stats)
  }
}

final case class StreamStats
(
  debits: Long,
  credits: Long,
  salaries: Long,
  skipped: Long
) {
  def record(eventType: String): StreamStats = {
    eventType match {
      case "debit" => copy(debits = debits + 1)
      case "credit" => copy(credits = credits + 1)
      case "salary" => copy(salaries = salaries + 1)
      case _ => copy(skipped = skipped + 1)
    }
  }

  def total: Long = debits + credits + salaries
}

object StreamStats {
  val empty: StreamStats = StreamStats(0L, 0L, 0L, 0L)
}