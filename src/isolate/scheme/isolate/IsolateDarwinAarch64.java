package scheme.isolate;

import com.oracle.truffle.api.InternalResource;
import com.oracle.truffle.api.InternalResource.CPUArchitecture;
import com.oracle.truffle.api.InternalResource.OS;
import com.oracle.truffle.api.impl.PolyglotIsolateLanguages;

/** The polyglot isolate library for macOS on Apple silicon ({@link IsolateResource}). */
@InternalResource.Id(value = IsolateDarwinAarch64.ID, componentId = "engine", optional = true)
@PolyglotIsolateLanguages(value = "scheme", os = OS.DARWIN, cpuArchitecture = CPUArchitecture.AARCH64)
final class IsolateDarwinAarch64 extends IsolateResource {

    static final String ID = "scheme-isolate-darwin-aarch64";

    IsolateDarwinAarch64() {
        super(ID);
    }
}
