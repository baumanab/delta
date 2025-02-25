/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

// scalastyle:off import.ordering.noEmptyLine
import scala.collection.mutable

import org.apache.spark.sql.delta.actions._
import org.apache.spark.sql.delta.actions.Action.logSchema
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.SchemaUtils
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.stats.DataSkippingReader
import org.apache.spark.sql.delta.stats.FileSizeHistogram
import org.apache.spark.sql.delta.stats.StatisticsCollection
import org.apache.spark.sql.delta.util.StateCache
import org.apache.hadoop.fs.{FileStatus, Path}

import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils

/**
 * An immutable snapshot of the state of the log at some delta version. Internally
 * this class manages the replay of actions stored in checkpoint or delta files.
 *
 * After resolving any new actions, it caches the result and collects the
 * following basic information to the driver:
 *  - Protocol Version
 *  - Metadata
 *  - Transaction state
 *
 * @param timestamp The timestamp of the latest commit in milliseconds. Can also be set to -1 if the
 *                  timestamp of the commit is unknown or the table has not been initialized, i.e.
 *                  `version = -1`.
 *
 */
class Snapshot(
    val path: Path,
    val version: Long,
    val logSegment: LogSegment,
    val minFileRetentionTimestamp: Long,
    val deltaLog: DeltaLog,
    val timestamp: Long,
    val checksumOpt: Option[VersionChecksum],
    val minSetTransactionRetentionTimestamp: Option[Long] = None,
    checkpointMetadataOpt: Option[CheckpointMetaData] = None)
  extends StateCache
  with StatisticsCollection
  with DataSkippingReader
  with DeltaLogging {

  import Snapshot._
  // For implicits which re-use Encoder:
  import org.apache.spark.sql.delta.implicits._

  protected def spark = SparkSession.active


  /** Snapshot to scan by the DeltaScanGenerator for metadata query optimizations */
  override val snapshotToScan: Snapshot = this

  protected def getNumPartitions: Int = {
    spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_SNAPSHOT_PARTITIONS)
      .getOrElse(Snapshot.defaultNumSnapshotPartitions)
  }

  /** Performs validations during initialization */
  protected def init(): Unit = {
    deltaLog.protocolRead(protocol)
    SchemaUtils.recordUndefinedTypes(deltaLog, metadata.schema)
  }

  // Reconstruct the state by applying deltas in order to the checkpoint.
  // We partition by path as it is likely the bulk of the data is add/remove.
  // Non-path based actions will be collocated to a single partition.
  private def stateReconstruction: Dataset[SingleAction] = {
    recordFrameProfile("Delta", "snapshot.stateReconstruction") {
      // for serializability
      val localMinFileRetentionTimestamp = minFileRetentionTimestamp
      val localMinSetTransactionRetentionTimestamp = minSetTransactionRetentionTimestamp

      val canonicalPath = deltaLog.getCanonicalPathUdf()

      // Canonicalize the paths so we can repartition the actions correctly, but only rewrite the
      // add/remove actions themselves after partitioning and sorting are complete. Otherwise, the
      // optimizer can generate a really bad plan that re-evaluates _EVERY_ field of the rewritten
      // struct(...)  projection every time we touch _ANY_ field of the rewritten struct.
      //
      // NOTE: We sort by [[ACTION_SORT_COL_NAME]] (provided by [[loadActions]]), to ensure that
      // actions are presented to InMemoryLogReplay in the ascending version order it expects.
      val ADD_PATH_CANONICAL_COL_NAME = "add_path_canonical"
      val REMOVE_PATH_CANONICAL_COL_NAME = "remove_path_canonical"
      loadActions
        .withColumn(ADD_PATH_CANONICAL_COL_NAME, when(
          col("add.path").isNotNull, canonicalPath(col("add.path"))))
        .withColumn(REMOVE_PATH_CANONICAL_COL_NAME, when(
          col("remove.path").isNotNull, canonicalPath(col("remove.path"))))
        .repartition(
          getNumPartitions,
          coalesce(col(ADD_PATH_CANONICAL_COL_NAME), col(REMOVE_PATH_CANONICAL_COL_NAME)))
        .sortWithinPartitions(ACTION_SORT_COL_NAME)
        .withColumn("add", when(
          col("add.path").isNotNull,
          struct(
            col(ADD_PATH_CANONICAL_COL_NAME).as("path"),
            col("add.partitionValues"),
            col("add.size"),
            col("add.modificationTime"),
            col("add.dataChange"),
            col(ADD_STATS_TO_USE_COL_NAME).as("stats"),
            col("add.tags")
          )))
        .withColumn("remove", when(
          col("remove.path").isNotNull,
          col("remove").withField("path", col(REMOVE_PATH_CANONICAL_COL_NAME))))
        .as[SingleAction]
        .mapPartitions { iter =>
          val state: LogReplay =
              new InMemoryLogReplay(
                localMinFileRetentionTimestamp,
                localMinSetTransactionRetentionTimestamp)
          state.append(0, iter.map(_.unwrap))
          state.checkpoint.map(_.wrap)
        }
    }
  }

  def redactedPath: String =
    Utils.redact(spark.sessionState.conf.stringRedactionPattern, path.toUri.toString)

  @volatile private[delta] var stateReconstructionTriggered = false
  private lazy val cachedState = recordFrameProfile("Delta", "snapshot.cachedState") {
    stateReconstructionTriggered = true
    cacheDS(stateReconstruction, s"Delta Table State #$version - $redactedPath")
  }

  /** The current set of actions in this [[Snapshot]] as a typed Dataset. */
  def stateDS: Dataset[SingleAction] = recordFrameProfile("Delta", "stateDS") {
    cachedState.getDS
  }

  /** The current set of actions in this [[Snapshot]] as plain Rows */
  def stateDF: DataFrame = recordFrameProfile("Delta", "stateDF") {
    cachedState.getDF
  }

  /**
   * A Map of alias to aggregations which needs to be done to calculate the `computedState`
   */
  protected def aggregationsToComputeState: Map[String, Column] = {
    Map(
      "protocol" -> last(col("protocol"), ignoreNulls = true),
      "metadata" -> last(col("metaData"), ignoreNulls = true),
      "setTransactions" -> collect_set(col("txn")),
      // sum may return null for empty data set.
      "sizeInBytes" -> coalesce(sum(col("add.size")), lit(0L)),
      "numOfFiles" -> count(col("add")),
      "numOfMetadata" -> count(col("metaData")),
      "numOfProtocol" -> count(col("protocol")),
      "numOfRemoves" -> count(col("remove")),
      "numOfSetTransactions" -> count(col("txn")),
      "fileSizeHistogram" -> lit(null).cast(FileSizeHistogram.schema)
    )
  }

  /**
   * Computes some statistics around the transaction log, therefore on the actions made on this
   * Delta table.
   */
  protected lazy val computedState: State = {
    withStatusCode("DELTA", s"Compute snapshot for version: $version") {
      recordFrameProfile("Delta", "snapshot.computedState") {
        val startTime = System.nanoTime()
        val aggregations =
          aggregationsToComputeState.map { case (alias, agg) => agg.as(alias) }.toSeq
        val _computedState = recordFrameProfile("Delta", "snapshot.computedState.aggregations") {
          stateDF.select(aggregations: _*).as[State].first()
        }
        if (_computedState.protocol == null) {
          recordDeltaEvent(
            deltaLog,
            opType = "delta.assertions.missingAction",
            data = Map(
              "version" -> version.toString, "action" -> "Protocol", "source" -> "Snapshot"))
          throw DeltaErrors.actionNotFoundException("protocol", version)
        }
        if (_computedState.metadata == null) {
          recordDeltaEvent(
            deltaLog,
            opType = "delta.assertions.missingAction",
            data = Map(
              "version" -> version.toString, "action" -> "Metadata", "source" -> "Metadata"))
          throw DeltaErrors.actionNotFoundException("metadata", version)
        } else {
          _computedState
        }
      }
    }
  }

  def protocol: Protocol = computedState.protocol
  def metadata: Metadata = computedState.metadata
  def setTransactions: Seq[SetTransaction] = computedState.setTransactions
  def sizeInBytes: Long = computedState.sizeInBytes
  def numOfFiles: Long = computedState.numOfFiles
  def fileSizeHistogram: Option[FileSizeHistogram] = computedState.fileSizeHistogram
  def numOfMetadata: Long = computedState.numOfMetadata
  def numOfProtocol: Long = computedState.numOfProtocol
  def numOfRemoves: Long = computedState.numOfRemoves
  def numOfSetTransactions: Long = computedState.numOfSetTransactions

  /**
   * Computes all the information that is needed by the checksum for the current snapshot.
   * May kick off state reconstruction if needed by any of the underlying fields.
   * Note that it's safe to set txnId to none, since the snapshot doesn't always have a txn
   * attached. E.g. if a snapshot is created by reading a checkpoint, then no txnId is present.
   */
  def computeChecksum: VersionChecksum = VersionChecksum(
    tableSizeBytes = sizeInBytes,
    numFiles = numOfFiles,
    numMetadata = numOfMetadata,
    numProtocol = numOfProtocol,
    protocol = protocol,
    metadata = metadata,
    histogramOpt = fileSizeHistogram,
    txnId = None,
    allFiles = checksumOpt.flatMap(_.allFiles))

  /** A map to look up transaction version by appId. */
  lazy val transactions: Map[String, Long] = setTransactions.map(t => t.appId -> t.version).toMap

  // Here we need to bypass the ACL checks for SELECT anonymous function permissions.
  /** All of the files present in this [[Snapshot]]. */
  def allFiles: Dataset[AddFile] = allFilesViaStateReconstruction

  private[delta] def allFilesViaStateReconstruction: Dataset[AddFile] = {
    stateDS.where("add IS NOT NULL").select(col("add").as[AddFile])
  }

  /** All unexpired tombstones. */
  def tombstones: Dataset[RemoveFile] = {
    stateDS.where("remove IS NOT NULL").select(col("remove").as[RemoveFile])
  }

  /** Returns the schema of the table. */
  def schema: StructType = metadata.schema

  /** Returns the data schema of the table, used for reading stats */
  def tableDataSchema: StructType = metadata.dataSchema

  /** Returns the schema of the columns written out to file (overridden in write path) */
  def dataSchema: StructType = metadata.dataSchema

  /** Number of columns to collect stats on for data skipping */
  lazy val numIndexedCols: Int = DeltaConfigs.DATA_SKIPPING_NUM_INDEXED_COLS.fromMetaData(metadata)

  /** Return the set of properties of the table. */
  def getProperties: mutable.HashMap[String, String] = {
    val base = new mutable.HashMap[String, String]()
    metadata.configuration.foreach { case (k, v) =>
      if (k != "path") {
        base.put(k, v)
      }
    }
    base.put(Protocol.MIN_READER_VERSION_PROP, protocol.minReaderVersion.toString)
    base.put(Protocol.MIN_WRITER_VERSION_PROP, protocol.minWriterVersion.toString)
    base
  }

  // Given the list of files from `LogSegment`, create respective file indices to help create
  // a DataFrame and short-circuit the many file existence and partition schema inference checks
  // that exist in DataSource.resolveRelation().
  protected[delta] lazy val deltaFileIndexOpt: Option[DeltaLogFileIndex] = {
    assertLogFilesBelongToTable(path, logSegment.deltas)
    DeltaLogFileIndex(DeltaLogFileIndex.COMMIT_FILE_FORMAT, logSegment.deltas)
  }

  protected lazy val checkpointFileIndexOpt: Option[DeltaLogFileIndex] = {
    assertLogFilesBelongToTable(path, logSegment.checkpoint)
    DeltaLogFileIndex(DeltaLogFileIndex.CHECKPOINT_FILE_FORMAT, logSegment.checkpoint)
  }

  def getCheckpointMetadataOpt: Option[CheckpointMetaData] = checkpointMetadataOpt

  def deltaFileSizeInBytes(): Long = deltaFileIndexOpt.map(_.sizeInBytes).getOrElse(0L)
  def checkpointSizeInBytes(): Long = checkpointFileIndexOpt.map(_.sizeInBytes).getOrElse(0L)

  protected lazy val fileIndices: Seq[DeltaLogFileIndex] = {
    checkpointFileIndexOpt.toSeq ++ deltaFileIndexOpt.toSeq
  }

  /**
   * Loads the file indices into a DataFrame that can be used for LogReplay.
   *
   * In addition to the usual nested columns provided by the SingleAction schema, it should provide
   * two additional columns to simplify the log replay process: [[ACTION_SORT_COL_NAME]] (which,
   * when sorted in ascending order, will order older actions before newer ones, as required by
   * [[InMemoryLogReplay]]); and [[ADD_STATS_TO_USE_COL_NAME]] (to handle certain combinations of
   * config settings for delta.checkpoint.writeStatsAsJson and delta.checkpoint.writeStatsAsStruct).
   */
  protected def loadActions: DataFrame = {
    val dfs = fileIndices.map { index => Dataset.ofRows(spark, deltaLog.indexToRelation(index)) }
    dfs.reduceOption(_.union(_)).getOrElse(emptyDF)
      .withColumn(ACTION_SORT_COL_NAME, input_file_name())
      .withColumn(ADD_STATS_TO_USE_COL_NAME, col("add.stats"))
  }

  protected def emptyDF: DataFrame =
    spark.createDataFrame(spark.sparkContext.emptyRDD[Row], logSchema)


  override def logInfo(msg: => String): Unit = {
    super.logInfo(s"[tableId=${deltaLog.tableId}] " + msg)
  }

  override def logWarning(msg: => String): Unit = {
    super.logWarning(s"[tableId=${deltaLog.tableId}] " + msg)
  }

  override def logWarning(msg: => String, throwable: Throwable): Unit = {
    super.logWarning(s"[tableId=${deltaLog.tableId}] " + msg, throwable)
  }

  override def logError(msg: => String): Unit = {
    super.logError(s"[tableId=${deltaLog.tableId}] " + msg)
  }

  override def logError(msg: => String, throwable: Throwable): Unit = {
    super.logError(s"[tableId=${deltaLog.tableId}] " + msg, throwable)
  }

  override def toString: String =
    s"${getClass.getSimpleName}(path=$path, version=$version, metadata=$metadata, " +
      s"logSegment=$logSegment, checksumOpt=$checksumOpt)"

  logInfo(s"Created snapshot $this")
  init()
}

