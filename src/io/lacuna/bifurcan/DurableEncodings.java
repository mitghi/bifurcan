package io.lacuna.bifurcan;

import io.lacuna.bifurcan.durable.BlockPrefix;
import io.lacuna.bifurcan.durable.Encodings;
import io.lacuna.bifurcan.durable.io.DurableBuffer;
import io.lacuna.bifurcan.durable.Util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.*;

public class DurableEncodings {

  public interface Codec {
    void encode(IList<Object> values, DurableOutput out);

    IDurableEncoding.SkippableIterator decode(DurableInput in, IDurableCollection.Root root);

    static Codec from(
        BiConsumer<IList<Object>, DurableOutput> encode,
        BiFunction<DurableInput, IDurableCollection.Root, IDurableEncoding.SkippableIterator> decode) {
      return new Codec() {
        @Override
        public void encode(IList<Object> values, DurableOutput out) {
          encode.accept(values, out);
        }

        @Override
        public IDurableEncoding.SkippableIterator decode(DurableInput in, IDurableCollection.Root root) {
          return decode.apply(in, root);
        }
      };
    }

    static Codec undelimited(
        BiConsumer<Object, DurableOutput> encode,
        BiFunction<DurableInput, IDurableCollection.Root, Object> decode) {
      return new Codec() {
        @Override
        public void encode(IList<Object> values, DurableOutput out) {
          for (Object p : values) {
            DurableBuffer.flushTo(out, BlockPrefix.BlockType.PRIMITIVE, o -> encode.accept(p, o));
          }
        }

        @Override
        public IDurableEncoding.SkippableIterator decode(DurableInput in, IDurableCollection.Root root) {
          return new IDurableEncoding.SkippableIterator() {
            @Override
            public void skip() {
              in.skipBlock();
            }

            @Override
            public boolean hasNext() {
              return in.hasRemaining();
            }

            @Override
            public Object next() {
              if (!hasNext()) {
                throw new NoSuchElementException();
              }
              return decode.apply(in.sliceBlock(BlockPrefix.BlockType.PRIMITIVE), root);
            }
          };
        }
      };
    }

    static Codec selfDelimited(
        BiConsumer<Object, DurableOutput> encode,
        BiFunction<DurableInput, IDurableCollection.Root, Object> decode) {
      return new Codec() {
        @Override
        public void encode(IList<Object> values, DurableOutput out) {
          values.forEach(o -> encode.accept(o, out));
        }

        @Override
        public IDurableEncoding.SkippableIterator decode(DurableInput in, IDurableCollection.Root root) {
          return new IDurableEncoding.SkippableIterator() {
            @Override
            public void skip() {
              next();
            }

            @Override
            public boolean hasNext() {
              return in.hasRemaining();
            }

            @Override
            public Object next() {
              return decode.apply(in, root);
            }
          };
        }
      };
    }
  }

  /**
   * @return an encoding of {@code List[elementEncoding]}
   */
  public static IDurableEncoding.List list(IDurableEncoding elementEncoding) {
    return new IDurableEncoding.List() {
      @Override
      public String description() {
        return String.format("List[%s]", elementEncoding.description());
      }

      @Override
      public IDurableEncoding elementEncoding() {
        return elementEncoding;
      }
    };
  }

  /**
   * @return an encoding of {@code Set[elementEncoding]}
   */
  public static IDurableEncoding.Set set(IDurableEncoding elementEncoding) {
    return new IDurableEncoding.Set() {
      @Override
      public String description() {
        return String.format("Set[%s]", elementEncoding.description());
      }

      @Override
      public IDurableEncoding elementEncoding() {
        return elementEncoding;
      }
    };
  }

  /**
   * @return an encoding of {@code Map[keyEncoding, valueEncoding]}
   */
  public static IDurableEncoding.Map map(IDurableEncoding keyEncoding, IDurableEncoding valueEncoding) {
    return new IDurableEncoding.Map() {
      @Override
      public String description() {
        return String.format("Map[%s, %s]", keyEncoding.description(), valueEncoding.description());
      }

      @Override
      public IDurableEncoding keyEncoding() {
        return keyEncoding;
      }

      @Override
      public IDurableEncoding valueEncoding() {
        return valueEncoding;
      }
    };
  }

  public static IDurableEncoding.Primitive primitive(
      String description,
      int blockSize,
      Codec codec) {
    return primitive(
        description,
        blockSize,
        Objects::hashCode,
        Objects::equals,
        IDurableEncoding::defaultComparator,
        o -> false,
        codec);
  }

