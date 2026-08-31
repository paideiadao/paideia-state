package services

import org.scalatest.FunSuite

import play.api.libs.json.Json

import im.paideia.common.contracts.TreasuryShortfallErgsException
import im.paideia.common.contracts.TreasuryShortfallTokensException

/** Covers the treasury shortfall registry (PaideiaStateService.replaceTreasuryShortfalls
  * / getTreasuryShortfall), the per-cycle merge helper
  * (PaideiaStateService.accumulateShortfall / mergeMaxTokenMaps) that PaideiaSyncTask
  * folds resp.exceptions into, and the JSON shapes the new
  * GET /dao/:daoKey/treasury/health endpoint returns for both the "ok" and "short"
  * states (built the same way DAOController.getDAOTreasuryHealth builds them, since
  * there's no existing controller-test harness in this project to drive the route
  * itself).
  */
class TreasuryShortfallSpec extends FunSuite {

  private val daoKey = "00" * 32

  test("getTreasuryShortfall is empty before anything is ever recorded") {
    val service = new PaideiaStateService()
    assert(service.getTreasuryShortfall(daoKey).isEmpty)
  }

  test("replaceTreasuryShortfalls + getTreasuryShortfall round-trips a shortfall") {
    val service = new PaideiaStateService()
    val shortfall = TreasuryShortfall(Some(500L), Map("tokenA" -> 10L), 12345)
    service.replaceTreasuryShortfalls(Map(daoKey -> shortfall))
    assert(service.getTreasuryShortfall(daoKey) === Some(shortfall))
    assert(service.getTreasuryShortfall("someOtherDao").isEmpty)
  }

  test("replaceTreasuryShortfalls clears a DAO that no longer appears in the new map") {
    val service = new PaideiaStateService()
    service.replaceTreasuryShortfalls(
      Map(daoKey -> TreasuryShortfall(Some(500L), Map.empty, 1))
    )
    assert(service.getTreasuryShortfall(daoKey).isDefined)

    // Next cycle's map no longer has this DAO in it (it recovered) - replacing
    // wholesale must make it disappear, not merge with the old entry.
    service.replaceTreasuryShortfalls(Map.empty)
    assert(service.getTreasuryShortfall(daoKey).isEmpty)
  }

  test("replaceTreasuryShortfalls fully replaces (not merges) an existing entry") {
    val service = new PaideiaStateService()
    service.replaceTreasuryShortfalls(
      Map(daoKey -> TreasuryShortfall(Some(500L), Map("tokenA" -> 10L), 1))
    )
    service.replaceTreasuryShortfalls(
      Map(daoKey -> TreasuryShortfall(Some(100L), Map.empty, 2))
    )
    assert(
      service.getTreasuryShortfall(daoKey) === Some(
        TreasuryShortfall(Some(100L), Map.empty, 2)
      )
    )
  }

  test("mergeMaxTokenMaps keeps the max amount per token and unions the token ids") {
    val a = Map("tokenA" -> 10L, "tokenB" -> 5L)
    val b = Map("tokenB" -> 20L, "tokenC" -> 3L)
    assert(
      PaideiaStateService.mergeMaxTokenMaps(a, b) === Map(
        "tokenA" -> 10L,
        "tokenB" -> 20L,
        "tokenC" -> 3L
      )
    )
  }

  test("accumulateShortfall on a single ergs exception records neededfound - foundNanoErgs as missing") {
    val ex = new TreasuryShortfallErgsException(daoKey, 1000L, 400L)
    val acc = PaideiaStateService.accumulateShortfall(Map.empty, ex, 42)
    assert(acc(daoKey) === TreasuryShortfall(Some(600L), Map.empty, 42))
  }

  test("accumulateShortfall on a single tokens exception records the per-token deficits") {
    val ex = new TreasuryShortfallTokensException(
      daoKey,
      Map("tokenA" -> 100L, "tokenB" -> 50L),
      Map("tokenA" -> 30L)
    )
    val acc = PaideiaStateService.accumulateShortfall(Map.empty, ex, 42)
    assert(
      acc(daoKey) === TreasuryShortfall(
        None,
        Map("tokenA" -> 70L, "tokenB" -> 50L),
        42
      )
    )
  }

