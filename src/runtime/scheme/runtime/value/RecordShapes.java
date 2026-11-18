package scheme.runtime.value;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;
import com.oracle.truffle.api.staticobject.StaticProperty;
import com.oracle.truffle.api.staticobject.StaticShape;

import scheme.runtime.staticobject.SRecord;

/**
 * A language's Static Object Model shapes for records (design.md D1, plan.md M7-04): one per number
 * of fields, every field an {@code Object}, shared by all the record types with that many fields. A
 * shape is a class the model generates (on HotSpot), so a record type defined in a loop doesn't
 * make a class per evaluation; a record's type is a field of {@link SRecord}, and record procedures
 * check it.
 */
public final class RecordShapes {

    /** A shape and its properties, field 0 first. */
    public record Layout(StaticShape<SRecord.Factory> shape, StaticProperty[] properties) {
    }

    private final TruffleLanguage<?> language;
    private final List<Layout> byCount = new ArrayList<>();

    public RecordShapes(TruffleLanguage<?> language) {
        this.language = language;
    }

    /** The layout of records with {@code count} fields, made the first time it is asked for. */
    @TruffleBoundary
    public synchronized Layout layout(int count) {
        while (byCount.size() <= count) {
            byCount.add(null);
        }
        Layout layout = byCount.get(count);
        if (layout == null) {
            StaticShape.Builder builder = StaticShape.newBuilder(language);
            StaticProperty[] properties = new StaticProperty[count];
            for (int i = 0; i < count; i++) {
                properties[i] = new DefaultStaticProperty("f" + i);
                builder.property(properties[i], Object.class, false);
            }
            layout = new Layout(builder.build(SRecord.class, SRecord.Factory.class), properties);
            byCount.set(count, layout);
        }
        return layout;
    }
}
