package scheme.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The options in a VM argument file ({@code --vm.@file}), as the native launcher's C part reads
 * them (design.md D17, "Options", plan.md M11-03). GraalVM's thin launcher ({@code launcher.cc},
 * {@code expand_vm_arg_file}) expands the file itself and creates the isolate with its options, and
 * the framework hands the Java side only {@code --vm.@file}; so {@link SchemeMain} reads the file
 * again, to check each option as it checks a {@code --vm.} one. This is {@code launcher.cc}'s
 * {@code arg_file_next_token} (25.5, "Parse @arg-files as handled by libjli"), state for state, so
 * that the launcher checks the options the isolate got: options separated by white space, a
 * {@code #} comment to the end of the line, single or double quotes around white space, escapes
 * ({@code \n}, {@code \t}, ..., and a line continuation) inside quotes only. Its quirks are kept: a
 * comment inside an option ends with the line and the option goes on after it, and a new line ends
 * an open quote.
 */
public final class ArgumentFile {

    private enum State {
        FIND_NEXT,
        IN_COMMENT,
        IN_QUOTE,
        IN_ESCAPE,
        SKIP_LEAD_WS,
        IN_TOKEN
    }

    private ArgumentFile() {
    }

    /** The options in the file. */
    public static List<String> read(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    /** The options in a file's bytes, each decoded as UTF-8, as the framework decodes arguments. */
    public static List<String> parse(byte[] bytes) {
        List<String> tokens = new ArrayList<>();
        int[] position = {0};
        String token;
        while ((token = nextToken(bytes, position)) != null) {
            tokens.add(token);
        }
        return tokens;
    }

    private static boolean isWhiteSpace(int ch) {
        return ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f';
    }

    /** The next option from position[0] on, or null at the end; position[0] moves past it. */
    private static String nextToken(byte[] bytes, int[] position) {
        State state = State.FIND_NEXT;
        int currentQuoteChar = -1;
        ByteArrayOutputStream token = new ByteArrayOutputStream();
        int i = position[0];
        try {
            while (i < bytes.length) {
                int ch = bytes[i++] & 0xff;
                if (state == State.FIND_NEXT || state == State.SKIP_LEAD_WS) {
                    while (isWhiteSpace(ch)) {
                        if (i >= bytes.length) {
                            return token.size() == 0 ? null : decode(token);
                        }
                        ch = bytes[i++] & 0xff;
                    }
                    state = state == State.FIND_NEXT ? State.IN_TOKEN : State.IN_QUOTE;
                } else if (state == State.IN_ESCAPE) {
                    if (ch == '\n' || ch == '\r') {
                        // A line continuation.
                        state = State.SKIP_LEAD_WS;
                    } else {
                        token.write(switch (ch) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'f' -> '\f';
                            default -> ch;
                        });
                        state = State.IN_QUOTE;
                    }
                    continue;
                } else if (state == State.IN_COMMENT) {
                    while (ch != '\n' && ch != '\r') {
                        if (i >= bytes.length) {
                            return token.size() == 0 ? null : decode(token);
                        }
                        ch = bytes[i++] & 0xff;
                    }
                    state = State.FIND_NEXT;
                    continue;
                }

                switch (ch) {
                    case ' ', '\t', '\f':
                        if (state == State.IN_QUOTE) {
                            token.write(ch);
                            continue;
                        }
                        return decode(token);
                    case '\n', '\r':
                        return decode(token);
                    case '#':
                        if (state == State.IN_QUOTE) {
                            token.write(ch);
                            continue;
                        }
                        state = State.IN_COMMENT;
                        break;
                    case '\\':
                        if (state != State.IN_QUOTE) {
                            token.write(ch);
                            continue;
                        }
                        state = State.IN_ESCAPE;
                        break;
                    case '\'', '"':
                        if (state == State.IN_QUOTE && currentQuoteChar != ch) {
                            // Not the quote this one started with.
                            token.write(ch);
                            continue;
                        }
                        if (state == State.IN_TOKEN) {
                            currentQuoteChar = ch;
                            state = State.IN_QUOTE;
                        } else {
                            state = State.IN_TOKEN;
                        }
                        break;
                    default:
                        token.write(ch);
                        break;
                }
            }
            return token.size() == 0 ? null : decode(token);
        } finally {
            position[0] = i;
        }
    }

    private static String decode(ByteArrayOutputStream token) {
        return token.toString(StandardCharsets.UTF_8);
    }
}
