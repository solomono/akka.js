/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps

import akka.testkit._
import akka.util.Timeout
import scala.concurrent.duration._
// @note IMPLEMENT IN SCALA.JS
import akka.concurrent.{ Await, BlockingEventLoop }
import java.lang.IllegalStateException
import scala.concurrent.Promise
import akka.pattern.ask
// @note IMPLEMENT IN SCALA.JS import akka.serialization.JavaSerializer
import akka.TestUtils.verifyActorTermination
import scala.scalajs.js.annotation.JSExport

object NonPublicClass {
  def createProps() = {
    Props.create(classOf[MyNonPublicActorClass])
  }
}

@JSExport
class MyNonPublicActorClass extends Actor {
  def receive = {
    case msg => sender() ! msg
  }
}

@JSExport
class ReplyActor extends Actor {
  var replyTo: ActorRef = null

  def receive = {
    case "complexRequest" ⇒ {
      replyTo = sender()
      val worker = context.actorOf(Props[WorkerActor])
      worker ! "work"
    }
    case "complexRequest2" ⇒
      val worker = context.actorOf(Props[WorkerActor])
      worker ! ActorRefSpec.ReplyTo(sender())
    case "workDone"      ⇒ replyTo ! "complexReply"
    case "simpleRequest" ⇒ sender() ! "simpleReply"
  }
}

@JSExport
class SenderActor(replyActor: ActorRef, latch: TestLatch) extends Actor {

  def receive = {
    case "complex"  ⇒ replyActor ! "complexRequest"
    case "complex2" ⇒ replyActor ! "complexRequest2"
    case "simple"   ⇒ replyActor ! "simpleRequest"
    case "complexReply" ⇒ {
      latch.countDown()
    }
    case "simpleReply" ⇒ {
      latch.countDown()
    }
  }
}

@JSExport
class WorkerActor() extends Actor {
  import context.system
  def receive = {
    case "work" ⇒ {
      work()
      sender() ! "workDone"
      context.stop(self)
    }
    case ActorRefSpec.ReplyTo(replyTo) ⇒ {
      work()
      replyTo ! "complexReply"
    }
  }

  private def work(): Unit = BlockingEventLoop.wait(1.second) // @note IMPLEMENT IN SCALA.JS Thread.sleep(1.second.dilated.toMillis)
}

object ActorRefSpec {

  case class ReplyTo(sender: ActorRef)

  class OuterActor(val inner: ActorRef) extends Actor {
    def receive = {
      case "self" ⇒ sender() ! self
      case x      ⇒ inner forward x
    }
  }

  class FailingOuterActor(val inner: ActorRef) extends Actor {
    val fail = new InnerActor

    def receive = {
      case "self" ⇒ sender() ! self
      case x      ⇒ inner forward x
    }
  }

  class FailingInheritingOuterActor(_inner: ActorRef) extends OuterActor(_inner) {
    val fail = new InnerActor
  }

  class InnerActor extends Actor {
    def receive = {
      case "innerself" ⇒ sender() ! self
      case other       ⇒ sender() ! other
    }
  }

  class FailingInnerActor extends Actor {
    val fail = new InnerActor

    def receive = {
      case "innerself" ⇒ sender() ! self
      case other       ⇒ sender() ! other
    }
  }

  class FailingInheritingInnerActor extends InnerActor {
    val fail = new InnerActor
  }
}

// @note IMPLEMENT IN SCALA.JS @org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorRefSpec extends AkkaSpec with DefaultTimeout {
  import akka.actor.ActorRefSpec._

  def promiseIntercept(f: ⇒ Actor)(to: Promise[Actor]): Actor = try {
    val r = f
    to.success(r)
    r
  } catch {
    case e: Throwable ⇒
      to.failure(e)
      throw e
  }

  def wrap[T](f: Promise[Actor] ⇒ T): T = {
    val result = Promise[Actor]()
    val r = f(result)
    Await.result(result.future, 1 minute)
    r
  }

  "An ActorRef" must {

    "not allow Actors to be created outside of an actorOf" in {
      BlockingEventLoop.blockingOn
      import system.actorOf

      intercept[akka.actor.ActorInitializationException] {
        new Actor { def receive = { case _ ⇒ } }
      }

      def contextStackMustBeEmpty(): Unit = ActorCell.contextStack.get.headOption should be(None)

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new Actor {
              val nested = promiseIntercept(new Actor { def receive = { case _ ⇒ } })(result)
              def receive = { case _ ⇒ }
            })))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(promiseIntercept(new FailingOuterActor(actorOf(Props(new InnerActor))))(result))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept(new FailingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(promiseIntercept(new FailingInheritingOuterActor(actorOf(Props(new InnerActor))))(result))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 2) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 2) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingInheritingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 2) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingInheritingOuterActor(actorOf(Props(promiseIntercept(new FailingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(new InnerActor {
              val a = promiseIntercept(new InnerActor)(result)
            }))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 2) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept({ new InnerActor; new InnerActor })(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        (intercept[java.lang.IllegalStateException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept({ throw new IllegalStateException("Ur state be b0rked"); new InnerActor })(result)))))))
        }).getMessage should be("Ur state be b0rked")

        contextStackMustBeEmpty()
      }
      BlockingEventLoop.blockingOff
    }
