package tasks

import org.scalatest.FunSuite

class TxRejectionSpec extends FunSuite {

  // ergo node (v6.1.2) rejection texts that mean "we lost a submission race" - see
  // app/tasks/TxRejection.scala for the source citation of each phrase.

  test("classifies a DoubleSpendingLoser rejection as a lost race") {
    assert(TxRejection.isLostRace("400: {\"error\":400,\"reason\":\"bad.request\",\"detail\":\"Double spending attempt\"}"))
  }

  test("classifies a mempool re-check Declined rejection as a lost race") {
    assert(TxRejection.isLostRace("400: {\"error\":400,\"reason\":\"bad.request\",\"detail\":\"not all utxos in place yet\"}"))
  }

  test("classifies a txBoxesToSpend validation rejection as a lost race") {
    assert(
      TxRejection.isLostRace(
        "400: {\"error\":400,\"reason\":\"bad.request\",\"detail\":\"Malformed transaction: Every input of the transaction should be in UTXO. abc123: 1 == 2. Missing inputs: 1\"}"
      )
    )
  }

  test("phrase matching is case-insensitive") {
    assert(TxRejection.isLostRace("DOUBLE SPENDING ATTEMPT"))
    assert(TxRejection.isLostRace("Not All UTXOs In Place Yet"))
  }

  test("does not classify a treasury not-enough-ergs failure as a lost race") {
    assert(
      !TxRejection.isLostRace(
        "Not enough ERGs to satisfy: 5000000000 nanoERGs required, 3000000000 nanoERGs found"
      )
    )
  }

  test("does not classify a generic malformed transaction message without spent-input semantics") {
    assert(!TxRejection.isLostRace("Malformed transaction: invalid transaction format"))
  }

  test("is null-safe and returns false for a null message") {
    assert(!TxRejection.isLostRace(null))
  }

  test("returns false for an empty message") {
    assert(!TxRejection.isLostRace(""))
  }
}
