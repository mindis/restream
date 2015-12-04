package replaydb.service

import java.util.concurrent.{Executors, CountDownLatch}

import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import io.netty.util.concurrent.EventExecutor
import org.slf4j.LoggerFactory
import replaydb.service.driver._

import scala.collection.mutable.ArrayBuffer

abstract class ClientGroupBase(runConfiguration: RunConfiguration) {
  val logger = Logger(LoggerFactory.getLogger(classOf[ClientGroupBase]))
  val group = new NioEventLoopGroup(10, Executors.newCachedThreadPool())
  val cf = new ArrayBuffer[ChannelFuture]()
  val progressTracker = new ProgressTracker(runConfiguration)
  var workLatch: CountDownLatch = _

  def getHandler(): ChannelHandler

  val b = new Bootstrap()
  b.group(group)
    .channel(classOf[NioSocketChannel])
    .option(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], true)
    .handler(new LoggingHandler(LogLevel.DEBUG))
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel): Unit = {
        val p = ch.pipeline()
        // Encode commands sent to clients
        p.addLast(new KryoCommandEncoder())

        // Decode commands received from clients
        p.addLast(new LengthFieldBasedFrameDecoder(KryoCommands.MAX_KRYO_MESSAGE_SIZE, 0, 4, 0, 4))
        p.addLast(new KryoCommandDecoder())
        p.addLast(getHandler())
      }
    })

  def connect(hostConfigurations: Iterable[Hosts.HostConfiguration]): Unit = {
    logger.info(s"connecting with host configuration ${hostConfigurations.mkString(",")}")
    workLatch = new CountDownLatch(runConfiguration.numPartitions)
    for (hostConfiguration <- hostConfigurations) {
      cf += b.connect(hostConfiguration.host, hostConfiguration.port).sync()
    }
  }

  def broadcastCommand(c: Command, exec: EventExecutor): Unit = {
    logger.info("Attempting to broadcast")
    exec.execute(new Runnable {
      override def run(): Unit = ClientGroupBase.this.synchronized {
        try {
          logger.info(s"started broadcast of $c")
          for (i <- cf.indices) {
            logger.info(s"broadcast: start sending to $i")
            val a = cf(i).channel()
            logger.info(s"broadcast: got channel for $i")
            val b = a.writeAndFlush(c)
            logger.info(s"broadcast: wrote to $i")
//            b.sync()
            logger.info(s"broadcast: done sending to $i")
          }
          cf.foreach {
            _.channel().writeAndFlush(c) //.sync()
          }
          logger.info("finished broadcast")
        } catch {
          case e: Throwable => e.printStackTrace()
        }
      }
    })
  }

  def issueCommand(i: Int, c: Command, exec: EventExecutor): Unit = {
    // TODO - do we need to sync here?
    logger.info(s"attempting to issue command on partition $i: ${c.toString}")
    exec.execute(new Runnable {
      logger.info(s"issuing command on partition $i: ${c.toString}")
      override def run(): Unit = cf(i).channel().writeAndFlush(c) //.sync()
      logger.info(s"finished issuing command on partition $i: ${c.toString}")
    })
  }

  def closeWhenDone(isWorker: Boolean = false): Unit = {
    try {
      val closeCmd = if (isWorker) {
        new CloseWorkerCommand()
      } else {
        workLatch.await()
        new CloseCommand()
      }
      cf.foreach { _.channel().writeAndFlush(closeCmd) }
      cf.foreach { _.channel().closeFuture().sync() }
    } finally {
      group.shutdownGracefully()
    }
  }

}