  test("accumulateShortfall merges an ergs and a tokens exception for the same dao in one cycle") {
    val ergsEx = new TreasuryShortfallErgsException(daoKey, 1000L, 700L)
    val tokensEx = new TreasuryShortfallTokensException(
      daoKey,
      Map("tokenA" -> 100L),
      Map("tokenA" -> 40L)
    )
    val acc1 = PaideiaStateService.accumulateShortfall(Map.empty, ergsEx, 42)
    val acc2 = PaideiaStateService.accumulateShortfall(acc1, tokensEx, 42)
    assert(
      acc2(daoKey) === TreasuryShortfall(Some(300L), Map("tokenA" -> 60L), 42)
    )
  }

  test("accumulateShortfall keeps the max missing ergs across repeated ergs exceptions in one cycle") {
    val small = new TreasuryShortfallErgsException(daoKey, 1000L, 900L) // missing 100
    val big = new TreasuryShortfallErgsException(daoKey, 1000L, 200L) // missing 800
    val acc = PaideiaStateService.accumulateShortfall(
      PaideiaStateService.accumulateShortfall(Map.empty, small, 1),
      big,
      2
    )
    assert(acc(daoKey).missingNanoErgs === Some(800L))
    // the later exception's height wins
    assert(acc(daoKey).height === 2)
  }

  test("accumulateShortfall on an unrelated exception leaves the accumulator unchanged") {
    val acc = PaideiaStateService.accumulateShortfall(
      Map(daoKey -> TreasuryShortfall(Some(1L), Map.empty, 1)),
      new RuntimeException("some other failure"),
      2
    )
    assert(acc === Map(daoKey -> TreasuryShortfall(Some(1L), Map.empty, 1)))
  }

  test("accumulateShortfall tracks separate DAOs independently") {
    val otherDaoKey = "11" * 32
    val acc = PaideiaStateService.accumulateShortfall(
      PaideiaStateService.accumulateShortfall(
        Map.empty,
        new TreasuryShortfallErgsException(daoKey, 1000L, 900L),
        10
      ),
      new TreasuryShortfallErgsException(otherDaoKey, 500L, 100L),
      10
    )
    assert(acc.keySet === Set(daoKey, otherDaoKey))
    assert(acc(daoKey).missingNanoErgs === Some(100L))
    assert(acc(otherDaoKey).missingNanoErgs === Some(400L))
  }

  // -- JSON shapes, built the same way DAOController.getDAOTreasuryHealth does --

  test("JSON shape when there is no shortfall") {
    val json = Json.obj("status" -> "ok")
    assert(Json.stringify(json) === """{"status":"ok"}""")
  }

  test("JSON shape when there is a shortfall") {
    val shortfall = TreasuryShortfall(Some(123L), Map("tokenA" -> 7L), 999)
    val json = Json.obj(
      "status" -> "short",
      "missingNanoErgs" -> shortfall.missingNanoErgs,
      "missingTokens" -> shortfall.missingTokens,
      "height" -> shortfall.height
    )
    assert(
      Json.stringify(json) ===
        """{"status":"short","missingNanoErgs":123,"missingTokens":{"tokenA":7},"height":999}"""
    )
  }

  test("JSON shape when there is a shortfall with no missing ergs (tokens only)") {
    val shortfall = TreasuryShortfall(None, Map("tokenA" -> 7L), 999)
    val json = Json.obj(
      "status" -> "short",
      "missingNanoErgs" -> shortfall.missingNanoErgs,
      "missingTokens" -> shortfall.missingTokens,
      "height" -> shortfall.height
    )
    assert(
      Json.stringify(json) ===
        """{"status":"short","missingNanoErgs":null,"missingTokens":{"tokenA":7},"height":999}"""
    )
  }
}
