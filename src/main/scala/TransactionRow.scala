import java.time.Instant

final case class TransactionRow
(
dateTime: Instant,
clientId: String,
amount:BigDecimal
) {
  def isDebit: Boolean = amount > 0
  def isCredit: Boolean = amount < 0

  def absoluteAmount: BigDecimal = amount.abs
}
