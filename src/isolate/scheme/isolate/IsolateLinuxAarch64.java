package scheme.isolate;

import com.oracle.truffle.api.InternalResource;
import com.oracle.truffle.api.InternalResource.CPUArchitecture;
import com.oracle.truffle.api.InternalResource.OS;
import com.oracle.truffle.api.impl.PolyglotIsolateLanguages;

/** The polyglot isolate library for AArch64 Linux ({@link IsolateResource}). */
@InternalResource.Id(value = IsolateLinuxAarch64.ID, componentId = "engine", optional = true)
@PolyglotIsolateLanguages(value = "scheme", os = OS.LINUX, cpuArchitecture = CPUArchitecture.AARCH64)
final class IsolateLinuxAarch64 extends IsolateResource {

    static final String ID = "scheme-isolate-linux-aarch64";

    IsolateLinuxAarch64() {
        super(ID);
    }
}
