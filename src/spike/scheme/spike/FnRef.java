package scheme.spike;

import com.oracle.truffle.api.CallTarget;

/** A reference to a spike function, filled in once all roots are built. */
final class FnRef {

    final String name;
    CallTarget target;

    FnRef(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "FnRef[" + name + "]";
    }
}
