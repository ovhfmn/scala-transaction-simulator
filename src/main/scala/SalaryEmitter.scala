import java.time.{Instant, ZoneOffset}
import scala.util.Random

/** Emitetes an income for a given account; determines salary's date and amount.
 *
 * Design: fullt deterministic, zero external state.
 * - Salary date is derived from clientId hash; stable across restarts.
 * - Salary emited when the current row's dateTime crosses the account's
 * salary day.
 *
 * Salary amount rule:
 * - overdraftLimit >= 20_000   ->  salary = overdraftLimit * 0.1
 * - overdraftLimit <  20_000   ->  salary = random in [1500, 5500]
 *
 * Random salary uses clientId + year-month as seed.
 */
object SalaryEmitter {
  /** Day to recive salary in range [1, 28] to avoid edge cases */
  def salaryDayOfMonth(clientId: String): Int = (clientId.hashCode.abs % 28) + 1

  /** True if salary should be emitted.
   *
   * Triggers when:
   * 1. The current row's day-of-month >= salary day, AND
   * 2. The previous row's day-of-month < salary day
   *
   *
   *
   * @param clientId  Account identifier
   * @param previous  dateTime of the previous row
   * @param current   dateTime of the current row
   */
  def shouldEmit(
                  clientId: String,
                  previous: Option[Instant],
                  current: Instant
                ): Boolean = {
    val salaryDay = salaryDayOfMonth(clientId)
    val currentLdt = current.atZone(ZoneOffset.UTC).toLocalDate
    val currentDay = currentLdt.getDayOfMonth

    previous match {
      case None =>
        // First transaction
        currentDay >= salaryDay

      case Some(prev) =>
        val prevLdt = prev.atZone(ZoneOffset.UTC).toLocalDate
        val prevDay = prevLdt.getDayOfMonth

        val newMonth = currentLdt.getYear > prevLdt.getYear
          || currentLdt.getMonthValue > prevLdt.getMonthValue

        if (newMonth) {
          currentDay >= salaryDay
        } else {
          prevDay < salaryDay && currentDay >= salaryDay
        }

    }
  }

  /**
   * Reproducable - same seed same amount.
   * Not trully random.
   */
  def salaryAmount(
                    clientId: String,
                    overdraftLimit: BigDecimal,
                    month: Int,
                    year: Int
                  ): BigDecimal = {
    val threshold = BigDecimal(20000)

    if (overdraftLimit >= threshold) {
      (overdraftLimit * BigDecimal("0.10")).setScale(2, BigDecimal.RoundingMode.HALF_UP)
    } else {
      val seed = s"$clientId-$year-$month".hashCode.abs
      val rng = new Random(seed)
      BigDecimal(1500 + rng.nextInt(4001)).setScale(2) // [1500, 5500]
    }
  }
}