  public static IDurableEncoding.Primitive primitive(
      String description,
      int blockSize,
      ToLongFunction<Object> valueHash,
      BiPredicate<Object, Object> valueEquality,
      Comparator<Object> comparator,
      Predicate<Object> isSingleton,
      Codec codec) {
    return new IDurableEncoding.Primitive() {
      @Override
      public String description() {
        return description;
      }

      @Override
      public ToLongFunction<Object> hashFn() {
        return valueHash;
      }

      @Override
      public BiPredicate<Object, Object> equalityFn() {
        return valueEquality;
      }

      @Override
      public boolean isSingleton(Object o) {
        return isSingleton.test(o);
      }

      @Override
      public Comparator<Object> comparator() {
        return comparator;
      }

      @Override
      public int blockSize() {
        return blockSize;
      }

      @Override
      public void encode(IList<Object> primitives, DurableOutput out) {
        codec.encode(primitives, out);
      }

      @Override
      public SkippableIterator decode(DurableInput in, IDurableCollection.Root root) {
        return codec.decode(in, root);
      }
    };
  }

  public static IDurableEncoding.Primitive tuple(
      Function<Object, Object[]> preEncode,
      Function<Object[], Object> postDecode,
      IDurableEncoding... encodings) {
    return tuple(
        preEncode,
        postDecode,
        Arrays.stream(encodings).mapToInt(IDurableEncoding::blockSize).min().getAsInt(),
        encodings);
  }

  public static IDurableEncoding.Primitive tuple(
      Function<Object, Object[]> preEncode,
      Function<Object[], Object> postDecode,
      int blockSize,
      IDurableEncoding... encodings) {
    return new IDurableEncoding.Primitive() {

      @Override
      public String description() {
        StringBuilder sb = new StringBuilder("(");
        for (IDurableEncoding e : encodings) {
          sb.append(e.description()).append(", ");
        }
        sb.delete(sb.length() - 2, sb.length()).append(")");

        return sb.toString();
      }

      @Override
      public int blockSize() {
        return blockSize;
      }

      @Override
      public boolean isSingleton(Object o) {
        Object[] ary = preEncode.apply(o);
        for (int i = 0; i < ary.length; i++) {
          if (encodings[i].isSingleton(ary[i])) {
            return true;
          }
        }
        return false;
      }

      @Override
      public void encode(IList<Object> primitives, DurableOutput out) {
        int index = 0;
        IList<Object[]> arrays = primitives.stream().map(preEncode).collect(Lists.linearCollector());
        for (IDurableEncoding e : encodings) {
          final int i = index++;
          DurableBuffer.flushTo(
              out,
              BlockPrefix.BlockType.PRIMITIVE,
              inner -> Encodings.encodeBlock(Lists.lazyMap(arrays, t -> t[i]), e, inner));
        }
      }

      @Override
      public SkippableIterator decode(DurableInput in, IDurableCollection.Root root) {
        SkippableIterator[] iterators = new SkippableIterator[encodings.length];
        for (int i = 0; i < encodings.length; i++) {
          iterators[i] = Encodings.decodeBlock(in.sliceBlock(BlockPrefix.BlockType.PRIMITIVE), root, encodings[i]);
        }

        return new SkippableIterator() {
          @Override
          public void skip() {
            for (SkippableIterator it : iterators) {
              it.skip();
            }
          }

          @Override
          public boolean hasNext() {
            for (int i = 1; i < iterators.length; i++) {
              assert(iterators[0].hasNext() == iterators[i].hasNext());
            }
            return iterators[0].hasNext();
          }

          @Override
          public Object next() {
            Object[] result = new Object[encodings.length];
            for (int i = 0; i < result.length; i++) {
              result[i] = iterators[i].next();
            }
            return postDecode.apply(result);
          }
        };
      }
    };
  }

  public static IDurableEncoding unityped(
      IDurableEncoding.Primitive primitiveEncoding) {
    return new IDurableEncoding.Unityped() {
      @Override
      public IDurableEncoding keyEncoding() {
        return this;
      }

      @Override
      public IDurableEncoding valueEncoding() {
        return this;
      }

      @Override
      public void encode(IList<Object> values, DurableOutput out) {
        primitiveEncoding.encode(values, out);
      }

      @Override
      public SkippableIterator decode(DurableInput in, IDurableCollection.Root root) {
        return primitiveEncoding.decode(in, root);
      }

      @Override
      public IDurableEncoding elementEncoding() {
        return this;
      }

      @Override
      public String description() {
        return primitiveEncoding.description();
      }

      @Override
      public Comparator<Object> comparator() {
        return primitiveEncoding.comparator();
      }

      @Override
      public ToLongFunction<Object> hashFn() {
        return primitiveEncoding.hashFn();
      }

      @Override
      public BiPredicate<Object, Object> equalityFn() {
        return primitiveEncoding.equalityFn();
      }

      @Override
      public int blockSize() {
        return primitiveEncoding.blockSize();
      }

      @Override
      public boolean isSingleton(Object o) {
        return primitiveEncoding.isSingleton(o);
      }
    };
  }
}
