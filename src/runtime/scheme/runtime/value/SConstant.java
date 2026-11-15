package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/** The singleton values: the empty list, the unspecified value, the eof object and the default object. */
public final class SConstant implements TruffleObject {

    public static final SConstant EMPTY_LIST = new SConstant("()");
    public static final SConstant UNSPECIFIED = new SConstant("#<undef>");
    public static final SConstant EOF = new SConstant("#<eof>");
    public static final SConstant DEFAULT = new SConstant("#<default>");

    private final String printName;

    private SConstant(String printName) {
        this.printName = printName;
    }

    @Override
    public String toString() {
        return printName;
    }
}
