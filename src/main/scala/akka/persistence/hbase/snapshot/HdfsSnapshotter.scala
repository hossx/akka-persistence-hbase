package akka.persistence.hbase.snapshot

import akka.persistence.{ SelectedSnapshot, SnapshotSelectionCriteria, SnapshotMetadata }
import scala.concurrent.Future
import org.apache.hadoop.fs.{ FileStatus, Path, FileSystem }
import akka.actor.ActorSystem
import akka.persistence.hbase.journal.PluginPersistenceSettings
import org.apache.hadoop.conf.Configuration
import org.apache.commons.io.FilenameUtils
import scala.util.{ Try, Failure, Success }
import akka.persistence.serialization.Snapshot
import scala.annotation.tailrec
import java.io.{ BufferedOutputStream, Closeable, BufferedInputStream }
import org.apache.commons.io.IOUtils
import scala.collection.immutable

/**
 * Dump and read Snapshots to/from HDFS.
 */
class HdfsSnapshotter(val system: ActorSystem, settings: PluginPersistenceSettings)
    extends HadoopSnapshotter {

  val log = system.log

  implicit val executionContext = system.dispatchers.lookup("akka-hbase-persistence-dispatcher")

  private val conf = new Configuration
  conf.set("fs.default.name", settings.hdfsDefaultName)
  private val fs = FileSystem.get(conf) // todo allow passing in all conf?

  /** Snapshots we're in progress of saving */
  private var saving = immutable.Set.empty[SnapshotMetadata]

  def loadAsync(processorId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    log.info("[HDFS] Loading async, for processorId [{}], criteria: {}", processorId, criteria)
    val snapshotMetas = listSnapshots(settings.snapshotHdfsDir, processorId)

    @tailrec def deserializeOrTryOlder(metas: List[HdfsSnapshotDescriptor]): Option[SelectedSnapshot] = metas match {
      case Nil =>
        None

      case desc :: tail =>
        tryLoadingSnapshot(desc) match {
          case Success(snapshot) =>
            Some(SelectedSnapshot(SnapshotMetadata(processorId, desc.seqNumber), snapshot.data))

          case Failure(ex) =>
            log.error(s"Failed to deserialize snapshot for $desc" + (if (tail.nonEmpty) ", trying previous one" else ""), ex)
            deserializeOrTryOlder(tail)
        }
    }

    // todo make configurable how many times we retry if deserialization fails (that's the take here)
    Future { deserializeOrTryOlder(snapshotMetas.take(3)) }
  }

  def saveAsync(meta: SnapshotMetadata, snapshot: Any): Future[Unit] =
    if (saving contains meta) {
      Future.failed(new Exception(s"Already working on persisting of $meta, aborting this (duplicate) request."))
    } else {
      Future { serializeAndSave(meta, snapshot) }
    }

  def saved(meta: SnapshotMetadata) {
    log.debug("Saved: {}", meta)
    saving -= meta
  }

  def delete(meta: SnapshotMetadata) {
    val desc = HdfsSnapshotDescriptor(meta)
    fs.delete(new Path(settings.snapshotHdfsDir, desc.toFilename), true)
    log.debug("Deleted snapshot: {}", desc)
    saving -= meta
  }

  def delete(processorId: String, criteria: SnapshotSelectionCriteria) {
    val toDelete = listSnapshots(settings.snapshotHdfsDir, processorId).dropWhile(_.seqNumber > criteria.maxSequenceNr)

    toDelete foreach { desc =>
      val path = new Path(settings.snapshotHdfsDir, desc.toFilename)
      fs.delete(path, true)
    }
  }

  // internals --------

  /**
   * Looks for snapshots stored in directory for given `processorId`.
   * Guarantees that the returned list is sorted descending by the snapshots `seqNumber` (latest snapshot first).
   */
  private def listSnapshots(snapshotDir: String, processorId: String): List[HdfsSnapshotDescriptor] = {
    val descs = fs.listStatus(new Path(snapshotDir)) flatMap { HdfsSnapshotDescriptor.from(_, processorId) }
    if (descs.isEmpty)
      Nil
    else
      descs.sortBy(_.seqNumber).toList
  }

  private[snapshot] def serializeAndSave(meta: SnapshotMetadata, snapshot: Any) {
    val desc = HdfsSnapshotDescriptor(meta)
    serialize(Snapshot(snapshot)) match {
      case Success(bytes) =>
        try {
          withStream(new BufferedOutputStream(fs.create(newHdfsPath(desc)))) { _.write(bytes) }
        } catch {
          case e: Exception => e.printStackTrace()
        }
      case Failure(ex) => log.error("Unable to serialize snapshot for meta: " + meta)
    }

  }

  private[snapshot] def tryLoadingSnapshot(desc: HdfsSnapshotDescriptor): Try[Snapshot] = {
    val path = new Path(settings.snapshotHdfsDir, desc.toFilename)
    deserialize(withStream(new BufferedInputStream(fs.open(path))) { IOUtils.toByteArray })
  }

  private def withStream[S <: Closeable, A](stream: S)(fun: S => A): A =
    try fun(stream) finally stream.close()

  private def newHdfsPath(desc: HdfsSnapshotDescriptor) = new Path(settings.snapshotHdfsDir, desc.toFilename)

  case class HdfsSnapshotDescriptor(processorId: String, seqNumber: Long, timestamp: Long) {
    def toFilename = s"snapshot~$processorId~$seqNumber~$timestamp"
  }

  object HdfsSnapshotDescriptor {
    def SnapshotNamePattern(processorId: String): scala.util.matching.Regex = {
      s"""snapshot~$processorId~([0-9]+)~([0-9]+)""".r
    }

    def apply(meta: SnapshotMetadata): HdfsSnapshotDescriptor =
      HdfsSnapshotDescriptor(meta.processorId, meta.sequenceNr, meta.timestamp)

    def from(status: FileStatus, processorId: String): Option[HdfsSnapshotDescriptor] = {
      val namePattern = SnapshotNamePattern(processorId)
      FilenameUtils.getBaseName(status.getPath.toString) match {
        case namePattern(seqNumber, timestamp) =>
          Some(HdfsSnapshotDescriptor(processorId, seqNumber.toLong, timestamp.toLong))

        case _ =>
          None
      }
    }
  }

  override def postStop(): Unit = {
    fs.close()
  }
}
