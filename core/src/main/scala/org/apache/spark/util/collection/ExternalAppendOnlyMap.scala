/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.util.collection

import java.io._
import java.lang.management.ManagementFactory
import java.util.Comparator

import scala.collection.BufferedIterator
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import com.google.common.io.ByteStreams

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.executor.ShuffleWriteMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.serializer.{DeserializationStream, Serializer, SerializerManager}
import org.apache.spark.storage.{BlockId, BlockManager}
import org.apache.spark.util.{CompletionIterator, SizeEstimator}
import org.apache.spark.util.collection.ExternalAppendOnlyMap.HashComparator

/**
 * :: DeveloperApi ::
 * An append-only map that spills sorted content to disk when there is insufficient space for it
 * to grow.
 *
 * This map takes two passes over the data:
 *
 *   (1) Values are merged into combiners, which are sorted and spilled to disk as necessary
 *   (2) Combiners are read from disk and merged together
 *
 * The setting of the spill threshold faces the following trade-off: If the spill threshold is
 * too high, the in-memory map may occupy more memory than is available, resulting in OOM.
 * However, if the spill threshold is too low, we spill frequently and incur unnecessary disk
 * writes. This may lead to a performance regression compared to the normal case of using the
 * non-spilling AppendOnlyMap.
 */
