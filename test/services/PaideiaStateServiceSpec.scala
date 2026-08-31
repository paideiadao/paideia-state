package services

import org.scalatest.FunSuite

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.Failure

import actors.PaideiaStateActor._

class PaideiaStateServiceSpec extends FunSuite {

  private val syncingMessage =
    "Paideia state is currently syncing, try again some time later."

  test("a freshly constructed service starts with syncing == true") {
    val service = new PaideiaStateService()
    assert(service.syncing)
  }

  test("getAllDAOs fails with the syncing message while syncing") {
    val service = new PaideiaStateService()
    service.getAllDAOs(GetAllDAOs()) match {
      case Failure(exception) => assert(exception.getMessage() == syncingMessage)
      case other               => fail(s"expected Failure, got $other")
    }
  }

  test("getDAOConfig fails with the syncing message while syncing") {
    val service = new PaideiaStateService()
    service.getDAOConfig(GetDAOConfig("00" * 32)) match {
      case Failure(exception) => assert(exception.getMessage() == syncingMessage)
      case other               => fail(s"expected Failure, got $other")
    }
  }

  test("withWriteLock is exclusive: a concurrent writer blocks until the first releases") {
    val service = new PaideiaStateService()

    val firstHasLock = new CountDownLatch(1)
    val releaseFirst = new CountDownLatch(1)
    val secondAcquired = new AtomicBoolean(false)
    val secondDone = new CountDownLatch(1)

    val t1 = new Thread(() =>
      service.withWriteLock {
        firstHasLock.countDown()
        releaseFirst.await()
      }
    )
    t1.start()
    firstHasLock.await()

    val t2 = new Thread(() =>
      service.withWriteLock {
        secondAcquired.set(true)
        secondDone.countDown()
      }
    )
    t2.start()

    // t2 must not have been able to acquire the write lock yet - the first writer is
    // still holding it.
    assert(!secondDone.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
    assert(!secondAcquired.get())

    releaseFirst.countDown()
    t1.join(5000)
    assert(secondDone.await(5, java.util.concurrent.TimeUnit.SECONDS))
    assert(secondAcquired.get())
    t2.join(5000)
  }
}
