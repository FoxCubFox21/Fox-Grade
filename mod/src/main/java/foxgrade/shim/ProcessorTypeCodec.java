package foxgrade.shim;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

/** A 1.21 structure-processor "type" was a lambda returning a Codec; 26.2's registry holds the MapCodec itself and the
 *  type interface has no method left. This object is both: a MapCodec (what the registry and vanilla's dispatch want)
 *  and a StructureProcessorType (what the mod's fields and signatures are typed as). */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class ProcessorTypeCodec extends MapCodec<StructureProcessor> implements StructureProcessorType {
  private final java.util.function.Supplier<?> source; private MapCodec<StructureProcessor> delegate;
  private ProcessorTypeCodec(java.util.function.Supplier<?> source) { this.source = source; }
  public static StructureProcessorType of(java.util.function.Supplier<?> source) { return new ProcessorTypeCodec(source); }
  private MapCodec<StructureProcessor> delegate() {
    if (delegate == null) {
      Object c = source.get();
      delegate = c instanceof MapCodec m ? m : MapCodec.assumeMapUnsafe((Codec) c);
    }
    return delegate;
  }
  @Override public <T> java.util.stream.Stream<T> keys(DynamicOps<T> ops) { return delegate().keys(ops); }
  @Override public <T> DataResult<StructureProcessor> decode(DynamicOps<T> ops, MapLike<T> input) { return delegate().decode(ops, input); }
  @Override public <T> RecordBuilder<T> encode(StructureProcessor input, DynamicOps<T> ops, RecordBuilder<T> prefix) { return delegate().encode(input, ops, prefix); }
  @Override public String toString() { return "ProcessorType[" + (delegate == null ? "unresolved" : delegate) + "]"; }
}
