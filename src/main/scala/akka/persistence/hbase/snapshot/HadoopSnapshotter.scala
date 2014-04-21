package akka.persistence.hbase.snapshot

import akka.actor.{ ActorSystem, Extension }
import akka.serialization.SerializationExtension
import akka.persistence.{ SelectedSnapshot, SnapshotSelectionCriteria, SnapshotMetadata }
import akka.persistence.serialization.Snapshot
import scala.concurrent.Future
import scala.util.Try

/**
 * Common API for Snapshotter implementations. Used to provide an interface for the Extension.
 */
trait HadoopSnapshotter extends Extension {

  def system: ActorSystem
  protected val serialization = SerializationExtension(system)

  def loadAsync(processorId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]]

  def saveAsync(meta: SnapshotMetadata, snapshot: Any): Future[Unit]

  def saved(metadata: SnapshotMetadata): Unit

  def delete(metadata: SnapshotMetadata): Unit

  def delete(processorId: String, criteria: SnapshotSelectionCriteria): Unit

  protected def deserialize(bytes: Array[Byte]): Try[Snapshot] =
    serialization.deserialize(bytes, classOf[Snapshot])

  protected def serialize(snapshot: Snapshot): Try[Array[Byte]] =
    serialization.serialize(snapshot)

  def postStop(): Unit
}
