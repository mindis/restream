package replaydb.exec.spam

import replaydb.event.{MessageEvent, NewFriendshipEvent}
import replaydb.runtimedev.ReplayRuntime._
import replaydb.runtimedev._
import replaydb.runtimedev.threadedImpl._

import scala.collection.immutable.HashSet


/*
RULES:

1. spam if sent messages to non friends > 2 * (messages to friends) and total messages > 5

 */

class SimpleSpamDetectorStats(replayStateFactory: replaydb.runtimedev.ReplayStateFactory) extends HasRuntimeInterface
  with HasSpamCounter with HasReplayStates[ReplayState with Threaded] {
  import replayStateFactory._

  val friendships: ReplayMap[UserPair, Int] = getReplayMap(0, (up: UserPair) => List(up.a.hashCode, up.b.hashCode))
  val friendSendRatio: ReplayMap[Long, (Long, Long)] = getReplayMap((0L,0L))
  val friendMessageCount = getReplayCounter
  val nonfriendMessageCount = getReplayCounter
  val spamUsers: ReplayMap[Boolean, HashSet[Long]] = getReplayMap(HashSet())
  val spamCounter: ReplayCounter = getReplayCounter
  val messageSpamRatings: ReplayTimestampLocalMap[Long, Int] = getReplayTimestampLocalMap(0)
  val spamCountByUser: ReplayMapTopK[Long, Int] = getReplayMapTopK(0, 20)

  // TODO Ideally this becomes automated by the code generation portion
  def getAllReplayStates: Seq[ReplayState with Threaded] = {
    val states = List(
      friendships, friendSendRatio, spamCounter, messageSpamRatings, spamUsers)
    for (s <- states) {
      if (!s.isInstanceOf[ReplayState with Threaded]) {
        throw new UnsupportedOperationException
      }
    }
    states.asInstanceOf[Seq[ReplayState with Threaded]]
  }

  def getRuntimeInterface: RuntimeInterface = emit {
    bind { e: NewFriendshipEvent =>
      friendships.merge(ts = e.ts, key = new UserPair(e.userIdA, e.userIdB), fn = _ => 1)
    }
    // RULE 1 STATE
    bind { me: MessageEvent =>
      friendships.get(ts = me.ts, key = new UserPair(me.senderUserId, me.recipientUserId)) match {
        case Some(_) =>
          friendSendRatio.merge(ts = me.ts, key = me.senderUserId, {case (friends, nonFriends) => (friends + 1, nonFriends)})
          friendMessageCount.add(1, me.ts)
        case None =>
          friendSendRatio.merge(ts = me.ts, key = me.senderUserId, {case (friends, nonFriends) => (friends, nonFriends + 1)})
          nonfriendMessageCount.add(1, me.ts)
      }
    }
    // RULE 1 EVALUATION
    bind {
      me: MessageEvent =>
        friendSendRatio.get(ts = me.ts, key = me.senderUserId) match {
          case Some((toFriends, toNonFriends)) =>
            if (toFriends + toNonFriends > 5) {
              if (toNonFriends > 2 * toFriends) {
                messageSpamRatings.merge(me.ts, me.messageId, _ + 10)
              }
            }
          case None =>
        }
    }
    // AGGREGATE
    bind {
      me: MessageEvent =>
        messageSpamRatings.get(me.ts, me.messageId) match {
          case Some(score) => if (score >= SpamScoreThreshold) {
            spamCounter.add(1, me.ts)
            spamUsers.merge(me.ts, true, s => s + me.senderUserId)
            spamCountByUser.merge(me.ts, me.senderUserId, _ + 1)
          }
          case None =>
        }
    }
    bind {
      e: PrintSpamCounter => println(s"\n\nSPAM COUNT is ${spamCounter.get(e.ts)}, " +
        s"with ${spamUsers.get(e.ts, true).get.size} users and " +
        s"friend message count of ${friendMessageCount.get(e.ts)} and " +
        s"nonfreidn msg count of ${nonfriendMessageCount.get(e.ts)}\n " +
        s"top 20 spammers: ${spamCountByUser.getTopK(e.ts, 20)}\n\n")
    }
  }
}
