package scheme.driver;

import java.util.List;
import java.util.Map;

/**
 * Test: a program under ours, interpreted (or under torture), has the same stdout and exit status
 * as under chibi-scheme, whose output is a build action's (tests/harness/defs.bzl chibi_output).
 *
 * <p>
 * {@code --module-path=JARS --file=PROGRAM --chibi-out=F --chibi-err=F --chibi-status=F
 * [--torture=torture|torture_owned]} ({@link Launch#vmArgs})
 */
public final class Diff {

    private Diff() {
    }

    public static void main(String[] args) throws Exception {
        Launch launch = new Launch(args);
        String file = launch.option("file");
        String chibiOut = Launch.read(Launch.runfile(launch.option("chibi-out")));
        String chibiErr = Launch.read(Launch.runfile(launch.option("chibi-err")));
        int chibiStatus = Integer.parseInt(Launch.read(Launch.runfile(launch.option("chibi-status"))).strip());
        Launch.Result ours = launch.run(Launch.vmArgs(false, launch.options.get("torture")), Launch.LAUNCHER_MAIN,
                        List.of(Launch.runfile(file).toString()), null, Map.of(), 600);
        if (ours.status() == chibiStatus && ours.out().equals(chibiOut)) {
            Launch.log("same (exit " + ours.status() + ", " + ours.out().length() + " chars of stdout): " + file);
            return;
        }
        StringBuilder message = new StringBuilder("DIFFERENT from chibi-scheme: " + file + "\n");
        if (!ours.out().equals(chibiOut)) {
            message.append(Launch.firstDifference(chibiOut, ours.out(), "chibi-scheme", "scheme")).append('\n');
        }
        message.append("exit status: chibi-scheme ").append(chibiStatus).append(", scheme ").append(ours.status()).append('\n');
        message.append("chibi-scheme stderr (tail):\n").append(Launch.tail(chibiErr, 5));
        message.append("scheme stderr (tail):\n").append(Launch.tail(ours.err(), 5));
        throw Launch.fail(message.toString());
    }
}
