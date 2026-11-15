package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/** An instance of an {@link SRecordType}. */
public final class SRecord implements TruffleObject {

    public final SRecordType type;
    public final Object[] fields;

    public SRecord(SRecordType type, Object[] fields) {
        this.type = type;
        this.fields = fields;
    }
}
