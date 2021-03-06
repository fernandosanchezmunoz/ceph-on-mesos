package com.vivint.ceph
package kvstore

import akka.stream.scaladsl.Source
import scala.collection.immutable.Seq
import scala.concurrent.{ Future, Promise }
import scala.util.Failure

/** KVStore which forwards the first failure through the crashed channel.
  * If this fails then the CrashingKVStore should be reinitialized
  */
case class CrashingKVStore(kvStore: KVStore) extends KVStore {
  private [this] val p = Promise[Unit]
  val crashed: Future[Unit] = p.future

  private def wrap[T](f: () => Future[T]): Future[T] = {
    if (crashed.isCompleted) {
      // throw the future in the calling thread to make it obvious that this shouldn't be used any more
      val Failure(ex) = crashed.value.get
      throw new IllegalStateException("This kvstore has crashed and should not be used any more", ex)
    }
    val resultingFuture = f()

    resultingFuture.onFailure { case ex =>
      p.tryFailure(ex)
    }(SameThreadExecutionContext)
    resultingFuture
  }

  override def getAll(paths: Seq[String]): Future[Seq[Option[Array[Byte]]]] = wrap(() => kvStore.getAll(paths))
  def create(path: String, data: Array[Byte]): Future[Unit] = wrap(() => kvStore.create(path, data))
  def set(path: String, data: Array[Byte]): Future[Unit] = wrap(() => kvStore.set(path, data))
  def createOrSet(path: String, data: Array[Byte]): Future[Unit] = wrap(() => kvStore.createOrSet(path, data))
  def delete(path: String): Future[Unit] = wrap(() => kvStore.delete(path))
  def get(path: String): Future[Option[Array[Byte]]] = wrap(() => kvStore.get(path))
  def children(path: String): Future[Seq[String]] = wrap(() => kvStore.children(path))
  def lock(path: String): Future[KVStore.CancellableWithResult] = {
    val f1 = wrap(() => kvStore.lock(path))
    f1.onSuccess { case cancellable =>
      try { wrap(() => cancellable.result) }
      catch { case ex: Throwable => println(ex) }
    }(SameThreadExecutionContext)
    f1
  }

  def watch(path: String, bufferSize: Int = 1): Source[Option[Array[Byte]], KVStore.CancellableWithResult] =
    kvStore.watch(path).mapMaterializedValue { r =>
      wrap(() => r.result)
      r
    }
}
