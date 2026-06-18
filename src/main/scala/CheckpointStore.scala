import cats.effect.IO
import fs2.io.file.{Files, Flags, Path}
import fs2.text
import org.typelevel.log4cats.Logger

import java.time.Instant

final class CheckpointStore(checkpointDir: String)(using logger: Logger[IO]) {

  private def checkpointPath(csvPath: String): Path = {
    val filename = Path(csvPath).fileName.toString
    Path(s"$checkpointDir/$filename.checkpoint")
  }

  def read(csvPath: String): IO[Option[Instant]] = {
    val path = checkpointPath(csvPath)
    Files[IO]
      .exists(path)
      .flatMap {
        case false => IO.pure(None)
        case true =>
          Files[IO]
            .readAll(path)
            .through(text.utf8.decode)
            .through(text.lines)
            .filter(_.trim.nonEmpty)
            .take(1)
            .compile
            .last
            .flatMap {
              case None => IO.pure(None)
              case Some(raw) =>
                IO(Instant.parse(raw.trim))
                  .map(Some(_))
                  .handleErrorWith { err =>
                    logger.warn(s"Corrupt checkpoint for $csvPath: $err - starting from begining") >>
                      IO.pure(None)
                  }
            }
      }
  }

  def write(csvPath: String, timestamp: Instant): IO[Unit] = {
    val path = checkpointPath(csvPath)
    fs2.Stream
      .emit(timestamp.toString)
      .through(text.utf8.encode)
      .through(Files[IO].writeAll(path, Flags.Write))
      .compile
      .drain
  }

  def init: IO[Unit] = Files[IO].createDirectories(Path(checkpointDir))
}
