package akka.persistence.hbase.common

import org.hbase.async.{HBaseClient, PutRequest, DeleteRequest, KeyValue}
import java.{util => ju}
import org.apache.hadoop.hbase.util.Bytes
import scala.concurrent.{ExecutionContext, Future}
import scala.Array
import akka.persistence.hbase.journal.RowTypeMarkers._

trait AsyncBaseUtils {

  val client: HBaseClient

  implicit val executionContext: ExecutionContext
  def getTable: String
  def Table = Bytes.toBytes(getTable)
  def getFamily: String
  def Family = Bytes.toBytes(getFamily)

  import Columns._
  import DeferredConversions._

  protected def isSnapshotRow(columns: Seq[KeyValue]): Boolean =
    ju.Arrays.equals(findColumn(columns, Marker).value, SnapshotMarkerBytes)

  protected def findColumn(columns: Seq[KeyValue], qualifier: Array[Byte]): KeyValue =
    columns find { kv =>
      ju.Arrays.equals(kv.qualifier, qualifier)
    } getOrElse {
      throw new RuntimeException(s"Unable to find [${Bytes.toString(qualifier)}}] field from: ${columns.map(kv => Bytes.toString(kv.qualifier))}")
    }

  protected def deleteRow(key: Array[Byte]): Future[Unit] = {
//      log.debug(s"Permanently deleting row: ${Bytes.toString(key)}")
      executeDelete(key)
    }

    protected def markRowAsDeleted(key: Array[Byte]): Future[Unit] = {
//      log.debug(s"Marking as deleted, for row: ${Bytes.toString(key)}")
      executePut(key, Array(Marker), Array(DeletedMarkerBytes))
    }

    protected def executeDelete(key: Array[Byte]): Future[Unit] = {
      val request = new DeleteRequest(Table, key)
      client.delete(request)
    }

    protected def executePut(key: Array[Byte], qualifiers: Array[Array[Byte]], values: Array[Array[Byte]]): Future[Unit] = {
      val request = new PutRequest(Table, key, Family, qualifiers, values)
      client.put(request)
    }

    /**
     * Sends the buffered commands to HBase. Does not guarantee that they "complete" right away.
     */
    def flushWrites() {
      client.flush()
    }

    protected def newScanner() = {
      val scanner = client.newScanner(Table)
      scanner.setFamily(Family)
      scanner
    }

}
