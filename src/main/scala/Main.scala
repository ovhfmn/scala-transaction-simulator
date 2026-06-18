import cats.effect.{ExitCode, IO, IOApp, Resource}
import config.Config
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

    for {
      config <- Config.load
      _ <- logger.info(
        s"transaction-simulator starting -" +
          s"dataDir=${config.dataDir} " +
          s"serviceUrl=${config.accountServiceUrl} " +
          s"controlPort=${config.controlPort}"
      )

      control <- StreamControl.make
      _ <- resource(config, control).use { simulator =>
        IO.race(
          simulator.runAll(config.dataDir, config.parallelism),
          IO.never
        ).void
      }
    } yield ExitCode.Success
  }

  private def resource(
                        config: Config,
                        control: StreamControl
                      )(using logger: Logger[IO]): Resource[IO, TransactionSimulator] = {
    for {
      httpClient <- EmberClientBuilder.default[IO].build

      baseUri <- Resource.eval(
        IO.fromEither(
          Uri.fromString(config.accountServiceUrl)
            .left.map(e => new IllegalArgumentException(s"Invalid URL: $e"))
        )
      )

      accountClient = AccountClient(httpClient, baseUri)
      checkpointStore = CheckpointStore(config.checkpointDir)

      _ <- Resource.eval(checkpointStore.init)
      _ <- ControlApi.server(control, config.controlPort)
      simulator = TransactionSimulator(accountClient, checkpointStore, control)
    } yield simulator
  }

}
