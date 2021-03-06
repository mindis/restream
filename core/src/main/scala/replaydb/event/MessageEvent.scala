package replaydb.event

case class MessageEvent (
  ts: Long,
  messageId: Long,
  senderUserId: Long,
  recipientUserId: Long,
  senderIp: Int,
  content: String
) extends Event with HasPartitionKey {
  override def partitionKey: Long = senderUserId
}
