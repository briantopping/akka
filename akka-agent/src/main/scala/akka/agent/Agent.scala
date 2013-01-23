/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.agent

import akka.actor._
import akka.japi.{ Function ⇒ JFunc, Procedure ⇒ JProc }
import akka.pattern.ask
import scala.concurrent.stm._
import scala.concurrent.{ ExecutionContext, Future, Promise, Await }
import scala.concurrent.duration.{ FiniteDuration, Duration }
import akka.util.{ SerializedSuspendableExecutionContext, Timeout }
import util.Try

/**
 * Factory method for creating an Agent.
 */
object Agent {
  def apply[T](initialValue: T)(implicit context: ExecutionContext) = new Agent(initialValue, context)
}

/**
 * The Agent class was inspired by agents in Clojure.
 *
 * Agents provide asynchronous change of individual locations. Agents
 * are bound to a single storage location for their lifetime, and only
 * allow mutation of that location (to a new state) to occur as a result
 * of an action. Update actions are functions that are asynchronously
 * applied to the Agent's state and whose return value becomes the
 * Agent's new state. The state of an Agent should be immutable.
 *
 * While updates to Agents are asynchronous, the state of an Agent is
 * always immediately available for reading by any thread (using ''get''
 * or ''apply'') without any messages.
 *
 * Agents are reactive. The update actions of all Agents get interleaved
 * amongst threads in a thread pool. At any point in time, at most one
 * ''send'' action for each Agent is being executed. Actions dispatched to
 * an agent from another thread will occur in the order they were sent,
 * potentially interleaved with actions dispatched to the same agent from
 * other sources.
 *
 * If an Agent is used within an enclosing transaction, then it will
 * participate in that transaction. Agents are integrated with the STM -
 * any dispatches made in a transaction are held until that transaction
 * commits, and are discarded if it is retried or aborted.
 * <br/><br/>
 *
 * Example of usage:
 * {{{
 * val agent = Agent(5)
 *
 * agent send (_ * 2)
 *
 * ...
 *
 * val result = agent()
 * // use result ...
 *
 * agent.close
 * }}}
 * <br/>
 *
 * Agent is also monadic, which means that you can compose operations using
 * for-comprehensions. In monadic usage the original agents are not touched
 * but new agents are created. So the old values (agents) are still available
 * as-is. They are so-called 'persistent'.
 * <br/><br/>
 *
 * Example of monadic usage:
 * {{{
 * val agent1 = Agent(3)
 * val agent2 = Agent(5)
 *
 * for (value <- agent1) {
 *   result = value + 1
 * }
 *
 * val agent3 = for (value <- agent1) yield value + 1
 *
 * val agent4 = for {
 *   value1 <- agent1
 *   value2 <- agent2
 * } yield value1 + value2
 *
 * agent1.close
 * agent2.close
 * agent3.close
 * agent4.close
 * }}}
 */
class Agent[T](initialValue: T, context: ExecutionContext) {
  private val ref = Ref(initialValue)
  private val updater = SerializedSuspendableExecutionContext(10)(context)

  /**
   * Read the internal state of the agent.
   * Java API
   */
  def get(): T = ref.single.get

  /**
   * Read the internal state of the agent.
   */
  def apply(): T = get

  /**
   * Dispatch a new value for the internal state. Behaves the same
   * as sending a function (x => newValue).
   */
  def send(newValue: T): Unit = withinTransaction(new Runnable { def run = ref.single.update(newValue) })

  /**
   * Dispatch a function to update the internal state.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def send(f: T ⇒ T): Unit = withinTransaction(new Runnable { def run = ref.single.transform(f) })

  /**
   * Dispatch a function to update the internal state but on its own thread.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `sendOff` or `send` will
   * still be executed in order.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def sendOff(f: T ⇒ T)(implicit ec: ExecutionContext): Unit = withinTransaction(
    new Runnable {
      def run =
        try updater.suspend() finally ec.execute(new Runnable { def run = try ref.single.transform(f) finally updater.resume() })
    })

  /**
   * Dispatch an update to the internal state, and return a Future where
   * that new state can be obtained.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def alter(newValue: T): Future[T] = alter(_ ⇒ newValue)

  /**
   * Dispatch a function to update the internal state, and return a Future where
   * that new state can be obtained.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def alter(f: T ⇒ T): Future[T] = {
    def dispatch = Future(ref.single.transformAndGet(f))(updater)
    Txn.findCurrent match {
      case Some(txn) ⇒
        val result = Promise[T]()
        Txn.afterCommit(status ⇒ result completeWith dispatch)(txn)
        result.future
      case _ ⇒ dispatch
    }
  }

  /**
   * Dispatch a function to update the internal state but on its own thread,
   * and return a Future where that new state can be obtained.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `alterOff` or `alter` will
   * still be executed in order.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def alterOff(f: T ⇒ T)(implicit ec: ExecutionContext): Future[T] = {
    val result = Promise[T]()
    withinTransaction(new Runnable {
      def run = {
        updater.suspend()
        result completeWith Future(try ref.single.transformAndGet(f) finally updater.resume())
      }
    })
    result.future
  }

  private final def withinTransaction(run: Runnable): Unit = {
    def dispatch = updater.execute(run)
    Txn.findCurrent match {
      case Some(txn) ⇒ Txn.afterCommit(status ⇒ dispatch)(txn)
      case _         ⇒ dispatch
    }
  }

  /**
   * A future to the current value that will be completed after any currently
   * queued updates.
   */
  def future(): Future[T] = Future(ref.single.get)(updater)

  /**
   * Map this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def map[B](f: T ⇒ B): Agent[B] = Agent(f(get))(updater)

  /**
   * Flatmap this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def flatMap[B](f: T ⇒ Agent[B]): Agent[B] = f(get)

  /**
   * Applies the function to the internal state. Does not change the value of this agent.
   * In Java, pass in an instance of `akka.dispatch.Foreach`.
   */
  def foreach[U](f: T ⇒ U): Unit = f(get)
}