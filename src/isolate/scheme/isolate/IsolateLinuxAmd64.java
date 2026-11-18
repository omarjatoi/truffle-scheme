package scheme.isolate;

import com.oracle.truffle.api.InternalResource;
import com.oracle.truffle.api.InternalResource.CPUArchitecture;
import com.oracle.truffle.api.InternalResource.OS;
import com.oracle.truffle.api.impl.PolyglotIsolateLanguages;

/** The polyglot isolate library for x86-64 Linux ({@link IsolateResource}). */
@InternalResource.Id(value = IsolateLinuxAmd64.ID, componentId = "engine", optional = true)
@PolyglotIsolateLanguages(value = "scheme", os = OS.LINUX, cpuArchitecture = CPUArchitecture.AMD64)
final class IsolateLinuxAmd64 extends IsolateResource {

    static final String ID = "scheme-isolate-linux-amd64";

    IsolateLinuxAmd64() {
        super(ID);
    }
}
