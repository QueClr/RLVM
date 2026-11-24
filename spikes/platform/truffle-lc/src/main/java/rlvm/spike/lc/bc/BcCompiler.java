package rlvm.spike.lc.bc;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.nodes.RootNode;

import rlvm.spike.lc.Expr;
import rlvm.spike.lc.Expr.App;
import rlvm.spike.lc.Expr.Arg;
import rlvm.spike.lc.Expr.Cap;
import rlvm.spike.lc.Expr.GlobalRef;
import rlvm.spike.lc.Expr.If;
import rlvm.spike.lc.Expr.Lam;
import rlvm.spike.lc.Expr.Let;
import rlvm.spike.lc.Expr.Lit;
import rlvm.spike.lc.Expr.Local;
import rlvm.spike.lc.Expr.Prim;
import rlvm.spike.lc.LcLanguage;
import rlvm.spike.lc.ProgramRootNode;

/** Expr IR to Bytecode DSL builder calls. Every lambda becomes its own (nested) bytecode root. */
public final class BcCompiler {
    private final LcBytecodeRootNodeGen.Builder b;
    private BytecodeLocal[] locals;

    private BcCompiler(LcBytecodeRootNodeGen.Builder b) {
        this.b = b;
    }

    /** Kept for footprint introspection by the launcher. */
    public static volatile BytecodeRootNodes<LcBytecodeRootNode> lastNodes;

    public static RootNode compile(LcLanguage language, Expr.Program p) {
        List<LcBytecodeRootNode> tops = new ArrayList<>();
        BytecodeRootNodes<LcBytecodeRootNode> nodes = LcBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, builder -> {
            tops.clear();
            BcCompiler c = new BcCompiler(builder);
            for (Lam init : p.inits()) {
                tops.add(c.root(init));
            }
            tops.add(c.root(p.main()));
        });
        lastNodes = nodes;
        RootCallTarget[] inits = new RootCallTarget[p.inits().length];
        for (int i = 0; i < inits.length; i++) {
            inits[i] = tops.get(i).getCallTarget();
        }
        for (LcBytecodeRootNode n : nodes.getNodes()) {
            LcLanguage.TARGETS.put(n.getName(), n.getCallTarget());
        }
        return new ProgramRootNode(language, p.globals(), inits, tops.get(inits.length).getCallTarget());
    }

    private LcBytecodeRootNode root(Lam l) {
        BytecodeLocal[] saved = locals;
        b.beginRoot();
        locals = new BytecodeLocal[l.numLocals()];
        for (int i = 0; i < locals.length; i++) {
            locals[i] = b.createLocal();
        }
        b.beginReturn();
        expr(l.body());
        b.endReturn();
        LcBytecodeRootNode node = b.endRoot();
        node.name = l.name();
        locals = saved;
        return node;
    }

    private void expr(Expr e) {
        switch (e) {
            case Lit l -> b.emitLoadConstant(l.value());
            case Arg a -> b.emitLoadArgument(a.index() + 1);
            case Cap c -> b.emitLoadCap(c.index());
            case Local l -> b.emitLoadLocal(locals[l.slot()]);
            case GlobalRef g -> b.emitLoadGlobal(g.global());
            case Lam l -> {
                LcBytecodeRootNode inner = root(l);
                b.beginMakeClosure(inner);
                for (Expr c : l.captures()) {
                    expr(c);
                }
                b.endMakeClosure();
            }
            case App a -> {
                b.beginCall();
                expr(a.fn());
                for (Expr x : a.args()) {
                    expr(x);
                }
                b.endCall();
            }
            case Prim p -> {
                switch (p.op()) {
                    case '+' -> b.beginAdd();
                    case '-' -> b.beginSub();
                    case '*' -> b.beginMul();
                    case '<' -> b.beginLess();
                    case '=' -> b.beginEq();
                    default -> throw new IllegalArgumentException("op " + p.op());
                }
                expr(p.left());
                expr(p.right());
                switch (p.op()) {
                    case '+' -> b.endAdd();
                    case '-' -> b.endSub();
                    case '*' -> b.endMul();
                    case '<' -> b.endLess();
                    default -> b.endEq();
                }
            }
            case If i -> {
                b.beginConditional();
                expr(i.cond());
                expr(i.then());
                expr(i.otherwise());
                b.endConditional();
            }
            case Let l -> {
                b.beginBlock();
                b.beginStoreLocal(locals[l.slot()]);
                expr(l.init());
                b.endStoreLocal();
                expr(l.body());
                b.endBlock();
            }
        }
    }
}
