import cats.effect.IO
import cats.effect.std.AtomicCell

/** Shared mutable control state for running simulator
 *
 * All streams read from the same StateControl instance -
 * pausing affects all streams simultaneously.
 *
 * Uses AtomicCell for thread-safe updates w/o locks.
 * Reads are cheap
 *
 */
final class StreamControl
(
  pausedCell: AtomicCell[IO, Boolean],
  statsCell: AtomicCell[IO, StreamStats]
) {

  def pause: IO[Unit] = pausedCell.set(true)
  def resume: IO[Unit] = pausedCell.set(false)
  def isPaused: IO[Boolean] = pausedCell.get

  /** Block the calling fiber until the stream is unpaused
   *
   * Uses IO.sleep - yields the thread, does not block.
   * 500ms is a reasonable tradeff between responsiveness & CPU overhead
   */
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

/** Running counters - reported by GET /control/status */
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