package tasks

/** Decides how often PaideiaSyncTask.syncRemainingBlocks checkpoints state
  * (Paideia.commit + Paideia.persistState) while walking confirmed blocks.
  *
  * Checkpointing after every block is cheap once the sync loop is essentially caught
  * up to the chain tip, but would make catching up from far behind (a fresh replay,
  * or a long outage) needlessly slow if done for every one of a large number of
  * blocks. So: checkpoint every block once within virtualMempoolHeight+1 of the node
  * height (i.e. once caught up, since syncRemainingBlocks itself only ever processes
  * blocks up to nodeHeight - virtualMempoolHeight), otherwise only every
  * `interval` blocks.
  */
object CheckpointPolicy {

  /** @param height
    *   the height of the block that was just fully handled
    * @param nodeHeight
    *   the node's full height as of the last poll
    * @param virtualMempoolHeight
    *   how many blocks below the node tip syncRemainingBlocks treats as "virtual
    *   mempool" and does not yet process as confirmed
    * @param interval
    *   checkpoint every this many blocks while still far behind the tip
    */
  def shouldCheckpoint(
      height: Int,
      nodeHeight: Int,
      virtualMempoolHeight: Int,
      interval: Int
  ): Boolean =
    if (nodeHeight - height <= virtualMempoolHeight + 1) true
    else interval > 0 && height % interval == 0
}
