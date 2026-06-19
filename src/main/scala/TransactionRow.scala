import java.time.Instant

/** Raw row parsed from a transaction CSV file
 *
 * Only the three columns the simulator cares about; other are ignored.
 *
 * Amount sign convention:
 * positive -> customer spent money -> Debit  -> balance decreases
 * negative -> income / refund      -> Credit -> balance increased
 *
 * @param dateTime  When this transaction occurred - drives IO.sleep timing
 * @param clientId  Maps 1:1 to accountId in Account Service
 * @param amount    Raw amount from CSV
 */
final case class TransactionRow
(
dateTime: Instant,
clientId: String,
amount:BigDecimal
) {
  def isDebit: Boolean = amount > 0
  def isCredit: Boolean = amount < 0

  /** Absolut value; endpoint always receive a positive amount */
  def absoluteAmount: BigDecimal = amount.abs
}
