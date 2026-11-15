package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/** A record type from {@code define-record-type}. */
public final class SRecordType implements TruffleObject {

    public final String name;
    public final String[] fieldNames;

    public SRecordType(String name, String[] fieldNames) {
        this.name = name;
        this.fieldNames = fieldNames;
    }
}
