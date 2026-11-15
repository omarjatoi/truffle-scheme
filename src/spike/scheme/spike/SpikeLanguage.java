package scheme.spike;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;

/** M0-02 throwaway language. Source text: {@code <mode> <value>...}; see {@link HarnessRootNode}. */
@TruffleLanguage.Registration(id = SpikeLanguage.ID, name = "Scheme continuation spike", characterMimeTypes = "application/x-scheme-spike", contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class SpikeLanguage extends TruffleLanguage<Object> {

    public static final String ID = "scheme-spike";

    @Override
    protected Object createContext(Env env) {
        return env;
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String[] words = request.getSource().getCharacters().toString().strip().split("\\s+");
        long[] values = new long[words.length - 1];
        for (int i = 1; i < words.length; i++) {
            values[i - 1] = Long.parseLong(words[i]);
        }
        return new HarnessRootNode(this, SpikePrograms.build(this), words[0], values).getCallTarget();
    }
}
