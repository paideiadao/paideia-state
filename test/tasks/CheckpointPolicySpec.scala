package tasks

import org.scalatest.FunSuite

class CheckpointPolicySpec extends FunSuite {

  test("checkpoints every block once within virtualMempoolHeight+1 of the node tip") {
    // nodeHeight - height == virtualMempoolHeight + 1, the boundary case
    assert(CheckpointPolicy.shouldCheckpoint(100, 111, 10, 100))
    // fully caught up
    assert(CheckpointPolicy.shouldCheckpoint(111, 111, 10, 100))
  }

  test("falls back to interval checkpointing while far behind the tip") {
    // nodeHeight - height == virtualMempoolHeight + 2, just outside the near-tip window
    assert(!CheckpointPolicy.shouldCheckpoint(101, 113, 10, 100))
  }

  test("checkpoints on interval boundaries while far behind the tip") {
    assert(CheckpointPolicy.shouldCheckpoint(100000, 500000, 10, 100))
    assert(!CheckpointPolicy.shouldCheckpoint(100001, 500000, 10, 100))
    assert(!CheckpointPolicy.shouldCheckpoint(100050, 500000, 10, 100))
  }

  test("height 0 is always a checkpoint boundary while far behind the tip") {
    assert(CheckpointPolicy.shouldCheckpoint(0, 500000, 10, 100))
  }

  test("a non-positive interval never triggers interval checkpointing while far behind the tip") {
    assert(!CheckpointPolicy.shouldCheckpoint(100000, 500000, 10, 0))
    assert(!CheckpointPolicy.shouldCheckpoint(100000, 500000, 10, -5))
  }

  test("a larger virtualMempoolHeight widens the always-checkpoint window near the tip") {
    assert(CheckpointPolicy.shouldCheckpoint(90, 100, 20, 100))
    assert(!CheckpointPolicy.shouldCheckpoint(50, 100, 20, 100))
  }
}