@DeveloperApi
class ExternalAppendOnlyMap[K, V, C](
    createCombiner: V => C,
    mergeValue: (C, V) => C,
    mergeCombiners: (C, C) => C,
    serializer: Serializer = SparkEnv.get.serializer,
    blockManager: BlockManager = SparkEnv.get.blockManager,
    context: TaskContext = TaskContext.get(),
    serializerManager: SerializerManager = SparkEnv.get.serializerManager)
  extends Spillable[SizeTracker](context.taskMemoryManager())
  with Serializable
  with Logging
  with Iterable[(K, C)] {

  if (context == null) {
    throw new IllegalStateException(
      "Spillable collections should not be instantiated outside of tasks")
  }

  // Backwards-compatibility constructor for binary compatibility
  def this(
      createCombiner: V => C,
      mergeValue: (C, V) => C,
      mergeCombiners: (C, C) => C,
      serializer: Serializer,
      blockManager: BlockManager) {
    this(createCombiner, mergeValue, mergeCombiners, serializer, blockManager, TaskContext.get())
  }

  @volatile private var currentMap = new SizeTrackingAppendOnlyMap[K, C]
  private val spilledMaps = new ArrayBuffer[DiskMapIterator]
  private val sparkConf = SparkEnv.get.conf
  private val diskBlockManager = blockManager.diskBlockManager

  /**
   * Size of object batches when reading/writing from serializers.
   *
   * Objects are written in batches, with each batch using its own serialization stream. This
   * cuts down on the size of reference-tracking maps constructed when deserializing a stream.
   *
   * NOTE: Setting this too low can cause excessive copying when serializing, since some serializers
   * grow internal data structures by growing + copying every time the number of objects doubles.
   */
  private val serializerBatchSize = sparkConf.getLong("spark.shuffle.spill.batchSize", 10000)
  private val estimateDeserMemory =
    sparkConf.getBoolean("spark.shuffle.spill.estimateDeserMemory", false)
  private val estimateRecordSizeInterval =
    sparkConf.getLong("spark.shuffle.spill.estimateRecordSizeInterval", -1)

  // Number of bytes spilled in total
  private var _diskBytesSpilled = 0L
  def diskBytesSpilled: Long = _diskBytesSpilled

  // Use getSizeAsKb (not bytes) to maintain backwards compatibility if no units are provided
  private val fileBufferSize =
    sparkConf.getSizeAsKb("spark.shuffle.file.buffer", "32k").toInt * 1024

  // Write metrics
  private val writeMetrics: ShuffleWriteMetrics = new ShuffleWriteMetrics()

  // Peak size of the in-memory map observed so far, in bytes
  private var _peakMemoryUsedBytes: Long = 0L
  def peakMemoryUsedBytes: Long = _peakMemoryUsedBytes

  private val keyComparator = new HashComparator[K]
  private val ser = serializer.newInstance()

  @volatile private var readingIterator: SpillableIterator = null

  private var previous_spills_recordsWritten: Long = 0L
  private var previous_spills__bytesWritten: Long = 0L

  private val memoryMXBean = ManagementFactory.getMemoryMXBean


  def getHeapUsage(): String = {
    // System.gc()
    val heapUsage = memoryMXBean.getHeapMemoryUsage
    val used = heapUsage.getUsed
    val committed = heapUsage.getCommitted
    val max = heapUsage.getMax
    "used = " + org.apache.spark.util.Utils.bytesToString(used) +
      ", committed = " + org.apache.spark.util.Utils.bytesToString(committed) +
      ", max = " + org.apache.spark.util.Utils.bytesToString(max)
  }

  def reduceClassName(className: String): String = {
    className.substring(className.lastIndexOf(".") + 1)
  }

  /**
   * Number of files this map has spilled so far.
   * Exposed for testing.
   */
  private[collection] def numSpills: Int = spilledMaps.size

  /**
   * Insert the given key and value into the map.
   */
  def insert(key: K, value: V): Unit = {
    insertAll(Iterator((key, value)))
  }

  /**
   * Insert the given iterator of keys and values into the map.
   *
   * When the underlying map needs to grow, check if the global pool of shuffle memory has
   * enough room for this to happen. If so, allocate the memory required to grow the map;
   * otherwise, spill the in-memory map to disk.
   *
   * The shuffle memory usage of the first trackMemoryThreshold entries is not tracked.
   */
  def insertAll(entries: Iterator[Product2[K, V]]): Unit = {
    if (currentMap == null) {
      throw new IllegalStateException(
        "Cannot insert new elements into a map after calling iterator")
    }
    // An update function for the map that we reuse across entries to avoid allocating
    // a new closure each time
    var curEntry: Product2[K, V] = null
    val update: (Boolean, C) => C = (hadVal, oldVal) => {
      if (hadVal) mergeValue(oldVal, curEntry._2) else createCombiner(curEntry._2)
    }

    while (entries.hasNext) {
      curEntry = entries.next()
      val estimatedSize = currentMap.estimateSize()
      if (estimatedSize > _peakMemoryUsedBytes) {
        _peakMemoryUsedBytes = estimatedSize
      }
      if (maybeSpill(currentMap, currentMap.size, estimatedSize)) {
        currentMap = new SizeTrackingAppendOnlyMap[K, C]
      }
      currentMap.changeValue(curEntry._1, update)
      addElementsRead()
    }
  }

  /**
   * Insert the given iterable of keys and values into the map.
   *
   * When the underlying map needs to grow, check if the global pool of shuffle memory has
   * enough room for this to happen. If so, allocate the memory required to grow the map;
   * otherwise, spill the in-memory map to disk.
   *
   * The shuffle memory usage of the first trackMemoryThreshold entries is not tracked.
   */
  def insertAll(entries: Iterable[Product2[K, V]]): Unit = {
    insertAll(entries.iterator)
  }

  /**
   * Sort the existing contents of the in-memory map and spill them to a temporary file on disk.
   */
  override protected[this] def spill(collection: SizeTracker): Unit = {
    val start = System.currentTimeMillis()

    val inMemoryIterator = currentMap.destructiveSortedIterator(keyComparator)
    val diskMapIterator = spillMemoryIteratorToDisk(inMemoryIterator)
    spilledMaps += diskMapIterator

    val end = System.currentTimeMillis()

    val spill_writeTime = (end - start) / 1000
    val spill_recordsWritten = writeMetrics.recordsWritten - previous_spills_recordsWritten
    val spill_bytesWritten = writeMetrics.bytesWritten - previous_spills__bytesWritten

    previous_spills_recordsWritten = writeMetrics.recordsWritten
    previous_spills__bytesWritten = writeMetrics.bytesWritten

    logInfo(s"[Task ${context.taskAttemptId} SpillMetrics] release = " +
      org.apache.spark.util.Utils.bytesToString(getUsed()) + ", writeTime = "
      + spill_writeTime + " s, recordsWritten = " + spill_recordsWritten
      + ", bytesWritten = " + org.apache.spark.util.Utils.bytesToString(spill_bytesWritten)
      + ", avgRecordSize = " +
      org.apache.spark.util.Utils.bytesToString(spill_bytesWritten / spill_recordsWritten))
  }

  /**
   * Force to spilling the current in-memory collection to disk to release memory,
   * It will be called by TaskMemoryManager when there is not enough memory for the task.
   */
  override protected[this] def forceSpill(): Boolean = {
    assert(readingIterator != null)
    val isSpilled = readingIterator.spill()
    if (isSpilled) {
      currentMap = null
    }
    isSpilled
  }

  /**
   * Spill the in-memory Iterator to a temporary file on disk.
   */
  private[this] def spillMemoryIteratorToDisk(inMemoryIterator: Iterator[(K, C)])
      : DiskMapIterator = {
    val (blockId, file) = diskBlockManager.createTempLocalBlock()
    val writer = blockManager.getDiskWriter(blockId, file, ser, fileBufferSize, writeMetrics)
    var objectsWritten = 0

    // List of batch sizes (bytes) in the order they are written to disk
    val batchSizes = new ArrayBuffer[Long]

    // Flush the disk writer's contents to disk, and update relevant variables
    def flush(): Unit = {
      if (estimateDeserMemory) {
        logDebug("[Serialized buffer in flush()] writerSize = "
          + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(writer))
        )
      }

      val segment = writer.commitAndGet()
      batchSizes += segment.length
      _diskBytesSpilled += segment.length

      objectsWritten = 0
    }

    var success = false
    try {
      while (inMemoryIterator.hasNext) {
        val kv = inMemoryIterator.next()
        writer.write(kv._1, kv._2)

        if (estimateRecordSizeInterval > 0 && objectsWritten % estimateRecordSizeInterval == 0) {
          logDebug("[SpillRecord] recordIndex = " + (objectsWritten + 1)
            + ", recordUnSerializedSize = "
            + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(kv))
            + ", record = (" + kv._1.getClass.getSimpleName + ", "
            + kv._2.getClass.getSimpleName + ")")
        }

        objectsWritten += 1

        if (objectsWritten == serializerBatchSize) {
          flush()
        }
      }
      if (objectsWritten > 0) {
        flush()
        writer.close()
      } else {
        writer.revertPartialWritesAndClose()
      }
      success = true
    } finally {
      if (!success) {
        // This code path only happens if an exception was thrown above before we set success;
        // close our stuff and let the exception be thrown further
        writer.revertPartialWritesAndClose()
        if (file.exists()) {
          if (!file.delete()) {
            logWarning(s"Error deleting ${file}")
          }
        }
      }
    }

    logDebug("[DiskMapIterator] file = " + file.getAbsolutePath + ", blockId = "
            + blockId + ", batchSizes = " + batchSizes)

    new DiskMapIterator(file, blockId, batchSizes)
  }

  /**
   * Returns a destructive iterator for iterating over the entries of this map.
   * If this iterator is forced spill to disk to release memory when there is not enough memory,
   * it returns pairs from an on-disk map.
   */
  def destructiveIterator(inMemoryIterator: Iterator[(K, C)]): Iterator[(K, C)] = {
    readingIterator = new SpillableIterator(inMemoryIterator)
    readingIterator
  }

  /**
   * Return a destructive iterator that merges the in-memory map with the spilled maps.
   * If no spill has occurred, simply return the in-memory map's iterator.
   */
  override def iterator: Iterator[(K, C)] = {
    if (currentMap == null) {
      throw new IllegalStateException(
        "ExternalAppendOnlyMap.iterator is destructive and should only be called once.")
    }
    if (spilledMaps.isEmpty) {
      CompletionIterator[(K, C), Iterator[(K, C)]](
        destructiveIterator(currentMap.iterator), freeCurrentMap())
    } else {
      new ExternalIterator()
    }
  }

  private def freeCurrentMap(): Unit = {
    if (currentMap != null) {
      currentMap = null // So that the memory can be garbage-collected
      releaseMemory()
    }
  }

  /**
   * An iterator that sort-merges (K, C) pairs from the in-memory map and the spilled maps
   */
  private class ExternalIterator extends Iterator[(K, C)] {

    // A queue that maintains a buffer for each stream we are currently merging
    // This queue maintains the invariant that it only contains non-empty buffers
    private val mergeHeap = new mutable.PriorityQueue[StreamBuffer]

    // Input streams are derived both from the in-memory map and spilled maps on disk
    // The in-memory map is sorted in place, while the spilled maps are already in sorted order
    private val sortedMap = CompletionIterator[(K, C), Iterator[(K, C)]](destructiveIterator(
      currentMap.destructiveSortedIterator(keyComparator)), freeCurrentMap())
    private val inputStreams = (Seq(sortedMap) ++ spilledMaps).map(it => it.buffered)

    private var recordsRead = 0L

    inputStreams.foreach { it =>
      // build a heap to sort records
      val kcPairs = new ArrayBuffer[(K, C)]
      readNextHashCode(it, kcPairs)
      if (kcPairs.length > 0) {
        mergeHeap.enqueue(new StreamBuffer(it, kcPairs))
      }
    }

    /**
     * Fill a buffer with the next set of keys with the same hash code from a given iterator. We
     * read streams one hash code at a time to ensure we don't miss elements when they are merged.
     *
     * Assumes the given iterator is in sorted order of hash code.
     *
     * @param it iterator to read from
     * @param buf buffer to write the results into
     */
    private def readNextHashCode(it: BufferedIterator[(K, C)], buf: ArrayBuffer[(K, C)]): Unit = {
      if (it.hasNext) {
        var kc = it.next()
        buf += kc
        val minHash = hashKey(kc)
        while (it.hasNext && it.head._1.hashCode() == minHash) {
          kc = it.next()
          buf += kc
        }
      }
    }

    /**
     * If the given buffer contains a value for the given key, merge that value into
     * baseCombiner and remove the corresponding (K, C) pair from the buffer.
     */
    private def mergeIfKeyExists(key: K, baseCombiner: C, buffer: StreamBuffer): C = {
      var i = 0
      while (i < buffer.pairs.length) {
        val pair = buffer.pairs(i)
        if (pair._1 == key) {
          // Note that there's at most one pair in the buffer with a given key, since we always
          // merge stuff in a map before spilling, so it's safe to return after the first we find
          removeFromBuffer(buffer.pairs, i)
          return mergeCombiners(baseCombiner, pair._2)
        }
        i += 1
      }
      baseCombiner
    }

    /**
     * Remove the index'th element from an ArrayBuffer in constant time, swapping another element
     * into its place. This is more efficient than the ArrayBuffer.remove method because it does
     * not have to shift all the elements in the array over. It works for our array buffers because
     * we don't care about the order of elements inside, we just want to search them for a key.
     */
    private def removeFromBuffer[T](buffer: ArrayBuffer[T], index: Int): T = {
      val elem = buffer(index)
      buffer(index) = buffer(buffer.size - 1)  // This also works if index == buffer.size - 1
      buffer.reduceToSize(buffer.size - 1)
      elem
    }

    /**
     * Return true if there exists an input stream that still has unvisited pairs.
     */
    override def hasNext: Boolean = mergeHeap.nonEmpty

    /**
     * Select a key with the minimum hash, then combine all values with the same key from all
     * input streams.
     */
    override def next(): (K, C) = {
      if (mergeHeap.isEmpty) {
        throw new NoSuchElementException
      }
      // Select a key from the StreamBuffer that holds the lowest key hash
      val minBuffer = mergeHeap.dequeue()
      val minPairs = minBuffer.pairs
      val minHash = minBuffer.minKeyHash
      val minPair = removeFromBuffer(minPairs, 0)
      val minKey = minPair._1
      var minCombiner = minPair._2
      assert(hashKey(minPair) == minHash)

      // For all other streams that may have this key (i.e. have the same minimum key hash),
      // merge in the corresponding value (if any) from that stream
      val mergedBuffers = ArrayBuffer[StreamBuffer](minBuffer)
      while (mergeHeap.nonEmpty && mergeHeap.head.minKeyHash == minHash) {
        val newBuffer = mergeHeap.dequeue()
        minCombiner = mergeIfKeyExists(minKey, minCombiner, newBuffer)
        mergedBuffers += newBuffer
      }

      if (estimateRecordSizeInterval > 0 && recordsRead % estimateRecordSizeInterval == 0) {
        logDebug("[RecordReadFromMergeHeap] recordIndex = " + (recordsRead + 1)
          + ", recordSize = "
          + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(minPair))
          + ", record = (" + minPair._1.getClass.getSimpleName + ", "
          + minPair._2.getClass.getSimpleName + ")")
        logDebug("[RecordReadFromMergeHeap.afterMerge] mergeHeapLength = " + mergeHeap.size
          + ", mergedBuffersLength = "
          + mergedBuffers.length)
        /*
        for (i <- 0 until mergedBuffers.length) {
          logDebug("  [StreamBuffer " + i + "] " + mergedBuffers(i).showSize + ", " +
            mergedBuffers(i).iterator.getClass.getSimpleName)
        }
        */
      }


      // Repopulate each visited stream buffer and add it back to the queue if it is non-empty
      mergedBuffers.foreach { buffer =>
        if (buffer.isEmpty) {
          readNextHashCode(buffer.iterator, buffer.pairs)
        }
        if (!buffer.isEmpty) {
          mergeHeap.enqueue(buffer)
        }
      }

      if (estimateRecordSizeInterval > 0 && recordsRead % estimateRecordSizeInterval == 0) {
        logDebug("[RecordReadFromMergeHeap.afterReadNewRecord] mergeHeapLength = " + mergeHeap.size
          + ", mergeHeapBytes = "
          + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(mergeHeap))
          + ", mergedBuffersLength = "
          + mergedBuffers.length
          + ", mergedBuffersBytes = "
          + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(mergedBuffers)))
        for (i <- 0 until mergedBuffers.length) {
          logDebug("  [StreamBuffer " + i + "]" + mergedBuffers(i).showSize + ", " +
            mergedBuffers(i).iterator.getClass.getSimpleName)
        }
      }

      recordsRead += 1

      (minKey, minCombiner)
    }

    /**
     * A buffer for streaming from a map iterator (in-memory or on-disk) sorted by key hash.
     * Each buffer maintains all of the key-value pairs with what is currently the lowest hash
     * code among keys in the stream. There may be multiple keys if there are hash collisions.
     * Note that because when we spill data out, we only spill one value for each key, there is
     * at most one element for each key.
     *
     * StreamBuffers are ordered by the minimum key hash currently available in their stream so
     * that we can put them into a heap and sort that.
     */
    private class StreamBuffer(
        val iterator: BufferedIterator[(K, C)],
        val pairs: ArrayBuffer[(K, C)])
      extends Comparable[StreamBuffer] {

      def isEmpty: Boolean = pairs.length == 0

      // Invalid if there are no more pairs in this stream
      def minKeyHash: Int = {
        assert(pairs.length > 0)
        hashKey(pairs.head)
      }

      override def compareTo(other: StreamBuffer): Int = {
        // descending order because mutable.PriorityQueue dequeues the max, not the min
        if (other.minKeyHash < minKeyHash) -1 else if (other.minKeyHash == minKeyHash) 0 else 1
      }

      def showSize(): String = {
        "pairsNum = " + pairs.length + ", pairsBytes = " +
          org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(pairs)) +
          ", bufferedIteratorSize = " +
          org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(iterator))
      }
    }
  }

  /**
   * An iterator that returns (K, C) pairs in sorted order from an on-disk map
   */
  private class DiskMapIterator(file: File, blockId: BlockId, batchSizes: ArrayBuffer[Long])
    extends Iterator[(K, C)]
  {
    private val batchOffsets = batchSizes.scanLeft(0L)(_ + _)  // Size will be batchSize.length + 1
    assert(file.length() == batchOffsets.last,
      "File length is not equal to the last batch offset:\n" +
      s"    file length = ${file.length}\n" +
      s"    last batch offset = ${batchOffsets.last}\n" +
      s"    all batch offsets = ${batchOffsets.mkString(",")}"
    )

    private var batchIndex = 0  // Which batch we're in
    private var fileStream: FileInputStream = null

    // An intermediate stream that reads from exactly one batch
    // This guards against pre-fetching and other arbitrary behavior of higher level streams
    private var deserializeStream = nextBatchStream()
    private var nextItem: (K, C) = null
    private var objectsRead = 0

    /**
     * Construct a stream that reads only from the next batch.
     */
    private def nextBatchStream(): DeserializationStream = {
      // Note that batchOffsets.length = numBatches + 1 since we did a scan above; check whether
      // we're still in a valid batch.
      if (batchIndex < batchOffsets.length - 1) {
        if (deserializeStream != null) {
          deserializeStream.close()
          fileStream.close()
          deserializeStream = null
          fileStream = null
        }

        val start = batchOffsets(batchIndex)
        fileStream = new FileInputStream(file)
        fileStream.getChannel.position(start)
        batchIndex += 1

        val end = batchOffsets(batchIndex)

        assert(end >= start, "start = " + start + ", end = " + end +
          ", batchOffsets = " + batchOffsets.mkString("[", ", ", "]"))

        /*
        logDebug("[DiskMapIterator.nextBatchStream] start = " + start + ", end = " + end +
          ", batchOffsets = " + batchOffsets.mkString("[", ", ", "]"))
        logDebug("[DiskMapIterator.beforeRead.heapUsage] " + getHeapUsage)
        */

        val bufferedStream = new BufferedInputStream(ByteStreams.limit(fileStream, end - start))
        val wrappedStream = serializerManager.wrapStream(blockId, bufferedStream)
        val deser = ser.deserializeStream(wrappedStream)

        logDebug("[DiskMapIterator.afterDeserializeStream.heapUsage] " + getHeapUsage)

        if (estimateDeserMemory) {
          logDebug("[DiskMapIterator.deserializer.memoryUsage] serSize = "
            + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(ser))
            + ", deserSize = "
            + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(deser))
            + ", bufferedStream = "
            + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(wrappedStream)))
        }
        deser
      } else {
        // No more batches left
        cleanup()
        null
      }
    }

    /**
     * Return the next (K, C) pair from the deserialization stream.
     *
     * If the current batch is drained, construct a stream for the next batch and read from it.
     * If no more pairs are left, return null.
     */
    private def readNextItem(): (K, C) = {
      var item: (K, C) = null
      try {
        val k = deserializeStream.readKey().asInstanceOf[K]
        val c = deserializeStream.readValue().asInstanceOf[C]
        item = (k, c)

        if (estimateRecordSizeInterval > 0 && objectsRead % estimateRecordSizeInterval == 0) {
          logDebug("[RecordReadFromDisk] recordIndex = " + (objectsRead + 1)
            + ", recordUnSerializedSize = "
            + org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(item))
            + ", record = (" + item._1.getClass.getSimpleName + ", "
            + item._2.getClass.getSimpleName + ")")
        }

        objectsRead += 1
        if (objectsRead == serializerBatchSize) {
          objectsRead = 0
          deserializeStream = nextBatchStream()
        }
        item
      } catch {
        case e: EOFException =>
          cleanup()
          null
        case eo: OutOfMemoryError =>
          logError(s"[Task ${context.taskAttemptId} OOM] In-memory map usage = " +
            org.apache.spark.util.Utils.bytesToString(getUsed()) + ", objectsRead = " +
            objectsRead + ", currentRecordSize = " +
            org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(item)) +
            ", deserializeStream = " +
            org.apache.spark.util.Utils.bytesToString(SizeEstimator.estimate(deserializeStream))
          )
          logError("[CurrentHeapMemoryUsage] " + getHeapUsage)

          throw eo
      }
    }

    override def hasNext: Boolean = {
      if (nextItem == null) {
        if (deserializeStream == null) {
          return false
        }
        nextItem = readNextItem()
      }
      nextItem != null
    }

    override def next(): (K, C) = {
      val item = if (nextItem == null) readNextItem() else nextItem
      if (item == null) {
        throw new NoSuchElementException
      }
      nextItem = null
      item
    }

    private def cleanup() {
      batchIndex = batchOffsets.length  // Prevent reading any other batch
      val ds = deserializeStream
      if (ds != null) {
        ds.close()
        deserializeStream = null
      }
      if (fileStream != null) {
        fileStream.close()
        fileStream = null
      }
      if (file.exists()) {
        if (!file.delete()) {
          logWarning(s"Error deleting ${file}")
        }
      }
    }

    context.addTaskCompletionListener(context => cleanup())
  }

  private[this] class SpillableIterator(var upstream: Iterator[(K, C)])
    extends Iterator[(K, C)] {

    private val SPILL_LOCK = new Object()

    private var nextUpstream: Iterator[(K, C)] = null

    private var cur: (K, C) = readNext()

    private var hasSpilled: Boolean = false

    def spill(): Boolean = SPILL_LOCK.synchronized {
      if (hasSpilled) {
        false
      } else {
        logInfo(s"[Spill] Task ${context.taskAttemptId} force spilling in-memory map to disk and " +
          s"it will release ${org.apache.spark.util.Utils.bytesToString(getUsed())} memory")

        val start = System.currentTimeMillis()
        nextUpstream = spillMemoryIteratorToDisk(upstream)
        val end = System.currentTimeMillis()

        val spill_writeTime = end - start
        val spill_recordsWritten = writeMetrics.recordsWritten - previous_spills_recordsWritten
        val spill_bytesWritten = writeMetrics.bytesWritten - previous_spills__bytesWritten

        previous_spills_recordsWritten = writeMetrics.recordsWritten
        previous_spills__bytesWritten = writeMetrics.bytesWritten

        logInfo(s"[Task ${context.taskAttemptId} SpillMetrics] release = " +
          org.apache.spark.util.Utils.bytesToString(getUsed()) + ", writeTime = "
          + spill_writeTime + " s, recordsWritten = " + spill_recordsWritten
          + ", bytesWritten = " + org.apache.spark.util.Utils.bytesToString(spill_bytesWritten)
          + ", avgRecordSize = " +
          org.apache.spark.util.Utils.bytesToString(spill_bytesWritten / spill_recordsWritten))

        hasSpilled = true
        true
      }
    }

    def readNext(): (K, C) = SPILL_LOCK.synchronized {
      if (nextUpstream != null) {
        upstream = nextUpstream
        nextUpstream = null
      }
      if (upstream.hasNext) {
        upstream.next()
      } else {
        null
      }
    }

    override def hasNext(): Boolean = cur != null

    override def next(): (K, C) = {
      val r = cur
      cur = readNext()
      r
    }
  }

  /** Convenience function to hash the given (K, C) pair by the key. */
  private def hashKey(kc: (K, C)): Int = ExternalAppendOnlyMap.hash(kc._1)

  override def toString(): String = {
    this.getClass.getName + "@" + java.lang.Integer.toHexString(this.hashCode())
  }
}

private[spark] object ExternalAppendOnlyMap {

  /**
   * Return the hash code of the given object. If the object is null, return a special hash code.
   */
  private def hash[T](obj: T): Int = {
    if (obj == null) 0 else obj.hashCode()
  }

  /**
   * A comparator which sorts arbitrary keys based on their hash codes.
   */
  private class HashComparator[K] extends Comparator[K] {
    def compare(key1: K, key2: K): Int = {
      val hash1 = hash(key1)
      val hash2 = hash(key2)
      if (hash1 < hash2) -1 else if (hash1 == hash2) 0 else 1
    }
  }
}
