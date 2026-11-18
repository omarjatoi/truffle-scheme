package scheme.isolate;

import java.io.IOException;
import java.nio.file.Path;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.InternalResource;

/**
 * The language's polyglot isolate library, an optional internal resource of the engine, as
 * GraalVM's {@code -isolate} jars carry theirs (mx's {@code polyglot_isolate_resource.template};
 * design.md D10, "ISOLATED and UNTRUSTED"). The library is in this module under
 * {@code META-INF/resources/engine/<id>/libvm/}, with {@code files}, the list Truffle unpacks to its
 * resource cache, and {@code sha256}, the library's digest, which names the version unpacked. A
 * context that runs the language in an isolate (the {@code ISOLATED} and {@code UNTRUSTED} sandbox
 * policies, or {@code spawnIsolate(true)}) spawns one of it. A subclass per platform says which
 * platform its library is for; a platform's jar has its own (//:scheme_isolate).
 */
abstract class IsolateResource implements InternalResource {

    private final Path base;

    IsolateResource(String id) {
        this.base = Path.of("META-INF", "resources", "engine", id, "libvm");
    }

    @Override
    public void unpackFiles(Env env, Path targetDirectory) throws IOException {
        env.unpackResourceFiles(base.resolve("files"), targetDirectory, base);
    }

    @Override
    public String versionHash(Env env) {
        try {
            return env.readResourceLines(base.resolve("sha256")).get(0);
        } catch (IOException e) {
            // The build puts it in the jar with the library.
            throw CompilerDirectives.shouldNotReachHere(e);
        }
    }
}
