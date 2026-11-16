import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class Hello {
    public static void main(String[] args) {
        try (Context context = Context.create("scheme")) {
            context.eval("scheme", "(define (greet name) (string-append \"Hello, \" name \"!\"))");
            Value greet = context.getBindings("scheme").getMember("greet");
            System.out.println(greet.execute("Java").asString());

            Value squares = context.eval("scheme", "(map (lambda (x) (* x x)) '(1 2 3))");
            System.out.println(squares);
        }
    }
}