/*
    "be serializable using Java Serialization on local node" in {
      val a = system.actorOf(Props[InnerActor])
      val esys = system.asInstanceOf[ExtendedActorSystem]

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      val bytes = baos.toByteArray

      JavaSerializer.currentSystem.withValue(esys) {
        val in = new ObjectInputStream(new ByteArrayInputStream(bytes))
        val readA = in.readObject

        a.isInstanceOf[ActorRefWithCell] should be(true)
        readA.isInstanceOf[ActorRefWithCell] should be(true)
        (readA eq a) should be(true)
      }

      val ser = new JavaSerializer(esys)
      val readA = ser.fromBinary(bytes, None)
      readA.isInstanceOf[ActorRefWithCell] should be(true)
      (readA eq a) should be(true)
    }

    "throw an exception on deserialize if no system in scope" in {
      val a = system.actorOf(Props[InnerActor])

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

      (intercept[java.lang.IllegalStateException] {
        in.readObject
      }).getMessage should be("Trying to deserialize a serialized ActorRef without an ActorSystem in scope." +
        " Use 'akka.serialization.Serialization.currentSystem.withValue(system) { ... }'")
    }

    "return EmptyLocalActorRef on deserialize if not present in actor hierarchy (and remoting is not enabled)" in {
      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      val sysImpl = system.asInstanceOf[ActorSystemImpl]
      val ref = system.actorOf(Props[ReplyActor], "non-existing")
      val serialized = SerializedActorRef(ref)

      out.writeObject(serialized)

      out.flush
      out.close

      ref ! PoisonPill

      verifyActorTermination(ref)

      JavaSerializer.currentSystem.withValue(sysImpl) {
        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
        in.readObject should be(new EmptyLocalActorRef(sysImpl.provider, ref.path, system.eventStream))
      }
    }*/

    "support nested actorOfs" in {
      BlockingEventLoop.blockingOn
      val a = system.actorOf(Props(new Actor {
        val nested = system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }))
        def receive = { case _ ⇒ sender() ! nested }
      }))

      val nested = Await.result((a ? "any")/*.mapTo[ActorRef]*/, timeout.duration).asInstanceOf[ActorRef]
      a should not be null
      nested should not be null
      (a ne nested) should be(true)
      BlockingEventLoop.blockingOff
    }

    "support advanced nested actorOfs" in {
      BlockingEventLoop.blockingOn
      val a = system.actorOf(Props(new OuterActor(system.actorOf(Props(new InnerActor)))))
      val inner = Await.result(a ? "innerself", timeout.duration)

      Await.result(a ? a, timeout.duration) should be(a)
      Await.result(a ? "self", timeout.duration) should be(a)
      inner should not be a

      Await.result(a ? "msg", timeout.duration) should be("msg")
      BlockingEventLoop.blockingOff
    }

    "support reply via sender" in {
      BlockingEventLoop.blockingOn
      val latch = new TestLatch(4)
      val serverRef = system.actorOf(Props[ReplyActor])
      val clientRef = system.actorOf(Props(new SenderActor(serverRef, latch)))

      clientRef ! "complex"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      Await.ready(latch, timeout.duration)

      latch.reset

      clientRef ! "complex2"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      Await.ready(latch, timeout.duration)

      system.stop(clientRef)
      system.stop(serverRef)
      BlockingEventLoop.blockingOff
    }

    "support actorOfs where the class of the actor isn't public" in {
      BlockingEventLoop.blockingOn
      val a = system.actorOf(NonPublicClass.createProps())
      a.tell("pigdog", testActor)
      expectMsg("pigdog")
      system stop a
      BlockingEventLoop.blockingOff
    }

    "stop when sent a poison pill" in {
      BlockingEventLoop.blockingOn
      val timeout = Timeout(20000)
      val ref = system.actorOf(Props(new Actor {
        def receive = {
          case 5 ⇒ sender() ! "five"
          case 0 ⇒ sender() ! "null"
        }
      }))

      val ffive = (ref.ask(5)(timeout))//.mapTo[String]
      val fnull = (ref.ask(0)(timeout))//.mapTo[String]
      ref ! PoisonPill

      Await.result(ffive, timeout.duration).asInstanceOf[String] should be("five")
      Await.result(fnull, timeout.duration).asInstanceOf[String] should be("null")

      verifyActorTermination(ref)

      BlockingEventLoop.blockingOff
    }


    "restart when Kill:ed" in {
      BlockingEventLoop.blockingOn
      filterException[ActorKilledException] {
        val latch = TestLatch(2)

        val boss = system.actorOf(Props(new Actor {

          override val supervisorStrategy =
            OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 second)(List(classOf[Throwable]))

          val ref = context.actorOf(
            Props(new Actor {
              def receive = { case _ ⇒ }
              override def preRestart(reason: Throwable, msg: Option[Any]) = latch.countDown()
              override def postRestart(reason: Throwable) = latch.countDown()
            }))

          def receive = { case "sendKill" ⇒ ref ! Kill }
        }))

        boss ! "sendKill"
        Await.ready(latch, 5 seconds)
      }
      BlockingEventLoop.blockingOff
    }

    "be able to check for existence of children" in {
      BlockingEventLoop.blockingOn
      val parent = system.actorOf(Props(new Actor {

        val child = context.actorOf(
          Props(new Actor {
            def receive = { case _ ⇒ }
          }), "child")

        def receive = { case name: String ⇒ sender() ! context.child(name).isDefined }
      }), "parent")

      assert(Await.result((parent ? "child"), remaining) === true)
      assert(Await.result((parent ? "whatnot"), remaining) === false)
      BlockingEventLoop.blockingOff
    }
  }
}