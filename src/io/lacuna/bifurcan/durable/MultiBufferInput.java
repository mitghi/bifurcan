package io.lacuna.bifurcan.durable;

import io.lacuna.bifurcan.DurableInput;
import io.lacuna.bifurcan.IEntry;
import io.lacuna.bifurcan.IntMap;
import io.lacuna.bifurcan.LinearList;
import io.lacuna.bifurcan.durable.allocator.SlabAllocator.SlabBuffer;

import java.nio.ByteBuffer;

public class MultiBufferInput implements DurableInput {

  private static final ThreadLocal<ByteBuffer> SCRATCH_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(8));

  private final Slice bounds;
  private final IntMap<SlabBuffer> buffers;
  private final long size;

  private long offset = 0;
  private ByteBuffer curr;

  private MultiBufferInput(IntMap<SlabBuffer> buffers, Slice bounds, long position, long size) {
    this.bounds = bounds;
    this.buffers = buffers;
    this.size = size;
    seek(position);
  }

  public MultiBufferInput(Iterable<SlabBuffer> buffers, Slice bounds) {
    IntMap<SlabBuffer> m = new IntMap<SlabBuffer>().linear();

    long size = 0;
    for (SlabBuffer b : buffers) {
      m.put(size, b);
      size += b.size();
    }
    assert (size == bounds.size());

    this.bounds = bounds;
    this.size = size;
    this.buffers = m.forked();
    this.curr = this.buffers.first().value().bytes();
  }

  @Override
  public Slice bounds() {
    return bounds;
  }

  @Override
  public DurableInput duplicate() {
    return new MultiBufferInput(buffers, bounds, position(), size);
  }

  @Override
  public DurableInput slice(long start, long end) {
    if (start < 0 && end >= size()) {
      throw new IllegalArgumentException(String.format("[%d, %d) is not within [0, %d)", start, end, size()));
    }

    long length = end - start;
    Slice bounds = new Slice(this.bounds, start, end);

    IEntry<Long, SlabBuffer> f = buffers.floor(start);
    SlabBuffer bf = f.value().slice((int) (start - f.key()), f.value().size());
    if (length <= bf.size()) {
      return new SingleBufferInput(bf.slice(0, (int) length), bounds);
    }

    IEntry<Long, SlabBuffer> l = buffers.floor(end);
    if ((end - l.key()) > l.value().size()) {
      System.out.println(l.value().bytes());
      System.out.println(start + " " + end + " " + buffers.keys());
    }
    SlabBuffer bl = l.value().slice(0, (int) (end - l.key()));

    LinearList<SlabBuffer> bufs =
        LinearList.from(buffers.slice(f.key() + bf.size(), l.key() - 1).values())
            .addFirst(bf)
            .addLast(bl);

    return new MultiBufferInput(bufs, bounds);
  }

  @Override
  public DurableInput seek(long position) {
    updateCurr(position);
    return this;
  }

  @Override
  public long remaining() {
    return size - position();
  }

  @Override
  public long position() {
    return offset + curr.position();
  }

  @Override
  public int read(ByteBuffer dst) {
    int pos = dst.position();

    while (dst.remaining() > 0 && remaining() > 0) {
      if (curr.remaining() == 0) {
        updateCurr(position());
      }
      Util.transfer(curr, dst);
    }

    return dst.position() - pos;
  }

  @Override
  public void close() {
    buffers.values().forEach(SlabBuffer::release);
  }

  @Override
  public byte readByte() {
    if (curr.remaining() == 0) {
      updateCurr(position());
    }
    byte b = curr.get();
    return b;
  }

  @Override
  public short readShort() {
    return readableBuffer(2).getShort();
  }

  @Override
  public char readChar() {
    return readableBuffer(2).getChar();
  }

  @Override
  public int readInt() {
    return readableBuffer(4).getInt();
  }

  @Override
  public long readLong() {
    return readableBuffer(8).getLong();
  }

  @Override
  public float readFloat() {
    return readableBuffer(4).getFloat();
  }

  @Override
  public double readDouble() {
    return readableBuffer(8).getDouble();
  }

  ///

  private void updateCurr(long position) {
    IEntry<Long, SlabBuffer> e = buffers.floor(position);
    offset = e.key();
    curr = (ByteBuffer) e.value().bytes().position((int) (position - offset));
  }

  private ByteBuffer readableBuffer(int bytes) {
    if (curr.remaining() >= bytes) {
      return curr;
    } else {
      ByteBuffer buf = (ByteBuffer) SCRATCH_BUFFER.get().clear();
      for (int i = 0; i < bytes; i++) {
        buf.put(readByte());
      }
      return (ByteBuffer) buf.flip();
    }
  }

}
