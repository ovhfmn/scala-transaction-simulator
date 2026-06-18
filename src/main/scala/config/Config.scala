package config

import cats.effect.*
import cats.implicits.catsSyntaxTuple5Parallel
import cats.instances.*
import cats.syntax.*
import cats.syntax.all.catsSyntaxTuple5Parallel
import cats.syntax.parallel.catsSyntaxTuple5Parallel
import ciris.*

final case class Config
(
  accountServiceUrl: String,
  dataDir: String,
  checkpointDir: String,
  controlPort: Int,
  parallelism: Byte
)

object Config {
  def load: IO[Config] = {
    (
      env("ACCOUNT_SERVICE_URL").as[String].default("http://localhost:8081"),
      env("DATA_DIR").as[String].default("./src/main/resources"),
      env("CHECKPOINT_DIR").as[String].default("./src/main/resources/checkpoints"),
      env("CONTROL_PORT").as[Int].default(9090),
      env("PARALLELISM").as[Byte].default(4)
    ).parMapN(Config.apply).load[IO]
  }
}