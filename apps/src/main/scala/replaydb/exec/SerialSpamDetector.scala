package replaydb.exec

import java.io.FileInputStream

import replaydb.event.{Event, MessageEvent, NewFriendshipEvent}
import replaydb.io.SocialNetworkStorage
import replaydb.runtimedev.ReplayRuntime._
import replaydb.runtimedev._
import replaydb.runtimedev.monotonicImpl.{ReplayCounterImpl, ReplayMapImpl}
import replaydb.util.ProgressMeter

/**
 * Single-threaded implementation of spam detector
 */
object SerialSpamDetector extends App {

  class UserPair(val a: Long, val b: Long) {
    override def equals(o: Any) = o match {
      case that: UserPair => (a == that.a && b == that.b) || (a == that.b && b == that.a)
      case _ => false
    }
    override def hashCode(): Int = {
      a.hashCode() + 25741 * b.hashCode()
    }
  }

  case class PrintSpamCounter(ts: Long) extends Event

  class Stats {
    val friendships: ReplayMap[UserPair, Int] = new ReplayMapImpl[UserPair, Int](0)
    val friendSendRatio: ReplayMap[Long, (Long, Long)] =
      new ReplayMapImpl[Long, (Long, Long)]((0L,0L))
    val spamCounter: ReplayCounter = new ReplayCounterImpl
    def getRuntimeInterface = emit {
      bind { e: NewFriendshipEvent =>
        friendships.update(ts = e.ts, key = new UserPair(e.userIdA, e.userIdB), fn = _ => 1)
      }
      bind { me: MessageEvent =>
        val ts = me.ts
        friendships.get(ts = ts, key = new UserPair(me.senderUserId, me.recipientUserId)) match {
          case Some(_) => friendSendRatio.update(ts = ts, key = me.senderUserId, {case (friends, nonFriends) => (friends + 1, nonFriends)})
          case None => friendSendRatio.update(ts = ts, key = me.senderUserId, {case (friends, nonFriends) => (friends, nonFriends + 1)})
        }
      }
      bind {
        me: MessageEvent =>
          val ts = me.ts
          friendSendRatio.get(ts = ts, key = me.senderUserId) match {
            case Some((toFriends, toNonFriends)) =>
              if (toFriends + toNonFriends > 5) {
                if (toNonFriends > toFriends) {
                  spamCounter.add(1, ts)
                }
              }
            case None =>
          }
      }
      bind {
        e: PrintSpamCounter => println(s"spam count is ${spamCounter.get(e.ts)}")
      }
    }
  }

  if (args.length != 1) {
    println("Usage: SerialSpamDetector filename")
    System.exit(1)
  }

  val inputFilename = args(0)
  val eventStorage = new SocialNetworkStorage
  val stats = new Stats
  val si = stats.getRuntimeInterface
  var lastTimestamp = 0L
  val pm = new ProgressMeter(printInterval = 1000000, () => { si.update(new PrintSpamCounter(lastTimestamp)); ""})
  val r = eventStorage.readEvents(new FileInputStream(inputFilename), e => {
    si.update(e)
    lastTimestamp = e.ts
    pm.increment()
  })
  pm.finished()
  println("Final spam count: " + stats.spamCounter.get(Long.MaxValue))
}
