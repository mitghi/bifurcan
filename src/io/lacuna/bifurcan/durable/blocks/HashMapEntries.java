package io.lacuna.bifurcan.durable.blocks;

import io.lacuna.bifurcan.*;
import io.lacuna.bifurcan.IDurableCollection.Root;
import io.lacuna.bifurcan.durable.BlockPrefix.BlockType;
import io.lacuna.bifurcan.durable.DurableBuffer;
import io.lacuna.bifurcan.durable.Util;
import io.lacuna.bifurcan.utils.Iterators;

import java.util.Iterator;
import java.util.PrimitiveIterator;
import java.util.function.BiPredicate;

/**
 * A block that represents zero or more key/value pairs in a HashMap.
 * <p>
 * Encoding:
 * - the number of preceding entries [VLQ]
 * - the hash for each entry [HashDeltas]
 * - keys [an ENCODED block generated by {@code DurableEncoding.keyEncoding()}]
 * - values [an ENCODED block generated by {@code DurableEncoding.valueEncoding(firstKey)}
 */
public class HashMapEntries {

  private static final BlockType BLOCK_TYPE = BlockType.TABLE;

  public static <K, V> void encode(
      long offset,
      IList<IEntry.WithHash<K, V>> block,
      IDurableEncoding.Map mapEncoding,
      DurableOutput out) {
    DurableBuffer.flushTo(out, BLOCK_TYPE, acc -> {
      acc.writeVLQ(offset);

      HashDeltas.Writer hashes = new HashDeltas.Writer();
      block.forEach(e -> hashes.append(e.keyHash()));
      hashes.flushTo(acc);

      Util.encodeBlock(Lists.lazyMap(block, IEntry::key), mapEncoding.keyEncoding(), acc);
      Util.encodeBlock(Lists.lazyMap(block, IEntry::value), mapEncoding.valueEncoding(), acc);
    });
  }

  public static HashMapEntries decode(DurableInput in, Root root, IDurableEncoding.Map mapEncoding) {
    DurableInput entries = in.sliceBlock(BLOCK_TYPE);
    long entryOffset = entries.readVLQ();
    HashDeltas deltas = HashDeltas.decode(entries);
    DurableInput keys = entries.slicePrefixedBlock();
    DurableInput values = entries.slicePrefixedBlock();

    return new HashMapEntries(root, entryOffset, deltas, keys, values, mapEncoding);
  }

  public static Object get(Iterator<HashMapEntries> it, Root root, int hash, Object key, Object defaultValue) {
    while (it.hasNext()) {
      HashMapEntries entries = it.next();
      HashDeltas.IndexRange candidates = entries.hashes.candidateIndices(hash);

      int keyIndex = entries.localIndexOf(candidates, key);
      if (keyIndex == -1 && candidates.isBounded) {
        return defaultValue;
      } else if (keyIndex != -1) {
        return Util.decodeBlock(entries.values, root, entries.mapEncoding.valueEncoding()).skip(keyIndex).next();
      }
    }

    return defaultValue;
  }

  public static long indexOf(Iterator<HashMapEntries> it, int hash, Object key) {
    while (it.hasNext()) {
      HashMapEntries entries = it.next();
      HashDeltas.IndexRange candidates = entries.hashes.candidateIndices(hash);

      int keyIndex = entries.localIndexOf(candidates, key);
      if (keyIndex == -1 && candidates.isBounded) {
      } else if (keyIndex != -1) {
        return entries.entryOffset + keyIndex;
      }
    }

    return -1;
  }

  public final long entryOffset;
  public final HashDeltas hashes;
  public final DurableInput keys, values;
  public final IDurableEncoding.Map mapEncoding;
  public final Root root;

  private HashMapEntries(
      Root root,
      long entryOffset,
      HashDeltas hashes,
      DurableInput keys,
      DurableInput values,
      IDurableEncoding.Map mapEncoding) {
    this.root = root;
    this.entryOffset = entryOffset;
    this.hashes = hashes;
    this.keys = keys;
    this.values = values;
    this.mapEncoding = mapEncoding;
  }

  private int localIndexOf(HashDeltas.IndexRange candidates, Object key) {
    if (candidates.start < 0) {
      return -1;
    }

    IDurableEncoding.SkippableIterator it = Util.decodeBlock(keys, root, mapEncoding.keyEncoding()).skip(candidates.start);
    BiPredicate<Object, Object> keyEquals = mapEncoding.keyEncoding().equalityFn();

    for (int i = candidates.start; i < candidates.end; i++) {
      Object k = it.next();
      if (keyEquals.test(k, key)) {
        return i;
      }
    }

    return -1;
  }

  public IEntry.WithHash<Object, Object> nth(long index) {
    Object key = Util.decodeBlock(keys, root, mapEncoding.keyEncoding()).skip(index).next();
    Object value = Util.decodeBlock(values, root, mapEncoding.valueEncoding()).skip(index).next();

    return IEntry.of(hashes.nth(index), key, value);
  }

  public Iterator<IEntry.WithHash<Object, Object>> entries(long dropped) {
    PrimitiveIterator.OfInt hashes = (PrimitiveIterator.OfInt) Iterators.drop(this.hashes.iterator(), dropped);
    IDurableEncoding.SkippableIterator keys = Util.decodeBlock(this.keys, root, mapEncoding.keyEncoding()).skip(dropped);
    IDurableEncoding.SkippableIterator values = Util.decodeBlock(this.values, root, mapEncoding.valueEncoding()).skip(dropped);
    return Iterators.from(hashes::hasNext, () -> IEntry.of(hashes.nextInt(), keys.next(), values.next()));
  }
}
