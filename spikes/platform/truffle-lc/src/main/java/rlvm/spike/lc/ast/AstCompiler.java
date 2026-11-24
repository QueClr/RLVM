package rlvm.spike.lc.ast;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
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

/** Expr IR to Truffle AST nodes. */
public final class AstCompiler {
    private final LcLanguage language;
    public int nodeCount;

    private AstCompiler(LcLanguage language) {
        this.language = language;
    }

    public static RootNode compile(LcLanguage language, Expr.Program p) {
        AstCompiler c = new AstCompiler(language);
        RootCallTarget[] inits = new RootCallTarget[p.inits().length];
        for (int i = 0; i < inits.length; i++) {
            inits[i] = c.root(p.inits()[i]);
        }
        return new ProgramRootNode(language, p.globals(), inits, c.root(p.main()));
    }

    private RootCallTarget root(Lam l) {
        FrameDescriptor.Builder fd = FrameDescriptor.newBuilder();
        fd.addSlots(l.numLocals(), FrameSlotKind.Illegal);
        RootCallTarget target = new Nodes.LcRootNode(language, fd.build(), l.name(), node(l.body())).getCallTarget();
        LcLanguage.TARGETS.put(l.name(), target);
        return target;
    }

    private LcNode node(Expr e) {
        nodeCount++;
        return switch (e) {
            case Lit l -> new Nodes.LitNode(l.value());
            case Arg a -> new Nodes.ArgNode(a.index());
            case Cap c -> new Nodes.CapNode(c.index());
            case Local l -> new Nodes.LocalNode(l.slot());
            case GlobalRef g -> new Nodes.GlobalNode(g.global());
            case Lam l -> new Nodes.LamNode(root(l), nodes(l.captures()));
            case App a -> new Nodes.AppNode(node(a.fn()), nodes(a.args()));
            case Prim p -> switch (p.op()) {
                case '+' -> AddNodeGen.create(node(p.left()), node(p.right()));
                case '-' -> SubNodeGen.create(node(p.left()), node(p.right()));
                case '*' -> MulNodeGen.create(node(p.left()), node(p.right()));
                case '<' -> LessNodeGen.create(node(p.left()), node(p.right()));
                case '=' -> EqNodeGen.create(node(p.left()), node(p.right()));
                default -> throw new IllegalArgumentException("op " + p.op());
            };
            case If i -> new Nodes.IfNode(node(i.cond()), node(i.then()), node(i.otherwise()));
            case Let l -> new Nodes.LetNode(l.slot(), node(l.init()), node(l.body()));
        };
    }

    private LcNode[] nodes(Expr[] es) {
        LcNode[] r = new LcNode[es.length];
        for (int i = 0; i < es.length; i++) {
            r[i] = node(es[i]);
        }
        return r;
    }
}
