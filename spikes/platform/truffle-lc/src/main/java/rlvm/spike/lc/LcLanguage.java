package rlvm.spike.lc;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.RootNode;

import rlvm.spike.lc.ast.AstCompiler;
import rlvm.spike.lc.bc.BcCompiler;

/**
 * Spike language. The first source line may be {@code ;;backend=ast} (default) or
 * {@code ;;backend=bc} to choose the Truffle AST or the Bytecode DSL interpreter.
 */
@TruffleLanguage.Registration(id = LcLanguage.ID, name = "LC", version = "0.1")
public final class LcLanguage extends TruffleLanguage<LcContext> {
    public static final String ID = "lc";

    /**
     * Spike-only registry (lambda name to call target) so that the launcher can drive compilation of
     * a chosen function explicitly (see Main --compile-at). A real implementation would keep the
     * call target in its own definition table.
     */
    public static final java.util.concurrent.ConcurrentHashMap<String, com.oracle.truffle.api.RootCallTarget> TARGETS = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected LcContext createContext(Env env) {
        return new LcContext();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String text = request.getSource().getCharacters().toString();
        boolean bytecode = text.startsWith(";;backend=bc");
        // One context per engine in this spike, so a per-parse table is sufficient.
        Expr.Program program = Parser.parse(text);
        RootNode root = bytecode ? BcCompiler.compile(this, program) : AstCompiler.compile(this, program);
        return root.getCallTarget();
    }
}