object Snapshot extends DeltaLogging {

  // Used by [[loadActions]] and [[stateReconstruction]]
  val ACTION_SORT_COL_NAME = "action_sort_column"
  val ADD_STATS_TO_USE_COL_NAME = "add_stats_to_use"

  private val defaultNumSnapshotPartitions: Int = 50

  /** Verifies that a set of delta or checkpoint files to be read actually belongs to this table. */
  private def assertLogFilesBelongToTable(logBasePath: Path, files: Seq[FileStatus]): Unit = {
    files.map(_.getPath).foreach { filePath =>
      if (new Path(filePath.toUri).getParent != new Path(logBasePath.toUri)) {
        // scalastyle:off throwerror
        throw new AssertionError(s"File ($filePath) doesn't belong in the " +
          s"transaction log at $logBasePath. Please contact Databricks Support.")
        // scalastyle:on throwerror
      }
    }
  }

  /**
   * Metrics and metadata computed around the Delta table
   * @param protocol The protocol version of the Delta table
   * @param metadata The metadata of the table
   * @param setTransactions The streaming queries writing to this table
   * @param sizeInBytes The total size of the table (of active files, not including tombstones)
   * @param numOfFiles The number of files in this table
   * @param numOfMetadata The number of metadata actions in the state. Should be 1
   * @param numOfProtocol The number of protocol actions in the state. Should be 1
   * @param numOfRemoves The number of tombstones in the state
   * @param numOfSetTransactions Number of streams writing to this table
   */
  case class State(
    protocol: Protocol,
    metadata: Metadata,
    setTransactions: Seq[SetTransaction],
    sizeInBytes: Long,
    numOfFiles: Long,
    numOfMetadata: Long,
    numOfProtocol: Long,
    numOfRemoves: Long,
    numOfSetTransactions: Long,
    fileSizeHistogram: Option[FileSizeHistogram])
}

