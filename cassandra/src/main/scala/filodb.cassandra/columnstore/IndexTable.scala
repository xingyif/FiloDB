package filodb.cassandra.columnstore

import java.nio.ByteBuffer

import scala.concurrent.{ExecutionContext, Future}

import com.datastax.driver.core.{ConsistencyLevel, Row}
import monix.reactive.Observable

import filodb.cassandra.FiloCassandraConnector
import filodb.core._
import filodb.core.store.ChunkSinkStats
import filodb.memory.format.UnsafeUtils

// Typical record read from serialized incremental index (ChunkInfo + Skips) entries
case class IndexRecord(binPartition: ByteBuffer, data: ByteBuffer) {
  def partBaseOffset: (Any, Long, Int) = UnsafeUtils.BOLfromBuffer(binPartition)
}

/**
 * Represents the table which holds the incremental chunk metadata or indexes for each segment
 * of a partition.  The chunk index contains info about each chunk including if it overrides
 * any records in previous chunks.  Each new chunkSet that is written results in a tiny bit more metadata.
 * Unlike the previous ChunkRowMap design, the layout is such that new chunks in a segment get written
 * only with its new metadata, previous index bits are not written again.
 *
 * There is an indextype field.
 *  1 = incremental indices
 * In the future there may be other types.  For example, for aggregated indices or extra metadata
 * representing keys to replace.
 */
sealed class IndexTable(val dataset: DatasetRef, val connector: FiloCassandraConnector)
                       (implicit ec: ExecutionContext) extends BaseDatasetTable {
  import scala.collection.JavaConverters._

  import filodb.cassandra.Util._

  val suffix = "index"

  val createCql = s"""CREATE TABLE IF NOT EXISTS $tableString (
                     |    partition blob,
                     |    indextype int,
                     |    chunkid bigint,
                     |    data blob,
                     |    PRIMARY KEY (partition, indextype, chunkid)
                     |) WITH compression = {
                    'sstable_compression': '$sstableCompression'}""".stripMargin


  def fromRow(row: Row): IndexRecord =
    IndexRecord(row.getBytes("partition"), row.getBytes("data"))

  val selectCql = s"SELECT partition, data FROM $tableString WHERE "
  val partitionFilter = "partition = ? AND indextype = 1"
  val partInFilter = "partition IN ? AND indextype = 1"
  lazy val allPartReadCql = session.prepare(selectCql + partitionFilter)
  lazy val inPartReadCql = session.prepare(selectCql + partInFilter)

  /**
   * Retrieves all indices from a single partition.
   */
  def getIndices(binPartition: Array[Byte]): Observable[IndexRecord] = {
    val it = session.execute(allPartReadCql.bind(toBuffer(binPartition)))
                    .asScala.toIterator.map(fromRow)
    Observable.fromIterator(it).handleObservableErrors
  }

  def getMultiIndices(partitions: Seq[Array[Byte]]): Observable[IndexRecord] = {
    val query = inPartReadCql.bind().setList(0, partitions.map(toBuffer).asJava, classOf[ByteBuffer])
    val it = session.execute(query).asScala.toIterator.map(fromRow)
    Observable.fromIterator(it).handleObservableErrors
  }

  val tokenQ = "TOKEN(partition)"

  def scanIndices(tokens: Seq[(String, String)]): Observable[IndexRecord] = {
    def cql(start: String, end: String): String =
      s"SELECT * FROM $tableString WHERE $tokenQ >= $start AND $tokenQ < $end AND indextype = 1 " +
      s"ALLOW FILTERING"
    val it = tokens.iterator.flatMap { case (start, end) =>
        session.execute(cql(start, end)).iterator.asScala
               .map { row => fromRow(row) }
      }
    Observable.fromIterator(it).handleObservableErrors
  }

  lazy val writeIndexCql = session.prepare(
    s"INSERT INTO $tableString (partition, indextype, chunkid, data) " +
    "VALUES (?, 1, ?, ?) USING TTL ?")
    .setConsistencyLevel(ConsistencyLevel.ONE)

  /**
   * Writes new indices to the index table
   * @return Success, or an exception as a Future.failure
   */
  def writeIndices(partition: Array[Byte],
                   indices: Seq[(Types.ChunkID, Array[Byte])],
                   stats: ChunkSinkStats,
                   diskTimeToLive: Int): Future[Response] = {
    var indexBytes = 0
    val partitionBuf = toBuffer(partition)
    val statements = indices.map { case (chunkId, indexData) =>
      indexBytes += indexData.size
      writeIndexCql.bind(partitionBuf,
                         chunkId: java.lang.Long,
                         ByteBuffer.wrap(indexData),
                         diskTimeToLive: java.lang.Integer)
    }
    stats.addIndexWriteStats(indexBytes)
    connector.execStmtWithRetries(unloggedBatch(statements).setConsistencyLevel(ConsistencyLevel.ONE))
  }
}
