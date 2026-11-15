package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/** A mutable pair. */
public final class SPair implements TruffleObject {

    public Object car;
    public Object cdr;

    public SPair(Object car, Object cdr) {
        this.car = car;
        this.cdr = cdr;
    }
}
