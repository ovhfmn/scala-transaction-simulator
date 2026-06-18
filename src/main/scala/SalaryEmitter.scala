import java.time.{Instant, ZoneOffset}
import scala.util.Random

object SalaryEmitter {
  def salaryDayOfMonth(clientId: String): Int = (clientId.hashCode.abs % 28) + 1

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
