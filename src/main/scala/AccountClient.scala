import cats.effect.IO
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.*
import org.typelevel.log4cats.Logger

final class AccountClient(
                           client: Client[IO],
                           baseUri: Uri,
                         )(using logger: Logger[IO]) {

  def debit(clientId: String, amount: BigDecimal): IO[Unit] = {
    call(
      uri = baseUri / "accounts" / clientId / "debit",
      body = AmountRequest(amount),
      action = "DEBIT",
      id = clientId,
    )
  }

  def credit(clientId: String, amount: BigDecimal): IO[Unit] = {
    call(
      uri = baseUri / "accounts" / clientId / "credit",
      body = AmountRequest(amount),
      action = "CREDIT",
      id = clientId,
    )
  }

  private def call(
                    uri: Uri,
                    body: AmountRequest,
                    action: String,
                    id: String,
                  ): IO[Unit] = {
    val request = Request[IO](method = Method.POST, uri = uri)
      .withEntity(body.asJson)

    client.run(request).use { response =>
      response.status match {
        case Status.Ok | Status.Created | Status.NoContent =>
          logger.debug(s"[$action] $id amount=${body.amount}")

        case Status.BadRequest | Status.UnprocessableEntity =>
          response.bodyText.compile.string.flatMap { b =>
            logger.warn(s"[$action] $id rejected: $b")
          }

        case other =>
          response.bodyText.compile.string.flatMap { b =>
            logger.warn(s"[$action] $id -> HTTP ${other.code}: $b")
          }
      }
    }
  }
}

private final case class AmountRequest(amount: BigDecimal)