/**
 * An initial snapshot with only metadata specified. Useful for creating a DataFrame from an
 * existing parquet table during its conversion to delta.
 *
 * @param logPath the path to transaction log
 * @param deltaLog the delta log object
 * @param metadata the metadata of the table
 */
class InitialSnapshot(
    val logPath: Path,
    override val deltaLog: DeltaLog,
    override val metadata: Metadata)
  extends Snapshot(
    path = logPath,
    version = -1,
    logSegment = LogSegment.empty(logPath),
    minFileRetentionTimestamp = -1,
    deltaLog = deltaLog,
    timestamp = -1,
    checksumOpt = None,
    minSetTransactionRetentionTimestamp = None) {

  def this(logPath: Path, deltaLog: DeltaLog) = this(
    logPath,
    deltaLog,
    Metadata(
      configuration = DeltaConfigs.mergeGlobalConfigs(
        SparkSession.active.sessionState.conf, Map.empty),
      createdTime = Some(System.currentTimeMillis()))
  )

  override def stateDS: Dataset[SingleAction] = emptyDF.as[SingleAction]
  override def stateDF: DataFrame = emptyDF
  override protected lazy val computedState: Snapshot.State = initialState
  private def initialState: Snapshot.State = {
    val protocol = Protocol.forNewTable(spark, metadata)
    Snapshot.State(protocol, metadata, Nil, 0L, 0L, 1L, 1L, 0L, 0L, None)
  }
}
