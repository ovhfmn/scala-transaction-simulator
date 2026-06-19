import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Port, ipv4, port}
import io.circe.generic.auto.deriveEncoder
import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.Uri.Path.Root
import org.http4s.circe.jsonEncoder
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.Logger

/** Runtime control HTTP API
 *
 * Endopints:
 * POST /control/pause  - stop all streams
 * POST /control/resume - resume all streams
 * GET  /control/status - current status + event counters
 *
 * Example:
 * curl -X POST https://localhost:9090/control/pause
 * curl -X POST https://localhost:9090/control/resume
 * curl https://localhost:9090/control/status
 *
 */
object ControlApi {

  def server(control: StreamControl,
             port: Int)
            (using logger: Logger[IO]): Resource[IO, Server] = {

    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "control" / "pause" =>
        control.pause >>
          logger.info("Streams paused via control API") >>
          Ok(StatusResponse("paused").asJson)

      case POST -> Root / "control" / "resume" =>
        control.resume >>
          logger.info("Stream resumed via control API") >>
          Ok(StatusResponse("running").asJson)

      case GET -> Root / "control" / "status" =>
        for {
          paused <- control.isPaused
          stats <- control.getStats
          state = if (paused) "paused" else "running"
          resp <- Ok(
            StatusResponse(
              status = state,
              debits = Some(stats.debits),
              credits = Some(stats.credits),
              salaries = Some(stats.salaries),
              total = Some(stats.total),
            ).asJson
          )
        } yield resp
    }

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(Port.fromInt(port).getOrElse(port"9090"))
      .withHttpApp(routes.orNotFound)
      .build
  }

  private final case class StatusResponse
  (
    status: String,
    debits: Option[Long] = None,
    credits: Option[Long] = None,
    salaries: Option[Long] = None,
    total: Option[Long] = None
  )
}

