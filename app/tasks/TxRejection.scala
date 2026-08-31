package tasks

/** Classifies Ergo node rejections of `POST /transactions` submissions.
  *
  * This service runs as multiple independent instances (operated by different parties) that all
  * race to submit the same protocol transactions (compound, snapshot, emit, ...). The Ergo node
  * arbitrates: only one submission wins, and the others get rejected because their inputs are
  * already spent by the winner's transaction (in the mempool or confirmed on-chain). Losing such a
  * race is the expected steady state of a multi-operator deployment and must not be logged/reported
  * as an error, or operators would be unable to distinguish race noise from real failures.
  */
object TxRejection {

  /** Substrings (matched case-insensitively) that unambiguously indicate the node rejected our
    * submission because another transaction already claimed (or is claiming) the same inputs -
    * i.e. we lost a submission race, not a real error.
    *
    * Each phrase is sourced from the ergoplatform/ergo node (tag v6.1.2) as returned in the JSON
    * error body of a non-2xx response to `POST /transactions`, which ends up embedded verbatim in
    * the message of the `ErgoClientException` thrown by ergo-appkit 6.0.1's
    * `NodeDataSourceImpl.executeCall` (`"<httpCode>: <raw error body>"`), which in turn is what
    * `BlockchainContextImpl.sendTransaction` (used via `ctx.sendTransaction` here) propagates.
    *
    *   - "Double spending attempt"
    *     Thrown as `IllegalArgumentException` for a `ProcessingOutcome.DoubleSpendingLoser` (a
    *     transaction that loses the in-mempool replace-by-fee tiebreak against another transaction
    *     already spending the same inputs) and turned into an HTTP 400 with this text as the
    *     `detail` field.
    *     Source: src/main/scala/org/ergoplatform/http/api/ErgoBaseApiRoute.scala
    *     (`sendLocalTransactionRoute`, `case _: DoubleSpendingLoser => ... "Double spending attempt"`)
    *
    *   - "not all utxos in place yet"
    *     The message of the `Exception` backing a `ProcessingOutcome.Declined` outcome, raised when
    *     the mempool re-checks the transaction's inputs against the UTXO set merged with the
    *     mempool and finds that at least one input box can no longer be resolved there (i.e. it was
    *     already spent by a winning transaction between initial validation and pool insertion).
    *     Source: src/main/scala/org/ergoplatform/nodeView/mempool/ErgoMemPool.scala (`process`,
    *     `if (tx.inputIds.forall(inputBoxId => utxoWithPool.boxById(inputBoxId).isDefined)) ... else
    *     val exc = new Exception("not all utxos in place yet")`)
    *
    *   - "every input of the transaction should be in utxo"
    *     The `txBoxesToSpend` consensus validation rule message, triggered when the transaction's
    *     inputs cannot all be resolved against the UTXO set at initial submission time (again,
    *     because they are already spent). Surfaces via `POST /transactions`' initial
    *     `verifyTransaction` check as `"Malformed transaction: Every input of the transaction
    *     should be in UTXO. ..."`.
    *     Source: ergo-core/src/main/scala/org/ergoplatform/settings/ValidationRules.scala
    *     (`txBoxesToSpend -> RuleStatus(im => fatal(s"Every input of the transaction should be in
    *     UTXO. ${im.error}", ...))`), raised from
    *     ergo-core/src/main/scala/org/ergoplatform/modifiers/mempool/ErgoTransaction.scala
    *     (`.validate(txBoxesToSpend, boxesToSpend.size == inputs.size, ...)`)
    *
    * Deliberately excluded: generic "Malformed transaction" / "invalid transaction" text on its
    * own (too broad - would swallow real errors), and "already in the mempool" (means our own
    * submission is a duplicate of one already accepted, not a lost race against someone else's
    * competing spend).
    */
  private val lostRacePhrases: Seq[String] = Seq(
    "double spending attempt",
    "not all utxos in place yet",
    "every input of the transaction should be in utxo"
  )

  /** True when a node rejection message means another transaction already spent our inputs
    * (we lost the race to another operator), i.e. an expected outcome, not an error.
    */
  def isLostRace(message: String): Boolean = {
    Option(message).exists { msg =>
      val lower = msg.toLowerCase
      lostRacePhrases.exists(lower.contains)
    }
  }
}
