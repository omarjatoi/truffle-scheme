package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/** Zero or two or more values. A single value is always returned as itself (design.md D1). */
public final class MultipleValues implements TruffleObject {

    public final Object[] values;

    public MultipleValues(Object[] values) {
        this.values = values;
    }
}
